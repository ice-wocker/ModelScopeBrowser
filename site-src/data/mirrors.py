# -*- coding: utf-8 -*-
"""下载加速源清单 —— **一个必须联网核对、因此必须显式维护的配置文件**。

这个文件和 data/releases.json 是同一类东西，但性质相反：

- `releases.json` 是「我的包长什么样」，由脚本从 GitHub API 自动冻结，事实稳定。
- 本文件是「**别人家的服务**」，列的是第三方反代/加速站。
  它们随时可能关停、限速、投毒，**没有任何自动机制能替你保证它们活着**。

所以这里的原则是：

1. **只列经过实测的**。每个前缀都真下过文件，也确认过它支持 HEAD 与 Range。
   没验过的地址不要往这里加。
2. **可以空着**。某个镜像挂了就直接删掉这一行，前端会自动少一个候选——
   不要为了「看起来选择多」留一个连不上的地址。
3. **官方永远是兜底**。`direct` 这条不允许删；任何加速失败都必须能退回它。

## 前缀怎么用

`prefix + 原始 Release URL` 就是加速地址：

    原始: https://github.com/ice-wocker/iceBrowser/releases/download/v5.0.1/icebrowser.apk
    加速: https://gh-proxy.com/https://github.com/ice-wocker/iceBrowser/releases/download/v5.0.1/icebrowser.apk

所以每个前缀**必须以 `/` 结尾**。空字符串 `""` 表示直连 GitHub 官方。

## 默认用哪个源：按地区选，不是按测速选

**先澄清一个被证伪的做法。** 前端曾经实现过「fetch 并发探测、按实测速度挑最快的源」，
那条路走不通：GitHub Release 会 302 到 `release-assets.githubusercontent.com`，
而那一跳**不带 `Access-Control-Allow-Origin`**，三个反代同样不带。
浏览器因此读不到响应字节数，任何基于 fetch 的计时都恒为 0。
更麻烦的是它**看起来能用**——探针全失败后照样退回官方、页面照常下完，没有报错。
所以 `check_site.py` 里有一条守卫专门禁止 `fetch` 出现在商店页的下载脚本里。

**那「默认开加速」靠什么？靠地区判断，不靠测速。**

理由：GitHub 直连的体验是**按地区两极分化**的。直连在大陆普遍是几百 KB/s 甚至断流，
在海外则接近满速。这个差异是**稳定的**、和「当时当地」无关，所以可以在本地推断，
不需要发任何探测请求。反过来，镜像之间的快慢才是随机的——那部分留给用户手动切。

判断只用**本地信号**，三个，全部零请求：

| 信号 | 取值 | 作用 |
|---|---|---|
| `Intl.DateTimeFormat().resolvedOptions().timeZone` | 如 `Asia/Shanghai` | 最强的地区信号 |
| `navigator.language` / `languages` | 如 `zh-CN` | 辅助 |

> 2026-10 补了一条：旧的时区名单漏了 `Asia/Guangzhou`、`Asia/Chungking`，
> 而个别国产 ROM 的默认时区正是前者。漏判的表现是「明明在大陆、页面却判定成直连」，
> 用户看到的就是「加速没开」。所以名单按 IANA 的 CN/HK/MO/TW 集合补齐，
> `check_site.py` 里加了一条守卫：名单必须覆盖这 4 个地区的全部 IANA 时区。

> 刻意**不用** `navigator.connection.effectiveType`。曾加过「2g/3g 也走加速」，
> 实测 headless Chromium 会随机把 `effectiveType` 报成 `3g`，导致加速被误开；
> 而且这个信号的语义是「我的链路慢」，不是「我到 GitHub 远」——两回事。

命中大陆时区/语言 → 判定为「直连大概率很慢」→ **默认启用加速**，
并把各包在候选源间**轮转**（详见文件末尾 ACCEL_DEFAULT 处的说明）。
其余情况 → 默认直连（海外直连本来就不慢，套一层反代只是多一跳）。
**两种情况下都保留了显式开关**，且开关状态存在 localStorage 里，用户改过就以用户为准。

## 关于「把下载流量交给第三方」

这是这个文件里唯一需要斟酌的伦理问题。做法是：

- **加速默认开，但必须可见**：商店页顶部有一行状态，写明「当前走加速源 X」，
  一点就能切回官方，不用去翻设置。
- **不静默降级**：加速源打不开时，页面显式提示「已回退到官方直连」，而不是假装没事。

理由是用户要的是「快」，而不是「绝对不经第三方」——后者是手段不是目的。
但「走了谁的路」这件事必须由用户看得见、改得动。默认开 + 完全透明，
比「默认关 + 藏在配置里」更接近用户的实际诉求。
"""

