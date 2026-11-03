# 更新日志

> 🌐 语言 / Language: 中文 · [English](CHANGELOG.en.md)

完整历史。按时间倒序。

## v2.8.0 — 让「智能体可以调工具」在带 function-call 模板的模型上真的跑通

这一版没有新功能。它修的是同一类问题：**已经宣称做到、但实际会静默失效的地方**。
最典型的一处是工具调用：模型明明调了工具，结果也执行了，但它看不见结果，于是再调一次。

### 工具调用为什么看不见结果

三个原因叠在一起，每一个单独都能让整条闭环失效：

1. **跨槽位 KV**（native）。上一轮生成结束后，KV 里还留着那一轮的输出 token，而 `s->kv`
   只记录 prompt 部分。下一轮的前缀匹配如果把它们也算进去，命中位置就会落进上一轮的输出里 ——
   删 KV 删错区间，decode 的位置与真实内容错位。表现就是「答非所问、重复调同一个工具」，
   而且越是「历史整段保留」的模板越容易触发。现在 `s->kv` **只记录 prompt 部分**，
   生成的 token 由 `decodeNext` 追加进 KV 但不进记录，前缀长度永远不会超过历史 prompt。
2. **原生协议不认**（Java）。工具结果原来用 `user` 角色回灌，于是出现
   `assistant(调用) / user(结果) / user(下一条)` 这种同角色相邻的消息。
   带 function-call 模板的模型（Qwen / DeepSeek / MiniCPM 的 chat template）按「轮」渲染，
   同角色连在一起时会在中间插入一次「上轮结束 + 新一轮开始」，前一条内容就被划到上一轮里。
   现在助手输出与工具结果**合并成一条 assistant 消息**（`<tool_response>` 包住结果），
   历史里相邻的同角色消息也统一合并。
3. **上下文只增不减**（Java）。工具结果原来是整段回灌，一次 `cat` 或抓网页就能把历史挤出
   上下文，随后消息裁剪又悄悄丢掉最早几条 —— 模型看到的历史突然缺一块，更容易重发调用。
   现在单条结果截断到 6k 字符（界面卡片仍显示完整内容），并对「已经执行过的调用」设闸门。

### 防打转：三层闸门

- **提示词**：`PromptTemplate.noRepeatRule()` 中英双语明说「已执行过的调用不要重复发起」。
- **宿主**：新增 `core/ToolGuard`，同一调用（工具名 + 与键序无关的参数指纹）一轮内最多真执行
  2 次，重复累计 2 次直接收尾。判重指纹按参数键排序后生成，模型换个键序绕不过去。
- **采样**：新增重复惩罚（窗口 128 / 系数 1.08，可在参数弹窗里调），并在引擎里加复读熔断：
  输出连续 6 秒一个字都没新增就异步打断，别让一次复读烧满 4096 token。

### 其它一起做实的地方

- **工具清单三处同源**：`AgentTools.enabled(web, files, shell)` 成为唯一真源，解析、剥离、
  提示词都从它取。以前关掉联网后提示词里不再列 `web_search`，解析器却仍认它 ——
  结果是模型调一个注定失败的接口，用户看到长时间空转。
- **KV 前缀主动作废**：改采样参数、改系统提示词、开/关联网都会让前缀失效，
  现在统一 `invalidatePrefix()`，不再让引擎「从第一个不同的 token 起重算」白烧一次长前缀。
- **消息裁剪提速**：原来逐条丢弃 + 每次全量分词，长会话上一次生成前要白烧几百毫秒。
  改为先按粗略 token 数估算该丢多少，再二分校验；并且永不把「本轮用户输入」一起丢掉。
- **采样参数收敛**：`Validators.Sampling` 增加重复惩罚窗口/系数两项，窗口关闭时系数归 1，
  系数收敛到 1.0~2.0（小于 1 等于鼓励复读）。参数弹窗新增「重复惩罚」输入。
- 重复执行超限、参数被收敛、工具被叫停这些情况，提示文案全部走资源，中英各一份。

### 测试

- 新增 `core/ToolGuardTest`（10 个用例）：键序无关的指纹、重复只执行一次、达上限收尾、
  换参数/换工具不算重复、超长参数不切断代理对、文案随语言切换。
- 新增 `agent/AgentToolsTest`（6 个用例）：启用集合随开关变化、是全量的子集、不可被外部修改、
  `describe` 只列启用的工具、`risky` 只覆盖会改状态的那些。
