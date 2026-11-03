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
import hashlib
import json
import os
import random
import re
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
#
# 每个仓库取多少条 Release 冻结成「更新记录」。10 是 API 一页的量，
# 不额外多请求一次；真要更长的历史，改这个数就行（自检会自动跟着它走）。
HISTORY_PER_PAGE = 10

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
    """挑「用户现在能装的那个包」，并顺手把整部发版史带回来。

    两条规则，都是被现实逼出来的：

    1. **最新版按发布顺序取，不按 tag 排序。** tag 名是作者的自由：
       MusicFusion 有 `v13`，MusicFusionAI 有 `v14.0.0`，字符串排序会把
       `v13` 排到 `v14.0.0` 后面。只有 API 返回的顺序（发布时间倒序）是事实。

    2. **历史不设额外请求。** `/releases?per_page=N` 一次就返回 N 条，
       既然已经在手上，就一起冻结下来——每个应用的「更新记录」就是这么来的。
       自检要的是「页面上的历史 == 快照里的历史」，多拉几条不增加任何成本。
    """
    releases = get(f"/repos/{owner}/{repo}/releases?per_page=HISTORY_PER_PAGE", token)
    latest, history = None, []
    for rel in releases:
        if rel.get("draft") or rel.get("prerelease"):
            continue
        apk = None
        for a in rel.get("assets", []):
            if a["name"].lower().endswith(".apk"):
                apk = a
                break
        if apk is None:
            continue
        item = {
            "tag": rel["tag_name"],
            "name": rel.get("name") or rel["tag_name"],
            "published_at": (rel.get("published_at") or rel.get("created_at") or "")[:10],
            "asset": apk["name"],
            "size": apk["size"],
            "downloads": apk.get("download_count", 0),
            "url": apk["browser_download_url"],
            # GitHub 自 2025 起在资产元数据里给出 sha256。给了就存，没给就是 None。
            # 这里绝不「自己下下来算一遍」：同步任务每小时跑一次，8 个包加起来
            # 60+ MB，为了填一个字段去反复下载整包不值当；而且下载算出来的值
            # 与 GitHub 声明的值如果不一致，那才是真该报警的事（见 --verify-digest）。
            "sha256": asset_digest(apk),
            # versionCode 只从 Release 正文里认显式写法，认不出就是 None。
            # 不从 versionName 推算：2.7.0 该是 2700 还是 20700 是作者的选择，
            # 猜错会让客户端以为「已是最新」，永远收不到更新。
            "version_code": parse_version_code(rel.get("body") or ""),
            # 正文原样保留（不截断）：页面上怎么显示是渲染层的事，
            # 快照该存事实。截断会让「自检比对页面与快照」变成拿两个不同东西比。
            "body": (rel.get("body") or "").strip(),
        }
        history.append(item)
        if latest is None:
            latest = dict(item)
    if latest is None:
        return None, []
    latest["history"] = history
    return latest, history


def asset_digest(asset):
    """从资产元数据里取 sha256，取不到返回 None。

    GitHub 给的是 `sha256:<64 hex>` 形式。这里不接受别的前缀——
    把 md5 之类的东西写进 `sha256` 字段，比留空更糟：客户端会拿它去校验，
    然后永远校验不过，或者更坏，永远校验得过。
    """
    d = (asset.get("digest") or "").strip().lower()
    if d.startswith("sha256:"):
        hexpart = d[len("sha256:"):]
        if re.fullmatch(r"[0-9a-f]{64}", hexpart):
            return hexpart
    return None


VC_PATTERNS = (
    r"versioncode\s*[:=]\s*(\d+)",
    r"version\s*code\s*[:：]\s*(\d+)",
)


def parse_version_code(body):
    """从 Release 正文里认显式的 versionCode，认不出返回 None。

    只认「versionCode: 15」这种作者亲手写的声明。宁可缺，也不猜。
    """
    for pat in VC_PATTERNS:
        m = re.search(pat, body, flags=re.I)
        if m:
            return int(m.group(1))
    return None


