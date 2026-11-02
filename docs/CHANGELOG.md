# 更新日志

> 🌐 语言 / Language: 中文 · [English](CHANGELOG.en.md)

完整历史。按时间倒序。

## v2.7.0 — 把散在四条分支上的改动收成一次版本

这一版没有新写什么，做的是合并与对账：`fix/v2.6.1`、`fix/version-consistency`、
`feat/agent-toolkit-57`、`feat/site-autoupdate`、`auto/profile-readme-1777` 五条分支各自独立开发，
谁也没进过 `main`；版本号有的写 2.6.1、有的写 2.7.0，`app/build.gradle` 里仍是 2.6.1。
现在全部合到一处，版本号只有一处真源。

### 合并进来的

- **智能体工具从 8 个扩到 57 个**（`feat/agent-toolkit-57`）：新增 `agent/ToolRegistry` 作为工具说明、
  工具名白名单、危险动作判定的唯一真源，加工具不必再改三处。文件、文本、计算与时间、设备、
  联网、记忆与待办、外部模型七组工具，按组开关；新增 `PathGuard` 作为 shell 执行前的两道闸，
  `SecureRedactor` 过滤密钥；纯逻辑件 `DigestKit` / `TimeKit` / `JsonAccess` / `SseParser` / `Bm25`
  / `DiffKit` / `DocConverter` 都能在 JVM 上直接单测。
- **修掉「文档说到了、代码没做到」的一批问题**（`fix/v2.6.1`）：短读导致对话历史整份丢失、
  会话文件没有写入上限、取消下载等于放弃全部进度、断点续传可能拼出畸形文件、
  `backup_rules.xml` 规则一直空转、Android 14 前台服务缺类型、未知长度下载没有兜底、
  `LlamaEngine.load()` 回调为空时 NPE、原生层 `g_last_error` 数据竞争等。
- **站点自动更新链路**（`feat/site-autoupdate`）：`site-src/` 下的构建与校验脚本、
  `update.json` 与过期检测、首页筛选与更新记录页。
- **GitHub 个人主页 README**（`auto/profile-readme-1777`）。

### 版本号落地

- `app/build.gradle` 的 `versionCode 15` / `versionName "2.7.0"` 与 README、文档、Release 产物
  名对齐。此前 Gradle 里还是 `2.6.1`，装上去系统看到的是 2.6.1，而 Release 页写的是 2.7.0。
- 合并冲突按「后续版本优先」处理：README 与文档里的版本行取 2.7.0，`CHANGELOG` 两节都保留。

### 验证

- `./gradlew testDebugUnitTest`：110 个用例全绿。
- `./gradlew assembleRelease`：arm64-v8a（多档指令集）+ armeabi-v7a 双 ABI 出包。

## v2.6.1 — 把「说到了但没做到」的地方做实

这一版不加功能。逐条对着代码找「文档宣称的行为和实际行为不一致」的地方，能复现的都修了，
每一条都能指到具体的出错路径。

### 会丢数据 / 会白折腾的

- **对话历史可能整份丢失**：`ChatStore.readAll()` 只调用了一次 `in.read(buf)`。`InputStream#read`
  只保证「至少读到一个字节」，设备存储紧张时一次短读就让 JSON 从中截断，解析失败后
  `readAll()` 返回空列表 —— 用户看到的是「聊天记录全没了」，而且下一次 `save()` 会把空列表写回去。
  模型索引（`index.json`）与待续传记录（`downloads.json`）是同一份写法，一起改成循环读满。
- **会话体积没有上限**：读取上限是 16 MB，写入却没有任何限制。超过后写出一份「自己都读不回来」的
  文件，等价于把历史清零。现在写入前按体积淘汰最久未动的会话。
- **点「取消」等于放弃全部进度**：取消时删除 `.part` 并清掉待续传记录，下次再点又从 0 开始。
  取消现在只停任务，断点保留，下次接着下；真正要清干净的是「删除」。
- **断点续传可能拼出畸形文件**：服务端换了同名文件时仍会返回 206，把新一代的尾部拼到旧一代的
  断点后面。现在解析 `Content-Range`，起始位置与本地区间不一致就丢弃断点整体重下。
- **自动备份必然失败**：`backup_rules.xml` 只排除了 `external/models`，而模型目录本身就在
  `getExternalFilesDir()` 下，规则一直是空转；24 MB 的备份配额被几个 GB 的模型撑爆后，
  连对话历史和语言设置也一起备份不上。现在补上 `root/file` 两个域，并把体积不可控的
  `workspace/` 一并排除。

### 会崩 / 会卡住的

- **Android 14 上前台服务起不来**：清单里声明了 `foregroundServiceType="dataSync"`，
  但 `startForeground()` 没有传类型，Android 14 会抛 `MissingForegroundServiceTypeException`，
  下载退化成普通后台线程，切走就被回收。现在显式传入类型。
- **未知长度下载不做出错兜底**：`Content-Length` 缺失时开头的空间体检整段跳过，只能等分区写满
  才报一个看不懂的 `IOException`。现在下载过程中每 5 秒补一次安全水位检查。
- **`LlamaEngine.load()` 的回调可为 null 但没判空**：任一分支回调都会 NPE，而模型加载失败
  （比如文件不全）恰恰是这条路径最常走的时候。
- **原生层 `g_last_error` 跨线程读写**：`nativeInit` 在加载线程写、`nativeLastError` 在界面线程读，
  是 `std::string` 的数据竞争。现在加锁，并统一走 `setLastError()`。

### 卡顿 / 白耗电的

- **流式输出每块都重绑整条气泡**：引擎已经把 40 ms 内的 token 合并，界面又对每块调
  `notifyItemChanged`，Markdown 渲染与推理线程抢 CPU，解码速度肉眼可见地掉。重绘改为 80 ms 节流，
  收尾强制刷一次。
- **联网工具没有超时上限兜底**：`fetch_url` / `web_search` 跑在智能体线程上，「停止」要到下一次
  回调才生效；黑洞地址会让线程挂十几秒。超时收紧到连接 8 s / 读 12 s，并按字节限长读取
  （原来按行计数，一行几 MB 的压缩页照样能吃满内存）。
- **`Lang.zh()` 每秒被调用上千次且带装箱**：改成标志位 + 原始 boolean。
- **`Workspace.list()` 用码点排序**：中文文件名按码点排出来是随机顺序，改用 `Collator` 走拼音。
- **写入 `.nomedia`**：模型目录在外部存储下，系统媒体扫描器会去扫几个 GB 的 `.gguf`。

### 测试

- 新增 `DownloadCenterTest`（4 个）：`Content-Range` 的标准写法、未知总长、大小写与空格、
  以及几种必须被拒绝的垃圾输入。
- `CoreKitTest` 补 7 个：UTF-8 安全截断（emoji 边界、上限超过长度时不越界）、
  存储体检的三类结论（幽灵 / 孤儿 / 空壳）与干净时的空结果、参数收敛（输出上限压到上下文 3/4、
  贪心采样 top-k 至少为 1）。
- 单测 99 → **110 个，全绿**；`lintDebug` 0 error；`assembleRelease` 通过（R8 + NDK 交叉编译）。

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