- `CoreKitTest` 补 3 个：`TextKit.tail` 的边界、重复惩罚收敛（含旧构造兼容）。
- `Bi18nTest` 补 1 个：「别重复调用」这条约束两种语言都在且不混排。
- 单测 99 → **118 个，全绿**。

### 文档

- 版本号 `versionCode 15 → 16`，`versionName 2.7.0 → 2.8.0`。
- 本文件与 `CHANGELOG.en.md` 同步。

---

## v2.6.0 — 中英双语

### 界面与文案

- **默认语言改为英文**：`values/strings.xml` 全量改写为英文，中文迁到 `values-zh/strings.xml`（216 条逐条对应）。
  原因很简单：`values` 是兜底目录，放中文等于让所有没被 `values-xx` 覆盖的语言都看中文。
- **Lint 开回 `MissingTranslation`**（此前一直关着）：漏译、多余项都会让 `lintDebug` 失败，双语从此是硬约束，不是自觉。
- **应用内语言切换**：「关于 → 语言」，可选跟随系统 / 中文 / English。用 `LocaleManager` 在
  `attachBaseContext` 里换 Configuration，切换后 `recreate()`，**不重启进程**——已加载进内存的模型和正在进行的下载都不会丢。

### 代码里的文案（不在资源里的那些）

- 新增 `i18n/Lang`：提示词、工具说明、工具执行结果、错误与建议，凡是拿不到 `Context` 的文案统一走它；
  每个 Activity 绑定语言时同步它，**界面语言与模型语言永远是同一个**。
- `PromptTemplate` 四种预设各出中英两版，英文版不是直译：同样短句、少从句，并要求「用用户的语言回答」。
- `AgentTools.describe/exec`、`WebSearch`、`Workspace`、`Shell`、`LlamaEngine`、`DownloadCenter` 的文案全部双语。
- `Validators`、`StorageGuard`、`PerfStats`、`MemoryBudget`、`FsKit` 增加 `boolean zh` 重载，纯逻辑层依旧零 Android 依赖。

### 本地化细节

- `Format.count` 不再固定中文：中文按「万 / 亿」，英文按 `K / M`——单位要整套换，只换符号等于没本地化。
- `ModelApi.facetLabel`、工具卡片标题、消息导出前缀、性能统计行全部走资源。
- 排序选项从 static 数组改为资源 id，避免在语言未定时被固化。

### 测试

- 新增 `core/Bi18nTest`（11 个用例）：每个预设两种语言都非空且不串味、工具说明分段双语、
  参数收敛的数值结论与语言无关、`Lang` 跟随系统与显式覆盖的优先级。
- `FormatTest` 补英文 `K/M` 断言；原有断言中文文案的用例显式 `Lang.setChinese(true)` 钉住语言，
  避免在英文 CI 机器上出现「看起来功能坏了」的假失败。
- 单测 87 → **99 个，全绿**。

### 文档（v2.6.0 补齐）

- 新增 `docs/README.en.md`：完整手册英文版（12 章 + API 速查 + 术语表）。
- 新增 `docs/MODULES.en.md`、`docs/ARCHITECTURE.en.md`、`docs/DEVELOPING.en.md`、
  `docs/TROUBLESHOOTING.en.md`、`docs/FAQ.en.md`、`docs/BENCHMARKS.en.md`、`docs/DIAGRAMS.en.md`、
  `docs/CHANGELOG.en.md`：全部 10 份长文档中英双语，每页顶部可互切。
- `docs/POSTER.md` 的说明文字改英文（图片内嵌，单文件可离线看）。
- `README.md` 与 `README.en.md` 的文档表改成「中文 / English」两列。
- 版本号 `versionCode 13 → 14`，`versionName 2.5.0 → 2.6.0`。

---

## v2.5.0 — 补齐「该有但一直没有」的东西

### 新增 `core/` 纯逻辑层

八个类，零 Android 依赖，全部有 JVM 单测：

| 类 | 职责 |
|---|---|
| `MemoryBudget` | 读 `/proc/meminfo`，按设备可用内存推荐上下文档位 |
| `StorageGuard` | 下载前空间判定；模型库索引与磁盘交叉体检 |
| `FsKit` | 路径收敛、原子写、GGUF / ZIP / SQLite 魔数嗅探、目录统计、递归删除 |
| `Validators` | 采样参数收敛、宽松数字解析、搜索词清洗、URL 白名单、内网判定 |
| `PromptTemplate` | 四种预设、工具说明（含压缩版）、记忆条目清洗、按模型名猜参数量 |
| `TextKit` | UTF-8 安全截断、完整字符判定、文件名清洗、短哈希、token 粗估、句子切分 |
| `PerfStats` | 滚动窗口统计：均值 / P90 / 首字延迟 / 劣化判定 |
| `MarkdownOutline` | 跳过围栏代码块的标题大纲抽取 |

