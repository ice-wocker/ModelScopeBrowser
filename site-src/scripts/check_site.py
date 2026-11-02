#!/usr/bin/env python3
"""站点自检：在没有浏览器的情况下，抓住最容易静默出错的问题。

1. 下载链接是不是真的能下（商店页每个 APK 都 HEAD 一次，并核对字节数）
2. 站内链接有没有指向不存在的页面（拼错 id 就白给一个 404）
3. 有没有页面忘了 CNAME / 首页是否漏了某个项目
4. 商店页与 releases.json 是否一致（体积、版本、可安装集合、一键下载按钮）

用标准库，不引入 requests。为什么要有这个：站点的内容源是手写清单，
最典型的翻车方式是「改了 repo 名或 APK 文件名，但站点点进去 404」——
用户看到的是死链，构建却一片绿。
"""
import argparse
import hashlib
import html
import json
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SITE_DIR = ROOT / "site"
UA = {"User-Agent": "ice-suite-link-check/1.0"}


def load_releases():
    f = ROOT / "data" / "releases.json"
    if not f.exists():
        return {}
    return json.loads(f.read_text(encoding="utf-8")).get("releases", {})


def head(url, timeout=20, retries=2):
    """HEAD 一次；5xx 视为服务端临时故障，重试后仍失败算「存疑」而不是死。"""
    last = ""
    for attempt in range(retries + 1):
        req = urllib.request.Request(url, method="HEAD", headers=UA)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.status, ""
        except urllib.error.HTTPError as e:
            last = f"HTTP {e.code}"
            if e.code in (500, 502, 503, 504) and attempt < retries:
                continue
            return e.code, last
        except Exception as e:            # 网络/DNS/超时
            last = type(e).__name__
            if attempt < retries:
                continue
            return 0, last
    return 0, last


def _body_is_placeholder(body):
    """与 build_site.py 里同一判断：GitHub 自动生成的「只有对比链接」正文。

    这里重写一份而不是 import：两个脚本必须能独立运行（站点的「零依赖」也包括
    「任何一个脚本单独拷走都能跑」）。逻辑只有三行，重复的代价远小于耦合。
    """
    t = (body or "").strip()
    if not t:
        return True
    m = re.sub(r"\[([^\]]+)\]\(https?://[^\s)]+\)", " ", t)
    m = re.sub(r"https?://\S+", " ", m)
    m = m.replace("**", "").replace("*", "")
    m = re.sub(r"Full\s*Changelog", "", m, flags=re.I)
    return not re.sub(r"[\s:：\-]", "", m)


def _first_sentence(body):
    """取正文里第一段够长的话，用来断言「这段正文真的被渲染出来了」。"""
    for line in (body or "").splitlines():
        st = re.sub(r"^\s*([-*+]|\d+[.)]|#{1,6}|>)\s*", "", line).strip()
        st = re.sub(r"^\*\s*", "", st)       # 列表项前缀（- 或 *）也要剥掉
        st = re.sub(r"\*\*|`", "", st)
        if len(st) >= 12:
            return st[:40]
    return ""


def _first_plain(body):
    """正文第一句的纯文本（去掉行内标记），用于报错信息里指认是哪一句。"""
    first = _first_sentence(body)
    return re.sub(r"[*`_]", "", first)


def _not_rendered(body, page_html):
    """正文第一句是否**没有**出现在页面上。

    比对时把页面上的行内标记剥掉再比：原文里的 `**修复死链**` 与代码片段
    渲染后会变成 <b> / <code>，直接拿原文找当然找不到——那样写出来的检查
    永远为红（第一版就是这样，两条假阳）。要判断的是「这句话有没有被显示」，
    不是「HTML 里有没有逐字保留 Markdown 符号」。
    """
    first = _first_plain(body)
    if not first:
        return False
    plain_page = re.sub(r"<[^>]+>", "", page_html)
    plain_page = html.unescape(plain_page)
    return first not in plain_page


