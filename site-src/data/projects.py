# -*- coding: utf-8 -*-
"""项目清单：站点的唯一内容源。

改内容只改这个文件，站点由 scripts/build_site.py 投影生成。
`repo` 对应 ice-wocker/<repo>；没有可安装产物的项目（脚本类 / 服务端类）
不写 `platform`，商店页会把它们归到「服务，不是安装包」那一栏，指向仓库而不是伪造一个包。

本文件只写「这个项目是什么」这类稳定事实：文案、分组、标签、亮点、已知限制。
「这个包现在是什么版本、多大、什么时候发的」全部来自 data/releases.json
（由 scripts/fetch_releases.py 从 GitHub API 冻结）。哪个仓库有可安装的 APK 也由那份文件决定——
不在清单里手写，否则发新版本忘改清单就会变成静默死链。

所以文案里要提体积时用 `{size}` 占位，构建时替换成真实值——
「70 KB」这种数字写死在文案里，发一次版就变成假的。
"""

SITE = {
    "name": "ice 工坊",
    "domain": "icesuite.eu.org",
    # base_url 才是「站点现在到底在哪」——它必须是一个**能打开的地址**。
    # 2026-09 实测：上面 domain 在公共 DNS 上是 NODATA（eu.org 侧没配 zone），
    # 解析失败，浏览器直接报错。CNAME 文件留着，等那边配好了把 base_url 换回去即可。
    "base_url": "https://ice-wocker.github.io/icesuite",
    "tagline": "零依赖 · 离线优先 · 极小体积",
    "author": "ice-wocker",
    "author_url": "https://github.com/ice-wocker",
    "desc": "ice-wocker 的十二个原创作品，全部零第三方依赖、离线可用、体积可验证。",
    # 域名当前状态：pending = 已申请但公共 DNS 还打不开
    "domain_status": "pending",
    "store_name": "ice 应用商店",
    "store_tagline": "一次下齐 · 全部离线可用 · 无追踪无广告",
}

GROUPS = [
    ("on-device-ai", "端侧 AI", "把模型塞进手机：本地推理、不上传、不联网也能用"),
    ("tools", "随身工具", "用完就走的实用小工具，装完即用"),
    ("media", "阅读与媒体", "书、音乐、声音——本地优先的消费与管理"),
    ("services", "服务与基础设施", "把设备变成服务端，或者让 API 更便宜"),
]

PROJECTS = []

