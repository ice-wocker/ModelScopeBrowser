# ice 工坊 · 站点源码

> **English:** Zero-dependency portfolio + app-store static site in pure Python stdlib. Fork it and make it yours.

[![CI](https://github.com/ice-wocker/icesuite/actions/workflows/ci.yml/badge.svg)](https://github.com/ice-wocker/icesuite/actions)
[![Release](https://img.shields.io/github/v/release/ice-wocker/icesuite)](https://github.com/ice-wocker/icesuite/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Live](https://img.shields.io/badge/Live-Demo-brightgreen)](https://ice-wocker.github.io/icesuite/)

> 一个作品集站点 + 应用商店，零第三方依赖，只用 Python 标准库从结构化数据渲染出纯静态 HTML。

**在线**：<https://ice-wocker.github.io/icesuite/> ·
**商店**：<https://ice-wocker.github.io/icesuite/store.html> ·
**标签**：<https://ice-wocker.github.io/icesuite/tags.html> ·
**RSS**：<https://ice-wocker.github.io/icesuite/feed.xml>

> **文档导航**
> [本文件](README.md)（门面 + 设计取舍） · [架构与数据流](docs/ARCHITECTURE.md) ·
> [自检与守卫](docs/CHECKS.md) · [部署与域名](docs/DEPLOY.md) ·
> [更新日志](CHANGELOG.md) · [域名切换手册](docs/DNS.md)

这个仓库是 [ice-wocker](https://github.com/ice-wocker) 全部项目的统一入口，
也是它自己的一个作品——因为它遵守同一套原则：**零第三方依赖、离线可用、体积可验证**。

它有两种用法，取决于你是谁：

- **访客**：直接打开商店页，一键下齐全部 8 个安装包。
- **开发者**：Fork 下来，改 `data/projects.py`，30 秒后得到一个属于你自己的同类站点。

---

## 为什么不用 MkDocs / VitePress

因为它们会给一个「主张零依赖」的账号加上一套会过期的构建链。

这个站的全部需求就是「把结构化数据渲染成 HTML，再放一份搜索索引」——
Python 标准库足够，产物是纯静态的：克隆下来双击 `index.html` 就能看，换任何托管都不用改代码。

代价是没有插件生态。以下功能都是手写的，因为它们都不需要依赖：

| 能力 | 实现 |
|---|---|
| 全站搜索 | 构建时预生成 `search.json`，运行时前端过滤；中文按二元切分，键盘可操作 |
| 站点图标 | 手写 `icon.svg`，没有引任何素材库 |
| 死链检查 | `urllib` + 自己实现的重试策略 |
| 下载加速 | 按地区选源 + 并行建连（不测速、不多线程，见下） |
| 主题切换 | 三档（跟随系统/浅色/深色），选择存 `localStorage`，首屏无闪烁 |
| 可被抓取 | `canonical` / `sitemap.xml` / `robots.txt` / RSS / JSON-LD，全部构建时生成 |
| 标签索引 | 从清单反推，46 个标签 + 双向校验（不多不少） |

---

## 快速开始

```bash
python3 scripts/build_site.py     # 渲染出 site/，不需要装任何东西
open site/index.html              # 双击就能看，不用起服务
```

想改成自己的：

```bash
# 1. data/projects.py 里的 SITE["author"] 改成你的 GitHub 用户名
# 2. PROJECTS 换成你的仓库列表
# 3. 拉一份你自己的 Release 快照
python3 scripts/fetch_releases.py --write
# 4. 重新构建
python3 scripts/build_site.py
```

发布：把它推到 GitHub，在 Settings → Pages 里选 GitHub Actions，
`deploy.yml` 会自动接管。合并进 `main` 即上线。

---

## 结构

```
data/projects.py              内容源：站点文案、项目清单、亮点、已知限制
data/releases.json            Release 快照：版本号/体积/下载地址（脚本生成，勿手改）
data/mirrors.py               下载源清单 + 地区判定规则（加速可默认开，官方永远兜底）
scripts/fetch_releases.py     从 GitHub API 冻结 Release 元数据；--check 用于 CI 防过期
scripts/build_site.py         渲染器：把清单投影成 site/ 下的静态 HTML
scripts/check_site.py         自检：产物完整性、站内死链、下载链接可达、商店页一致性
docs/ARCHITECTURE.md          一次请求到一页 HTML 的完整路径
docs/CHECKS.md                每条守卫在防什么、怎么验过它真的会红
docs/DEPLOY.md                发布流程、Pages 配置、回滚
site/                         构建产物（不入库）
```

构建产物里有 10 个非项目页文件，都是「别人会用到、漏了就静默坏掉」的东西：

| 文件 | 谁在用 | 漏了会怎样 |
|---|---|---|
| `sitemap.xml` / `robots.txt` | 搜索引擎 | 收录全靠爬虫自己猜，新项目可能几周都不进索引 |
| `feed.xml` | RSS 阅读器 | 没有「出新版」的通知渠道 |
| `tags.html` | 首页标签行、卡片技术栈 | 那些链接全部 404 |
| `icon.svg` | 浏览器标签页 | 显示一个空白方块 |
| `404.html` | GitHub Pages | 死链落到 GitHub 的默认 404，不带站内导航 |
| `CNAME` / `.nojekyll` | GitHub Pages | 自定义域名失效 / Jekyll 吞掉下划线开头的文件 |

**唯一真相源是 `data/projects.py`**，站点只是它的投影。
改内容只改这一个文件，不碰 HTML。

构建过程**不联网**：Release 事实读的是入库的快照文件，所以同一个 commit 永远产出同一份站点。
联网只发生在 `fetch_releases.py` 和 `check_site.py` 里。

---

## 应用商店

`store.html` 是本账号全部可安装应用的下载页，支持**一键下载全部**（8 个，66.8 MB）。

设计上有三条硬约束，每条都对应一个具体踩过的坑。

### 一、体积和版本号不手写

它们来自 `data/releases.json`，由 `scripts/fetch_releases.py` 从 GitHub Release 元数据冻结。
文案里要提体积就用 `{size}` 占位，构建时替换成真实值。

理由很实在：这类数字写死在文案里，**发一次版就变成假的，而且页面看起来一切正常**。
CI 里有个 job 专门对比快照与线上 Release——仓库发了新版却忘了重新生成快照，构建直接红。
这个 job 上线第一次跑就报错了，因为我自己测试时留了个假版本号在文件里。

### 二、一键下载不是打 ZIP

GitHub Pages 是纯静态托管，没有服务端可以打包。前端打包只有两条路，都不通：
引 JSZip 破了零依赖的口径，把 66.8 MB 读进内存手机上直接崩。

所以是**并行触发 8 个独立下载**。好处是单个失败不影响其他，
用户在系统通知栏里看到 8 个独立下载，断哪个重下哪个。

### 三、「从哪下」按地区自动选，「怎么下」靠并行建连

两件事分开看，因为一件有可靠的本地信号，另一件没有。

**「从哪下」：按地区选，不测速。**

直连 GitHub 的体验是**按地区两极分化**的，而且这个差异**稳定**：大陆普遍几百 KB/s
甚至断流，海外接近满速。既然稳定，就能在本地推断，一个请求都不用发：

| 信号 | 例子 | 来源 |
|---|---|---|
| 时区（主要） | `Asia/Shanghai` | `Intl.DateTimeFormat().resolvedOptions().timeZone` |
| 语言 / 地区 | `zh-CN` → `CN` | `navigator.language(s)` |

刻意**没有**用 `navigator.connection.effectiveType`。第一版加过「2g/3g 也走加速」，
实测 headless Chromium 会随机把 `effectiveType` 报成 `3g`（同 Tokyo / ja-JP
上下文 5 次里 1 次），加速被误开。而且语义不对：它说的是「我的链路慢」，
不是「我到 GitHub 远」——慢链路上反代同样慢，套一层只是多一跳。

命中 → 默认启用加速；否则默认官方直连（海外直连本来就不慢，套反代只是多一跳）。
默认走加速的前提是**它完全透明**：页面顶部有状态条显示当前源，一点就能切回官方，
选择存 `localStorage`。加速源打不开时会显式回退并在界面说明，不静默降级。

**「怎么下」：并行建连；下面两条都是实测后否掉的。**

| 想法 | 实测 | 结论 |
|---|---|---|
| 多线程分块下载大文件 | 同一文件开 1 / 2 / 4 条连接，总吞吐都是 ~6.2 MB/s | ❌ 单连接吞吐固定，加连接不增带宽 |
| fetch 并发测速、自动选最快镜像 | 四个候选**全部读不到数据** | ❌ 下载响应不带 `Access-Control-Allow-Origin`，计时恒为 0 |
| 并行发起多个安装包 | 单连接速度依赖文件大小：58 MB = 2.4 MB/s，0.07 MB = 0.78 MB/s | ✅ 小包慢在建连（TTFB 0.3~1.3s），并行后总吞吐是各连接之和 |

第二条那个实现**已经写过、也撤掉了**，因为它失败得很安静：
GitHub Release 会 302 到 `release-assets.githubusercontent.com`，
那一跳不带 `Access-Control-Allow-Origin`，三个反代同样不带——浏览器拿不到字节数。
探针全失败后会自动退回官方直连，页面照常下完，用户不会察觉，
区别只是白花 4 次请求而「智能选源」从未生效。

`check_site.py` 里因此有两条守卫：**禁止 `fetch` 出现在商店页的下载脚本里**，
以及**必须存在地区判断函数且真的被调用、有状态条、有持久化**——防止有人
把「按地区选源」退化成「永远直连」或「永远某个反代」。

镜像清单在 `data/mirrors.py`。加速源之间的快慢是随机的（实测差 50 倍以上），
没有可靠的本地信号，所以那部分不猜，交给用户手动切。

---

## 页面本身：这次补上的三处「看不见的坏」

站点的技术骨架没变，但有三处东西是「构建成功、自检通过、页面上却明显不对」的。
写下来是因为它们都不报错。

### 一、深色配色写在媒体查询外的单套变量里

`<meta name="color-scheme" content="dark light">` 写着「两种都支持」，实际只有一套深色。
浅色系统的用户看到深色页面，而地址栏与滚动条却按浅色渲染——视觉上是断的。

现在 `:root` 是深色底，`[data-theme=light]` 与 `prefers-color-scheme: light + theme=auto`
两处覆盖，变量名不变，所以组件样式一处没改。切换按钮三档循环（跟随系统/浅色/深色），
选择存 `localStorage`；内联在 `<head>`、在样式表之前执行，所以不会先闪一帧浅色。

### 二、卡片整块是 `<a>`，而卡片里还有链接

浏览器不允许 `<a>` 嵌套 `<a>`。它不会报错——它会**当场把内层 `<a>` 提到外层之外**，
于是卡片被拆成两半，页面上多出几个「只有技术栈、没有标题」的幽灵方块。

现在卡片是容器，里面一个绝对定位的透明 `<a>` 铺满整卡负责点击，标题另有一个真链接。
键盘用户在卡片上按 Tab 只停一次，读屏读到的名字是 `aria-label` 里的项目名。

### 三、入场动效把内容藏起来了

原来靠 JS 给卡片加 `.rv`（`opacity:0`），再由 IntersectionObserver 加 `.on` 显示。
回调没来时——后台标签页、无头浏览器、某些 WebView——卡片**永远不可见**，控制台一声不响。

现在顺序反过来：先由 JS 挂 `<html class="js">`，`.rv` 的隐藏态依赖它；
同时带一条 1.2 秒的 CSS 动画兜底，即使回调一次都不来，内容也会自己淡入。
打印样式里直接 `opacity:1 !important`。**宁可没有动画，也不能让内容依赖 JS 才可见。**

---

## 自检

```bash
python3 scripts/check_site.py            # 完整检查（含外链 HEAD）
python3 scripts/check_site.py --skip-links   # 只查本地结构与一致性
python3 scripts/fetch_releases.py --check    # 快照是否过期（CI 用）
```

`check_site.py` 盯的是**「构建成功但站点坏掉」**这类问题，因为这类错不会自己浮出来：

- 站内链接指向不存在的页面（拼错 id 就白给一个 404）
- 商店页的体积/版本/可下载集合与 `releases.json` 不一致
- 一键下载的并行数被改回 1、或者实现与文案对不上
- 下载脚本里又出现了 `fetch`
- 外链 404（5xx 判为「存疑」并重试，只有 404/410 才算死链）
- **样式表大括号不配平**：一条没闭合的 `@media` 会让它之后的规则**全部失效**，
  页面变成无样式纯文本，而浏览器不报错、文件 HTTP 200、前几条规则照样能解析
- 各页面的导航项数不一致（加页面时漏了一处，页面上看不出来，只是点不到）
- 主题脚本排在样式表之后（深色用户会先看到一帧浅色）
- 可索引页面缺 `canonical`，或缺 `icon`
- 站内锚点不存在（标签/技术栈都是页内锚点，拼错就是「点了没反应」）
- 标签索引与清单不一致（少一个 → 首页那颗 pill 是死链；多一个 → 引用了不存在的标签）
- 项目详情页没显示当前版本（用户从搜索直接落到详情页时看不到版本，得再跑一趟商店）
- 地区名单漏了某个 IANA 时区（漏判的表现是「加速没开」，没有任何报错）
- 下载源前缀不是 `https://` 或以 `/` 结尾（拼接方式是 `prefix + URL`，会拼出坏地址）
- `sitemap.xml` / `feed.xml` 与产物、Release 快照不一致

每条守卫都做过**变异测试**——注入对应缺陷确认会变红。
第一版里有两条是橡皮图章：只搜关键字，而注释里也有这个词，于是永远为真。
现在改成断言「被赋值 + 真的进了循环条件」，并在查找前先剥掉注释。

这一轮的每条新守卫都逐条验过，其中三条**第一版就是橡皮图章**，写下来当反面教材：

| 守卫 | 第一版怎么写的 | 为什么永远为绿 |
|---|---|---|
| 主题脚本顺序 | 找 `data-theme` 是否在 `rel="stylesheet"` 之前 | `<html data-theme>` 就在第 2 行，永远在前面 |
| 时区名单完整 | 在文件**文本**里找 `Asia/Guangzhou` | 文件注释里正解释着为什么补它，文本里一直有 |
| 卡片结构 | 只看渲染出的 HTML 有没有 `<a class="card">` | 源码里本来就长这样，坏的是浏览器解析后的 DOM |

第三条因此改成「结构上不允许 `<a>` 嵌套 `<a>`」：在构建时断言卡片内部没有再出现 `<a class="cardhit">`。
校验清单与做法见 [docs/CHECKS.md](docs/CHECKS.md)。

---

## 部署

合并进 `main` 后由 `.github/workflows/deploy.yml` 自动构建并发布到 GitHub Pages。
`build_site.py` 会同时产出 `CNAME`，自定义域名靠它。

换域名只需要改 `data/projects.py` 里的 `SITE["domain"]` 一处，
CI 有 job 专门校验它只出现一次。完整步骤见 `docs/DNS.md`。

---

## Star History
[![Star History Chart](https://api.star-history.com/svg?repos=ice-wocker/icesuite&type=Date)](https://star-history.com/#ice-wocker/icesuite&Date)

## 许可

[MIT](LICENSE)。站点内容（项目文案）随仓库一并授权。