def esc(s):
    return html.escape(str(s), quote=False)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--skip-links", action="store_true", help="只做本地结构检查")
    args = ap.parse_args()

    ns = {}
    exec((ROOT / "data" / "projects.py").read_text(encoding="utf-8"), ns)
    projects = ns["PROJECTS"]
    site = ns["SITE"]
    author = site["author"]
    for p in projects:
        p.setdefault("repo_url", f"https://github.com/{author}/{p['repo']}")
    releases = load_releases()
    errors, warnings = [], []

    # --- 1. 产物结构 ---
    for f in ("index.html", "style.css", "search.json", "CNAME", "404.html", "store.html",
              "tags.html", "changes.html", "sitemap.xml", "robots.txt", "feed.xml",
              "icon.svg", "update.json"):
        if not (SITE_DIR / f).exists():
            errors.append(f"缺少产物 {f}")
    if (SITE_DIR / "CNAME").exists():
        got = (SITE_DIR / "CNAME").read_text(encoding="utf-8").strip()
        if got != site["domain"]:
            errors.append(f"CNAME 与配置不一致：{got} != {site['domain']}")

    # --- 1.5 样式表必须能被完整解析 ---
    # 这条是踩出来的：往 CSS 里插了一段 @media 却没闭合大括号，结果它之后
    # 的规则**全部失效**，页面变成无样式纯文本——而浏览器不报错、文件 HTTP 200、
    # 前几条规则照样能解析出来，肉眼也只在「打开页面」时才看得出来。
    # 校验方式刻意从简：去掉注释与字符串后数大括号，必须配平且结尾为 0。
    css_file = SITE_DIR / "style.css"
    if css_file.exists():
        css = css_file.read_text(encoding="utf-8")
        stripped = re.sub(r"/\*.*?\*/", "", css, flags=re.S)
        stripped = re.sub(r'"[^"\n]*"', '""', stripped)
        depth, bad = 0, False
        for ch in stripped:
            if ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth < 0:
                    bad = True
                    break
        if bad or depth != 0:
            errors.append(f"style.css 大括号不配平（扫描结束时深度 {depth}）——"
                          "这会让其后的规则全部失效，页面变成无样式纯文本")
        if "prefers-reduced-motion" not in css:
            errors.append("style.css 没有 prefers-reduced-motion 兜底")

    # --- 2. 项目页齐不齐 ---
    ids = [p["id"] for p in projects]
    for pid in ids:
        if not (SITE_DIR / f"{pid}.html").exists():
            errors.append(f"项目页缺失：{pid}.html")
    index = (SITE_DIR / "index.html").read_text(encoding="utf-8")
    for p in projects:
        if f'{p["id"]}.html' not in index:
            errors.append(f"首页没有收录：{p['id']}")

    # --- 3. 站内链接可达 ---
    broken = set()
    for html_file in SITE_DIR.rglob("*.html"):
        body = html_file.read_text(encoding="utf-8")
        # 去掉 <script>，否则会把内联 JS 里拼字符串的 '...href="'+d.url+'"' 当成链接
        body = re.sub(r"<script.*?</script>", "", body, flags=re.S)
        for href in re.findall(r'href="([^"#]+)"', body):
            if href.startswith(("http://", "https://", "mailto:")):
                continue
            target = (html_file.parent / href).resolve()
            if not target.exists():
                broken.add(f"{html_file.name} -> {href}")
    errors.extend(f"站内死链：{b}" for b in sorted(broken))

    # --- 3.5 商店页与 releases.json 一致 ---
    # 商店页上的「版本 / 体积 / 能否安装」必须是 releases.json 的投影。
    # 这层校验的价值在于：以后改渲染逻辑改坏了，不会静默上线一个空商店。
    store = (SITE_DIR / "store.html").read_text(encoding="utf-8") if (SITE_DIR / "store.html").exists() else ""
    if store:
        apks = re.findall(r'data-apk="([^"]+)"[^>]*data-apk-size="(\d+)"', store)
        if len(apks) != len(releases):
            errors.append(f"商店页可下载应用 {len(apks)} 个 != releases.json 里 {len(releases)} 个")
        for url, size in apks:
            repo = url.split("/")[4] if url.count("/") > 4 else ""
            rel = releases.get(repo)
            if not rel:
                errors.append(f"商店页有 releases.json 里没有的仓库：{repo}")
                continue
            if str(rel["size"]) != size:
                errors.append(f"{repo} 页面体积 {size} != 真实 {rel['size']}")
            if rel["url"] != url:
                errors.append(f"{repo} 页面链接与 releases.json 不一致：{url}")
        # 必须匹配按钮本身，不能只搜 "data-bulk" ——
        # 内联 JS 里也有 "[data-bulk]" 字样，宽匹配会让这条检查永远为真
        if not re.search(r'data-bulk="1"', store):
            errors.append("商店页缺少「一键下载全部」按钮")
        # 停止按钮与「继续」按钮必须各自有唯一选择器。
        # 之前两者共用 .qx，「继续」一点就等于点停止——功能整个失效，
        # 而页面看起来完全正常。这里只认类名，不认注释。
        for sel, what in (("qstop", "停止按钮"), ("qmore", "继续按钮")):
            if f'class="{sel} ' not in store and f' {sel}"' not in store:
                errors.append(f"商店页缺少{what}的独立类名 .{sel}（会与按钮互相误触发）")
        if "querySelector('.qstop')" not in store:
            errors.append("停止按钮未使用 .qstop 选择器")
        if not re.search(r'data-more="1"', store):
            errors.append("商店页缺少「继续」按钮")
        # 版本号必须以「列表里显示的那个」形式出现，避免只是碰巧在别处被提到
        for repo, rel in releases.items():
            if f'<span class="ver">{rel["tag"]}</span>' not in store:
                errors.append(f"{repo} 的版本号 {rel['tag']} 没有出现在商店页")
        # 首页要有入口
        if "store.html" not in index:
            errors.append("首页没有商店入口")

        # --- 3.55 update.json：给机器看的版本清单，必须与 releases.json 同源 ---
    # 这个文件的价值全在「准」：错一个字节，客户端就会把旧包当新包，
    # 或者把新包当成「已是最新」永远收不到更新。所以逐字段核对。
    up = SITE_DIR / "update.json"
    if up.exists():
        try:
            data = json.loads(up.read_text(encoding="utf-8"))
        except json.JSONDecodeError as e:
            errors.append(f"update.json 不是合法 JSON：{e}")
            data = None
        if data:
            apps = data.get("apps") or {}
            if data.get("schema") != 2:
                errors.append(f"update.json 的 schema 应为 2，实际 {data.get('schema')}")
            if set(apps) != set(releases):
                errors.append("update.json 的应用集合与 releases.json 不一致："
                              f"多 {sorted(set(apps) - set(releases))}，"
                              f"少 {sorted(set(releases) - set(apps))}")
            for repo, ent in apps.items():
                rel = releases.get(repo)
                if not rel:
                    continue
                latest = ent.get("latest") or {}
                if latest.get("url") != rel["url"]:
                    errors.append(f"update.json/{repo} 下载地址与 releases.json 不一致")
                if latest.get("size") != rel["size"]:
                    errors.append(f"update.json/{repo} 体积与 releases.json 不一致")
                if (latest.get("versionName") or "") != rel["tag"].lstrip("vV"):
                    errors.append(f"update.json/{repo} 版本号与 releases.json 不一致："
                                  f"{latest.get('versionName')} vs {rel['tag']}")
                # 地址必须直接能用：跳到 Releases 页面那种地址，客户端拿不到文件
                if not latest.get("url", "").lower().endswith(".apk"):
                    errors.append(f"update.json/{repo} 的 url 不是 APK 直链")
    else:
        errors.append("缺少产物 update.json（客户端没法检查更新）")

    # --- 3.6 智能下载层的守门 ---
        # 这块的价值在于：下载逻辑坏了在页面上看不出来（按钮在、样式对，
        # 只是慢或者下不来），所以必须由检查盯着。下面每条都验过「注入缺陷会变红」。
        if not re.search(r'data-apk="[^"]+"[^>]*data-apk-size="\d+"', store):
            errors.append("商店页的下载按钮没有带 data-apk-size（一键下载拿不到体积）")
        # 并行发起：并发数必须真的**被赋值**、且 >1。
        # 只搜 "MAX_PARALLEL" 是橡皮图章——注释里也有这个词，永远为真。
        # 要断言的是「这个数字确实进了下载循环」，所以连 while 条件一起钉。
        mp = re.search(r"MAX_PARALLEL\s*=\s*(\d+)", store)
        if not mp:
            errors.append("商店页没有给 MAX_PARALLEL 赋值——并行发起逻辑不见了")
        elif int(mp.group(1)) < 2:
            errors.append(f"MAX_PARALLEL={mp.group(1)}，退化成串行下载："
                          "8 个包会排队等建连，这正是慢的根因")
        elif not re.search(r"burst\s*<\s*MAX_PARALLEL\s*&&", store):
            errors.append("MAX_PARALLEL 没有被用在发起循环里（只有赋值没有使用）")
        # 曾经踩过的真坑：用 fetch 探测镜像速度。GitHub Release 与三个反代
        # 都不带 Access-Control-Allow-Origin，探针永远失败，等于白花 4 次请求
        # 且永远选不出源。这条守卫防止有人「好心」把它加回来。
        # 同样只认代码，不认注释：先剥掉 /* */ 与 // 再找 fetch。
        code = re.sub(r"/\*.*?\*/", "", store, flags=re.S)
        code = re.sub(r"^\s*//.*$", "", code, flags=re.M)
        if re.search(r"fetch\s*\(", code):
            errors.append("商店页又出现了 fetch 调用——下载地址不带 CORS 头，"
                          "任何基于 fetch 的测速/选源都必然失败")
        # 官方直连必须存在且是兜底：加速源可以默认开，但官方这条不允许被删掉，
        # 否则一旦所有反代挂掉就没有可退的路。
        if '"prefix": ""' not in store and 'prefix\": \"' not in store:
            errors.append("商店页没有配置官方直连源（空前缀）——加速源全挂时就无路可退")
        if '"role": "fallback"' not in store and 'role\": \"fallback\"' not in store:
            errors.append("商店页没有标记官方直连为兜底源（role=fallback）")
        for label in ("GitHub 官方", "兜底"):
            if label not in store:
                errors.append(f"商店页缺少下载源说明：{label}")

        # --- 地区选源逻辑的守卫 ---
        # 需求是「默认开加速 / 按地区选源」。实现必须满足下面几条，
        # 否则会退化成「永远直连」或「永远某个反代」——两种都不是按地区。
        if not re.search(r"function\s+slowDirect\s*\(", store):
            errors.append("商店页没有地区判断函数 slowDirect——"
                          "「按用户所在地区选源」的逻辑不见了")
        if "resolvedOptions" not in store or "timeZone" not in store:
            errors.append("地区判断没有读时区（Intl.DateTimeFormat.timeZone）")
        if "navigator.language" not in store and "navigator.languages" not in store:
            errors.append("地区判断没有读 navigator.language(s)")
        # 定义了还必须真的被调用。注意不能只搜 slowDirect()——函数声明本身
        # 就长这样，那样写是橡皮图章（第一版就是，注入「永不调用」抓不到）。
        # 要断言的是「调用出现在赋值/条件里」，所以连上下文一起钉。
        # slowDirect() 必须在「实际参与决策」的位置被调用。现在它被
        # wantAccel() 调用（`return slowDirect();`），所以同时接受
        # return / 赋值 / 三元这类上下文。纯 `function slowDirect(` 声明不算。
        if not re.search(r"(?:return|\?|\:|\=|\()\s*slowDirect\s*\(\s*\)", store):
            errors.append("slowDirect 定义了却没有被用来做决策（地区判断没生效）")
        # 「按地区选源」必须真的产出候选列表，而不是只判断了却不用
        if not re.search(r"function\s+wantAccel\s*\(", store):
            errors.append("商店页没有 wantAccel——地区判断没有被用来决定是否加速")
        # 不得用 navigator.connection.effectiveType 做地区判断。曾经加过
        # 「2g/3g 也走加速」，实测 headless Chromium 会随机把 effectiveType
        # 报成 3g，导致加速被误开；而且它说的是「我的链路慢」，不是
        # 「我到 GitHub 远」——慢链路上反代同样慢，套一层只是多一跳。
        # 只认代码不认注释：上面那段 JS 的块注释里正好写着「不能用它」，
        # 直接搜字符串会把说明本身当成违规。复用上面剥注释后的 code。
        if "effectiveType" in code:
            errors.append("商店页又用 effectiveType 判断地区了——"
                          "它会被随机报成 3g 导致误开加速，且语义是链路慢而非距离远")
        if "localStorage" not in store:
            errors.append("用户切换下载源后没有持久化（localStorage）——"
                          "用户改过的选择必须被记住")
        # 状态条：默认开加速的前提是「走了第三方」这件事看得见
        if 'id="srcbar"' not in store or 'id="srctoggle"' not in store:
            errors.append("商店页缺少下载源状态条 / 切换按钮——"
                          "默认开加速就必须让用户看得见、改得动")
        # 说明文案必须与实现一致：声称的并发数要真的是代码里的数。
        # 注意不能拿 "5" 当基准去比——那只是把常量抄了一遍，改 MAX_PARALLEL
        # 时文案照样写着 5，脚本却仍然绿。必须拿代码里的值去比文案。
        want = int(re.search(r"MAX_PARALLEL\s*=\s*(\d+)", store).group(1))
        claims = set(re.findall(r"每批(?:并行发起)? (\d+) 个", store))
        if not claims:
            errors.append("商店页没有写明每批并发几个下载")
        for c in sorted(claims):
            if int(c) != want:
                errors.append(f"商店页写「每批 {c} 个」与代码里的 MAX_PARALLEL={want} 不一致")
        # 不能吹「多线程下载大文件」：实测无收益，写了就是假话。
        # 提到它就必须同时给出「实测不成立」的依据，否则就是在当卖点讲。
        # --- 多源轮转 + 换源重试（针对「链接经常失效」）---
        # 旧实现把 8 个包全押在同一个源上，单个反代限速/挂掉就全灭。
        # 实测 ghproxy.net 在 61 MB 包上 3/3 次下不完，这种源一旦被选中，
        # 用户看到的就是「链接失效」。所以：
        #   ① 源必须按包轮转（sourceFor 用取模分摊），不能全局只选一个；
        #   ② 必须给用户逐包「换源重下」的入口——因为 CORS 决定了页面
        #      无法自动感知下载失败，自动重试做不到，只能让用户点一下。
        if "function sourceFor(" not in store:
            errors.append("商店页缺少 sourceFor——多源轮转不见了，"
                          "所有包会重新押在同一个源上")
        # 必须断言「包下标 i 参与了取模」，而不是「某处出现了 % list.length」——
        # 后者对 `((0) + (srcIdx[i]||0)) % list.length` 这种「退化成固定源」也成立。
        if not re.search(r"i\s*%\s*list\.length", store):
            errors.append("sourceFor 没有按包下标 i 轮转（i % list.length）——"
                          "源没有被真正分摊到各个包上")
        if not re.search(r"function\s+rotateFor\s*\(", store):
            errors.append("商店页缺少 rotateFor——没有「换到下一个源」的能力")
        # 断言「函数声明」——只搜 buildRetry 会在调用点命中，是橡皮图章
        if not re.search(r"function\s+buildRetry\s*\(", store):
            errors.append("商店页缺少 buildRetry 函数——没有逐包换源重下的入口，"
                          "下载失败时用户只能撞墙")
        if 'id="retry"' not in store:
            errors.append("商店页缺少 #retry 容器——换源重下的 UI 没有落点")
        # 只认按钮文案本身，不认周边说明里提到它
        if not re.search(r"textContent\s*=\s*['\"]换源重下['\"]", store):
            errors.append("商店页没有「换源重下」按钮的文案（textContent）")
        # 官方直连必须仍然排在候选列表最后（兜底）
        if not re.search(r"a\.concat\(\[d\]\)", store):
            errors.append("candidates() 没有把官方直连排在候选列表末尾（失去兜底）")

        if "多线程下载" in store and "实测不成立" not in store:
            errors.append("商店页提到「多线程下载」却没有说明它实测不成立——"
                          "这会被读成卖点，而事实恰好相反")

    # --- 3.7 页面骨架的一致性（导航 / 主题 / SEO） ---
    # 这一组盯的是「页面能打开、但少了一半东西」这类问题：
    # 加了新页面却忘了导航项、某张页面漏了 canonical、某个链接指向不存在的锚点。
    # 它们在浏览器里都不会报错，只是安静地缺一块。
    pages = sorted(SITE_DIR.rglob("*.html"))
    nav_counts = {}
    for f in pages:
        body = f.read_text(encoding="utf-8")
        # 每个页面的导航里应该恰好有 4 个站内/站外链接（全部项目 / 商店 / 标签 / GitHub）
        nav = re.search(r"<header class=\"top\">.*?</nav>", body, flags=re.S)
        if not nav:
            errors.append(f"{f.name} 没有导航栏")
        else:
            n = len(re.findall(r"<a [^>]*href=", nav.group(0)))
            nav_counts.setdefault(n, []).append(f.name)
        # 主题脚本必须在 <link rel=stylesheet> 之前，否则深色用户会闪一下浅色。
        # 注意不能拿「data-theme 这个字符串」当下标 —— <html data-theme> 就在
        # 第 2 行，永远排在样式表前面，那样写是橡皮图章（做过变异测试，抓不到）。
        # 要定位的是**那段脚本**的结尾 </script> 与样式表标签的先后。
        i_theme = body.find("localStorage.getItem(K)")
        i_css = body.find('rel="stylesheet"')
        if i_theme < 0:
            errors.append(f"{f.name} 没有内联主题脚本（首屏会闪一帧错误配色）")
        elif i_css >= 0 and i_theme > i_css:
            errors.append(f"{f.name} 的主题脚本排在样式表之后——会先闪一帧错误配色")
        # 每张可索引页面都要有 canonical，否则分享链接在别处是裸 URL
        if 'rel="canonical"' not in body and 'name="robots" content="noindex' not in body:
            errors.append(f"{f.name} 缺少 canonical（不是 noindex 页面就必须有）")
        if 'rel="icon"' not in body:
            errors.append(f"{f.name} 缺少 icon（浏览器标签页会是空白方块）")
    if len(nav_counts) > 1:
        errors.append("各页面的导航项数不一致："
                      + "；".join(f"{k} 项 → {','.join(v)}" for k, v in sorted(nav_counts.items())))

    # --- 3.8 站内锚点必须存在 ---
    # 「标签」「技术栈」都做成了页内锚点，拼错一个就得到一个点了没反应的链接——
    # 而站内链接检查只看文件是否存在，锚点它管不到。
    anchors = {}
    for f in pages:
        body = f.read_text(encoding="utf-8")
        anchors[f.name] = set(re.findall(r'id="([^"]+)"', body))
    for f in pages:
        body = re.sub(r"<script.*?</script>", "", f.read_text(encoding="utf-8"), flags=re.S)
        for href in re.findall(r'href="([^"]*#[^"]+)"', body):
            target, _, frag = href.partition("#")
            if target and not target.endswith(".html"):
                continue          # 外链锚点不查
            target = target or f.name
            if frag in ("main", ""):
                continue
            if frag not in anchors.get(target, set()):
                errors.append(f"{f.name} → {href} 锚点不存在")

    # --- 3.9 标签索引必须覆盖全部标签 ---
    # 标签页是首页标签行的落点。少一个标签，首页那颗 pill 就是死链。
    tags_page = (SITE_DIR / "tags.html").read_text(encoding="utf-8") if (SITE_DIR / "tags.html").exists() else ""
    all_tags = sorted({t for p in projects for t in p["tags"]})
    for t in all_tags:
        if f"<h2>{t}" not in tags_page:
            errors.append(f"tags.html 缺少标签「{t}」")
    # 反向：标签索引里不许出现清单外的标签
    listed = re.findall(r'<div class="indexgroup" id="[^"]+"><h2>([^<]+)', tags_page)
    for t in listed:
        if t.strip() not in all_tags:
            errors.append(f"tags.html 出现了清单外的标签「{t.strip()}」")
    if len(listed) != len(all_tags):
        errors.append(f"tags.html 标签数 {len(listed)} != 清单里的 {len(all_tags)}")

    # --- 3.10 Release 事实必须出现在项目页 ---
    # 用户从搜索结果直接落到详情页，看不到版本和体积就得再跑一趟商店。
    for p in projects:
        page_file = SITE_DIR / f"{p['id']}.html"
        if not page_file.exists():
            continue
        body = page_file.read_text(encoding="utf-8")
        rel = releases.get(p["repo"])
        if rel and rel["tag"] not in body:
            errors.append(f"{p['id']}.html 没有显示当前版本 {rel['tag']}")

    # --- 3.105 卡片结构：不允许 <a> 嵌套 <a> ---
    # 卡片里既有「进详情页」的链接，又有「进标签页」的链接。如果把整张卡片
    # 做成 <a>，浏览器会当场把内层 <a> 提到外层之外，卡片被拆成两半、
    # 页面上多出几个只有技术栈的幽灵方块 —— 而 HTML 源码、构建日志、
    # HTTP 状态码全都正常。肉眼不看页面是发现不了的，所以由这里钉住。
    #
    # 断言两件事：
    #   ① 首页卡片是 <div class="card reveal">，不是 <a>；
    #   ② 每张卡片里恰好有一个 .cardhit（整卡点击层），且**不含**嵌套的 <a>。
    # 只数 .cardhit 是不够的——上一版就是只数这个，改回整卡 <a> 时依然全绿。
    index_src = (SITE_DIR / "index.html").read_text(encoding="utf-8")
    if re.search(r'<a [^>]*class="card[ "]', index_src):
        errors.append("首页出现了 <a class=\"card\"> —— 整卡做成链接会把内层标签链接"
                      "提到外层之外，卡片被浏览器拆碎（页面看起来缺标题、多出幽灵方块）")
    card_blocks = re.findall(r'<div class="card reveal" data-key="[^"]*">(.*?)</div></div>',
                             index_src, flags=re.S)
    n_cards_expected = len(projects)
    if len(card_blocks) != n_cards_expected:
        errors.append(f"首页卡片数 {len(card_blocks)} != 项目数 {n_cards_expected}")
    for i, block in enumerate(card_blocks):
        if block.count('class="cardhit"') != 1:
            errors.append(f"第 {i+1} 张卡片缺少唯一的整卡点击层 .cardhit")
            break
        rest = block.replace('<a class="cardhit"', '<span class="cardhit"', 1)
        if re.search(r"<a [^>]*>(?:(?!</a>).)*?<a ", rest, flags=re.S):
            errors.append(f"第 {i+1} 张卡片里出现了 <a> 嵌套 <a>")
            break

    # --- 3.11 地区名单必须覆盖 CN/HK/MO/TW 的 IANA 时区 ---
    # 2026-10 的真问题：旧的时区名单漏了 Asia/Guangzhou，而个别国产 ROM
    # 的默认时区正是它。漏判的表现是「明明在大陆，页面却判定直连」——
    # 用户看到的是「加速没开」，而这里没有任何报错。
    # 从**代码里**取这两个名单，而不是从文件文本里取：
    # 文件正文有大段解释性注释，注释里也写着 "Asia/Guangzhou"（就是解释为什么补它），
    # 拿文本做包含判断永远是绿的——第一次写这条守卫时正是这么写错的。
    ns_m = {}
    exec((ROOT / "data" / "mirrors.py").read_text(encoding="utf-8"), ns_m)
    tz = set(ns_m.get("SLOW_DIRECT_TIMEZONES", ()))
    must = {"Asia/Shanghai", "Asia/Guangzhou", "Asia/Urumqi", "Asia/Hong_Kong",
            "Asia/Macau", "Asia/Taipei"}
    for t in sorted(must - tz):
        errors.append(f"data/mirrors.py 的 SLOW_DIRECT_TIMEZONES 缺少 {t}——"
                      "该时区的用户会被误判成「直连不慢」，加速不会自动开")
    langs = set(ns_m.get("SLOW_DIRECT_LANGS", ()))
    for lg in ("zh-CN", "zh-TW", "zh-HK"):
        if lg not in langs:
            errors.append(f"data/mirrors.py 的 SLOW_DIRECT_LANGS 缺少 {lg}")
    # 官方直连必须存在且 role=fallback，否则加速源全挂时无路可退
    mirrors = ns_m.get("MIRRORS", [])
    if not any(m.get("prefix") == "" for m in mirrors):
        errors.append("data/mirrors.py 里没有官方直连（空前缀）——加速源全挂时无路可退")
    if not any(m.get("role") == "fallback" for m in mirrors):
        errors.append("data/mirrors.py 里没有 role=fallback 的兜底源")
    # 每个加速前缀都必须是 https 且以 / 结尾：拼接方式是 prefix + 原始 URL，
    # 少了结尾斜杠就会拼成 https://gh-proxy.comhttps://github.com/...
    for m in mirrors:
        px = m.get("prefix") or ""
        if not px:
            continue
        if not px.startswith("https://"):
            errors.append(f"下载源 {m.get('id')} 的前缀不是 https：{px}")
        if not px.endswith("/"):
            errors.append(f"下载源 {m.get('id')} 的前缀缺结尾斜杠：{px}"
                          "（拼接方式为 prefix + URL，缺斜杠会拼出坏地址）")

    # --- 3.115 更新记录：页面上的版本史必须是快照的投影 ---
    # 这一整页的价值在于「原文照登」。所以检查的不是「有没有内容」，
    # 而是「页面上出现的版本集合」与 releases.json 里的历史**完全相同**——
    # 多一个版本（凭空长出来）和少一个版本（渲染时被过滤掉）都是假话。
    changes = (SITE_DIR / "changes.html").read_text(encoding="utf-8") \
        if (SITE_DIR / "changes.html").exists() else ""
    if changes:
        for repo, rel in releases.items():
            pid = None
            for p in projects:
                if p["repo"] == repo:
                    pid = p["id"]
                    break
            if not pid:
                continue
            seg = re.search(rf'<section class="applog" id="{pid}">(.*?)</section>',
                            changes, flags=re.S)
            if not seg:
                errors.append(f"changes.html 里没有 {repo} 的更新记录区块（id={pid}）")
                continue
            shown = re.findall(r'<li class="vitem"[^>]*><div class="vhead"><b>([^<]+)</b>', seg.group(1))
            hist = rel.get("history") or []
            want = [h["tag"] for h in hist] or [rel["tag"]]
            # 顺序必须与原样一致：倒过来读，用户会以为「最新版」是页面上第一条，
            # 而它其实是三年前那个。集合对、顺序错，比少一条更误导。
            if shown != want:
                errors.append(f"changes.html/{repo} 版本顺序或集合与快照不一致："
                              f"页面 {shown} vs 快照 {want}")
            for h in hist:
                if h.get("sha256") and h is hist[0]:
                    continue
                # 每个版本的下载地址必须原样出现在页面上（指向该版本的资产）
                if h.get("url") and h["url"] not in seg.group(1):
                    errors.append(f"changes.html/{repo} 缺少 {h['tag']} 的下载地址")
            # --- 版本顺序的独立证据：详情页正文里写着「上一版是谁」---
            # 只靠「页面顺序 == 快照顺序」是自证：两边都由同一份历史渲染，
            # 一起排错时这条检查照样绿。所以再多一条不依赖快照顺序的判据：
            # GitHub 自动生成的正文里明明白白写着 `compare/<上一版>...<这一版>`，
            # 拿它去对「页面上排在它后面的那一版」，对不上就是顺序真的错了。
            # KayaGo 的正文里是手写的本地 compare 链接，格式不同，这里只认
            # 这种标准写法，认不出就跳过——宁可漏，也不误报。
            cur_page = (SITE_DIR / f"{pid}.html").read_text(encoding="utf-8")
            dv = re.findall(r'<b>([^<]+)</b></span>', cur_page)
            for i2, h in enumerate(hist):
                # 版本号里本来就有点（v2.6.0），所以不能把 . 排除在字符类外——
                # 那样写永远匹配不上，这条检查会变成永不触发的橡皮图章。
                m = re.search(r"compare/(v[0-9][0-9A-Za-z._-]*)\.\.\.(v[0-9][0-9A-Za-z._-]*)",
                              h.get("body") or "")
                if not m or m.group(2) != h["tag"]:
                    continue
                if i2 + 1 >= len(hist):
                    continue
                if m.group(1) != hist[i2 + 1]["tag"]:
                    errors.append(
                        f"{repo} 的版本顺序可能错了：{h['tag']} 的正文写着自己接在 "
                        f"{m.group(1)} 之后，但快照里它后面是 {hist[i2 + 1]['tag']}")
            # 有正文的版本必须把正文渲染出来：抓下来却不显示，等于白抓
            for h in hist:
                if not _body_is_placeholder(h.get("body")):
                    continue
            latest = hist[0] if hist else None
            if latest and not _body_is_placeholder(latest.get("body")):
                # 正文里的第一句必须出现在页面上（转义后比较，避免 HTML 差异）
                if _not_rendered(latest.get("body") or "", seg.group(1)):
                    first = _first_plain(latest.get("body") or "")
                    errors.append(f"changes.html/{repo} 的 {latest['tag']} 正文没有渲染出来："
                                  f"页面上找不到「{first[:24]}…」")
    else:
        errors.append("缺少产物 changes.html（更新记录页）")

    # 详情页也要有更新记录入口，否则用户从搜索落地后不知道还有一整部历史
    for p in projects:
        f = SITE_DIR / f"{p['id']}.html"
        if not f.exists():
            continue
        body = f.read_text(encoding="utf-8")
        if 'id="changes"' not in body:
            errors.append(f"{p['id']}.html 没有更新记录区块（id=changes）")

    # --- 3.116 校验值必须与快照一致，且不许「猜」出来 ---
    # update.json 里的 sha256 会被客户端拿去验包：它错了就是「装不上 / 验不过」。
    # 所以两件事都要钉：①有值就必须与快照逐字节相同；②快照没有值时必须写 null，
    # 不能出现任何 64 位十六进制串（那意味着有人从别处「补」了一个值）。
    up_file = SITE_DIR / "update.json"
    if up_file.exists():
        try:
            up_data = json.loads(up_file.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            up_data = None
        if up_data:
            for repo, ent in (up_data.get("apps") or {}).items():
                rel = releases.get(repo)
                if not rel:
                    continue
                got = (ent.get("latest") or {}).get("sha256")
                want = rel.get("sha256")
                if want and got != want:
                    errors.append(f"update.json/{repo} 的 sha256 与快照不一致：{got} vs {want}")
                if not want and got is not None:
                    errors.append(f"update.json/{repo} 的 sha256 快照里没有，页面却写了 {got}——"
                                  "校验值不许推算")
                if got is not None and not re.fullmatch(r"[0-9a-f]{64}", str(got)):
                    errors.append(f"update.json/{repo} 的 sha256 不是 64 位小写十六进制：{got}")
            if "snapshotAt" not in up_data:
                errors.append("update.json 缺少 snapshotAt（客户端无法判断这份清单有多新）")
    # 详情页展示的校验值同理：有则一字不差，无则不许出现替代值
    for p in projects:
        f = SITE_DIR / f"{p['id']}.html"
        if not f.exists():
            continue
        body = f.read_text(encoding="utf-8")
        sha = (releases.get(p["repo"]) or {}).get("sha256")
        if sha:
            box = re.search(r'<h2 id="verify">.*?(?=<h2|$)', body, flags=re.S)
            if not box or sha not in box.group(0):
                errors.append(f"{p['id']}.html 的「校验安装包」区块里没有 sha256"
                              "（用户无法校验下载的包）")
        else:
            box = re.search(r'<h2 id="verify">.*?(?=<h2|<div class="pager")', body, flags=re.S)
            if box and re.search(r"\b[0-9a-f]{64}\b", box.group(0)):
                errors.append(f"{p['id']}.html 的快照里没有 sha256，页面却显示了一个 64 位值——"
                              "这是编出来的校验值，比没有更糟")

    # --- 3.117 首页筛选：按钮、标签键、脚本三者必须对得上 ---
    # 首页筛选坏了的表现很阴：按钮在、样式对，点了没反应或者筛出空列表。
    # 所以这里断言：①每个分组都有一个 data-filter 按钮；②每张卡片都带 data-key；
    # ③脚本真的存在且监听的是 #filterbar。
    if index_src:
        for slug, label, _ in ns.get("GROUPS", []):
            if not any(p["group"] == slug for p in projects):
                continue
            if f'data-filter="group:{slug}"' not in index_src:
                errors.append(f"首页缺少分组筛选按钮：{slug}")
        if index_src.count('data-key="') != len(projects):
            errors.append(f"首页带 data-key 的卡片 {index_src.count('data-key=')} 张 "
                          f"!= 项目数 {len(projects)}（筛选会漏或筛出多余项）")
        for t in all_tags:
            if f'data-filter="tag:{t}"' not in index_src:
                # 只有前 N 个标签会出现在首页标签行，其余在 tags.html——
                # 这里只要求「出现在首页的标签按钮」都带 data-filter，见下一条。
                continue
        if not re.search(r"getElementById\('filterbar'\)", index_src):
            errors.append("首页没有筛选脚本（#filterbar 没有被监听）——按钮点了不会有反应")
        # 按钮的 data-filter 必须与卡片 data-key 的取值域一致：
        # 拼错一个字符，点下去就是「0 个项目」，而页面上只会看到一片空白。
        keys = set()
        for m in re.findall(r'data-key="([^"]*)"', index_src):
            # data-key 里既有单个词的标签（Agent），也有带空格的（OpenAI API）。
            # 逐个 split() 会把后者拆成 "t:OpenAI" 与 "API"，永远对不上——
            # 所以按 " t:" / " g:" 切分，而不是按空白。
            for tok in re.split(r"\s+(?=[gt]:)", m.strip()):
                keys.add(tok.strip())
        for val in re.findall(r'data-filter="([^"]*)"', index_src):
            if not val:
                continue
            # 「全部」按钮必须是空 data-filter。写成 "group:" 这类空键会切出半个
            # 键值，表现为「点全部 → 0 个项目」——而页面上只是变空白，不会报错。
            kind, _, name = val.partition(":")
            if not name:
                errors.append(f"首页筛选按钮 data-filter=\"{val}\" 是空键——"
                              "「全部」应该用空字符串，否则点下去会筛出 0 个项目")
                continue
            tok = f"g:{name}" if kind == "group" else f"t:{name}"
            if tok not in keys:
                errors.append(f"首页筛选按钮 {val} 在卡片 data-key 里找不到对应项——"
                              "点下去会是 0 个项目")

    # --- 3.12 sitemap / robots / feed 必须与产物一致 ---
    sm = (SITE_DIR / "sitemap.xml").read_text(encoding="utf-8") if (SITE_DIR / "sitemap.xml").exists() else ""
    for p in projects:
        url = f"{p['id']}.html"
        if f"/{url}<" not in sm:
            errors.append(f"sitemap.xml 缺少 {url}")
    # 站点的「顶层页面」也要在 sitemap 里。只查项目页是不够的：
    # 新增一页（比如更新记录）忘了登记，搜索引擎就永远不知道它存在，
    # 而页面上一切正常、构建也是绿的。
    for name in ("store.html", "changes.html", "tags.html"):
        if f"/{name}<" not in sm:
            errors.append(f"sitemap.xml 缺少 {name}")
    robots = (SITE_DIR / "robots.txt").read_text(encoding="utf-8") if (SITE_DIR / "robots.txt").exists() else ""
    if "Sitemap:" not in robots:
        errors.append("robots.txt 没有声明 Sitemap")
    feed = (SITE_DIR / "feed.xml").read_text(encoding="utf-8") if (SITE_DIR / "feed.xml").exists() else ""
    if feed.count("<item>") != len(releases):
        errors.append(f"feed.xml 条目数 {feed.count('<item>')} != 可安装应用 {len(releases)}"
                      "（RSS 按发版推送，少一条就是漏了一个新版本）")
    for rel in releases.values():
        if rel["url"] not in feed:
            errors.append(f"feed.xml 没有包含 {rel['asset']} 的下载地址")

    # --- 4. 外链可达性 ---
    checked = 0
    if not args.skip_links:
        # 直接查商店页上的链接——那才是用户真正会点的东西。
        # 查别的来源等于「测了个寂寞」：页面渲染坏了照样绿。
        pairs = re.findall(r'data-apk="([^"]+)"[^>]*data-apk-size="(\d+)"', store)
        targets_all = [("APK", url) for url, _ in pairs]
        for p in projects:
            targets_all.append(("仓库", p["repo_url"]))
            if p.get("external_url"):
                targets_all.append(("站点", p["external_url"]))
        for label, url in targets_all:
            code, why = head(url)
            checked += 1
            if code == 200:
                continue
            if code in (0, 500, 502, 503, 504):
                warnings.append(f"[存疑] {label} {url} → {why}")
            else:
                errors.append(f"[死链] {label} {url} → {why}")

    # 搜索索引与项目数一致
    idx = json.loads((SITE_DIR / "search.json").read_text(encoding="utf-8"))
    if len(idx) != len(projects):
        errors.append(f"search.json 条目数 {len(idx)} != 项目数 {len(projects)}")

    print(f"项目数：{len(projects)}")
    print(f"可安装应用：{len(releases)}")
    print(f"页面数：{len(list(SITE_DIR.rglob('*.html')))}")
    print(f"外链检查：{checked} 条")
    for w in warnings:
        print(w)
    if errors:
        print("\n发现问题：")
        for e in errors:
            print(" ", e)
        return 1
    print("\n全部通过。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