PROJECTS += [
    # ---------------- 端侧 AI ----------------
    {
        "id": "icescribe",
        "repo": "iceScribe",
        "name": "iceScribe · 冰记",
        "group": "on-device-ai",
        "pitch": "装完即用的离线录音转写，<b>权限列表里没有网络</b>",
        "summary": "whisper.cpp 与模型内置在应用里，装完就能把语音转成文字。因为不申请 INTERNET 权限，"
                   "录音和文字在技术上没有任何路径离开这台设备——这不是承诺，是权限层面的事实。",
        "tags": ["离线", "隐私", "语音转写", "零依赖"],
        "metrics": [("第三方依赖", "0"), ("关键权限", "仅麦克风")],
        "platform": "android",
        "min_android": "8.0",
        "arch": "arm64-v8a",
        "store_note": "内置 57 MB 模型，包体就是这么来的；换 tiny 模型可降到约 39 MB。",
        "installation": "下载 APK 后直接安装。若系统提示「未知来源」，在安装界面允许本次安装即可；本应用不需要联网，装完就能用。",

        "status": "mature",
        "highlights": [
            ("无网络权限", "系统设置里点开权限列表，只有录音相关权限。飞行模式下照常工作。"),
            ("常数内存", "边录边实时重采样成 16 kHz 单声道写盘，转写按段流式读取，录一小时内存也不涨。"),
            ("跨段接续", "每 4 分钟一段，上一段的结尾文字作为提示词喂给下一段，断句和用词接得上。"),
            ("导入已有音频", "m4a / mp3 / wav / ogg 走系统解码器边解码边重采样，同一条链路转写。"),
            ("性能可调", "CPU 线程数 2 / 3 / 4 / 6 / 8 任选，不同机型大核数量差别很大。"),
        ],
        "tech": ["Java", "C++", "whisper.cpp", "NDK"],
        "known_limits": [
            "内置 base 模型 57 MB，安装包体积直接等于模型体积；换 tiny 模型可降到约 39 MB。",
            "仅 arm64-v8a，未做多 ABI 拆包。",
        ],
    },
    {
        "id": "modelscopebrowser",
        "repo": "ModelScopeBrowser",
        "name": "魔搭模型库",
        "group": "on-device-ai",
        "pitch": "浏览 25.9 万模型 · 下载 GGUF · <b>本地推理，模型能自己调工具</b> · 中英双语",
        "summary": "把魔搭社区的模型目录装进手机，下载 .gguf 后用内置 llama.cpp 纯 CPU 离线对话。"
                   "对话页是个真正闭环的智能体——模型可以自己调终端、联网、读写文件，拿到真实结果再继续推理。",
        "tags": ["端侧推理", "GGUF", "Agent", "llama.cpp", "中英双语"],
        "metrics": [("模型总量", "25.9 万"), ("单测", "99"), ("语言", "中 / EN")],
        "platform": "android",
        "min_android": "7.0",
        "arch": "arm64-v8a",
        "store_note": "默认不带模型；GGUF 模型在应用内按需下载，所以安装包只有 {size}。",
        "installation": "下载 APK 后直接安装。想聊天需要先在应用内下载一个 .gguf 模型，安装包本身不含模型。",

        "status": "active",
        "highlights": [
            ("六个维度筛选", "许可证 / 框架库 / 标签 / 语言 / 模型结构 / 领域，取值与数量实时来自接口聚合。"),
            ("下载可靠", "断点续传 + 前台服务通知，进程被杀后再进来自动接着下；完成后校验字节数与 GGUF 魔数。"),
            ("多档指令集", "arm64 编了多档后端，运行时按芯片自动选档，不用用户自己判断。"),
            ("工具调用闭环", "模型自己发指令 → 真执行 → 结果回灌 → 继续推理，不是假装的 function calling。"),
            ("中英双语", "<code>values/</code> 英文、<code>values-zh/</code> 中文，216 条逐条对应；"
                        "提示词、工具说明、执行结果也各出两版。切换语言不重启进程，已加载的模型不丢。"),
            ("提示词按模型规模分级", "≤2.5B 的小模型自动改用压缩版工具说明——同样的工具，"
                                 "说明短一半，模型才不至于把上下文全花在读说明书上。"),
            ("KV 前缀复用修对了", "上一轮生成的输出 token 留在 KV 里，而复用只按 prompt 匹配，"
                             "位置整体错位——表现是「聊到第五六轮开始答非所问」。现在按真实序列位置对齐。"),
        ],
        "tech": ["Java", "C++", "llama.cpp", "CMake"],
        "known_limits": [
            "引入了 8 个 AndroidX / Material / Markwon 库，与账号整体的「零第三方依赖」口径不一致，"
            "README 中已注明 AndroidX 属框架层的口径说明。",
            "首次编译需拉取 llama.cpp 源码（约 50 MB）。",
            "纯 CPU 推理，未开 Vulkan / OpenCL；手机上的速度天花板就在这里，"
            "0.5B 量化模型大致 10~25 tok/s，7B 只有个位数。",
            "长文档（<code>docs/</code> 下 10 份）已中英双语，但配图里的文字仍是中文。",
        ],
    },
    {
        "id": "kayago",
        "repo": "KayaGo",
        "name": "KayaGo",
        "group": "on-device-ai",
        "pitch": "从零手写的 MCTS 围棋 AI，<b>{size} 的安装包</b>",
        "summary": "不联网、不加载任何权重文件、不引任何第三方库的 Android 围棋应用。"
                   "引擎是从零实现的蒙特卡洛树搜索，另附一套纯 Java 的自对弈进化工具链——"
                   "这是账号里唯一「算法本身即资产」的项目。",
        "tags": ["围棋", "MCTS", "算法", "自对弈"],
        "metrics": [("第三方依赖", "0"), ("单测", "47")],
        "platform": "android",
        "min_android": "5.0",
        "arch": "universal",
        "store_note": "{size} 的安装包，不加载任何权重文件，装完立刻能下棋。",
        "installation": "下载 APK 后直接安装。引擎与规则内置，首次启动无需任何配置和下载。",

        "status": "active",
        "highlights": [
            ("规则内核", "坐标用带边框内部索引，边界统一为 WALL，省掉全部越界判断；棋块用单向链表，"
                        "落子与撤销都在 O(棋块大小) 量级。"),
            ("自对弈进化", "tools/ 下近 1500 行纯 Java 训练链（Evolve / Trainer / MatchRunner），改超参就能挂机跑进化。"),
            ("可复现性修复", "曾出现「同一 seed 换线程数就得到四个不同首手」，"
                            "根因是根候选过多导致胜率估计被噪声主导，收紧后贴边问题消失且棋力无损。"),
            ("棋力透明", "README 明说 9 路约业余级位、13/19 路明显更弱，并说明它不是 KataGo 那条路线。"),
        ],
        "tech": ["Java", "MCTS", "Gradle"],
        "known_limits": [
            "13 / 19 路搜索空间大、推演短，棋力明显弱于 9 路。",
            "没有神经网络与人类棋谱训练，不追求职业级棋力。",
        ],
    },
    # ---------------- 随身工具 ----------------
    {
        "id": "icescan",
        "repo": "iceScan",
        "name": "iceScan · 冰扫",
        "group": "tools",
        "pitch": "拍一张，自动找边拉直去阴影，<b>权限列表是空的</b>",
        "summary": "零第三方依赖、零权限、不联网的文档扫描 App。拍照交给系统相机、选图走系统文件选择器、"
                   "导出的 PDF 用自己的 ContentProvider 递出去——三件事都不要权限，所以权限列表真的是空的。",
        "tags": ["扫描", "PDF", "图像算法", "零权限"],
        "metrics": [("权限", "0 个"), ("单测", "34")],
        "platform": "android",
        "min_android": "7.0",
        "arch": "universal",
        "store_note": "权限列表是空的——相机走系统相机、选图走系统选择器，都不需要授权。",
        "installation": "下载 APK 后直接安装。装好后权限列表为空属正常现象，不是功能缺失。",

        "status": "mature",
        "highlights": [
            ("自动找边", "拍完立刻猜出纸张四角，拖角微调；找不到明显纸边时退成整幅画面等你手动拖，不拦着用。"),
            ("透视拉直", "由四组对应点解出单应矩阵，对输出图每个像素反查原图位置再双线性取色，斜着拍也拉得正。"),
            ("去阴影", "彩色档用「像素 × 全局均值 ÷ 局部背景」做光照归一化，同张纸一半被照、一半在阴影也能压平。"),
            ("自适应阈值", "黑白档用 Bradley 阈值，靠积分图做到 O(像素数)，阴影区不会被整片染黑。"),
        ],
        "tech": ["Java", "图像处理", "Gradle"],
        "known_limits": [
            "回归测试目前全部使用合成图，缺少真实拍摄图片的边界用例。",
        ],
    },
    {
        "id": "icebrowser",
        "repo": "iceBrowser",
        "name": "iceBrowser",
        "group": "tools",
        "pitch": "纯 Java 单 dex 浏览器，<b>{size}</b>（同类普遍 30 MB+）",
        "summary": "零第三方依赖的 Android 浏览器。真正的多 Tab、四个可切换搜索引擎、自研搜索引擎与本地爬取、"
                   "内置广告拦截与阅读模式。体积是它的招牌，但作者自己也说：轻量浏览器换不来账号同步与完美兼容。",
        "tags": ["浏览器", "广告拦截", "单 dex", "零依赖"],
        "metrics": [("第三方依赖", "0"), ("单测", "11")],
        "platform": "android",
        "min_android": "7.0",
        "arch": "universal",
        "store_note": "纯 Java 单 dex，{size}；同类主流浏览器安装包普遍在 30 MB 以上。",
        "installation": "下载 APK 后直接安装。它是独立浏览器应用，不会接管系统默认打开方式。",

        "status": "mature",
        "highlights": [
            ("真正的多 Tab", "每个 tab 独立 WebView，OS 级别隔离；target=_blank、intent://、market:// 全部拦在应用内。"),
            ("自研搜索引擎", "不依赖第三方 API，走 DuckDuckGo HTML 端点并带 Bing / Google 兜底，结果带 LRU 缓存。"),
            ("广告拦截可测", "规则解析已从 Android 类里拆成纯 Java 的 AdRules，11 个单测覆盖域名的 label 边界匹配、"
                            "@@ 例外优先级、坏正则隔离。"),
            ("内部资源不拦", "file / data / blob / about 协议永不拦截——这是自研广告拦截最容易踩的坑。"),
        ],
        "tech": ["Java", "WebView", "Shell"],
        "known_limits": [
            "浏览器赛道拥挤，轻量是特色而非刚需，兼容性与账号同步天然弱于主流浏览器。",
        ],
    },
    {
        "id": "icereading",
        "repo": "iceReading",
        "name": "iceReading",
        "group": "tools",
        "pitch": "{size} 的 EPUB 阅读器，<b>零云同步零追踪</b>",
        "summary": "纯 Java、零依赖、单 dex 的本地 EPUB 2/3 阅读器，带 OPDS 在线书库发现、本地扫描、"
                   "5 套主题与阅读统计。工程规范度是这个账号里最高的——有 CHANGELOG、有 PRIVACY、有 OPDS 发现文件。",
        "tags": ["EPUB", "阅读器", "OPDS", "零依赖"],
        "metrics": [("第三方依赖", "0"), ("内置书库", "6 个")],
        "platform": "android",
        "min_android": "7.0",
        "arch": "universal",
        "store_note": "{size}，零云同步、零追踪、零账号。",
        "installation": "下载 APK 后直接安装。首次打开会引导从本地目录或内置的 6 个 OPDS 书库添加书。",

        "status": "mature",
        "highlights": [
            ("OPDS 发现", "内置古登堡 / Standard Ebooks / Feedbooks 等 6 个在线书库，支持 Basic Auth 与 Bearer Token。"),
            ("五套主题", "日间 / 护眼 / 羊皮 / 夜间 / 深邃，字体、行距、段距、边距均可调。"),
            ("进度可迁移", "阅读进度 JSON 导入导出，换设备不用重来。"),
            ("工程完整", "CHANGELOG、PRIVACY、discover.xml 齐全，是这个账号里文档最齐的项目。"),
        ],
        "tech": ["Java", "WebView", "EPUB"],
        "known_limits": [
            "阅读器市场已非常成熟，差异化主要靠 OPDS 与极简，目标用户偏精准小众。",
        ],
    },
    # ---------------- 阅读与媒体 ----------------
    {
        "id": "musicfusion",
        "repo": "MusicFusion",
        "name": "MusicFusion",
        "group": "media",
        "pitch": "4 个合法音源聚合，<b>900 万曲 + 2900 电台</b>，全离线缓存",
        "summary": "纯 Java、零依赖的 Android 音乐播放器，聚合 Audius、Internet Archive、RadioBrowser、SomaFM "
                   "四个公开合法音源。零广告、零追踪、零账号，下载后可完整离线播放。",
        "tags": ["音乐", "播客", "离线缓存", "零广告"],
        "metrics": [("曲库", "900 万+"), ("电台", "2945"), ("音源", "4 个")],
        "platform": "android",
        "min_android": "7.0",
        "arch": "universal",
        "store_note": "聚合 4 个公开合法音源，900 万曲 + 2945 电台，可完整离线缓存。",
        "installation": "下载 APK 后直接安装。拉流需要联网，已下载的曲目可离线播放。",

        "status": "active",
        "highlights": [
            ("全部合法音源", "Audius、Internet Archive、RadioBrowser、SomaFM——没有灰色接口，也没有破解流。"),
            ("完整离线", "下载后本地缓存，飞行模式照常播放。"),
            ("零广告零追踪", "不接广告 SDK，不做埋点，不需要账号。"),
            ("从死链到可用", "README 里的下载链接曾长期 404（仓库零 Release），现已发布 v13 并实测可下。"),
        ],
        "tech": ["Java", "aapt/d8", "Shell"],
        "known_limits": [
            "构建未走 Gradle（裸 aapt/javac/d8），CI 依赖自研 build.sh，迁移成本不小。",
        ],
    },
    {
        "id": "musicfusionai",
        "repo": "MusicFusionAI",
        "name": "MusicFusion AI",
        "group": "media",
        "pitch": "给播放器加端侧 AI：<b>歌词翻译 · 卡拉OK · Android Auto</b>",
        "summary": "MusicFusion 的 AI 增强 add-on，13 个 Java 类提供端侧大模型、五大 AI 任务调度、"
                   "多源歌词、卡拉 OK 渲染，以及 Android Auto 与 Wear OS 支持。",
        "tags": ["端侧 LLM", "歌词", "Android Auto", "Wear OS"],
        "metrics": [("Java 类", "13"), ("AI 任务", "5")],
        "platform": "android",
        "min_android": "7.0",
        "arch": "universal",
        "store_note": "MusicFusion 的 AI 增强模块，{size}。",
        "installation": "下载 APK 后直接安装。建议与 MusicFusion 一起装：本体负责播放，它负责 AI 能力。",

        "status": "beta",
        "highlights": [
            ("端侧模型", "内置 Qwen2.5-0.5B 本地推理，翻译与分类不上传。"),
            ("五项 AI 任务", "补全、分类、命名、心情识别、翻译统一调度。"),
            ("多源歌词", "本地 / 网易云 / QQ / Genius 四路获取，失败自动回退。"),
            ("车载与手表", "Android Auto MediaBrowserService + Wear OS BLE 同步。"),
        ],
        "tech": ["Java", "端侧 LLM", "Wear OS"],
        "known_limits": [
            "此前构建只产 .class 不产 APK，README 却描述为可用模块；现已补齐打包链路并发布首个 APK。",
            "定位仍介于「模块」与「独立 App」之间，作为 v14 的预研存在。",
        ],
    },
    {
        "id": "frontier",
        "repo": "frontier-knowledge-base",
        "name": "前沿科技知识库",
        "group": "media",
        "pitch": "163 篇前沿科技专题，<b>逐条附来源，四千余条外链持续体检</b>",
        "summary": "覆盖 AI、硬件半导体、软件工程、数据工程、网络安全、基础科学、新兴科技、产业与社会的纯 Markdown 知识库。"
                   "关键事实就地标注来源，每篇文末列完整链接清单，另有每周全量外链可达性检查。",
        "tags": ["知识库", "Markdown", "全文搜索", "外链体检"],
        "metrics": [("文档", "163 篇"), ("领域", "9 个"), ("外链", "4181 条")],
        "status": "active",
        "external_url": "https://ice-wocker.github.io/frontier-knowledge-base/",
        "external_label": "知识库在线站点",
        "highlights": [
            ("零依赖静态站", "只用 Python 标准库把 docs/ 渲染成 173 个 HTML 页，克隆下来双击就能看。"),
            ("浏览器端全文搜索", "预生成 search.json + 前端过滤，不需要后端。"),
            ("外链体检", "把 5xx 临时故障与真 404 分开处理，每周全量扫描一次四千余条外链。"),
            ("Markdown 是唯一真相源", "站点只是投影，改内容只改 docs/。"),
        ],
        "tech": ["Markdown", "Python", "静态站点"],
        "known_limits": [
            "内容基于公开资料整理，不对来源准确性作担保，时效性以官方一手信息为准。",
        ],
    },
    # ---------------- 服务与基础设施 ----------------
    {
        "id": "icellm",
        "repo": "iceLLM",
        "name": "iceLLM",
        "group": "services",
        "pitch": "一行命令，把安卓手机变成 <b>OpenAI 兼容的本地 AI 服务器</b>",
        "summary": "跑在 Termux 里的单文件脚本，装完就在局域网得到一个本地大模型：自带 WebUI，"
                   "同时暴露 /v1/chat/completions，任何 OpenAI 客户端改个 base_url 就能用。完全离线，不要 API Key。",
        "tags": ["Termux", "本地 LLM", "OpenAI API", "单文件"],
        "metrics": [("体积", "单文件脚本"), ("部署", "一行命令"), ("模型", "3 个系列")],
        "status": "mature",
        "highlights": [
            ("一行命令", "curl 管道进 bash，不需要 clone、不需要装依赖。"),
            ("协议兼容", "暴露标准 OpenAI 端点，现有客户端改 base_url 即可接入。"),
            ("完全离线", "不要 API Key、不上云、数据不出设备。"),
            ("WebUI 自带", "手机浏览器直接对话，不用另装客户端。"),
        ],
        "tech": ["Shell", "Termux", "llama.cpp"],
        "known_limits": [
            "本质是把 Termux + llama.cpp 的安装流程脚本化，护城河较浅、最易被复制。",
        ],
    },
    {
        "id": "iceproxy",
        "repo": "iceProxy",
        "name": "iceProxy",
        "group": "services",
        "pitch": "一个端点，<b>8 个免费模型 / 5 家 provider</b>，OpenAI 协议直替",
        "summary": "单文件 Cloudflare Worker，把各家的免费额度模型统一成 OpenAI 兼容 API，"
                   "支持多账号轮换与跨 provider 回退。对已有客户端是 drop-in 替换。",
        "tags": ["Cloudflare Workers", "API 网关", "OpenAI 协议", "零冷启动"],
        "metrics": [("模型", "8 个"), ("Provider", "5 家"), ("单测", "15")],
        "status": "active",
        "highlights": [
            ("一致性测试", "有一条单测专门断言 PROVIDERS 里的每个模型都出现在 README 表格里——"
                          "此前文档宣传的 6 个模型在代码里一个都不存在，现在这类漂移会直接 CI 变红。"),
            ("多账号轮换", "Qwen 免费额度 2000 请求/天 × N 账号，跨 provider 自动回退。"),
            ("完整历史映射", "Gemini 路径此前只取最后一条消息，导致多轮对话失忆；现改为完整映射含 system 指令。"),
            ("测试真的在跑", "此前 npm test 因 glob 被引号包裹而从未执行过任何用例，修掉后 15 个测试全部运行。"),
        ],
        "tech": ["JavaScript", "Cloudflare Workers"],
        "known_limits": [
            "依赖上游免费额度政策，随时可能被调整或限制，稳定性不由本项目决定。",
        ],
    },
    {
        "id": "icesuite",
        "repo": "icesuite",
        "name": "ice 工坊（本站）",
        "group": "services",
        "pitch": "你正在看的这个站点：<b>零依赖生成器 + 纯静态产物</b>",
        "summary": "把项目清单渲染成站点的生成器。它必须和账号的主张一致，所以也零依赖——"
                   "不引 MkDocs / VitePress，只用 Python 标准库；产物是纯静态 HTML，"
                   "克隆下来双击 index.html 就能看，换任何托管都不用改一行代码。",
        "tags": ["静态站点", "Python", "零依赖", "自检"],
        "metrics": [("HTML 页面", "16 个"), ("第三方依赖", "0"), ("构建脚本", "3 个")],
        "status": "active",
        "release_asset": None,
        "highlights": [
            ("唯一真相源", "data/projects.py 是内容源，scripts/build_site.py 只是投影；改内容只改一处。"),
            ("自检比构建更严", "check_site.py 会 HEAD 每个下载链接、扫站内死链、对数搜索索引条目，"
                             "CI 定时任务每周全量查一遍外链——站点最典型的翻车是「改了文件名但站点点进去 404」。"),
            ("域名只有一处", "域名在 data/ 里只允许出现一次，CI 有 job 专门断言这件事，"
                           "避免 CNAME 和配置打架导致 Pages 悄悄掉回 *.github.io。"),
            ("主题与可访问性", "深色/浅色/跟随系统三档，选择存本地；跳转链接、焦点可见、"
                          "搜索框可用键盘上下键与回车——这些不是加分项，是「能不能用」的差别。"),
            ("可被抓取", "canonical、sitemap.xml、robots.txt、RSS、JSON-LD 全部构建时生成，"
                      "不引任何第三方统计脚本。"),
            ("商店页会自己核对", "版本号、体积、可安装集合全部来自入库的 Release 快照，"
                          "CI 联网比对一次——「发了新版但商店还挂着旧版」会直接构建失败。"),
            ("产物即仓库之外", "site/ 不入库，只有 data/ 与 scripts/ 是真相源，克隆体积一直是几十 KB。"),
        ],
        "tech": ["Python", "HTML", "GitHub Actions"],
        "known_limits": [
            "本站是清单的投影，不提供评论、后台与统计——没有服务端，也没有账号系统。",
        ],
    },
]