# 加速源。列表顺序 = 在大陆的推荐优先级（越靠前越先被选中）。
# 实测口径：本环境对 iceScribe 的 58.3 MB 包取 3 MB 计时。
MIRRORS = [
    {
        "id": "direct",
        "label": "GitHub 官方",
        "prefix": "",
        "role": "fallback",          # fallback = 永远可用、永远兜底，不允许删
        "note": "官方直连。没有中间人，也不依赖任何第三方服务活着。海外速度正常，大陆普遍偏慢。",
    },
    {
        "id": "ghproxy",
        "label": "gh-proxy.com",
        "prefix": "https://gh-proxy.com/",
        "role": "accelerator",
        "note": "大陆反代。本环境实测 61 MB 包 3/3 次完整下载（6.2 MB/s，与直连同级），小包 8/8 次成功。作为首选加速源。",
    },
    {
        "id": "ghfast",
        "label": "ghfast.top",
        "prefix": "https://ghfast.top/",
        "role": "accelerator",
        "note": "大陆反代。本环境实测 61 MB 包 3/3 次完整下载（1.9~4.5 MB/s，抖动较大）。备选。",
    },
]

# ---------------------------------------------------------------- 地区判定规则
# 判定「直连大概率很慢」的地区信号。命中任意一条即视为大陆网络环境。
# 只做前缀/精确匹配，不做模糊包含——宁可漏判（退回直连）也不要误判。
#
# 这份名单来自 IANA 时区数据库里的「中国大陆 + 港澳台」集合。
# 旧版漏了 Asia/Guangzhou、Asia/Shanghai 之外的几处（如 Asia/Harbin 有，
# 但 Asia/Guangzhou 没有），而它在部分国产 ROM 上正是默认时区——
# 漏判的后果是「明明在大陆却走了直连」，也就是用户看到的「还是慢」。
SLOW_DIRECT_TIMEZONES = (
    "Asia/Shanghai", "Asia/Chongqing", "Asia/Chungking", "Asia/Harbin",
    "Asia/Urumqi", "Asia/Kashgar", "Asia/Guangzhou", "Asia/Macau",
    "Asia/Hong_Kong", "Asia/Taipei", "PRC", "ROC",
)
SLOW_DIRECT_LANGS = ("zh-CN", "zh-Hans", "zh", "zh-Hant", "zh-TW", "zh-HK", "zh-MO")
SLOW_DIRECT_REGIONS = ("CN", "HK", "MO", "TW")

# 默认是否启用加速。注意这不是「默认走某个镜像」，而是
# 「先按地区判断该不该走加速；要走时，把各个包在**全部候选源之间轮转**，
#   而不是所有包都押在第一个源上」。
#
# 为什么改成轮转：旧实现只挑一个源给所有包用，单个反代限速/挂掉就整批全灭。
# 实测 ghproxy.net 在 61 MB 包上 3/3 次下不完（小包正常），
# 这种源一旦被选中，用户看到的就是「链接经常失效」。
ACCEL_DEFAULT = "auto"          # auto | on | off

# 说明：这里**故意不提供**「探测字节数」和「分块并发数」这类参数。
#
# 前者属于已被证伪的 fetch 测速方案（见上），留着只会诱导别人重新捡起来；
# 后者属于「多线程下载大文件」—— 实测同一文件开 1/2/4 条连接总吞吐都是 ~6.2 MB/s，
# 说明单连接吞吐固定、加连接不增带宽，所以本就不该有分块下载这个功能。
#
# 如果以后 GitHub 放宽了下载响应的 CORS 头，「按实测速度自动选源」才值得重做，
# 那时再把探测参数加回来。
