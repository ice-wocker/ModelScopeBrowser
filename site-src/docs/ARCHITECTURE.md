# 架构与数据流

> 一次构建、一次请求、一次点击，分别发生了什么。
> 想改内容去 `data/projects.py`；想知道为什么这么设计，看这里。

## 1 三层，只有三层

```text
data/         真相源（手写 + 脚本冻结的快照）
  ↓ exec()
scripts/build_site.py    渲染器
  ↓
site/         纯静态产物（不入库）
```

没有插件、没有模板引擎、没有配置合并。`build_site.py` 用 f-string 拼 HTML，
`load()` 用 `exec()` 把 `data/*.py` 当配置读进来——这两件事在正经项目里都是坏味道，
在这里是刻意的：**依赖越少，三年后还能构建的概率越高**。

`data/projects.py` 只写「这个项目是什么」这类稳定事实（文案、分组、标签、亮点、已知限制）。
「这个包现在是什么版本、多大、什么时候发的」全部来自 `data/releases.json`，
由 `scripts/fetch_releases.py` 从 GitHub API 冻结。**构建过程不联网**——
同一个 commit 永远产出同一份站点，CI 里有一条 job 专门验证这一点。

## 2 构建：从清单到 16 张页面

```text
load()
 ├── data/projects.py   → SITE / GROUPS / PROJECTS
 ├── data/releases.json → 每个 repo 的 tag / size / published_at / url
 └── data/mirrors.py    → 下载源清单 + 地区判定规则
        ↓
   给每个项目派生：group_label / url / version / size_text / installable / repo_url …
        ↓
build()
 ├── style.css           主题变量 + 组件样式（一份，被所有页面共用）
 ├── search.json         搜索索引（标题 + 摘要 + 标签 + 指标 + 亮点 + 技术栈）
 ├── index.html          首页：hero、统计、搜索、标签行、四个分组
 ├── <id>.html × 12      项目页：亮点、已知限制、技术栈、Release 事实、上下篇
 ├── tags.html           标签索引（从清单反推，双向校验）
 ├── store.html          应用商店（含内联下载脚本）
 ├── 404.html            带搜索框的兜底页
 ├── sitemap.xml / robots.txt / feed.xml / icon.svg
 └── CNAME / .nojekyll
```

关键点：**`{size}` 占位在 `load()` 里替换**，所以页面上写的体积与 Release 里的字节数
永远是同一个数。文案里写死「69 KB」，发一次版就变成假的，而页面看起来完全正常。

## 3 页面骨架：一件事只写一次

`page()` 是唯一的页面外壳。导航项由 `nav_items()` 生成——加一个导航项只需要改一处。
这条规则有代价：任何「某张页面想多一个导航项」的临时需求都变得麻烦。
值得，因为反过来（每页手写导航）的失败方式更糟：**总有几页会被忘掉，
而界面上完全看不出来，只是少一个入口**。

`<head>` 里的顺序是刻意的：

```text
charset → viewport → title → description → robots → color-scheme
→ theme-color ×2 → icon → feed → canonical → og:* → twitter:* 
→ 主题脚本 → style.css → JSON-LD
```

主题脚本必须在 `style.css` **之前**：它在任何样式生效前把 `data-theme` 挂到 `<html>`，
否则深色用户会先看到一帧浅色。CI 有一条守卫断言这个顺序（用「脚本里那段
`localStorage.getItem`」定位，不是用 `data-theme` 这个字符串——后者就在第 2 行，永远在前）。

## 4 主题：三档，不是两档

```text
localStorage['icesuite.theme'] ∈ {auto, light, dark}   （缺省 auto）
        ↓ 内联脚本在 <head> 里读一次
<html data-theme="auto|light|dark">
        ↓ CSS
:root                                  → 深色（默认）
html[data-theme=light]                 → 浅色
@media (prefers-color-scheme: light)
  html[data-theme=auto]                → 跟系统
```

为什么不是「只跟随系统」：深色偏好是**设备级**设置，而用户在某个站点上的偏好
常常和系统不同（系统夜里自动切深色，但他就是想看清图表）。显式覆盖 + 记住选择，
比「猜他想要什么」诚实。

## 5 搜索：预生成索引 + 纯前端过滤

```text
构建时  →  search.json（12 条，每条含 title/summary/text/url/tags）
运行时  →  fetch('search.json') 一次，输入时本地过滤（120ms 防抖）
```

三处刻意设计：

1. **必须能降级**。`fetch` 失败（`file://` 打开、静态托管漏了文件）时，输入框不能变成
   「输什么都没反应」的死控件——所以失败时明说「索引没加载到」，并给出标签索引作为兜底入口。
2. **键盘可用**。上下键移动、回车打开、Esc 清空，高亮项用 `aria-selected` 表达而不只是颜色。
3. **中文按二元切分**。英文按空格分词天经地义，中文没有空格：「本地推理」这个词
   用户可能只打「推理」。所以中文查询额外生成 bigram 候选，任一片命中即算命中。
   这不是搜索引擎，但比「整串不匹配就零结果」好得多。

## 6 商店页的下载层

这部分单独说吧，它是整个站点唯一有运行时逻辑的地方。

```text
candidates()  = [用户固定的源]  或  [加速源…, 官方直连]（地区决定要不要加速）
sourceFor(i)  = candidates()[(i % n + 换源次数[i]) % n]     ← 第 i 个包用哪个源
fire(i)       = 创建 <a href=prefix+url download=name>，挂进 DOM 再 click()
tick()        = 每批 MAX_PARALLEL 个，发完就停，等用户点「继续」
```

三道约束，每条都对应一次实测结论（完整数据在 `README.md` 与 `CHANGELOG.md`）：

| 约束 | 依据 |
|---|---|
| 不做「多线程分块下载」 | 同一文件开 1/2/4 条连接总吞吐都是 ~6.2 MB/s，单连接吞吐固定 |
| 不做「fetch 测速选源」 | Release 302 到不带 CORS 头的域名，四个候选全读不到字节数，计时恒为 0 |
| 不做「自动重试」 | 下载走 `<a>` 导航，跨域读不到任何响应信息，页面**无法知道**某个包有没有下成 |

所以能做的是：多源轮转（单源挂掉不全灭）+ 逐包「换源重下」（把失败变成用户一眼可见
且一键可恢复）。这两个都在，且都有守卫。

## 7 一次点击到一张页面（没有服务端）

```text
浏览器 GET /icesuite/modelscopebrowser.html
  → GitHub Pages（纯静态，没有函数、没有重写规则）
  → 返回构建时生成的 HTML 字节
  → 解析 <head> 内联主题脚本 → 立刻挂 data-theme（无闪烁）
  → 加载 style.css（一份，全站共用）
  → DOMContentLoaded 之后：主题按钮、入场动效
  → 首页额外 fetch search.json；商店页额外执行内联下载脚本
```

没有任何请求会打到第三方域：没有统计、没有字体 CDN、没有图标库。
这一点在页脚里写着，也就意味着它可被核对——打开开发者工具的网络面板就能验。
