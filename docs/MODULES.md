# 模块深入

> 🌐 语言 / Language: 中文 · [English](MODULES.en.md)

一个模块一节，讲清三件事：**它要解决什么**、**怎么做的**、**哪里容易写错**。
读完这一页，你应该能独立改这个仓库里的任何一处逻辑，而不必先通读全部源码。

目录：
[1 ModelApi](#1-modelapi--接口与降级) · [2 DownloadCenter](#2-downloadcenter--下载与续传) · [3 LocalModelStore](#3-localmodelstore--索引与原子写) · [4 Workspace](#4-workspace--文件系统收敛) · [5 Shell](#5-shell--真-shell) · [6 ToolCall](#6-toolcall--协议解析) · [7 AgentTools](#7-agenttools--工具执行) · [8 WebSearch](#8-websearch--联网检索) · [9 LlamaEngine](#9-llamaengine--会话管理) · [10 llama_bridge](#10-llama_bridgecpp--jni-桥接) · [11 ChatActivity](#11-chatactivity--对话页) · [12 MemoryBudget](#12-memorybudget--上下文预算) · [13 StorageGuard](#13-storageguard--存储体检) · [14 FsKit](#14-fskit--文件操作) · [15 Validators](#15-validators--输入收敛) · [16 PromptTemplate](#16-prompttemplate--提示词) · [17 TextKit](#17-textkit--文本工具) · [18 PerfStats](#18-perfstats--性能统计) · [19 MarkdownOutline](#19-markdownoutline--大纲) · [20 ToolGuard](#20-toolguard--防打转闸门) · [21 Rounds](#21-rounds--单轮预算) · [22 ToolSchemas](#22-toolschemas--工具定义的真源) · [23 Journal](#23-journal--工作日志) · [24 Memory](#24-memory--长期记忆) · [25 KbIndex](#25-kbindex--工作区检索) · [26 Patcher](#26-patcher--补丁应用) · [27 PerfAdvisor](#27-perfadvisor--自适应采样) · [28 LoopBreaker](#28-loopbreaker--内容级熔断) · [29 Calc](#29-calc--确定性算术)

## 1 ModelApi · 接口与降级

**要解决什么**：魔搭的公开接口没有正式文档，参数名靠猜会得到 404；而且服务端会随版本调整结构。
数据层因此要做三件事：请求、降级、宽松解析。

### 1.1 请求

```java
public static String http(String method, String url, String body) throws IOException
```

一个最小的 HTTP 封装：设 UA、超时、读流、错误码转异常。没有引入 OkHttp，
因为全项目只有几处请求，多一个 200 KB 的依赖不划算。

### 1.2 降级链

```text
尝试 1：完整参数（分页 + 排序 + 筛选）
    ↓ 失败（404 / 参数不认 / 网关拦 PUT）
尝试 2：去掉筛选
    ↓ 失败
尝试 3：去掉排序
    ↓ 失败
尝试 4：首页聚合接口 GET /api/v1/dolphin/agg/homepage
    ↓ 失败
抛异常 → 界面展示错误态 + 一键重试 + 网页模式兜底
```

每次降级都会在返回的 `Page` 上打标记：

| 标记 | 含义 | 界面表现 |
|---|---|---|
| `sortApplied` | 排序真的生效了 | false 时 Toast「该排序方式暂不可用」 |
| `filterApplied` | 筛选真的生效了 | false 时 Toast「筛选条件暂不可用」 |
| `fallback` | 用的是首页聚合数据 | 底部提示「当前为兜底数据」 |

这三个标记是这个模块存在的主要理由：**静默失败比失败更糟**。用户以为筛了，其实没筛，
会得出完全错误的结论。

### 1.3 宽松解析

不写死 `Data.Model.Models`，而是「在 JSON 里找最像模型数组的字段」：

```java
// 伪代码：先试已知路径，再逐层下探找数组
JSONArray arr = pickModelArray(root);
int total = pickTotalCount(root);   // 优先读数组同级的 TotalCount
```

代价是每次解析多花几毫秒；收益是接口改版时不必立刻发版。
历史上服务端改过两次字段层级，界面都只是「少了个数字」，没有白屏。

### 1.4 容易写错的地方

- **筛选参数名**是 `Criterion`（数组），不是 `SingleCriterion`；后者会被静默忽略并返回全量。
- **列表请求必须 PUT**。`GET` / `POST` 都返回 404，这一点曾经浪费过一整轮排查。
- **页码不要自己 +1**。翻页跳页的经典成因是「请求发出后页码又被外部改了」；
  现在页码由内部 `nextPage` 维护，外部只读。

## 2 DownloadCenter · 下载与续传

**要解决什么**：把几百 MB 到几 GB 的模型文件稳定地弄到手机上，网络断了能接、进程死了能继续、
下完能确认它真的是模型。

### 2.1 为什么是单线程

多个大文件并发下载会互相抢带宽，而且手机上多一条长连接就多一份电耗。
串行 + 队列，用户看到的是「一个接一个」，体验反而更可预期。

### 2.2 任务表与「僵尸任务」

这是本项目最隐蔽的一个 bug：

```java
// 错误顺序：先投递、后登记
pool.execute(run);         // 单线程池可能立刻跑完 run()
tasks.put(key, task);      // run() 里的 tasks.remove(key) 已经执行过了
// 结果：任务表里留下一条永远「下载中」、实际什么都没发的记录
```

```java
// 正确顺序：先登记、后投递
if (tasks.putIfAbsent(key, task) != null) return;   // 已在下载中
pool.execute(run);
```

还有一个对称的坑：`run()` 开头如果有个「已取消就提前 return」的分支，它会绕过 `tasks.remove()`，
任务卡在表里，同一个文件再也点不动。现在的做法是**取消统一走主流程**，
在发请求前判一次，保证无论如何都会走到 `finish()`。

### 2.3 续传

```text
有 .part 文件 → 读已下载字节数 → 请求头加 Range: bytes=N-
  ├─ 服务端 206 Partial Content → 追加写，total = N + Content-Length
  └─ 服务端 200 OK            → 不支持续传，从头写，total = Content-Length
```

魔搭对部分文件会 302 到 CDN，重定向后 `Range` 依然有效——这一点实测过，
所以续传命中率比想象中高。

### 2.4 校验

两道关：

1. **长度**：`part.length() == total`（服务端给了 `Content-Length` 时才检查）。
2. **魔数**：读前 4 字节必须是 `GGUF`。这一步能挡掉「鉴权失败返回了一段 HTML」或「拿到了一个 JSON 错误响应」。

只看后缀是没用的——`.gguf` 后缀的 HTML 文件在磁盘上完全合法。

### 2.5 任务落盘

`downloads.json` 记录未完成任务。应用重启后读这张表，把任务重新排进队列。
这张表是「进程被系统杀掉也能接着下」的关键。

### 2.6 容易写错的地方

- **不要用 `String.format` 拼进度文案**在回调里：每 400ms 一次，字符串分配会积累 GC 压力。
- **`Formatter.formatFileSize` 需要 Context**，在 worker 线程调用前先拿到。
- **取消要主动断开连接**，否则要等下一次读超时（默认 20 秒）才反应过来。

## 3 LocalModelStore · 索引与原子写

**要解决什么**：记住「下载了哪些模型」。听起来简单，但它是「本地模型全部消失」这类事故的高发区。

### 3.1 存储位置

```text
filesDir/models/
  ├── index.json          # 索引
  └── <name>.gguf         # 模型文件
```

应用私有目录，卸载即删，不需要任何存储权限。代价是用户无法用文件管理器直接看到——
但工作区页提供了浏览与分享。

### 3.2 原子写

```java
File tmp = new File(dir, name + ".tmp");
try (FileOutputStream out = new FileOutputStream(tmp)) {
    out.write(data);
    out.flush();
    out.getFD().sync();      // 关键：确保数据真的落到闪存
}
target.delete();
tmp.renameTo(target);        // 同目录改名是原子操作
```

半截索引的后果很严重：解析失败 → 本地模型列表变空 → 用户以为模型被删了 → 重新下载一遍。

### 3.3 去重

`add()` 会按 `localPath` 去重并保留最新记录。旧版本的并发写可能留下两条一样的记录，
现在读索引时会顺手丢掉重复项。

### 3.4 体检

`StorageGuard.audit()` 做交叉比对（见 13 节）。这类「幽灵条目」的危害是：
用户点进去报「文件不存在」，但列表里还在，看起来像软件坏了。

## 4 Workspace · 文件系统收敛

**要解决什么**：模型会给出任意路径，包括 `../../shared_prefs/auth.xml`。
工作区是唯一允许写的地方，所以它必须是唯一能收敛的地方。

### 4.1 路径收敛

```java
String rootPath = root.getCanonicalPath();      // 解开软链接、消除 ..
String target = candidate.getCanonicalPath();
if (target.equals(rootPath)) return root;
if (!target.startsWith(rootPath + File.separator)) return null;   // 越界
```

用 canonical path 而不是字符串拼接，是为了挡两件事：

| 攻击 | 字符串前缀比较 | canonical 比较 |
|---|---|---|
| `../x` | 挡不住（不以 root 开头） | 挡住 |
| `a/../../x` | 看实现 | 挡住 |
| `link -> /data/data/.../shared_prefs` | 挡不住（前缀是 root） | 挡住（解链接后越界） |
| `/data/data/pkg/a` 用 `..` 拼成绝对路径 | 看实现 | 挡住 |

### 4.2 原子写

同 `3.2`。工作区里还有一个额外要求：**目录要自动创建**，因为模型常写 `page/index.html` 这种带子目录的路径。

### 4.3 列表与统计

`list()` 返回一级条目，目录在前、名字升序；`usage()` 递归统计文件数与总字节。
递归时不跟随软链接，避免做成环。

## 5 Shell · 真 shell

**要解决什么**：让模型真的能在手机上执行命令，同时不把手机交出去。

### 5.1 实现

```java
ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", command);
pb.directory(workspace.dir());        // 工作目录固定在工作区
pb.redirectErrorStream(true);
Process p = pb.start();
// 读输出 + 超时 60 秒 + 超过 200K 字符截断
```

### 5.2 边界

| 限制 | 值 | 理由 |
|---|---|---|
| 工作目录 | 工作区 | 让 `ls`、`cat` 有意义 |
| 超时 | 60 秒 | 防止 `sleep 9999` 卡住整个智能体循环 |
| 输出上限 | 200K 字符 | `cat` 一个大文件就能把界面撑爆 |
| 权限 | 应用沙箱 | 写不了 `/sdcard`、`/system`，也不需要 root |

### 5.3 为什么不是伪终端

v2.2 的「终端」是个受限命令解析器，只能跑白名单里的几条命令。
v2.4 换成了真 shell：既然模型已经能执行命令，假装它不能只是自欺欺人；
正确的做法是**加确认弹窗**，而不是**阉割功能**。

## 6 ToolCall · 协议解析

**要解决什么**：本地 GGUF 模型没有 function-call 模板，各自的输出格式还都不一样。

### 6.1 四种格式

```text
① MiniCPM5  <function name="shell"><param name="command">ls</param></function>
② Qwen3-Coder <function=shell><parameter=command>ls</parameter></function>
③ JSON 代码块 ```tool {"name":"shell","arguments":{"command":"ls"}} ```
④ 标签/行    <tool_call>{...}</tool_call>   /   TOOL_CALL: {...}
```

### 6.2 容错

| 场景 | 处理 |
|---|---|
| 没有闭合标签 | 正则里用 `(?=<function|\z)` 断言到下一个调用或结尾 |
| 值里含 `</param>` | 支持 `<![CDATA[...]]>` 包裹，解析时拆掉 |
| 一个回复里多个调用 | `parse()` 返回列表，按出现顺序执行 |
| 模型输出普通 JSON 示例 | 只有 name 命中已注册集合才算调用 |
| 思考块里也有标签 | 先剥 `<think>...</think>`（含未闭合） |
| 参数名大小写不一 | 每个工具支持多个别名（`command` / `cmd` / `script`） |

### 6.3 为什么不用正则一把梭

因为**嵌套**。参数值里可能出现 `<param`，JSON 里可能出现 `</function>`。
现在的做法是分层：先按「结构特征」切块，再在块内提取字段，最后用 `JSONObject` 解析 JSON 形式。

### 6.4 安全阀

```java
// 只有工具名命中已注册工具集才算调用
if (!AgentTools.names().contains(call.name)) continue;
```

没有这一条，模型回复里随手写的 `{"name": "shell", "arguments": {...}}` 示例会被真的执行。

## 7 AgentTools · 工具执行

**要解决什么**：把「模型想干什么」翻译成「手机上真实发生的动作」，并把结果变成模型能读的文本。

### 7.1 统一契约

```java
public String exec(ToolCall c)   // 永远返回文本，永不抛异常
```

出错也返回文本（`"错误：缺少参数 command"`），让模型能自己纠错。
抛异常会让智能体循环中断，用户看到的是一个死掉的气泡。

### 7.2 结果截断

```text
MAX_RESULT = 6000 字符
```

工具结果会进上下文。一个 `cat` 大文件的结果如果不截断，会直接把上下文吃满，
下一轮对话立刻触发裁剪，前缀复用全废。

### 7.3 危险操作

| 工具 | 需要确认 | 理由 |
|---|---|---|
| `shell` | ✅ | 等于把终端交出去 |
| `write_file` | ✅ | 会覆盖已有文件 |
| `delete_file` | ✅ | 不可恢复 |
| `read_file` / `list_files` | ❌ | 只读工作区 |
| `web_search` / `fetch_url` | ❌ | 只读公网 |
| `open_preview` | ❌ | 只是开个页面 |

「本会话都允许」不会跨会话继承——授权是有时效的。

### 7.4 结果回灌格式

```text
<tool_response>
$ ls -la
total 12
-rw-r--r-- 1 u0_a123 812 index.html
</tool_response>
```

用 `user` 角色 + `<tool_response>` 包裹，这正是 MiniCPM5 / Qwen 模板里工具返回的标准写法，兼容性最好。
用 `system` 角色回灌会让部分模型认为「这是又一个指令」，开始重复调用。

## 8 WebSearch · 联网检索

**要解决什么**：不申请 API Key 也能联网。

### 8.1 来源

| 顺序 | 来源 | 解析方式 |
|---|---|---|
| 1 | DuckDuckGo 轻量 HTML 版 | `class="result__a"` + `result__snippet` |
| 2 | Bing 网页结果 | `<h2><a href=...>` + `b_lineclamp` |

两个都失败会抛异常，由调用方提示「联网检索失败」。

### 8.2 正文抓取

`fetchPage()` 抓 HTML 去标签取正文，限长截断。两处细节：

- 截断按 UTF-8 完整字符（`TextKit.trimUtf8`），否则 emoji 会变问号；
- 只允许 http/https，且拒绝本机 / 内网地址（`Validators.isPrivateHost`）。

第二条是必须的：本地模型完全可能写出 `http://localhost:8080/...` 去探测用户手机上的服务，
而这条链路是「模型可控」的。

### 8.3 局限

没有 API Key 意味着受反爬策略影响：结果页结构一变就解析失败，频率太高会被限流。
这是已知的、写在「已知限制」里的取舍。

## 9 LlamaEngine · 会话管理

**要解决什么**：模型常驻内存、生成串行、回调到 UI、状态可取消。

### 9.1 单例 + 单线程

```java
private final ExecutorService worker = Executors.newSingleThreadExecutor();
```

一个 llama context 不能被两个线程同时用。单线程池保证「同一时刻只有一个生成任务」，
但**不够**：两个线程可能先后把任务排进队列，第二个白等一轮还会覆盖句柄状态。
所以 `generating` 的检查与置位必须在锁内：

```java
synchronized (this) {
    if (generating) { listener.onError("正在生成中"); return; }
    generating = true;
}
```

注意生成本身**不**持有这把锁——生成在 worker 线程跑，锁只保护状态位。

### 9.2 加载的原子性

「检查是否已加载 + 置 loading 位」如果不是原子的，连点两次加载会互相把对方刚建的会话关掉。

### 9.3 裁剪

见 [`DIAGRAMS.md`](DIAGRAMS.md#4-上下文裁剪策略)。核心原则：**以消息为单位丢，不在 native 层按 token 截断**。

### 9.4 回调合并

```java
if (now - lastFlush[0] >= UI_FLUSH_MS) { lastFlush[0] = now; emit(buf, listener); }
```

40ms 一批。太小会和 UI 抢 CPU（每个 token 一次跨 JNI + 一次布局），
太大影响「逐字」手感。40ms 大约 25 帧/秒，人眼看着是连续的。

## 10 llama_bridge.cpp · JNI 桥接

**要解决什么**：把 llama.cpp 的 C API 包装成 Java 能用的、线程安全的、可取消的接口。

### 10.1 会话表与引用计数

```text
g_sessions: map<handle, shared_ptr<Session>>
g_mu:       保护这张表
s->mu:      保护单个会话的生成状态
```

`acquire(handle)` 拿到 `shared_ptr` 的**副本**，出表后仍然有效；
`nativeFree` 只从表里摘掉并放掉自己那份，最后一个持有者析构时 `delete` context。

旧写法「拿 g_mu 查存活 → 放 g_mu → 抢 s->mu」有一个窗口：
另一个线程在这个空档里 `nativeFree` 掉会话，接下来就是对着已释放的 mutex 上锁。

### 10.2 UTF-8 分片

token 边界会把一个汉字切成两半（3 字节 UTF-8 序列只给了前 2 字节）。
直接把半个字符转 UTF-16 会得到替换符。做法：累积字节，只在「完整字符边界」才转：

```cpp
size_t valid = 0;
for (size_t i = 0; i < len; ) {
    size_t step = utf8_step(bytes[i]);         // 1~4
    if (i + step > len) break;                 // 半个字符，留着下次
    i += step;
    valid = i;
}
```

Java 侧收到的一定是可解码的字节流。单测里对「任意截断位置都不出现替换符」有覆盖。

### 10.3 KV 复用

```text
1. 计算本轮 prompt token 序列
2. 与上一轮的序列求公共前缀长度 n
3. 用「按位置对齐」的方式重建序列（上一轮输出仍占着 KV 位置）
4. llama_memory_seq_rm(pos=n, end=-1) 删掉 n 之后的 KV
5. 只解码 tokens[n..]
```

第 3 步是 v2.4.2 修的重点。跳过它，第 4 步删的就是别的区间，随后 decode 位置错乱。

### 10.4 取消

`nativeCancel` 只置一个 `atomic<bool>`，采样循环每个 token 检查一次。
不杀线程、不求立即返回——保证「最多多生成一个 token」就停下。

## 11 ChatActivity · 对话页

**要解决什么**：把所有东西缝在一起——引擎、存储、工具、渲染、状态。

这个类有 1500 多行，是全项目最大的文件。它承担的是「编排」，不是「逻辑」：
真正有规则的代码都在 `core/` 与 `agent/` 里。

### 11.1 智能体循环

```java
for (int step = 1; step <= MAX_ROUNDS; step++) {
    Gen gen = generateBlocking(ctx);
    if (stoppedBy(gen)) break;
    List<ToolCall> calls = ToolCall.parse(gen.text);
    if (calls.isEmpty()) break;
    for (ToolCall c : calls) {
        if (stoppedBy(gen)) break;          // 每个安全点都检查
        if (AgentTools.risky(c.name) && !confirm(c)) { 回灌拒绝原因; continue; }
        回灌(AgentTools.exec(c));
    }
}
```

### 11.2 流式渲染

两件事必须同时做：

1. 剥离工具标签与思考块（否则用户看到 `name="web_search">` 这种碎片）；
2. 保持 Markdown 渲染不崩（半个代码块、半个表格）。

做法是流式阶段用「纯文本 + 剥标签」，结束后再整段 Markdown 渲染。
中间会有一个视觉切换，但比让用户盯着原始 token 好得多。

### 11.3 消息下标

流式生成时列表会不断变化，长按用「绑定时刻的位置」会删错消息。
必须用 `getBindingAdapterPosition()` 现取。

### 11.4 生命周期

```java
private boolean alive = true;
// onDestroy: alive = false; ui.removeCallbacksAndMessages(null);
```

所有回调第一步都是 `if (!alive) return;`。看起来啰嗦，但比 `IllegalStateException: 
Can't create handler inside thread that has not called Looper.prepare()\` 好排查一百倍。

## 12 MemoryBudget · 上下文预算

**要解决什么**：给一个不会把进程撑爆的默认上下文。

### 12.1 输入

| 输入 | 来源 |
|---|---|
| `MemTotal` / `MemAvailable` | `/proc/meminfo` |
| 模型体积 | `LocalModel.size` |
| KV 是否量化 | 固定 true（代码里用 q8_0） |

### 12.2 计算

```text
room = available - modelSize/2 - 320MB          # 权重走 mmap，一半算进页缓存
逐档试 STEPS（512…32768）：
    estimateExtraKb(step) ≤ room → 记为可行
取最大的可行档；再按 total/8 封顶（给页缓存留空间）
memory 信息读不到 → 返回 fallback
room < 256MB      → 返回 min(fallback, 512)
```

### 12.3 为什么封顶 `total/8`

`MemAvailable` 是「现在」的值，应用长时间运行后会被别的进程挤压。
按比例封顶比按绝对值封顶更稳，因为设备差异太大。

### 12.4 局限

这是**启发式**，不是精确测量。它的目标是「默认值不要离谱」，不是「精确预测」。
真机表现仍然取决于具体模型结构（层数、头数、词表大小）。

## 13 StorageGuard · 存储体检

**要解决什么**：两件事——下载前别把自己写爆；索引与磁盘别悄悄不同步。

### 13.1 四档判定

| 判定 | 条件 | 界面 |
|---|---|---|
| `OK` | 放下后仍 ≥ 256 MB | 正常开始 |
| `TIGHT` | 放得下但 < 256 MB | 开始 + Toast 提醒 |
| `INSUFFICIENT` | 放不下 | 拒绝 + 说清差多少 |
| `CRITICAL` | 剩余 < 128 MB | 拒绝 + 建议先删模型 |

### 13.2 体检

```java
List<Finding> f = StorageGuard.audit(entriesFromIndex, filesOnDisk);
String msg = StorageGuard.summarize(f);   // 空串表示健康
```

三种结论：

- `GHOST_ENTRY`：索引有、磁盘无 → 点进去报错
- `ORPHAN_FILE`：磁盘有、索引无 → 白占空间
- `EMPTY_FILE`：0 字节 → 下载中断的遗留

### 13.3 为什么只在「与上次不同」时提示

体检结论很可能一直存在（用户懒得清理）。每次都弹 Toast 会变成噪音，
只在第一次发现时提一次，用户记住就行。

## 14 FsKit · 文件操作

**要解决什么**：把「路径收敛、原子写、魔数嗅探、目录统计」收口到一处，避免每个模块各写一份。

| 方法 | 用途 |
|---|---|
| `resolveInside(root, rel)` | 路径收敛，越界返回 null |
| `writeAtomic(file, data)` | 临时文件 → fsync → 改名 |
| `readText(file, max)` | 限长读取，超长加提示 |
| `sniff(file)` | 读头 4~16 字节判断真实类型 |
| `statDir(dir, prefix)` | 递归统计文件数 / 目录数 / 字节 / 最大文件 |
| `listSorted(dir)` | 目录在前、名字升序 |
| `deleteTree(f, root)` | 递归删除，拒绝删根 |

### 14.1 魔数表

| 魔数 | 类型 |
|---|---|
| `GGUF` | GGUF 模型 |
| `PK\x03\x04` | ZIP / APK |
| `SQLite format 3` | SQLite |
| `<!DOCTYPE` / `<html` / `{` | 网页或 JSON（多半是错误响应） |

### 14.2 为什么不用 `Files` API

minSdk 24，`java.nio.file` 要到 API 26。所以只能用 `File` + 手工 fsync。

## 15 Validators · 输入收敛

**要解决什么**：用户能填的东西和引擎能接受的东西之间有鸿沟，填错一个值就能让采样退化或崩。

### 15.1 收敛顺序

```text
1. 先定上下文（clamp 512~32768）
2. 输出上限 = min(输入, 上下文 × 3/4)，至少 64
3. temperature ∈ [0, 2]
4. top-k ∈ [0, 200]；temperature ≤ 0.01 时 top-k 至少 1
5. top-p ∈ (0, 1]
```

顺序很重要：如果先 clamp 输出再看上下文，会出现「输出上限大于上下文」的非法组合。

### 15.2 为什么 top-k 不能是 0 with greedy

`temperature=0` 是贪心采样，分布退化为 one-hot；此时 `top-k=0` 的语义在部分实现里是
「不截断」，两者叠加会让采样器走到一条奇怪的路径，输出整段重复。至少留 1 最稳。

### 15.3 URL 白名单

```java
safeUrl(url)      // 只放行 http:// 与 https://，去掉 CR/LF
isPrivateHost(h)  // localhost / 127.0.0.1 / ::1 / 10. / 192.168. / 172.16-31. / 169.254. / *.local
```

## 16 PromptTemplate · 提示词

**要解决什么**：提示词散落在字符串常量里，模型一换就得改代码；小模型被长提示词带跑偏。

### 16.1 四种预设

| 预设 | 场景 | 特点 |
|---|---|---|
| `GENERAL` | 普通对话 | 简洁，要求带文件名的代码块 |
| `AGENT` | 智能体 | 鼓励先调工具再回答 |
| `CODER` | 编程 | 强调代码块与文件落盘 |
| `MINIMAL` | ≤2.5B | 极短，工具说明压成一行 |

### 16.2 压缩版工具说明

```text
完整版：<function name="工具名"><param name="参数名">参数值</param></function>
        + 每个工具的说明（多行）

压缩版：只给「格式 + 工具名列表」，一行一个
```

小模型的注意力预算很紧，工具清单写详细反而更容易漏格式。

### 16.3 工具清单不能自己维护

```java
String tools = PromptTemplate.toolSection(AgentTools.describe(web, files, shell), compact);
```

清单来自 `AgentTools`，与真实可执行集合同源。否则会出现「提示词说有某个工具，但执行时报未注册」。

### 16.4 参数量猜测

```java
guessBillionParams("Qwen2.5-0.5B-Instruct-Q4_K_M.gguf")   // 0.5
guessBillionParams("qwen2.5-7b-instruct")                 // 7.0
guessBillionParams("model-fp16.gguf")                      // 0.0（猜不到）
```

用正则找 `数字 + b` 且后面不接字母数字。猜不到就按 `AGENT` 处理——宁可给完整说明。

## 17 TextKit · 文本工具

| 方法 | 用途 | 关键点 |
|---|---|---|
| `trimUtf8(s, max)` | 按字节截断 | 绝不切在字符中间 |
| `endsWithCompleteChar(bytes, len)` | 判断末尾是否完整字符 | 流式拼接用 |
| `safeFileName(raw, max)` | 清洗文件名 | 替换 `/\:*?"<>|`、去控制字符、避开 Windows 设备名 |
| `shortHash(s, n)` | FNV-1a 短哈希 | 长文件名加后缀，避免超 255 字节 |
| `sha256Hex(s)` | 完整哈希 | 需要强唯一性时用 |
| `roughTokens(s)` | 粗估 token | 中文 1 字 ≈ 1，英文 3.6 字符 ≈ 1 |
| `sentences(s)` | 切句 | 用于判断「是否写完」 |
| `looksComplete(s)` | 是否收尾 | 句末标点 / 代码块闭合 / 括号闭合 |

### 17.1 `safeFileName` 为什么这么啰嗦

因为它挡的都是真实会遇到的：

| 输入 | 问题 | 输出 |
|---|---|---|
| `../../etc/passwd` | 路径穿越 | `.._.._etc_passwd` → 再由 `resolveInside` 兜住 |
| `CON` | Windows 保留设备名 | `_CON` |
| `a\u0000b` | 控制字符 | 去掉 |
| 200 个汉字 | 超 255 字节 | 截断 + `~a1b2c3d4` |

### 17.2 `looksComplete` 的用途

「继续生成」之前判断上一段是否真的写完了。已经以 `。` 或 ```` ``` ```` 结尾还去续写，
模型会重新组织一遍语言，看起来像「重复」。

## 18 PerfStats · 性能统计

**要解决什么**：单次 tok/s 抖动极大，只显示最后一个数字没有参考价值。

### 18.1 指标

| 指标 | 含义 |
|---|---|
| `lastTps()` | 最近一次生成速度 |
| `meanTps()` | 窗口内平均 |
| `percentileTps(0.9)` | P90，看尾部劣化 |
| `meanPrefillMs()` | 平均首字延迟 |
| `degraded()` | 最近一次是否低于历史均值 60% |

### 18.2 为什么用中位数思路

平均值会被一次异常（切后台、来电）拉低。P90 更能反映「正常情况下能有多快」。
两个都给，用户自己判断。

### 18.3 劣化判定

```java
if (size < 3) return false;              // 预热阶段不判断
return last < meanOfPrevious * 0.6;
```

给一句可操作的建议，而不是只报数字：「关掉后台应用、降一档上下文通常能恢复」。

## 19 MarkdownOutline · 大纲

**要解决什么**：两三千字的回复在手机上翻回去找某一节很痛苦。

### 19.1 实现

扫一遍行，识别 ATX 标题（`#` ~ `######`），跳过围栏代码块里的 `#` 注释。

```text
```bash          ← 进入代码块
# 这不是标题      ← 跳过
```              ← 离开代码块
# 这是标题        ← 收录
```

### 19.2 判定值得给大纲

```java
return markdown.length() > 800 && extract(markdown).size() >= 2;
```

太短或只有一个小标题时，菜单里不出现这一项——避免「点了没反应」。

### 19.3 行内标记清理

`**粗**`、反引号包裹的代码、「链接」形式、`<tag>` 都会被剥掉，留下纯文字。
菜单里显示 `## **注意事项**` 很难看。

---

## 写在最后

这十九个模块里，真正「聪明」的代码不多——大部分是在**处理现实**：
接口没有文档、服务端会改、模型不听话、内存不够、用户手滑、进程会被杀。

如果只能带走一条经验：**把「不确定」变成「可检查」**。
不确定空间够不够就写 `StorageGuard`；不确定文件是不是模型就读魔数；
不确定参数合不合法就跑一趟 `Validators`。每一次把猜测换成检查，
就少一个「偶尔不对、但复现不出来」的 bug。

## 20 ToolGuard · 防打转闸门

**要解决什么**：本地小模型拿到工具结果后重复同一个调用。它并不真的「记得」自己刚读过那个文件，
所以再发一次；不拦的话一轮 8 步全用在同一个调用上，最后什么答案都没有。

### 20.1 判重要与参数顺序无关

模型给同一件事的参数排序千变万化：

```text
{"path": "a.txt", "content": "hi"}   ← 第一次
{"content": "hi", "path": "a.txt"}   ← 第二次，其实是同一个调用
```

直接比 JSON 字符串会把它们当成两个，闸门形同虚设。`ToolGuard.canonicalArgs()`
先把键转小写、值去空白，再按字典序排成 `content=hi;path=a.txt`。

### 20.2 三道闸门，各管一段

| 层 | 手段 | 作用 |
|---|---|---|
| 提示词 | `PromptTemplate.noRepeatRule()` | 让模型少走一步（成本一句话） |
| 宿主 | `ToolGuard` | 同一调用最多真执行 2 次，重复 2 次收尾 |
| 采样 | 重复惩罚 + 复读熔断 | 连文字层面的复读也掐掉 |

只管一层都不够：提示词拦不住「模型就是不听话」，闸门拦不住「模型换个写法继续试」，
采样惩罚又收不了场。

### 20.3 为什么上限是 2 而不是 1

有些调用本来就该来两次：先读、改完再读回来确认。一次就拦会误伤这条最正常的用法。

### 20.4 超长参数

写大文件的 `content` 可能几万字符。指纹只取前 `MAX_ARGS` 个字符，并且**按完整字符截断**
（不切断 emoji 的代理对）——判重不需要全文，但截出来的键要能看懂。

## 21 Rounds · 单轮预算

**要解决什么**：`MAX_ROUNDS = 8` 是写死的。8 不是安全边界，只是当初随手写的数字 ——
而「读→改→验」本身就要 3 步，稍复杂的任务必然撞上限，用户看到「已达上限」而事情只做了一半。

### 21.1 为什么是三条线而不是一条

```java
public static Stop stopReason(int round, Budget budget, long nowMs, double ctxUse)
```

| 线 | 触发 | 为什么必须有 |
|---|---|---|
| 步数 | `round + 1 >= budget.rounds` | 防止模型无限空转 |
| 墙钟 | `now - startedAt >= limitMs` | 步数再多，也不该让用户盯着几分钟没动静 |
| 上下文 | `ctxUse >= 0.95` | 再喂一轮只会把最早的内容挤掉，模型看到残缺历史 |

单靠步数不行：一个 `shell` 卡住 60 秒，8 步就是 8 分钟。
单靠时间也不行：一次 `list_files` 只要 20 毫秒，时间预算能允许上千次空转。

### 21.2 收尾必须说清是哪条线

`stopMessage()` 按原因给不同措辞：步数用完 → 提示「回复继续或拆小目标」；
时间到 → 「先给当前结论」；上下文满 → 「新开会话或让我先总结」。
只说「已达上限」等于没说 —— 用户不知道该继续还是该换目标。

### 21.3 空转判定比重复判定更隐蔽

`ToolGuard` 原来只判「同一个调用又来一次」。但更常见的失败是**换着花样查**：
`read_file` → `list_files` → `kb_search` → 再 `read_file`，每次都合法、都不重复，就是不写。
`markIdleRound()` 连续 3 轮只读就收尾；任何一次危险工具（会改动状态）执行都会 `markProgress()` 清零。

## 22 ToolSchemas · 工具定义的真源

**要解决什么**：工具说明以前是一段手写散文，提示词能看懂、程序看不懂。
想给外部模型发 OpenAI 兼容的 `tools` 数组时只能再手写一份，两边一漂移就是
「提示词说能调、实际调不通」——不报错，只是模型一直在调一个不存在的工具。

### 22.1 一份定义，三种渲染

```java
ToolSchemas.describe(enabled, zh)   // → 提示词里的文字说明
ToolSchemas.toJson(enabled, zh)     // → OpenAI 兼容的 tools 数组
AgentTools.names()                  // → 解析器白名单
```

三者都从同一张 `ALL` 表生成。新增工具只改一处，三边自动同步；
`AgentToolsTest.everyEnabledToolHasSchema()` 会保证没有工具漏登记。

### 22.2 为什么自己拼 JSON 而不用 JSONObject

这个类要能在纯 JVM 单测里跑，而 Android 的 `org.json` 在单测环境是空壳（调用即抛异常）。
自己拼的同时把转义写全（`"` `\` `\n` 与控制字符），比依赖一个在测试里会炸的实现稳。

## 23 Journal · 工作日志

**要解决什么**：上下文一裁，模型就忘了自己做过什么，于是反复重读同一个文件、或把改好的又改回去。

### 23.1 为什么是外部日志而不是塞进上下文

日志是**按需读**的（`journal_read` 工具）。它不占常驻窗口，也不会被裁剪弄丢。
进提示词的只有 `summary()` 那两三行：执行次数 + 已改动的路径。

### 23.2 两个细节都会咬人

- **结果摘到首行**：`cat` 一个文件的结果可能是 6000 字，日志里只留首行 + 省略号。
- **参数与结果都要脱敏**：日志会被回灌进上下文、也可能被用户导出。
  实测过一次：参数里原样留着一个 GitHub token，结果被标成 `<redacted:github>`，参数却没有。
  现在两边都过 `SecretVault.redact()`。

## 24 Memory · 长期记忆

**要解决什么**：跨会话记住用户。这件事最大的风险不是记不住，是**记得太死**。

### 24.1 结构

```text
<id>|<timestamp>|<fact>
```

一行一条，`SecretVault` 在入口脱敏，`Memory.add()` 按 Jaccard 相似度 ≥0.6 判定为「同一件事」，
**覆盖而不是追加** —— 否则写上半年就会出现「用户喜欢简短」和「用户喜欢详细」并列，
模型只能猜哪个算数。

### 24.2 为什么不用向量

手机上跑 embedding 意味着再多一份 .so、再多一块内存、再多几十毫秒冷启动；
而记忆条目通常只有几十条、每条一句话，BM25 + 二元组足够。
向量检索真正的价值在「语义近似」（问「怎么让手机不卡」命中「内存优化」），
那种情况让模型改写关键词更划算。

### 24.3 淘汰与限额

`MAX_FACTS = 120`，超出丢最旧的。记忆是「当前生效」的东西，不是存档。

## 25 KbIndex · 工作区检索

**要解决什么**：一个 40KB 的日志读进上下文就是上万 token，而模型只想知道「哪一行报了错」。

`Retriever.chunk()` 按**行**滑窗切块（默认 900 字 / 重叠 2 行），保留 `文件#L行号` 作为来源标识。
按行而不是按字符切，是因为代码/日志里一行通常就是一个语义单元，从中间切断的片段读起来毫无意义。

### 25.1 中文为什么要二元组

中英文混排时，直接按字符切会把「内存」拆成「内」「存」两个高频字，
于是「内」和「存」各自命中半篇文章。按 2-gram 切（内存/存不/不足）既保留词边界，又不需要词典。

### 25.2 增量重建

`build(force)` 按 `lastModified` 比对：没改过的文件原样复用旧片段。
工作区通常只有几 MB，但全量重切一次也要几十毫秒，按键就重建会卡。

## 26 Patcher · 补丁应用

**要解决什么**：`write_file` 是覆盖写。改 20KB 文件里的一行要重写另外 19.9KB，
输出一截断，原文件就毁了一半。

### 26.1 四条铁律

1. **定位块必须逐字匹配**：上下文行 + 删除行，顺序也要一致。行号只是提示 —— 模型写的行号经常整体偏几行。
2. **多处匹配就拒绝**：并给出候选行号。静默改错行比不改糟得多。
3. **纯插入才允许按行号落点**：此时没有内容可校验。
4. **全部片段先算成功才落盘**：中途失败不产生半成品。

### 26.2 一个实现上的教训

最初的实现把「上下文 + 删除行」拼成一个大块去 `indexOf`。这在 diff 里所有行都相邻时才成立；
一旦中间夹着没写进 diff 的行，就永远匹配不上，报错还会指错地方。
现在的做法是**逐行推导位置**：先定位锚点序列的起点，再逐行重写这一段。

### 26.3 `edit_file` 与 `apply_patch` 的分工

改一处用 `edit_file`（`old` 必须唯一命中）；同时改多处用 `apply_patch`。
两者都不猜：宁可让模型重读一遍文件，也不能让它在「大概这一行」上落笔。

## 27 PerfAdvisor · 自适应采样

**要解决什么**：采样参数写死，于是快的时候保守、慢的时候让用户干等。

按实测解速分四档：慢（<4 tok/s）降温度、压输出上限；快（>12）放宽输出与多样性。
**全程兜底合法性**：`top-p` 为 0 会让采样器除零、`top-k` 为 0 直接崩，
温度 NaN 会污染整条 logit 链。这些都不弹错误，只表现为「模型变笨了」。

用户手动设过温度/输出上限时，`adaptive` 语义上让位给用户值（`advise()` 里 `userTemp >= 0` 分支）。

## 28 LoopBreaker · 内容级熔断

**要解决什么**：`LlamaEngine.isStuck()` 判的是「几秒内一个 token 没新增」，
对「同一句话改几个字再说一遍」无能为力 —— 而后者恰恰是小上下文里最常见的坏输出。

窗口 1200 字里，一段 ≥16 字的片段出现 ≥3 次就判定复读。
阈值取 16 且要 3 次：代码块、表格、列表天然高频重复，阈值低一点就会把正常回答切断 ——
**宁可漏判，不要误杀**。

## 29 Calc · 确定性算术

**要解决什么**：本地小模型三位数以上的乘除必错，而且错得看不出来 ——
它给出的格式完全正常，只是数字不对。

递归下降求值，支持 `+ - * / % ^ ()`、一元负号、`pi/e`、`sqrt()`，右结合幂（`2^3^2 = 512`）。
`%` 的处理是个细节：`10%3` 是取模，`15%` 是百分之十五。只在 `%` 后面紧跟数字或 `(` 时才当取模，
否则当中缀百分号 —— 否则「涨了 15%」会被算成「涨了 15 除以 1」。