### 接入

- 对话页：首次进入按设备内存定默认上下文；参数弹窗改用 `Validators` 并把修正原因回显；
  状态条显示均值 / P90 与劣化建议；菜单新增「本篇大纲」「推理性能」。
- 系统提示词：改走 `PromptTemplate`，≤2.5B 的小模型自动用压缩版工具说明（工具清单仍与可执行集合同源）。
- 下载：空间判定换 `StorageGuard`；GGUF 校验换 `FsKit.sniff`（读文件头，不再是只看后缀）。
- 模型库：进页面顺手体检索引与磁盘，发现幽灵 / 孤儿 / 空壳时提示一次（不重复打扰）。
- 联网抓取：`fetch_url` 增加内网拦截，正文截断改为 UTF-8 安全截断。

### 工程

- CI 拆成 `verify`（单测 + Lint）与 `build`（APK + 产物校验）；
- 新增 native 构建缓存（`app/.cxx`）；
- 新增产物校验：`apksigner` 打印证书、`aapt2` 打印 ABI 与版本，tag 上拒绝 debug 签名；
- 新增并发组，同分支新推送取消旧运行；
- 单测 40 → 87 个。

### 文档

- 新增 `docs/`：完整手册、架构与数据流、故障排查、性能数据、图解与流程图；
- 13 张配图（架构图、闭环图、数据图表、终端截图、能力矩阵、参数收敛示意、目录树、数据模型）。

## v2.4.2 — 一轮系统性纠错（并发 / JNI / 智能体 / 安全）

这一版没有新功能，全是把「看起来能用、偶尔不对」的地方钉死。按影响面从大到小：

**下载会静默地不下载**：`start()` 先投递任务、后登记任务表。单线程池可能在上半段代码执行前就跑完 `run()`
并把任务从表里摘掉，随后任务又被塞回去 —— 留下一个「永远显示下载中、实际什么都没发」的僵尸任务，
直到进程重启。改为先 `putIfAbsent` 登记再投递，并把「取消」判定移出 `run()` 的入口。

**点了取消却永远不结束**：`run()` 开头有个 `if (cancelled) { finish(...); return; }` 提前返回分支，
它绕过了 `tasks.remove()`，任务就卡在表里，`task()` 永远是「下载中」，同一个文件再也点不动。
取消现在统一走主流程（发请求前先判一次），保证一定会 `finish()`。

**native 层的 use-after-free 窗口**：`acquire()` 原来是「拿 `g_mu` 查存活 → 放 `g_mu` → 抢 `s->mu`」，
这个空档里另一个线程 `nativeFree` 掉会话，就会对一个已释放的 mutex 上锁；而 `nativeFree` 自己在持锁
`delete` 的同时，生成线程可能仍握着这具 ctx 在采样。改成引用计数：`nativeFree` 只把会话从存活表摘掉
并放掉自己那份引用，最后一个持有者销毁。

**KV 前缀复用的位置错位**：上一轮生成结束后，KV 里还留着「上一轮输出」的 token，而 `s->kv` 只记录了
prompt 部分。下一轮直接在这段序列上做前缀匹配，命中位置会整体错位，`llama_memory_seq_rm` 删掉的是
别的区间，随后 decode 的位置与 KV 实际内容不一致 —— 多轮对话里表现为答非所问、偶发采样崩溃。
现在先用公共前缀重建一份「按位置对齐」的序列，再做带位置的删除；同时修掉了两条会漏掉
`llama_sampler_free` 的提前返回（每次失败泄漏一个采样器 + 一段解码批次）。

**中文输出被 JNI 掐断**：`nativeBuildPrompt` 用 `std::u16string(s.begin(), s.end())` 直接把 UTF-8 字节
逐字节加宽，中文会变成乱码，BMP 外字符（emoji）还会让 JNI 拒绝。改为走完整的 UTF-8 → UTF-16 转换。

**按了停止，工具还在跑**：智能体循环只在「生成」阶段检查取消，生成被打断后依然会执行本轮解析出的
工具调用（包括真命令）。现在每个安全点都检查，确认弹窗里按停止也会让该工具不执行。

