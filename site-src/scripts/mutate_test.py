#!/usr/bin/env python3
"""守卫自证：逐条注入缺陷，确认 check_site.py 真的会变红。

## 为什么需要这个

一个永远不会红的检查，比没有检查更危险——它会让人以为已经守住了。
这个仓库里有过真实教训：早先有几条守卫写成了橡皮图章（在注释里搜关键词、
拿「页面顺序」当「顺序正确」的证据），注入缺陷之后它们照样绿。
所以每条守卫都必须先证明它抓得住东西。

## 怎么做

对 `site/` 里已经构建好的产物做**最小改动**（删一个字段、换一个字、交换两条），
每次改完跑一遍 `check_site.py`。改了必须红，没红就是守卫失效，脚本以 1 退出。

改动都落在产物上，不碰 `data/` 与 `scripts/`，因此不需要还原源码，
只还原 `site/`（重新跑一次 build_site.py 即可，构建本身可重复）。

## 用法

    python3 scripts/build_site.py
    python3 scripts/mutate_test.py
"""
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SITE = ROOT / "site"
BACKUP = ROOT / ".site-backup"

CASES = []


def case(name):
    def deco(fn):
        CASES.append((name, fn))
        return fn
    return deco


def _edit(fname, fn):
    f = SITE / fname
    fn(f)


def _sub(fname, old, new):
    f = SITE / fname
    t = f.read_text(encoding="utf-8")
    assert old in t, f"注入点不存在：{old[:40]}"
    f.write_text(t.replace(old, new, 1), encoding="utf-8")


def _edit_json(fname, fn):
    f = SITE / fname
    d = json.loads(f.read_text(encoding="utf-8"))
    fn(d)
    f.write_text(json.dumps(d, ensure_ascii=False, indent=2, sort_keys=True), encoding="utf-8")


def _sha(fname):
    return re.search(r'"sha256": "([0-9a-f]{64})"',
                     (SITE / "update.json").read_text(encoding="utf-8")).group(1)


# --------------------------------------------------------------- 校验值
@case("update.json 里的 sha256 被改成假的")
def _():
    _edit_json("update.json", lambda d: d["apps"]["KayaGo"]["latest"].__setitem__("sha256", "0" * 64))


@case("快照里没有的 sha256 被页面编了一个出来")
def _():
    _edit_json("update.json",
               lambda d: d["apps"]["KayaGo"]["latest"].__setitem__("sha256", "a" * 64))


@case("详情页的「校验安装包」区块里没有 sha256")
def _():
    sha = _sha("kayago.html")
    f = SITE / "kayago.html"
    lines = f.read_text(encoding="utf-8").split("\n")
    f.write_text("\n".join(l.replace(sha, "") if 'id="verify"' in l else l for l in lines),
                 encoding="utf-8")


# --------------------------------------------------------------- 更新记录
@case("changes.html 少了一个版本")
def _():
    t = (SITE / "changes.html").read_text(encoding="utf-8")
    m = re.search(r'<li class="vitem"><div class="vhead"><b>v1\.1\.0</b>.*?</li>', t, flags=re.S)
    assert m, "找不到 KayaGo v1.1.0 那一段"
    (SITE / "changes.html").write_text(t.replace(m.group(0), "", 1), encoding="utf-8")


@case("changes.html 里相邻两个版本被交换了顺序")
def _():
    t = (SITE / "changes.html").read_text(encoding="utf-8")
    seg = re.search(r'(<section class="applog" id="kayago">.*?</section>)', t, flags=re.S).group(1)
    items = re.findall(r'<li class="vitem".*?</li>', seg, flags=re.S)
    a, b = items[0], items[1]
    newseg = seg.replace(a, "@@TMP@@", 1).replace(b, a, 1).replace("@@TMP@@", b, 1)
    (SITE / "changes.html").write_text(t.replace(seg, newseg), encoding="utf-8")


@case("changes.html 里某个版本的正文没渲染出来")
def _():
    _sub("changes.html", "修复死链", "修复")


@case("项目详情页没有更新记录区块")
def _():
    _sub("kayago.html", 'id="changes"', 'id="changelog-old"')


@case("首页没有更新记录入口")
def _():
    _sub("index.html", 'href="changes.html"', 'href="changelog.html"')


# --------------------------------------------------------------- 首页筛选
@case("首页某个分组筛选按钮指向不存在的分组")
def _():
    _sub("index.html", 'data-filter="group:tools"', 'data-filter="group:toolz"')


@case("「全部」按钮用了空键（group:）——点了会筛出 0 个项目")
def _():
    _sub("index.html", 'data-filter="" data-label="全部"', 'data-filter="group:" data-label="全部"')


@case("首页某张卡片丢了标签键")
def _():
    _sub("index.html", 'data-key="g:tools t:EPUB', 'data-key="g:tools')


@case("首页筛选脚本被删掉")
def _():
    f = SITE / "index.html"
    t = f.read_text(encoding="utf-8")
    t2 = re.sub(r"<script>\s*/\* 首页筛选.*?</script>", "<script></script>", t, flags=re.S)
    assert t2 != t, "找不到筛选脚本"
    f.write_text(t2, encoding="utf-8")


# --------------------------------------------------------------- 其它产物
@case("sitemap 漏了更新记录页")
def _():
    f = SITE / "sitemap.xml"
    t = f.read_text(encoding="utf-8")
    out = "\n".join(l for l in t.splitlines() if "changes.html" not in l)
    assert out != t, "sitemap 里本来就没有 changes.html"
    f.write_text(out, encoding="utf-8")


@case("update.json 丢了 snapshotAt")
def _():
    _edit_json("update.json", lambda d: d.pop("snapshotAt", None))


@case("update.json 的 schema 退回 1")
def _():
    _edit_json("update.json", lambda d: d.__setitem__("schema", 1))


def run_check():
    r = subprocess.run([sys.executable, "scripts/check_site.py", "--skip-links"],
                       cwd=ROOT, capture_output=True, text=True)
    return r.returncode, (r.stdout + r.stderr)


def main():
    if not (SITE / "index.html").exists():
        print("还没有产物：先跑 python3 scripts/build_site.py", file=sys.stderr)
        return 2
    if BACKUP.exists():
        shutil.rmtree(BACKUP)
    shutil.copytree(SITE, BACKUP)

    code, out = run_check()
    if code != 0:
        print("基线就不干净，先修好再跑变异测试：\n" + out, file=sys.stderr)
        return 2

    bad = []
    for name, fn in CASES:
        shutil.rmtree(SITE)
        shutil.copytree(BACKUP, SITE)
        fn()
        code, out = run_check()
        caught = code != 0
        print(f"{'✅' if caught else '❌ 没抓住'} {name}")
        if not caught:
            bad.append(name)
    shutil.rmtree(SITE)
    shutil.copytree(BACKUP, SITE)
    shutil.rmtree(BACKUP)

    code, out = run_check()
    if code != 0:
        print("还原后自检仍然失败，说明产物被改坏了：\n" + out, file=sys.stderr)
        print("（site/ 是构建产物，跑一次 python3 scripts/build_site.py 就能重建）", file=sys.stderr)
        return 2
    if bad:
        print(f"\n{len(bad)} 条守卫没抓住自己该抓的东西：")
        for b in bad:
            print("  - " + b)
        return 1
    print(f"\n{len(CASES)} 条守卫全部自证通过（注入缺陷 → 自检变红 → 还原 → 重新变绿）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
