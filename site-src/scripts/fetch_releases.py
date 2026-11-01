#!/usr/bin/env python3
"""从 GitHub API 拉取各项目的 Release 元数据，冻结成 data/releases.json。

应用商店页面要显示「版本号 / 体积 / 发布时间」，这些必须是真的：
手写在清单里，发一次版就会漂一次，而且没人会发现。

所以这里做两件事：
1. 联网拉一次，把结果落盘成 data/releases.json（入库、可供 CI 校验）；
2. 构建时只读这个文件，构建过程不联网——同一个 commit 永远产出同一份站点。

谁在调用它（见 .github/workflows/sync.yml）：
- 对方仓库一发 Release（repository_dispatch）就立刻同步；
- 每小时定时兜底（对方的 webhook 会丢，定时不会）；
- 发现线上已经发了新版本（比站点知道的新），顺手建一个 Issue 提醒；
- 数据真的变了才提交，没变化就只报一行「无变化」。

手动：
    python3 scripts/fetch_releases.py --write
    python3 scripts/fetch_releases.py --check
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "data" / "releases.json"
API = "https://api.github.com"

# 只取这些仓库；key 必须对应 data/projects.py 里的 repo 名。
# 与 projects.py 的偏离会由 check_site.py 报出来。
WATCHED = [
    "iceScribe", "ModelScopeBrowser", "KayaGo", "iceScan",
    "iceBrowser", "iceReading", "MusicFusion", "MusicFusionAI",
]


def get(path, token, timeout=30, retries=3):
    url = path if path.startswith("http") else API + path
    headers = {"User-Agent": "ice-suite-release-fetcher/1.0",
               "Accept": "application/vnd.github+json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    last = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            last = e
            # 403/429 多半是限流，退避重试
            if e.code in (403, 429, 500, 502, 503, 504) and attempt < retries - 1:
                time.sleep(2 ** attempt)
                continue
            raise
        except Exception as e:
            last = e
            if attempt < retries - 1:
                time.sleep(2 ** attempt)
                continue
            raise
    raise last


def pick_release(repo, owner, token):
    """挑「用户现在能装的那个包」：最新一条非草稿、带 .apk 的 Release。

    不按 tag 排序——tag 名是作者的自由，v13 与 v14.0.0 可以同时存在。
    按发布顺序取第一条，就是用户在 Releases 页面看到的那个。
    """
    releases = get(f"/repos/{owner}/{repo}/releases?per_page=10", token)
    for rel in releases:
        if rel.get("draft") or rel.get("prerelease"):
            continue
        for a in rel.get("assets", []):
            if a["name"].lower().endswith(".apk"):
                return {
                    "tag": rel["tag_name"],
                    "name": rel.get("name") or rel["tag_name"],
                    "published_at": (rel.get("published_at") or "")[:10],
                    "asset": a["name"],
                    "size": a["size"],
                    "downloads": a.get("download_count", 0),
                    "url": a["browser_download_url"],
                }
    return None


def collect(owner, token):
    out, failed = {}, []
    for repo in WATCHED:
        try:
            entry = pick_release(repo, owner, token)
        except Exception as e:
            failed.append(f"{repo}: {type(e).__name__} {e}")
            print(f"  ⚠️  {repo} 拉取失败：{type(e).__name__} {e}")
            continue
        if entry:
            out[repo] = entry
            print(f"  ✅ {repo} {entry['tag']} {entry['size']}")
        else:
            failed.append(f"{repo}: 没有带 .apk 资源的正式 Release")
            print(f"  ⚠️  {repo} 没有带 .apk 资源的正式 Release")
    return out, failed


def diff(old, new):
    """只关心会让页面变成假话的变化：版本、体积、文件名、下载地址。"""
    drift = []
    for repo, cur in new.items():
        prev = old.get(repo)
        if not prev:
            drift.append(f"{repo}: 新增 {cur['tag']}（快照里没有）")
            continue
        for k, label in (("tag", "版本"), ("size", "体积"), ("asset", "文件名"), ("url", "下载地址")):
            if prev.get(k) != cur.get(k):
                drift.append(f"{repo}: {label} {prev.get(k)} → {cur.get(k)}")
    for repo in old:
        if repo not in new:
            drift.append(f"{repo}: 线上已找不到带 APK 的 Release（快照里还留着）")
    return drift


def notify(owner, repo_name, releases, drift, token):
    """线上有新版本、站点还不知道时，开一个 Issue。

    为什么要提醒而不是「让定时任务自己处理就好」：定时任务已经处理了数据，
    但**人**不一定知道某个项目的文案过期了（比如介绍里写着「8 个可安装」）。
    一个 Issue 就是一张待办，比 silently 更新数据更诚实。
    """
    if not token or not releases:
        return False
    title = "站点里的版本快照已更新：" + "、".join(
        f"{k} {v['tag']}" for k, v in releases.items())
    body = ("自动同步发现这些应用发了新版本，data/releases.json 已更新：\n\n"
            + "\n".join(f"- {d}" for d in drift)
            + "\n\n如果某个项目的介绍文案里写死了数量 / 版本号，顺手改一下 `data/projects.py`。\n"
            + f"\n_由 `scripts/fetch_releases.py` 生成 · {time.strftime('%Y-%m-%d %H:%M UTC', time.gmtime())}_")
    data = json.dumps({"title": title[:250], "body": body,
                       "labels": ["release-sync"]}).encode("utf-8")
    req = urllib.request.Request(
        f"{API}/repos/{owner}/{repo_name}/issues", data=data, method="POST",
        headers={"User-Agent": "ice-suite-release-fetcher/1.0",
                 "Accept": "application/vnd.github+json",
                 "Authorization": f"Bearer {token}",
                 "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            info = json.load(r)
        print(f"已开 Issue #{info.get('number')}：{info.get('html_url')}")
        return True
    except Exception as e:
        print(f"开 Issue 失败（不影响数据同步）：{e}")
        return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--owner", default="ice-wocker")
    ap.add_argument("--token", default=None, help="不传则读 GH_TOKEN / GITHUB_TOKEN 环境变量")
    ap.add_argument("--write", action="store_true", help="写入 data/releases.json")
    ap.add_argument("--check", action="store_true",
                    help="只比对 data/releases.json 与线上是否一致，不一致返回 1（CI 用）")
    ap.add_argument("--notify", action="store_true",
                    help="线上比快照新时，在 --repo 参数的仓库里开 Issue 提醒（需要 issues: write）")
    ap.add_argument("--repo", default=None, help="开 Issue 用的仓库名，如 ice-wocker/icesuite")
    args = ap.parse_args()

    token = args.token or os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN") or ""

    old = {}
    if OUT.exists():
        old = json.loads(OUT.read_text(encoding="utf-8")).get("releases", {})

    new, failed = collect(args.owner, token)
    if not new:
        print("一个 Release 都没拉到，保持快照不动（不写空文件）。", file=sys.stderr)
        return 1

    if args.check:
        drift = diff(old, new)
        if drift:
            print("data/releases.json 已过期，请运行 python3 scripts/fetch_releases.py --write 后提交：")
            for d in drift:
                print("  - " + d)
            return 1
        print(f"data/releases.json 与线上一致（{len(new)} 个仓库）。")
        return 0

    # 被拉取失败的仓库不能从快照里删掉：网络抽风不等于对方删了 Release。
    # 已经拿到的那部分照常更新，这是「部分成功也要前进」。
    merged = dict(old)
    merged.update(new)

    payload = {
        "_note": "由 scripts/fetch_releases.py 从 GitHub API 生成，请勿手改。构建时只读本文件，不联网。",
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "owner": args.owner,
        "releases": merged,
    }
    text = json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    before = OUT.read_text(encoding="utf-8") if OUT.exists() else ""
    # 忽略 generated_at 的比较：时间戳每跑一次都变，只看内容有没有真的变
    changed = strip_ts(before) != strip_ts(text)

    if args.write:
        OUT.write_text(text, encoding="utf-8")
        print(f"已写入 {OUT}（{len(merged)} 个仓库）")
    else:
        print(text)

    # ---- 给 CI 的机器可读结论 ----
    # 只有「某个应用真的发了新版」才值得打扰人；generated_at 变化不算。
    fresh = {k: v for k, v in new.items() if old.get(k, {}).get("tag") != v["tag"]}
    drift = diff(old, merged)
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as f:
            f.write(f"changed={'true' if changed else 'false'}\n")
            f.write(f"fresh={'true' if fresh else 'false'}\n")
            f.write("summary=" + urllib.parse.quote("、".join(f"{k} {v['tag']}" for k, v in fresh.items())) + "\n")
    if changed:
        print("变化：")
        for d in drift:
            print("  - " + d)
    else:
        print("无变化。")

    if args.notify and fresh and args.repo:
        notify(*args.repo.split("/", 1), fresh, drift, token)

    # 全挂（一个都没拉到）才算失败；个别仓库没 Release 是正常状态（那是没做安卓包的项目）
    return 0 if new else 1


def strip_ts(text):
    """去掉 generated_at 行，用于判断「内容是否真的变了」。"""
    return "\n".join(l for l in text.splitlines() if '"generated_at"' not in l)


if __name__ == "__main__":
    sys.exit(main())