**「继续生成」变成重复输出**：续写时上下文里没带上「已经写出来的部分」，模型只能从头再写一遍，
而气泡里已经有前一段，两段叠在一起。另外续写没有产出时会把原文覆盖成提示语 —— 一并修正。

**流式过程中满屏工具标签**：生成时界面直接显示原始 token，`<function name=...>`、`<think>` 全都露出来，
随后又整体替换成「只显示正文」的版本，看起来像回答被吞了。流式渲染也剥掉标签与思考块；回灌给模型的
历史同样先剥离，避免诱导模型重复调用同一个工具。

**本地网页预览可读应用私有文件**：预览页开着 `allowUniversalAccessFromFileURLs`，而它渲染的正是
模型生成的 HTML —— 一段 `fetch('file:///data/data/.../chats.json')` 就能把对话历史读出来再发到任意地址。
关掉后相对资源的 `<img>` / `<script>` / `<link>` 仍正常，核心预览需求不受影响。

**其它**：
- 详情页每次进度回调都会为整张文件列表重新构造 `LocalModel`，且在渲染路径里重复算文件名；改为纯字符串下标。超长文件名补哈希前缀，避免触到文件名长度上限。
- 对话列表长按用绑定时刻的下标，流式生成期间可能删错消息；改用 `getBindingAdapterPosition()`。
- `LlamaEngine.load()` 的「检查 + 置位」不是原子的，连点两次加载会互相把对方刚建的会话关掉。
- 消息裁剪会跳过 system，小上下文机型上 system + 首条用户输入即超预算，一次都裁不掉。
- 终端页输出缓冲无上限，`cat` 一个大文件就能把页面撑爆；超过 200K 字符从头丢。
- 本地模型索引不去重，旧版本并发写可能留下两条一样的记录；删除时文件不存在就跳过删索引，会留下删不掉的幽灵条目。
- 下载前检查剩余空间；进度通知带上速度；`SimpleDateFormat` 改 `ThreadLocal` 复用；几处 `toLowerCase/toUpperCase` 补 `Locale`。

## v2.4.1 — 修复智能体工具调用被吞

模型其实一直在**正确**调用工具，是两处问题让调用变成一堆残渣：

1. **主因**：`llama_token_to_piece` 传了 `special=false`，llama.cpp 对 `LLAMA_TOKEN_ATTR_CONTROL/UNKNOWN` 的 token 直接返回空串。MiniCPM5 把 `<function>` / `<param>` 做成特殊 token，于是标签头被丢弃，只剩 `name="web_search">` 这种残缺文本。改为 `special=true`（llama.cpp 自身 server / CLI 的默认行为）。
2. **次因**：解析器只认 JSON，而 MiniCPM5 的原生格式是 XML。现已支持 MiniCPM5 原生格式、Qwen3-Coder 风格、以及原有的 JSON 代码块 / `<tool_call>` / `TOOL_CALL:`。

另外：剥掉思考块（含未闭合的截断情况）、工具结果改用 `<tool_response>` 回灌、system prompt 改为教模型用它自己的原生格式。

## v2.4.0 — 真正的工具调用闭环

在此之前对话页**没有任何工具调用**：模型输出不被解析、工具结果不回灌，「联网」只是个强制检索开关，「工作区 / 终端」只是跳转页面——所以模型只能回你「我无法访问终端」。

现在是一条完整闭环：**模型输出调用 → App 真实执行 → 结果回灌 → 继续推理**，最多 8 步、可随时停止；新增 `Shell`（真 shell）、`ToolCall`（协议解析）、`AgentTools`（工具集），危险操作弹窗确认，终端页也从受限伪终端换成真 shell。

## v2.3.1 — 修复模型加载失败（native 库未释放）

**现象**：模型加载必然失败。

**原因**：v2.3.0 用了 `useLegacyPackaging = false`（等价 `extractNativeLibs=false`），`.so` 不落盘；而 arm64 的 CPU 后端是多档指令集独立库，只在运行时按路径 `dlopen`，且 `libggml.so` 本身**不含** CPU 后端。于是注册后端数为 0，模型必然加载失败。

**修复**：改为 `useLegacyPackaging = true`，强制 `.so` 释放到 `nativeLibraryDir`。顺带补了加载可靠性：检测后端注册数、区分「后端未注册」与「模型文件问题」、KV 量化 / FlashAttention 逐档降级、新增 `nativeLastError()` 在界面显示具体失败原因。

## v2.3.0 — 多档指令集 + FlashAttention + KV 量化（提速）

