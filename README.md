<div align="center">

# 魔搭模型库 · ModelScopeBrowser

**浏览魔搭全部大模型 · 把 GGUF 下载到手机 · 内置 llama.cpp 离线对话 · 模型可自主调用终端与联网**

*Browse all ModelScope LLMs, download GGUF to your phone, and chat offline with built-in llama.cpp — the model can run shell commands, search the web and work with files.*

[![Release](https://img.shields.io/github/v/release/ice-wocker/ModelScopeBrowser?color=FF5A2D&label=Release)](https://github.com/ice-wocker/ModelScopeBrowser/releases)
[![CI](https://github.com/ice-wocker/ModelScopeBrowser/actions/workflows/android.yml/badge.svg)](https://github.com/ice-wocker/ModelScopeBrowser/actions/workflows/android.yml)
[![Stars](https://img.shields.io/github/stars/ice-wocker/ModelScopeBrowser?color=FF5A2D)](https://github.com/ice-wocker/ModelScopeBrowser/stargazers)
[![Android](https://img.shields.io/badge/Android-7.0%2B%20(API%2024)-3DDC84?logo=android&logoColor=white)](#)
[![llama.cpp](https://img.shields.io/badge/llama.cpp-b11205-000000)](#)
[![推理](https://img.shields.io/badge/%E6%8E%A8%E7%90%86-%E7%BA%AFCPU%20%C2%B7%20%E5%A4%9A%E6%A1%A3%E6%8C%87%E4%BB%A4%E9%9B%86-4B5563)](#)

[下载安装](#下载安装) · [功能](#功能) · [怎么用](#怎么用) · [技术亮点](#技术亮点) · [更新日志](#更新日志) · [构建](#构建) · [作者](#作者)

</div>

---

一个轻量 Android App：浏览[魔搭社区 ModelScope](https://www.modelscope.cn/models) 上的**全部大模型**（分页、搜索、排序、按维度筛选、详情与文件下载），并把 `.gguf` 下载到手机，**用内置的 llama.cpp 直接离线对话**。

对话页不是「只能聊天」的玩具——它是一个**能动手的智能体**：模型可以自己调用手机终端跑命令、联网搜索、读写工作区文件、预览网页，并在拿到真实结果后继续推理。

> **当前版本 v2.4.1**（versionCode 12）· 原生化 arm64 多档指令集 · 纯 CPU 离线推理 · 数据不出设备

---

## 功能

<table>
<tr><td width="130"><b>模型浏览</b></td><td>

分页加载魔搭**全部**模型（接口总量约 **25.9 万**），滑到底自动加载下一页；关键字搜索、综合/最多下载/最多收藏/最近更新排序；按 **许可证 / 框架库 / 标签 / 语言 / 模型结构 / 领域** 六个维度筛选（取值与数量实时来自接口聚合）。

</td></tr>
<tr><td><b>模型详情</b></td><td>

基本信息 + **模型文件列表**（体积、LFS 标记）+ **Markdown 简介渲染**；`.gguf` 可一键下载。

</td></tr>
<tr><td><b>应用内下载</b></td><td>

`.gguf` 直接存到应用私有目录（**无需存储权限**），带进度与取消；支持**断点续传**、**前台服务通知**，进程被杀后再进入应用自动接着下；完成后校验字节数 + `GGUF` 魔数，挡掉错误页 / 半截文件。

</td></tr>
<tr><td><b>离线对话</b></td><td>

内置 **llama.cpp**，流式逐字输出，可停止；**Markdown 渲染**（标题 / 列表 / 表格 / 代码块）；长按气泡可复制 / 重新生成 / 编辑重发 / 继续生成 / 删除；可导出对话；状态条实时显示上下文用量与解码速度。

</td></tr>
<tr><td><b>智能体</b></td><td>

模型输出工具调用 → App 真实执行 → 结果回灌 → 继续推理，**最多 8 步**，可随时停止。可用工具：

| 工具 | 作用 |
|---|---|
| `shell` | 在手机**真终端**执行命令（`/system/bin/sh`，工作目录 = 工作区） |
| `web_search` / `fetch_url` | 联网搜索 / 抓取网页正文 |
| `read_file` / `write_file` / `list_files` / `delete_file` | 读写工作区文件 |
| `open_preview` | 在内置 WebView 打开工作区 HTML |

`shell`、`write_file`、`delete_file` 执行前**弹窗确认**，也可选「本会话都允许」。

</td></tr>
<tr><td><b>工作区</b></td><td>

应用私有目录 `filesDir/workspace`：路径强制收敛在根目录内、写入原子落盘。可浏览 / 新建 / 查看 / 分享 / 删除；点 `.html` 直接用内置 WebView 预览（支持 JS / CSS / 相对资源，可切桌面版或转系统浏览器）。

</td></tr>
<tr><td><b>终端页</b></td><td>

**真 shell**（`/system/bin/sh`），工作目录固定在工作区，超时 60 秒、输出自动截断；后台执行不卡界面。受 Android 沙箱限制，只能访问应用私有目录（无需 root）。

</td></tr>
<tr><td><b>对话历史</b></td><td>

每个模型可保存多个会话（自动落盘，退出不丢），支持历史会话切换；每个模型最多保留最近 50 条。

</td></tr>
<tr><td><b>本地模型库</b></td><td>

查看已下载模型、占用空间与剩余空间，一键进入对话或删除。

</td></tr>
<tr><td><b>其它</b></td><td>

下拉刷新、加载 / 空 / 错态与一键重试、Material 3 卡片列表、**暗色模式自动适配**；右上角「网页模式」直接打开魔搭官网，作为任何异常情况下的兜底。

</td></tr>
</table>

**大输出**：单次生成默认 **4096 tokens**（上限 8192），默认上下文 **8192**（上限 32768）；长按回复可「继续生成」，到上限也能接着写。

---

<!--
## 界面预览

把截图放进 docs/screenshots/ 后，删掉本段注释即可启用：

| 模型列表 | 模型详情 | 对话 · 智能体 | 工作区 / 终端 |
|:---:|:---:|:---:|:---:|
| ![](docs/screenshots/list.png) | ![](docs/screenshots/detail.png) | ![](docs/screenshots/chat.png) | ![](docs/screenshots/terminal.png) |
-->

## 下载安装

| 渠道 | 说明 |
|---|---|
| **[Releases](https://github.com/ice-wocker/ModelScopeBrowser/releases)** | 推荐。最新 **v2.4.1** `ModelScope-Models-2.4.1.apk`，CI 构建 + 正式签名（约 **8.2 MB**） |

- 要求：**Android 7.0 (API 24) 及以上**；首次安装需允许「安装未知来源应用」
- 建议：**arm64 机型 + ≥4 GB 内存**，模型优先选 `Q4_K_M` / `Q4_0` 量化，**0.5B~3B 体验最佳**
- 从 **v1.0 起的任意 Release** 均可直接覆盖安装（同一签名）

> [!WARNING]
> **请不要安装 v2.1 的包。** 该版本开启 R8 时漏掉了 JNI 回调方法的 keep 规则，加载任意模型都会失败（`no non-static method "...onToken(Ljava/lang/String;)V"`）。已在 v2.1.1 修复，请用 v2.1.1 或更高版本。
>
> 另外，v1.2 的 Release 包是 CI 未配签名时的 **debug 签名**产物；若你装的正是它，需先卸载再安装。

---

## 怎么用

**1. 下载一个模型** — 列表里挑一个 `.gguf`，点「下载」，完成后选「立即对话」。

**2. 直接说人话，让它动手** — 顶部确认「智能体 · 开」「联网 · 开」，然后：

| 你可以说 | 它会做什么 |
|---|---|
| 「看看工作区里有哪些文件」 | 调 `list_files` 列出目录 |
| 「在终端里跑一下 `ls -la`」 | 弹确认 → 执行真命令 → 把输出读回来 |
| 「搜索今天的科技新闻并总结」 | 调 `web_search`（必要时再 `fetch_url` 读正文）→ 总结并附来源 |
| 「写个 index.html 放到工作区再预览」 | 调 `write_file` → `open_preview` |

每一步都会在对话里生成一张**工具卡片**，显示真实执行结果；点发送键可随时中止。

**3. 斜杠命令**（输入框以 `/` 开头）

```
/search <关键词>   联网检索并总结
/web on|off        允许 / 禁止 AI 联网
/run <命令>        在真终端执行命令
/ls [目录]         列出工作区目录
/cat <文件>        查看文件
/preview <文件>    预览网页
/files             打开工作区
/help              全部命令
```

其余输入会直接当本机命令执行，例如 `/pwd`、`/grep foo *.txt`。

> **提示**：工具调用依赖模型的指令跟随能力。MiniCPM5 用的是它原生的 XML 调用格式，Qwen3-Coder 用另一种，本项目两种都认；如果用某模型发现它「不愿动手」，换个 instruct 版本更听话。

### 界面流程

```
启动 → 模型列表（分页 / 搜索 / 排序 / 维度筛选）
        ├── 点击某一项 → 详情页（文件列表、简介 Markdown 渲染）
        │                   ├── .gguf  → 应用内下载 → 「立即对话」→ 对话页
        │                   └── 其他文件 → 系统浏览器下载
        │                                   └── 在魔搭打开 / 复制链接 / 浏览器打开
        ├── 右上角「对话」→ 本地模型库（占用空间 / 删除 / 进入对话）
        │                        └── 对话页（Markdown 流式输出 / 历史会话 / 长按消息操作）
        │                               ├── 「智能体」开关 → 模型自主调用工具（工具卡片）
        │                               ├── 「联网」开关   → 允许 AI 联网
        │                               ├── 「工作区」     → 工作区页 → 点 .html 预览
        │                               ├── 「终端」       → 真 shell 终端页
        │                               └── 输入 / 开头    → 斜杠命令
        └── 右上角「网页模式」→ 内置 WebView 打开魔搭官网（兜底）
```

---

## 技术亮点

### 智能体：一条真正闭环的工具调用

不依赖模型自带的 function-call 模板（本地 GGUF 往往没有），而是把各家模型习惯输出的格式都解析下来：

| 格式 | 例子 |
|---|---|
| MiniCPM5 原生 | `<function name="shell"><param name="command">ls</param></function>` |
| Qwen3-Coder | `<function=shell><parameter=command>ls</parameter></function>` |
| 通用 | ` ```tool {"name": "shell", "arguments": {...}} ``` ` / `<tool_call>{...}</tool_call>` / `TOOL_CALL: {...}` |

配套处理了不少坑，都写成了单元测试：

- **特殊 token 的渲染**：MiniCPM5 把 `<function>` / `<param>` 做成了**特殊 token**，而 `llama_token_to_piece` 传 `special=false` 时会对它们**直接返回空串**——工具标签被静默丢弃，调用退化成残缺文本。改用 `special=true` 后恢复正常。
- **截断容错**：输出被 `maxTokens` 截断、缺少闭合标签时，依然能从半截标签里取出调用。
- **参数值 CDATA**：值里含 `</param>` 时会用 `<![CDATA[...]]>` 包裹，解析时自动拆掉。
- **防误判**：只有工具名命中已注册工具集才算调用，回复里的普通 JSON/XML 示例不会被误执行。
- **思考块剥离**：推理模型的思考内容不会当成正式回答显示。
- **结果回灌**：工具结果以 `<tool_response>` 包裹回灌，与 MiniCPM5 / Qwen 的模板约定一致。

### arm64 多档指令集：运行时自动选档

不再是「一种构建打天下」，而是用 `GGML_CPU_ALL_VARIANTS` 为 Android arm64 编出多份 CPU 后端：

| 后端 | 指令集 | 典型芯片 |
|---|---|---|
| `android_armv8.0_1` | 基线 armv8-a | 所有 arm64（兜底，绝不会 SIGILL） |
| `android_armv8.2_1/2` | dotprod / +fp16 | 2018 年后的处理器 |
| `android_armv8.6_1` | **+i8mm** | 骁龙 8 Gen 1+ / 天玑 9000+ / Cortex-X3·A715+ |
| `android_armv9.0_1` | +SVE2 | 天玑 9200+ 等 |
| `android_armv9.2_1/2` | +SVE / +SME | 最新旗舰 |

运行时由 ggml 的 `ggml_backend_score()` 按 `HWCAP` 打分，**只加载分数最高的那一档**。反汇编核对过：`android_armv8.0_1` 里 `sdot` / `smmla` 均为 0，`armv8.6_1` 有 747 处 `sdot` + **174 处 `smmla`**，`armv9.0_1` 有 **308 处 `smmla`**——i8mm 机型确实会走 int8 矩阵乘内核，而不是「只编了个变体」。

顺带一个好处：基线退回 armv8-a 后，**移除了原先 dotprod+fp16 的硬门槛**，缺指令的老 arm64 不再被拒绝。

### 其它已经踩平的坑

| 项 | 做法 |
|---|---|
| **KV 前缀复用** | 跨轮复用公共前缀，只解码新增 token；实测三轮对话累计预填充 173 → 59 tokens（降至 34%），输出与全量重算逐字一致 |
| **KV 量化 + FlashAttention** | KV 量化为 `q8_0`，注意力读写减半、KV 内存约 1/2（这是默认上下文能拉到 8192 的前提）；模型支持时自动启用 FlashAttention |
| **线程数** | 按 `/proc/self/status` 的 `Cpus_allowed_list` 取真正可用核数。超核时 ggml 线程屏障自旋会让单次 decode 从 28ms 恶化到 **3952ms**，现已根除 |
| **物理批** | `n_ubatch` 512 → 128，计算缓冲区 ~311MB → ~78MB，单 token 解码 35.8ms → 24.1ms |
| **JNI 生命周期** | 存活会话表 + 统一加锁，消除 `nativeCancel` / `nativeFree` 的 use-after-free 与二次释放 |
| **下载可恢复** | 续传任务落盘 `downloads.json`，进程被杀后自动接着下；索引「临时文件 + 原子改名」避免半截索引让本地模型「全部消失」 |
| **native 库释放** | arm64 后端是独立 `.so`，必须落到 `nativeLibraryDir` 才能被 `ggml_backend_load_all_from_path` 加载——Android 的 `extractNativeLibs` 一旦为 false 就会「一个后端都注册不上、模型必然加载失败」|

---

## 更新日志

### v2.4.1 — 修复智能体工具调用被吞

模型其实一直在**正确**调用工具，是两处问题让调用变成一堆残渣：

1. **主因**：`llama_token_to_piece` 传了 `special=false`，llama.cpp 对 `LLAMA_TOKEN_ATTR_CONTROL/UNKNOWN` 的 token 直接返回空串。MiniCPM5 把 `<function>` / `<param>` 做成特殊 token，于是标签头被丢弃，只剩 `name="web_search">` 这种残缺文本。改为 `special=true`（llama.cpp 自身 server / CLI 的默认行为）。
2. **次因**：解析器只认 JSON，而 MiniCPM5 的原生格式是 XML。现已支持 MiniCPM5 原生格式、Qwen3-Coder 风格、以及原有的 JSON 代码块 / `<tool_call>` / `TOOL_CALL:`。

另外：剥掉思考块（含未闭合的截断情况）、工具结果改用 `<tool_response>` 回灌、system prompt 改为教模型用它自己的原生格式。

### v2.4.0 — 真正的工具调用闭环

在此之前对话页**没有任何工具调用**：模型输出不被解析、工具结果不回灌，「联网」只是个强制检索开关，「工作区 / 终端」只是跳转页面——所以模型只能回你「我无法访问终端」。

现在是一条完整闭环：**模型输出调用 → App 真实执行 → 结果回灌 → 继续推理**，最多 8 步、可随时停止；新增 `Shell`（真 shell）、`ToolCall`（协议解析）、`AgentTools`（工具集），危险操作弹窗确认，终端页也从受限伪终端换成真 shell。

<details>
<summary><b>v2.3.1</b> — 修复模型加载失败（native 库未释放）</summary>

**现象**：模型加载必然失败。

**原因**：v2.3.0 用了 `useLegacyPackaging = false`（等价 `extractNativeLibs=false`），`.so` 不落盘；而 arm64 的 CPU 后端是多档指令集独立库，只在运行时按路径 `dlopen`，且 `libggml.so` 本身**不含** CPU 后端。于是注册后端数为 0，模型必然加载失败。

**修复**：改为 `useLegacyPackaging = true`，强制 `.so` 释放到 `nativeLibraryDir`。顺带补了加载可靠性：检测后端注册数、区分「后端未注册」与「模型文件问题」、KV 量化 / FlashAttention 逐档降级、新增 `nativeLastError()` 在界面显示具体失败原因。

</details>

<details>
<summary><b>v2.3.0</b> — 多档指令集 + FlashAttention + KV 量化（提速）</summary>

见上文「技术亮点」。要点：`GGML_CPU_ALL_VARIANTS` 多档后端 + 运行时按 `HWCAP` 选档；KV 量化为 `q8_0` + FlashAttention；单次输出上限 1024 → 4096，默认上下文 4096 → 8192，新增「继续生成」。

</details>

<details>
<summary><b>v2.2.0</b> — 对话页升级为智能体界面（初版）</summary>

输出上限放开（256 → 1024，上下文 2048 → 4096）；新增**开关式联网搜索**（DuckDuckGo → Bing 兜底，无需 API Key）；新增**应用私有工作区**与自动保存生成的 HTML、**工作区页**与 **HTML 预览页**；新增受限终端与斜杠命令；AI 回复改用 **Markwon** 渲染 Markdown，新增工具卡片。

</details>

<details>
<summary><b>v2.1.1</b> — 修复 release 包无法加载模型（R8 回归）</summary>

**原因**：`llama_bridge.cpp` 用**字面方法名**回调 Java（`GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V")`），而 v2.1 的 proguard 规则只保住了 native 方法与 `LlamaBridge` 类本身，没保住回调接口的方法名，`onToken` 被 R8 重命名。

**修复**：在 `proguard-rules.pro` 保留 `LlamaBridge$TokenCallback` 及**所有实现类**的 `onToken` 方法名，并逐项复核产物 dex 中的 JNI 符号。

**教训**：只要 native 层按「字符串名字」查找 Java 符号，就必须在 proguard 里显式保留——这一点**编译期无法验证**，`assembleRelease` 通过并不代表运行可用。

</details>

<details>
<summary><b>v2.1</b> — 更快、更稳、更好用</summary>

**提速**：arm64 显式 `-march=armv8.2-a+dotprod+fp16`（此前 `GGML_NATIVE` 交叉编译自动关闭，整包退化为基线，量化内核被整段编译掉——是当时偏慢的主因）；KV 前缀复用；按真实可用核数限制线程数；`n_ubatch` 512 → 128。实测表见「技术亮点」。

**体验**：历史会话持久化（`chats.json`，原子写）；长按气泡操作与「导出对话」；上下文用量实时显示；停止即时反馈。

**下载**：断点续传、`GGUF` 魔数校验、前台服务通知、任务落盘可恢复、取消即时断开。

**工程化**：修复 JNI use-after-free；索引原子写；各 Activity 补 Handler 清理与 alive 守卫；列表改 DiffUtil；17 个单元测试；Lint 改严格模式；Version Catalog；Gradle Wrapper；CI 加单测与 Lint；release 开 R8（20.6 MB → 11.6 MB）。

</details>

<details>
<summary><b>v2.0</b> — 内置 llama.cpp，下载完就能对话</summary>

接入 llama.cpp b11205（JNI + CMake，纯 CPU 推理）、应用内下载 GGUF、流式对话（逐 token 上屏 / 可中断 / 参数可调）、本地模型库；界面改渐变头部 + 圆角卡片 + 对话气泡。

实现要点：`llama_bridge.cpp` 负责加载 GGUF、套用模型自带 chat template、分词、上下文超长丢弃最早历史、采样器链、逐 token 流式回调与取消；**UTF-8 分片处理**（token 边界常把中文 / emoji 切成半个字符，按「完整字符」切分后再转 UTF-16）。

踩坑记录：`llama_model_params` 新版移除 `use_mmap` / `use_mlock`，改为 `load_mode = LLAMA_LOAD_MODE_MMAP`；`GGML_LLAMAFILE` 的 sgemm 在 32 位 ARM 上用到 `vld1q_f16`，NDK clang 编译不过，已关闭（主要面向 x86，ARM 走通用 / NEON 路径）。

</details>

<details>
<summary><b>v1.2 / v1.1 / v1.0</b> — 早期：把接口和列表做对</summary>

**v1.2 修复「404 page not found」**：通过抓取魔搭网页自身请求，确认真实接口要对 `/api/v1/dolphin/models` 用 **`PUT`**，筛选参数名是 **`Criterion`**（`SingleCriterion` 被忽略），筛选维度是响应里 `Data.FiledAgg` 的字段。实测：`POST`/`GET` → 404，`PUT` → 200。

**v1.1 升级与修 Bug**：AGP 8.13.2、compileSdk/targetSdk 36、AndroidX + Material 3 + Markwon、RecyclerView + SwipeRefreshLayout、Material 3 DayNight 暗色、正式 release 签名、GitHub Actions 自动构建与发布。修复：双重标题栏、首页聚合兜底导致的分页报错、搜索竞态覆盖（引入 `reqSeq`）、翻页跳页（页码改由 `nextPage` 内部维护）、edge-to-edge 遮挡。

**v1.0**：零第三方依赖的 ListView 初版。

</details>

---

## 构建

```bash
# 环境：JDK 17、Android SDK（platform 36 + build-tools 36 + ndk 27.2.12479018 + cmake 3.22.1）
export JAVA_HOME=/path/to/jdk17
echo "sdk.dir=/path/to/android-sdk" > local.properties

# 首次编译需拉取 llama.cpp（约 50 MB）；本地已有源码时可指定路径加速
./gradlew assembleDebug   -PllamaCppDir=/path/to/llama.cpp
./gradlew assembleRelease -PllamaCppDir=/path/to/llama.cpp

# 不传 -PllamaCppDir 时，CMake 会通过 FetchContent 按 tag b11205 拉取 llama.cpp
./gradlew assembleRelease

# 单元测试与 Lint（CI 也会跑）
./gradlew testDebugUnitTest
./gradlew lintDebug
```

**原生库**：`armeabi-v7a` 为静态单档（全部塞进 `libmscope_llama.so`）；`arm64-v8a` 为动态构建（`libllama.so` / `libggml.so` / `libggml-base.so` + 7 份 `libggml-cpu-android_*.so` 变体）。

release 开启 **R8 混淆 + 资源压缩**（规则见 [`app/proguard-rules.pro`](app/proguard-rules.pro)），APK 约 **8.2 MB**——多份 CPU 后端以**压缩形式**存放，安装时释放到 `nativeLibraryDir`。依赖版本集中在 [`gradle/libs.versions.toml`](gradle/libs.versions.toml)。

**正式签名**：在项目根目录创建 `keystore.properties`（已被 `.gitignore` 排除）：

```properties
storeFile=/absolute/path/to/release.keystore
storePassword=******
keyAlias=mscope
keyPassword=******
```

缺失该文件时 release 自动回退 debug 签名，保证任何环境都能出包。

---

## 目录结构

```
app/src/main/java/com/mscope/browser/
├── MainActivity.java          # 列表页：分页 / 搜索 / 排序 / 维度筛选 / 空错态
├── ModelAdapter.java          # 列表卡片适配器
├── DetailActivity.java        # 详情页 + 文件列表（GGUF 应用内下载 / 一键对话）
├── WebActivity.java           # 网页兜底模式
├── ModelApi.java              # 数据层：请求 + 多级降级 + 宽松 JSON 解析
├── ModelItem.java / ModelFile.java / Format.java / Ui.java
├── llama/
│   ├── LlamaBridge.java       # native 方法声明（加载 / 生成 / 取消 / 重置 / 错误原因）
│   ├── LlamaEngine.java       # 会话单例：模型常驻、单线程串行生成、线程数选择
│   ├── ChatActivity.java      # 对话页：智能体循环、工具卡片、消息操作、斜杠命令
│   ├── ChatStore.java         # 对话历史持久化（chats.json，多会话）
│   └── ChatMessage.java       # 一条对话消息（含 tool 工具消息）
├── agent/                     # 智能体能力
│   ├── ToolCall.java          # 工具调用协议解析（MiniCPM5 / Qwen / JSON，含截断容错）
│   ├── AgentTools.java        # 工具集定义与执行（shell / 联网 / 文件 / 预览）
│   ├── Shell.java             # 真 shell：/system/bin/sh，超时与输出截断
│   ├── Workspace.java         # 应用私有工作区文件系统（路径收敛 / 原子写）
│   ├── WorkspaceActivity.java # 工作区页：浏览 / 新建 / 查看 / 分享 / 删除
│   ├── HtmlPreviewActivity.java # 生成的 HTML 本地预览（WebView）
│   ├── TerminalActivity.java  # 终端页（真 shell）
│   └── WebSearch.java         # 联网检索（DuckDuckGo → Bing 兜底）+ 网页正文抓取
└── local/
    ├── LocalModel.java        # 本地 GGUF 模型（量化识别 / 体积格式化）
    ├── LocalModelStore.java   # 模型目录与 index.json 索引（原子写）
    ├── DownloadCenter.java    # 下载中心：断点续传 / 校验 / 取消 / 任务落盘
    ├── DownloadService.java   # 下载前台服务与进度通知
    └── LocalModelsActivity.java # 本地模型库页

app/src/main/cpp/
├── CMakeLists.txt             # 编译 llama.cpp；arm64 开多档变体（GGML_CPU_ALL_VARIANTS）
└── llama_bridge.cpp           # JNI 桥接：模板 / 分词 / 采样 / 流式回调 / UTF-8 分片 / KV 复用

app/src/test/java/com/mscope/browser/   # 单元测试（37 个用例）
app/proguard-rules.pro                   # R8 keep 规则（保留 JNI 符号）
gradle/libs.versions.toml                # Version Catalog
gradlew / gradle/wrapper/                # Gradle Wrapper 8.14.5

.github/workflows/android.yml  # CI：单元测试 + Lint + 构建 + 打 Tag 自动发布
```

---

## 接口说明

数据全部来自魔搭公开接口：

| 用途 | 接口 |
|---|---|
| 模型列表（主） | **`PUT /api/v1/dolphin/models`**，body 含 `PageSize` / `PageNumber` / `Name` / `SortBy` / `Order` / `Criterion` |
| 模型列表（兜底） | `GET /api/v1/dolphin/agg/homepage`（全部接口失败时的降级） |
| 模型详情 | `GET /api/v1/models/{namespace}/{name}` |
| 模型文件列表 | `GET /api/v1/models/{namespace}/{name}/repo/files?Revision=master&Recursive=true` |
| 文件下载 | `GET /api/v1/models/{namespace}/{name}/repo?Revision=master&FilePath={path}` |

响应结构：`Data.Model.Models[]`（模型数组）、`Data.Model.TotalCount`（总数）、`Data.FiledAgg`（各筛选维度的取值与数量）。

**筛选维度**取自 `Data.FiledAgg`，因此界面里出现的条件一定是服务端真正支持的：`license`、`libraries`、`tags`、`language`、`model_type`、`nexa_catalog`。

**排序取值**：`Default`（综合）、`DownloadsCount`（最多下载）、`StarsCount`（最多收藏）、`GmtModified`（最近更新），配合 `Order: desc`。

**降级策略**：列表请求按「完整参数 → 去掉筛选 → 去掉排序 → 首页聚合」依次尝试，并通过 `sortApplied` / `filterApplied` / `fallback` 标记把实际生效情况反馈到界面（不可用时 Toast 提示，而不是静默失败）。

**宽松解析**：不依赖固定返回层级，自动在响应 JSON 中定位「最像模型数组」的字段，并优先读取其同级的 `TotalCount`，接口结构调整时仍可工作。

---

## CI / 自动发布

[`.github/workflows/android.yml`](.github/workflows/android.yml) 在 push / PR 时依次执行 **单元测试 → Lint → 构建 debug + release APK** 并上传产物（含测试 / Lint 报告）；推送 `v*` 标签时自动创建 Release 并附上 release APK。构建统一走仓库内的 **Gradle Wrapper**，由 `gradle/actions/setup-gradle` 负责依赖缓存。

要在 CI 里使用正式签名，需在仓库 **Settings → Secrets and variables → Actions** 配置：

| Secret | 说明 |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 release.keystore` 的结果 |
| `KEYSTORE_PASSWORD` | storePassword |
| `KEY_ALIAS` | 密钥别名 |
| `KEY_PASSWORD` | keyPassword |

未配置时：普通分支 / PR 构建回退 debug 签名（仅作 CI 产物）；但**推送 `v*` 标签发布 Release 时会直接失败并提示补配置**，避免把 debug 签名的包发给用户。

> ⚠️ **务必配置这四个 Secrets**：否则各 Release 之间签名不一致，用户无法互相覆盖更新（v1.2 就出现过；v2.1 的 Release 包已手工替换为正式签名包）。

---

## 已知限制

- **终端是「真 shell」但受沙箱限制**：Android 非 root 只能访问应用私有目录，写不了 `/sdcard`、`/system`；命令有 60 秒超时与输出截断
- **工具调用依赖模型的指令跟随能力**：小模型可能不按格式输出，换个 instruct 版本通常更听话；单轮最多 8 步
- **未启用 GPU 卸载**：Vulkan / OpenCL 后端需要 `glslc`（Vulkan SDK）等工具链，构建环境不具备，故仍为**纯 CPU 推理**。这也意味着「3~5 倍」这类幅度只在支持 i8mm / SVE 的新芯片上才可能接近，老机型拿不到
- **多档指令集只覆盖 arm64**：`armeabi-v7a`（32 位）仍是单档；各档实际提速幅度与芯片强相关
- 列表接口必须用 `PUT`，部分企业网关会拦截 PUT，此时自动降级为首页聚合数据；全部失败可用「网页模式」
- 筛选维度由接口聚合数据驱动，不含「任务类型」（该条件下服务端无效）
- **应用内下载仅支持 `.gguf`**（其他文件走系统浏览器）；单线程串行，续传能否生效取决于服务端是否支持 `Range`
- 下载期间常驻一条前台服务通知；Android 13+ 未授予通知权限时通知不显示，但下载照常进行
- **联网搜索**依赖搜索引擎网页结果（DuckDuckGo / Bing），无 API Key，受反爬策略影响可能偶发失败；结果质量与时效由来源决定
- **自动保存文件**只对带文件名标注的代码块或 html 代码块生效；同名文件自动加序号
- release 已开 R8 混淆，若怀疑是混淆导致的异常，可用 `./gradlew assembleDebug` 的包复现排查
- 本地推理速度取决于机型；超大模型（30B+）在手机上不具可用性，建议 0.5B~4B
- 未做登录，因此不展示需要登录权限的模型内容

---

## 作者

<table>
  <tr>
    <td width="150" align="center" valign="top">
      <img src="https://github.com/ice-wocker.png" width="96" alt="ice-wocker" />
      <br /><br />
      <b>ice-wocker</b>
    </td>
    <td valign="top">

**昵称**：ice-wocker

**正在折腾**：把大模型塞进手机——让离线推理在移动端真正可用，而不是个演示 Demo。

**联系方式**

- GitHub：[@ice-wocker](https://github.com/ice-wocker)

<a href="https://github.com/ice-wocker">
  <img src="https://img.shields.io/badge/GitHub-ice--wocker-181717?logo=github" alt="GitHub" />
</a>

    </td>
  </tr>
</table>

**关于这个项目**：起初只是想找个能方便逛魔搭、把 GGUF 拿到手机上跑的工具，市面上要么得连电脑、要么要登账号，于是干脆自己写了一个。做着做着把 llama.cpp 也接进来，接着是工作区、终端、联网，最后变成现在这个「能在手机上干活」的智能体。全部代码开源，欢迎提 Issue 和 PR。

如果它对你有用，点个 **Star** 是最大的鼓励 ⭐

---

## 免责声明

本项目为第三方客户端，与魔搭社区 / 阿里巴巴无关联，仅用于学习与技术研究。所有模型数据与内容的版权归原作者及魔搭社区所有，请遵守其服务条款。

---

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=ice-wocker/ModelScopeBrowser&type=Date)](https://www.star-history.com/#ice-wocker/ModelScopeBrowser&Date)