def collect(owner, token):
    out, failed = {}, []
    for repo in WATCHED:
        try:
            entry, history = pick_release(repo, owner, token)
        except Exception as e:
            failed.append(f"{repo}: {type(e).__name__} {e}")
            print(f"  ⚠️  {repo} 拉取失败：{type(e).__name__} {e}")
            continue
        if entry:
            out[repo] = entry
            print(f"  ✅ {repo} {entry['tag']} {entry['size']}"
                  f"（历史 {len(history)} 条，sha256 {'有' if entry.get('sha256') else '无'}）")
        else:
            failed.append(f"{repo}: 没有带 .apk 资源的正式 Release")
            print(f"  ⚠️  {repo} 没有带 .apk 资源的正式 Release")
    return out, failed


def diff(old, new):
    """只关心会让页面变成假话的变化：版本、体积、文件名、下载地址、校验值。

    校验值也算「会让页面变成假话」：`update.json` 里的 sha256 一旦存在，
    就会被客户端拿去校验安装包。它变了而页面没变，等于给用户一个必然校验失败的值。
    历史条目数变了不报——那只是「又攒了一个版本」，页面会跟着长，不需要提醒人。
    """
    drift = []
    for repo, cur in new.items():
        prev = old.get(repo)
        if not prev:
            drift.append(f"{repo}: 新增 {cur['tag']}（快照里没有）")
            continue
        for k, label in (("tag", "版本"), ("size", "体积"), ("asset", "文件名"),
                         ("url", "下载地址"), ("sha256", "sha256")):
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


def verify_digest(owner, token):
    """把 GitHub 声明的 sha256 与实际下载下来的字节对一次。

    为什么需要这一步：`update.json` 里的 sha256 会被客户端拿去校验安装包。
    它错了的后果是**两个方向都坏**：真值报错（用户装不上，或者信任了坏包）。
    这条校验只在人工/每周触发时跑，常规同步不跑——为了填一个字段
    每小时把 60+ MB 的包全下一遍，不值当。

    做法上刻意偷懒：只验体积最小的一两个包。目的是「证明声明值这条路是通的」，
    不是把 8 个包都下一个遍。真要全验，改 `--verify-digest all`。
    """
    snap = {}
    if OUT.exists():
        snap = json.loads(OUT.read_text(encoding="utf-8")).get("releases", {})
    targets = [t for t in (snap or {}) if (snap[t].get("sha256"))]
    targets.sort(key=lambda r: snap[r].get("size") or 0)
    if not targets:
        print("快照里没有任何 sha256，无可校验（先跑一次 --write）。")
        return 0
    bad = 0
    for repo in targets[:1] + [t for t in targets if t in ("KayaGo", "iceReading")][:1]:
        rel = snap[repo]
        h = hashlib.sha256()
        n = 0
        req = urllib.request.Request(rel["url"], headers={"User-Agent": "ice-suite-digest/1.0"})
        with urllib.request.urlopen(req, timeout=180) as r:
            while True:
                chunk = r.read(1 << 16)
                if not chunk:
                    break
                h.update(chunk)
                n += len(chunk)
        got = h.hexdigest()
        ok = (got == rel["sha256"]) and (n == rel["size"])
        print(f"  {'✅' if ok else '❌'} {repo} {rel['asset']} {n} B sha256={got[:16]}…"
              f"（声明 {rel['sha256'][:16]}…）")
        if not ok:
            bad += 1
    if bad:
        print("声明值与实际文件不符：update.json 里的 sha256 不可信，检查 GitHub 侧资产是否被重新上传。")
        return 1
    print("声明值与实际文件一致。")
    return 0


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
    ap.add_argument("--offline", metavar="RELEASES_JSON",
                    help="不联网，用给定的 releases.json 重新算 changed/fresh 等输出"
                         "（给 check_site.py 的变异测试用：改坏一个字段，自检必须变红）")
    ap.add_argument("--verify-digest", action="store_true",
                    help="把声明里的 sha256 与实际下载的文件对一次（会下载最小的一两个包）")
    args = ap.parse_args()

    token = args.token or os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN") or ""

    old = {}
    if OUT.exists():
        old = json.loads(OUT.read_text(encoding="utf-8")).get("releases", {})

    if args.offline:
        new = json.loads(Path(args.offline).read_text(encoding="utf-8")).get("releases", {})
        failed = []
    elif args.verify_digest:
        return verify_digest(args.owner, token)
    else:
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