`GGML_CPU_ALL_VARIANTS` 多档后端 + 运行时按 `HWCAP` 选档；KV 量化为 `q8_0` + FlashAttention；单次输出上限 1024 → 4096，默认上下文 4096 → 8192，新增「继续生成」。

## v2.2.0 — 对话页升级为智能体界面（初版）

输出上限放开（256 → 1024，上下文 2048 → 4096）；新增**开关式联网搜索**（DuckDuckGo → Bing 兜底，无需 API Key）；新增**应用私有工作区**与自动保存生成的 HTML、**工作区页**与 **HTML 预览页**；新增受限终端与斜杠命令；AI 回复改用 **Markwon** 渲染 Markdown，新增工具卡片。

## v2.1.1 — 修复 release 包无法加载模型（R8 回归）

**原因**：`llama_bridge.cpp` 用**字面方法名**回调 Java（`GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V")`），而 v2.1 的 proguard 规则只保住了 native 方法与 `LlamaBridge` 类本身，没保住回调接口的方法名，`onToken` 被 R8 重命名。

**修复**：在 `proguard-rules.pro` 保留 `LlamaBridge$TokenCallback` 及**所有实现类**的 `onToken` 方法名，并逐项复核产物 dex 中的 JNI 符号。

**教训**：只要 native 层按「字符串名字」查找 Java 符号，就必须在 proguard 里显式保留——这一点**编译期无法验证**，`assembleRelease` 通过并不代表运行可用。

## v2.1 — 更快、更稳、更好用

**提速**：arm64 显式 `-march=armv8.2-a+dotprod+fp16`（此前 `GGML_NATIVE` 交叉编译自动关闭，整包退化为基线，量化内核被整段编译掉——是当时偏慢的主因）；KV 前缀复用；按真实可用核数限制线程数；`n_ubatch` 512 → 128。

**体验**：历史会话持久化（`chats.json`，原子写）；长按气泡操作与「导出对话」；上下文用量实时显示；停止即时反馈。

**下载**：断点续传、`GGUF` 魔数校验、前台服务通知、任务落盘可恢复、取消即时断开。

**工程化**：修复 JNI use-after-free；索引原子写；各 Activity 补 Handler 清理与 alive 守卫；列表改 DiffUtil；17 个单元测试；Lint 改严格模式；Version Catalog；Gradle Wrapper；CI 加单测与 Lint；release 开 R8（20.6 MB → 11.6 MB）。

## v2.0 — 内置 llama.cpp，下载完就能对话

接入 llama.cpp b11205（JNI + CMake，纯 CPU 推理）、应用内下载 GGUF、流式对话（逐 token 上屏 / 可中断 / 参数可调）、本地模型库；界面改渐变头部 + 圆角卡片 + 对话气泡。

实现要点：`llama_bridge.cpp` 负责加载 GGUF、套用模型自带 chat template、分词、上下文超长丢弃最早历史、采样器链、逐 token 流式回调与取消；**UTF-8 分片处理**（token 边界常把中文 / emoji 切成半个字符，按「完整字符」切分后再转 UTF-16）。

踩坑记录：`llama_model_params` 新版移除 `use_mmap` / `use_mlock`，改为 `load_mode = LLAMA_LOAD_MODE_MMAP`；`GGML_LLAMAFILE` 的 sgemm 在 32 位 ARM 上用到 `vld1q_f16`，NDK clang 编译不过，已关闭（主要面向 x86，ARM 走通用 / NEON 路径）。

## v1.2 / v1.1 / v1.0 — 早期：把接口和列表做对

**v1.2 修复「404 page not found」**：通过抓取魔搭网页自身请求，确认真实接口要对 `/api/v1/dolphin/models` 用 **`PUT`**，筛选参数名是 **`Criterion`**（`SingleCriterion` 被忽略），筛选维度是响应里 `Data.FiledAgg` 的字段。实测：`POST`/`GET` → 404，`PUT` → 200。

**v1.1 升级与修 Bug**：AGP 8.13.2、compileSdk/targetSdk 36、AndroidX + Material 3 + Markwon、RecyclerView + SwipeRefreshLayout、Material 3 DayNight 暗色、正式 release 签名、GitHub Actions 自动构建与发布。修复：双重标题栏、首页聚合兜底导致的分页报错、搜索竞态覆盖（引入 `reqSeq`）、翻页跳页（页码改由 `nextPage` 内部维护）、edge-to-edge 遮挡。

**v1.0**：零第三方依赖的 ListView 初版。
