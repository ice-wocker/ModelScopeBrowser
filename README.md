# 魔搭模型库 (ModelScopeBrowser)

一个轻量 Android App，用来浏览[魔搭社区 ModelScope](https://www.modelscope.cn/models)上的**全部大模型**：分页列表、关键字搜索、排序、按维度筛选、模型详情与文件下载，并内置网页兜底模式。

当前版本：**v1.2**（versionCode 3）

## 功能

- **模型列表**：分页加载魔搭全部模型（当前接口返回总量约 **25.9 万个**），滑到底自动加载下一页
- **关键字搜索**：按模型名 / 中文名检索，如 `Qwen`、`DeepSeek`、`语音`
- **排序**：综合排序 / 最多下载 / 最多收藏 / 最近更新
- **维度筛选**：按 **许可证 / 框架库 / 标签 / 语言 / 模型结构 / 领域** 过滤，取值与数量实时来自接口聚合
- **列表卡片**：中文名、`命名空间/模型名`、任务类型、下载量、收藏数、许可证、标签、简介
- **详情页**：基本信息 + **模型文件列表**（含体积、LFS 标记，可单个下载）+ **Markdown 简介渲染**
- **交互**：下拉刷新、加载/空态/错误态与一键重试、Material 3 卡片式列表、**暗色模式自动适配**
- **网页模式**：右上角一键进入，直接加载 `modelscope.cn/models`，作为任何异常情况下的兜底

## 下载安装

- 仓库内：[`dist/ModelScope-Models.apk`](dist/ModelScope-Models.apk)
- 或到 [Releases](../../releases) 下载

要求：Android 7.0 (API 24) 及以上。首次安装需允许「安装未知来源应用」。

## v1.2 更新

**修复「404 page not found」**

v1.0/v1.1 的列表请求一直报 `404 page not found`，根因是我用错了请求方式与路径。通过抓取魔搭网页自身发出的请求，确认真实接口是：

| 项 | 错误做法（v1.1） | 正确做法（v1.2） |
|---|---|---|
| 路径 | `/api/v1/dolphin/models` | 路径相同，但… |
| HTTP 方法 | `POST` / `GET` | **`PUT`**（用 POST/GET 访问该路径会返回 404） |
| 筛选参数名 | `SingleCriterion` | **`Criterion`**（`SingleCriterion` 会被服务端忽略） |
| 筛选维度 | 任务类型（`category:"tasks"`） | **许可证 / 框架库 / 标签 / 语言 / 模型结构 / 领域**（即响应里 `Data.FiledAgg` 的字段） |

实测对照（同一路径、不同方法）：

- `POST` → `404 page not found`；`GET` → `404 page not found`；**`PUT` → `200`**
- `PUT` + `{"Name":"qwen"}` → `TotalCount 26291`（搜索生效）
- `PUT` + `{"SortBy":"StarsCount"}` → 首位变为高星标模型（排序生效）
- `PUT` + `{"Criterion":[{"category":"license","predicate":"contains","values":["apache-2.0"]}]}` → `TotalCount 48472`（筛选生效，与聚合计数一致）

**其他变更**

- 「任务筛选」替换为上述**真实生效**的维度筛选，二级弹窗直接展示可选项与模型数量
- 模型总数按新接口校正为约 **25.9 万**
- 顺带移除已确认无效的任务筛选相关代码

## v1.1 更新

**升级**

| 项 | v1.0 | v1.1 |
|---|---|---|
| AGP / Gradle | 8.7.3 / 8.14.5 | **8.13.2** / 8.14.5 |
| compileSdk / targetSdk | 34 / 34 | **36 / 36**（Android 16） |
| 依赖 | 零第三方 | AndroidX + Material 3 + Markwon |
| 列表控件 | ListView | RecyclerView + SwipeRefreshLayout |
| 主题 | 系统 Material | **Material 3 DayNight**（支持暗色） |
| 能力 | 列表 / 搜索 / 详情 | **+ 排序 / 任务筛选 / 文件列表与下载 / Markdown 简介** |
| 签名 | debug | **正式 release keystore** |
| CI | 无 | **GitHub Actions 自动构建 + Tag 自动发布** |

**修复的 Bug**

1. **双重标题栏**：原来主题带系统 ActionBar，与自定义标题栏重复显示。改为 `NoActionBar` + MaterialToolbar。
2. **首页聚合兜底导致的分页报错**：降级到首页聚合接口时只有 10 条数据，但 `hasMore` 仍为 true，下滑会继续请求并必然失败。现由 `Page.fallback` 标记终止分页。
3. **搜索竞态覆盖**：快速连续搜索时，先发出的旧请求可能后返回并覆盖新结果。现引入请求序号 `reqSeq`，过期响应直接丢弃。
4. **翻页跳页**：原实现在滚动回调里先 `page++` 再请求，若请求被 `loading` 拦截，页码已自增导致漏页。现页码改由加载流程内部维护（`nextPage`）。
5. **边到边适配**：targetSdk 35+ 强制 edge-to-edge，内容会被状态栏遮挡。现统一用 Insets 处理。
6. 顺带修复：排序/筛选参数不被服务端接受时不再静默无效，会自动降级并提示；错误态新增「重试」入口（原来只能重新搜索）。

## 界面流程

```
启动 → 模型列表（分页 / 搜索 / 排序 / 任务筛选）
        ├── 点击某一项 → 详情页（文件列表可下载、简介 Markdown 渲染）
        │                   └── 在魔搭打开 / 复制链接 / 浏览器打开
        └── 右上角「网页模式」→ 内置 WebView 打开魔搭官网（兜底）
```

## 构建

```bash
# 环境：JDK 17、Android SDK（platform 36 + build-tools 36）
export JAVA_HOME=/path/to/jdk17
echo "sdk.dir=/path/to/android-sdk" > local.properties

gradle assembleDebug      # 调试包
gradle assembleRelease    # 发布包
```

**正式签名**：在项目根目录创建 `keystore.properties`（已被 `.gitignore` 排除）：

```properties
storeFile=/absolute/path/to/release.keystore
storePassword=******
keyAlias=mscope
keyPassword=******
```

缺失该文件时，release 构建自动回退到 debug 签名，保证任何环境都能出包。

## 目录结构

```
app/src/main/java/com/mscope/browser/
├── MainActivity.java      # 列表页：分页 / 搜索 / 排序 / 任务筛选 / 空错态
├── ModelAdapter.java      # 列表卡片适配器
├── DetailActivity.java    # 详情页 + 文件列表适配器
├── WebActivity.java       # 网页兜底模式
├── ModelApi.java          # 数据层：请求 + 多级降级 + 宽松 JSON 解析
├── ModelItem.java         # 模型数据模型
├── ModelFile.java         # 模型文件数据模型
└── Ui.java                # edge-to-edge Insets 工具

.github/workflows/android.yml   # CI：构建 + 打 Tag 自动发布
```

## 接口说明

数据全部来自魔搭公开接口：

| 用途 | 接口 |
|---|---|
| 模型列表（主） | **`PUT /api/v1/dolphin/models`**，body 含 `PageSize`/`PageNumber`/`Name`/`SortBy`/`Order`/`Criterion` |
| 模型列表（兜底） | `GET /api/v1/dolphin/agg/homepage`（全部接口失败时的降级） |
| 模型详情 | `GET /api/v1/models/{namespace}/{name}` |
| 模型文件列表 | `GET /api/v1/models/{namespace}/{name}/repo/files?Revision=master&Recursive=true` |
| 文件下载 | `GET /api/v1/models/{namespace}/{name}/repo?Revision=master&FilePath={path}` |

响应结构：`Data.Model.Models[]`（模型数组）、`Data.Model.TotalCount`（总数）、`Data.FiledAgg`（各筛选维度的取值与数量）。

**筛选维度**取自 `Data.FiledAgg`，因此界面里出现的条件一定是服务端真正支持的：`license`、`libraries`、`tags`、`language`、`model_type`、`nexa_catalog`。

**排序取值**：`Default`（综合）、`DownloadsCount`（最多下载）、`StarsCount`（最多收藏）、`GmtModified`（最近更新），配合 `Order: desc`。

**降级策略**：列表请求按「完整参数 → 去掉筛选 → 去掉排序 → 首页聚合」依次尝试，并通过 `sortApplied` / `filterApplied` / `fallback` 标记把实际生效情况反馈到界面（不可用时 Toast 提示，而不是静默失败）。

**宽松解析**：不依赖固定返回层级，自动在响应 JSON 中定位「最像模型数组」的字段，并优先读取其同级的 `TotalCount`，接口结构调整时仍可工作。

## CI / 自动发布

`.github/workflows/android.yml` 在 push / PR 时构建 debug + release APK 并上传产物；推送 `v*` 标签时自动创建 Release 并附上 release APK。

要在 CI 里使用正式签名，需在仓库 Settings → Secrets and variables → Actions 配置：

| Secret | 说明 |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 release.keystore` 的结果 |
| `KEYSTORE_PASSWORD` | storePassword |
| `KEY_ALIAS` | 密钥别名 |
| `KEY_PASSWORD` | keyPassword |

未配置时 CI 仅产出 debug 签名包。

## 已知限制

- 列表接口必须用 `PUT`，部分企业网关会拦截 PUT，此时会自动降级为首页聚合数据；全部失败可用「网页模式」
- 筛选维度由接口聚合数据驱动，不含「任务类型」（该条件下服务端无效）
- 文件「下载」通过系统浏览器打开魔搭原始下载地址，App 内不做断点续传
- 未做登录，因此不展示需要登录权限的模型内容

## 免责声明

本项目为第三方客户端，与魔搭社区/阿里巴巴无关联，仅用于学习与技术研究。所有模型数据与内容的版权归原作者及魔搭社区所有，请遵守其服务条款。