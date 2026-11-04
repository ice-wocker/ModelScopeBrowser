# 更新日志

> 🌐 语言 / Language: 中文 · [English](CHANGELOG.en.md)

完整历史。按时间倒序。

## 合并记录 — 把 GitHub 线与 CNB 线收成一棵

两条线在 `602fdab`（v2.7.0）之后各自往前走了一段，谁也不包含谁：

- **GitHub 线**（15 个提交）：`f83e9fe` 工具 8 → 57（`ToolRegistry` 作为唯一真源）、`f41c4b6` 原生工具调用与跨槽位 KV、
  `feat(site)` 站点自动更新、`profile/README` 等；
- **CNB 线**（7 个提交）：`e898b71`/`1c65f7b` 步数预算、编辑补丁、检索索引、自适应采样，`0e7dd61` 真实构建下抓到的两个硬伤，
  `516ad64` 「答非所问」与「回空答案」。

合并规则：**GitHub 线的实现为准，CNB 线独有的判断移植上去**。逐条：

| 事项 | 取哪边 | 理由 |
|---|---|---|
| 工具表 | GitHub 线（57 个 / `ToolRegistry`） | 它的说明、白名单、风险判定已经三处同源；CNB 线的 21 个工具是同一批能力的子集 |
| 上下文重建 | GitHub 线（每轮从历史重建） | CNB 线那版靠「前缀是逐步追加」的假设，在重建模型下不成立 |
| 跨槽位 KV | GitHub 线 | 一轮里出现两条 assistant 消息时换槽位，原生工具调用才连得起来 |
| 流式重绘节流 | CNB 线（80ms + 缓冲） | GitHub 线 40ms 还没做完限流 |
| 熔断自打断 | CNB 线 | 往单线程 `worker` 里排 `nativeCancel` 等于排在这场生成自己后面，必然空回答 |
| 重复惩罚 | GitHub 线的默认值（1.1）+ CNB 线的参数校验 | `Validators` 会把窗口与系数收敛到合法区间 |
| 工具结果回灌 | GitHub 线的 `responseBlock` 包装 + CNB 线的单条截断 | 协议包装决定模型认不认，截断决定上下文撑不撑 |
| 工具轮上下文上限 | 两边都留 | GitHub 线的 24000 字总量上限 + CNB 线的单条 6000 字 |

**验证**（JDK 17 + Android SDK 36 + build-tools 36 + NDK 27.2.12479018，真构建、无桩）：

- `testDebugUnitTest`：**282 个全绿**；
- `lintDebug`：**0 error**（120 warning，均为既有基线）；
- `assembleRelease`：通过，含 NDK 交叉编译与 R8。产物 `versionCode 17` / `versionName alpha 1.0`，
  `native-code: arm64-v8a armeabi-v7a`，arm64 七档 CPU 后端 `.so` 齐全（共 12 个 `.so`）。

顺带删掉了随 CNB 线一起进来的四个单测（`AgentToolsTest` / `AgentToolsExecTest` / `ToolSchemasTest`
以及一批针对已下线纯逻辑件的用例）：它们钉的是 21 工具那套契约，留着只会和 `ToolRegistry` 打架。

**仍然没有真机回归。** 这次合并最该在真机上验的两件事：连聊五六轮看有没有答非所问；
用带 function-call 模板的模型（Qwen2.5 / DeepSeek-V3.1 蒸馏版）连续调三次工具，看会不会重复调用。

## alpha 1.0 — 从「能调工具」到「能把一件事做完」

上一版把工具调用链路修通了。这一版回答一个更实际的问题：**通了之后，它还缺什么。**

缺的不是更多按钮，是四件具体的、每一件都会让多步任务半途而废的事。

### 一、写死的 8 步，是这一版最大的问题

`MAX_ROUNDS = 8` 不是安全边界，只是当初随手写的数字。而「读文件 → 改一处 → 再读回来核对」
本身就要 3 步；带一点摸索的任务必然撞上限，用户看到的是「已达到单轮工具调用上限」，
事情只做了一半，还得自己接着说「继续」。

现在换成三条线，任何一条到顶都体面收尾、并说明是哪一条：

| 档位 | 步数 | 墙钟 |
|---|---|---|
| 快速 | 32 | 3 分钟 |
| 标准 | 64 | 8 分钟 |
| 深度 | 128 | 20 分钟 |
| 极限 | 200 | 45 分钟 |

第三条线是**上下文将满**（≥95%）：再喂一轮只会把最早的内容挤掉，模型看到一段残缺历史。

另加一条**空转判定**：连续 3 轮全是查询类调用、一次改动都没有，就收尾并说明「它只是在查，没往前走」。
这比「重复调用」更隐蔽 —— 每次都合法、都不重复，却永远不写。

### 二、上下文一裁，模型就忘了自己做过什么

工具结果被裁掉之后，模型会反复重读同一个文件、或把改好的又改回去。

- **工作日志**（`journal_read`）：每次工具调用压成一行，append-only，不占常驻上下文，要用时再取。
- **计划清单**（`todo_write`）：模型自己写，系统每轮把「1/3 完成 + 未完成项」回灌进提示词。
  这比提示词里写「请先制定计划」有效 —— 后者是建议，前者是事实。
- 两者的摘要在系统提示词里各占两三行，成本极低。

### 三、改大文件只能整篇重写，截断就把文件毁一半

`write_file` 是覆盖写。模型改 20KB 文件里的一行，必须把另外 19KB 一字不差重写一遍；
写不完就截断，截断的写入把原文件毁了。

- `edit_file`：片段替换，`old` 必须唯一命中。找不到、或多处命中，都直接报错并给出候选行号，**绝不猜**。
- `apply_patch`：批量统一 diff。定位块必须逐字匹配，多处匹配就拒绝，全部片段先在内存算成功才落盘 ——
  中途失败不产生半成品。

### 四、模型的算术与「我手机怎么样」都是编的

- `calc`：确定性求值（`+ - * / % ^ ()`、`pi/e`、`sqrt`，支持 `15%` 这种后缀百分号）。三位数以上的乘除，小模型必错，而且错得看不出来。
- `world_info`：机型、ABI、内存、存储、电量、已加载模型、上下文用量、实测速度。它看得见，才不会瞎猜。
- `now` / `file_info`：时间和文件元信息，同样是为了不让它猜。

### 另外几件

- **`kb_index` / `kb_search`**：工作区检索索引（BM25 + 中文二元组切分）。一个 40KB 日志读进来就是上万 token，
  而模型只想知道哪一行报了错。索引把成本从「全文」降到「几条片段」。
- **长期记忆**（`remember` / `recall`）：结构化的 `id | ts | fact`，按相似度覆盖而不是叠加，
  写入前过一遍密钥脱敏（GitHub / OpenAI / AWS / Slack / JWT / 私钥块 / `password=xx` / `URL 里的 user:pass`）。
- **自适应采样**：按实测解速选参数。慢机降温度、压输出上限；快机放宽。全程兜底 `top-p ∈ (0,1]`、`top-k ≥ 1` ——
  这两个值越界会让采样器静默退化甚至崩。
- **内容级复读熔断**：原来只看「几秒没有新字节」，抓不到「换着花样重复同一段」。现在窗口内 ≥16 字片段重复 3 次就打断。
- **测速基准**（`bench` / 菜单「测速基准」）：短输入 + 长输入两个固定用例，给一句人话结论（「很流畅」/「偏慢，换更小的量化」）。
- **工具定义收敛成一份 schema**（`ToolSchemas`）：提示词、解析器白名单、以及将来发给外部模型的 `tools` JSON 数组共用一份。以前是三处各写一遍，漂移一次就是「提示词说能调、实际调不通」。
- 版本号改为 **alpha 1.0**（versionCode 17）。alpha 的意思是接口还会动，不用 2.x 假装成熟。

### 验证到哪一步（已从「桩编译」升级为真构建）

首次提交时只做了 JDK 17 + 手写桩的编译验证，明确标注「native / Lint 未验证」。补齐真环境后，那两项直接炸了：

- **native 编译不过**：`llama_sampler_init_penalties` 在 llama.cpp b11205 起多了一个 `n_vocab` 首参，
  `llama_bridge.cpp` 仍按 4 个参数调用，arm64-v8a 与 armeabi-v7a 都在 `[10/184]` 处 `error: no matching function`。
  这不是警告，是整个包出不来。
- **Android 7.0/7.1 上记忆会崩**：`Memory.Store` 用了 `File#toPath` 与 `java.nio.file.Files`，
  这两个 API 要 26，而 `minSdk` 是 24。API 24/25 上不是「功能缺失」而是 `NoClassDefFoundError` ——
  一碰长期记忆就闪退。Lint `NewApi` 报了 4 个 error。

两处都已修。修完的实测数据（JDK 17 + Android SDK 36 + NDK 27.2.12479018，非桩）：

- `testDebugUnitTest`：**207 个全绿**（204 + 1 条记忆落盘回归 + 2 条工具清单一致性）。
- `lintDebug`：**0 errors**（101 warnings 为存量，未新增基线）。
- `assembleDebug` / `assembleRelease`：**真出包**。`aapt2` 确认 `versionCode='17' versionName='alpha 1.0'`、
  `native-code: arm64-v8a armeabi-v7a`；arm64 侧 8 个多档后端 `.so` 齐全（armv8.0 / 8.2 / 8.6 / 9.0 / 9.2）。
- 双语资源 242 : 242 逐条对齐、占位符一致，这条是单测。

**仍然没验证的只有一项：真机运行。** 装完选「深度」档跑多文件改动，看会不会提前收尾或漏步。

顺带一条流程结论：**桩编译证明不了能出包**。桩能让 `src/main/java` 通过 javac，但碰不到 `cpp/`，
也碰不到 `minSdk` 与 API 等级的边界——恰恰是这两处同时出了事。

### 一个还在的问题

`ChatActivity` 又长了（约 2100 行）。这一版往里塞了档位、待办、日志、工具清单、记忆、基准六块 UI。
它该拆了 —— 但拆 UI 和加能力放在同一个 PR 里，diff 会大到没法审。下一步单独做。

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
- `ARCHITECTURE` 补上「同一 role 不能在请求里连续出现两次」这条不变量，`MODULES` 新增 ToolGuard 一节。

## v2.8.0（GitHub 线）— 让本地模型的「原生工具调用」真正能跑通

这一版没有加新工具，也没有加新按钮。它修的是**三处让工具调用在真机上根本不成立**的问题，
以及两处会悄悄把文件改坏 / 把结果漏掉的问题。三条都出在「我以为它是对的」的地方。

### 1. 跨槽位 KV：同一轮里出现两条 assistant 时，模型看不见「轮次已重新开始」

工具调用天生会产生**连续两条 assistant 消息**（第一条「我要调 tool」，工具结果回灌后第二条续写），
中间夹一条 role=tool。这种消息序列过完对话模板后不一定以 assistant 起始标记结尾：

- **MiniCPM5**：模板在 BOS 上已写过 `<|im_start|>`，追加时又写一遍，结束符落在开头，追加态是空串；
- **Gemma**：模板不写 `<end_of_turn>`，追加态是 `<start_of_turn>model\n`；
- **DeepSeek-V3.1 / GLM-4.5** 产出的是 `<|Assistant|>` 这类专用标记。

旧实现把下一段 token 直接续在「上一段 assistant 输出」后面，于是要么得到 `assistant\n assistant`，
要么让模型把工具调用当成自己上一句的续写 —— 表现就是**工具调用发不出去、或者一直重复同一次调用**。

修法：给每条新流分配一个独立的 **KV 序列槽（llama_seq_id）**。当上一轮的末尾 token 不在新 prompt 的
前缀里时（= 需要真正续写新一轮），换到另一个空槽位，pos 从 0 重新开始，两条序列在 KV 里互不覆盖。
旧槽位的数据保留，下次切回去仍能命中前缀。

### 2. 原生工具调用：`<|DSML|tool_calls>` / `[TOOL_CALLS]` / `<tool_call>` 以前一个都不认

项目一直只认提示词协议（模型照着我们写的 `<function name="x">` 抄一遍）。但带 function-call 模板的
GGUF 模型**本来就会用训练时的格式**，让它去模仿一个人造格式，小模型的失败率高得多。
新增 `agent/ServerToolCall`，按「强特征优先」的顺序认四种原生协议：

| 协议 | 谁在用 | 形状 |
|---|---|---|
| DSML | DeepSeek-V3.1 / GLM-4.5 | `<|DSML|invoke name="x"><|DSML|parameter name="k" string="true">v` |
| JSON | Hermes / Qwen2.5 / Llama-3.x | `<tool_call>{"name":…,"arguments":…}</tool_call>` |
| Mistral | Mistral 系列 | `[TOOL_CALLS][{…}]` |
| 裸 JSON | 少数模型 | `{"name":…,"arguments":…}` |

DSML 那些标签结尾是**单个 `>`**，不是漏写 —— 官方模板就这样，照原样实现。
同时 native 层补上「模板的助手起始标记」：只在第一步生成时写一次（`<start_of_turn>model`、
`<|Assistant|>` 这类），让模型看到 `assistant\n` 而不会自己再写一遍。

> 顺便修掉一个只有写测试才会发现的 bug：`[TOOL_CALLS]` 自己的 `]` 会把括号计数配平，
> 于是「取一个平衡的 JSON 数组」取到的是 `[TOOL_CALLS]` 本身，真调用参数一个都读不到。

### 3. 上下文只增不减：工具结果现在只在本轮可见

以前 `runAgent` 把「模型输出 + 工具结果」一路往 ctx 里累加，一轮 8 步下来，
两三个 `read_file` 就能把 8k 上下文吃干净，然后原生层从头截断 prompt ——公共前缀全废，反而更慢。

现在每一轮的上下文都从**历史**重建，工具结果天然是「当轮可见、下一轮不可见」。两条收益：

- 工具输出不再堆积，也不会诱导模型反复调同一个工具；
- 「剔除工具结果」正好等于「把 KV 回滚到工具调用之前」，于是 native 层可以按
  **可回放（replay）** 处理：只删公共前缀之后的部分，不换槽位、不整段重填。
  `LlamaEngine` 用一个脏标记把这件事串起来，`invalidateKv()` 在编辑重发 / 删除消息 / 续写 / 切会话时作废。

另外加了单轮工具结果的**总量上限**（24000 字）。超了就把最早的结果换成一句占位说明，
模型仍知道「那次调用发生过、结果已丢弃」，而不是以为自己没调过。

### 4. 会悄悄改坏文件的地方：`apply_patch` 的模糊匹配

旧的 `locate()` 有三条分支会**用错下标切原文**：

- CRLF→LF 归一会改变长度，却拿归一后的下标去切原文；
- 引号归一时只校验了 `find` 的长度，没校验 `text` 的；
- 原样片段出现多处时直接 `return 第一处`，后面所有唯一性检查成了摆设。

模型给的片段在文件里出现两次是常态（模板代码、重复的 `<div>`），
改动落在用户没预期的位置，而界面上看不出来。现在：任何模糊策略都只接受**唯一命中**，
判不出来就让模型重试；CRLF 走逐行定位，命中后用**原文里的真实区间**。

### 5. 其余修正

- **`Http.download` 先建目录再写临时文件**：写到 `<工作区>/下载/a.bin` 时 parent 可能不存在，
  旧代码顺序反了，直接 `FileNotFoundException`，用户只看到「下载失败」。
- **`Http.download` 手动跟随重定向并逐跳复查**：自动跟随不做地址校验，
  一个公网地址 302 到内网就能把手机当跳板。
- **断点续传校验 `Content-Range`**：远端换了同名文件时，206 的区间与我们本地断点不衔接，
  拼出来的 GGUF「长度校验能过、加载就崩」。现在起点对不上就整体重下（只重试一次，不会死循环）。
- **重复惩罚（默认 1.1）**：3B 级模型在纯 CPU 长输出里容易陷入「一句话反复重写」，
  温度与 top-k/top-p 都压不住。参数里可关（设 1.0）。

### 测试与产物

- 单测 157 → **189 个**，全绿。新增 `ServerToolCallTest`（16 例，字符串逐字抄自各家模板文档）、
  `PatchKitEdgeTest`（12 例，专盯「改错地方」与「宁可不改」）、`ContentRangeTest`（4 例）。
- `lintDebug` 0 error；`assembleRelease` 通过（R8 混淆 + 12 个 `.so`，arm64 七档后端齐全）。
- `versionCode 15 → 16`，`versionName 2.7.0 → 2.8.0`。

### 老实说

以上全部是编译期 + 189 个单测 + 静态推导，**没有真机回归**。最该在真机上验的是第 1 条：
装个带 function-call 模板的模型（Qwen2.5 / DeepSeek-V3.1 蒸馏版），让它连续调三次工具看看
会不会重复调用或答非所问。

---

---

## v2.7.1 — 五条分支收口，版本号只有一处真源

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

## v2.7.0 — 57 个工具，工具说明 / 白名单 / 风险判定合成一张表

### 智能体（本次主体）

之前只有 8 个工具，而且**工具说明、工具名白名单、危险动作判定是三份手写清单**：
加一个工具要改三个地方，漏一处就会出现「能调用但模型不知道它存在」，或者「危险动作没经过确认」。
这次先把这件事修掉，再往上加量。

- **`agent/ToolRegistry` 成为唯一真源**：每个工具声明名字、分组、参数、是否危险、中英各一句说明。
  `AgentTools.describe` / `ToolCall` 白名单 / `AgentTools.risky` 全部从它派生。
- **工具从 8 个扩到 57 个**，按组开关（文件 / 文本 / 计算 / 设备 / 联网 / 终端 / 智能体 / 外部模型）。
  关掉的组模型连说明都看不到，不是「看得到但调不动」。
- **`apply_patch`**：模型只给「要找的原文」和「替换成什么」。小模型重写整个文件必丢内容
  （300 行 HTML 改个按钮回来只剩 120 行），片段替换把破坏面压到最小；找不到原文时
  **文件一个字都不改**，并把最像的一段回给它自己纠错。
- **`grep` / `find_files` / `file_tree` / `file_stat`**：找东西不必把整个文件读进上下文。
- **`todo_*`**：计划外化成 `/todo.json`，长任务不再「跑到第四步忘了第一步」。
- **`memory_*`**：长期偏好落 `/memory.txt`，每轮注入，条数与长度沿用 `PromptTemplate` 的裁剪。
- **`skill_*`**：技能库 7 条内置套路（长文摘要 / 代码评审 / 故障定位 / 单文件网页 / 翻译校对 / 小步重构 / 安全命令），
  BM25 检索后**只把命中的一条正文**塞进上下文 —— 3B 模型也能用上几十条技能而不被撑爆。
- **`llm_ask`**：可选 OpenAI 兼容通道。端侧 3B 负责做决定，难的子问题外包给更大的模型；
  Key 只存本机，不进对话历史，也不回显在工具结果里。
- **`redact`**：把密钥 / Token / 私钥 / URL 里的密码抹掉再回灌，避免它们跟着导出和下一轮 prompt 走。

### 新增纯逻辑件（零 Android 依赖，可 JVM 单测）

`DigestKit`（流式摘要，4 GB 文件不吃内存）· `TimeKit`（日期换算集中一处）·
`JsonAccess`（`org.json` 容错读取，支持 `a.b[0].c`）· `SseParser`（SSE 增量解析，扛住跨块切断）·
`Bm25`（中文二元切词 + 检索排序）· `DiffKit`（LCS 差分 + unified 渲染）·
`DocConverter`（CSV ↔ Markdown 表格、宽字符对齐、Mermaid 折线图）。

### 安全边界

- **`PathGuard`**：`shell` 执行前两道闸。硬拒绝 `rm -rf /`、`dd of=/dev/block/…`、fork 炸弹、
  `mkfs`、`su`、`pm uninstall`、递归 chmod/chown；警告递归删除、通配符、重定向到根目录、
  「下载直接管道进 shell」。另外拒绝命令里出现的**工作区外绝对路径**。
- **HTTP 重定向手动跟随**：每一跳都重新过地址白名单。默认的自动跟随会绕过第一跳的检查。
- **下载 / 解压有上限**：`download_file` 512 MB、zip 条目 2000 个 / 256 MB，逐条校验 canonical 路径挡 zip slip。

### 界面

- 状态条新增「能力」入口：按组开关工具，一眼看到开了几个。
- 新增斜杠命令：`/caps` `/ext` `/memo` `/todo` `/skill`。
- 外部模型配置在应用内完成（基地址 / Key / 模型名），不写死在代码里。

### 验证

```
testDebugUnitTest  →  157 个全绿（99 → 157，新增 ToolKitTest 26 + AgentKitTest 32）
lintDebug          →  0 error（新增代码引入 6 个 NewApi，全部改掉：
                       java.util.Base64 与 Files.readAllBytes 在 minSdk 24 上不可用）
assembleDebug      →  通过，含 NDK 交叉编译 llama.cpp 与 JNI 链接
values / values-zh →  各 247 条 key，逐条对齐
```

这次新增的用例里有几条是**踩了自己写的 bug 才补上的**，留在这里当记录：
引号归一用了长度不变的逐字符替换（否则按归一后的下标切原文会错位）；
`SecureRedactor` 的宽松规则和严格规则混在一个列表里，导致 `includePair=false` 时形同虚设；
日历天加减必须走 `Calendar`，纯毫秒加减会被时区推偏一天；
断言中文文案的用例必须显式钉住语言，否则在英文机器上是假失败。

### 没做（不假装做完）

- **仍然没有真机回归**。全部是编译期 + 单测 + 静态推导。`apply_patch`、`notify`、`clipboard`、`share`
  这几个只在真机上有意义的功能，建议装上后逐个点一遍。
- **`llm_ask` 没有对着真实服务商跑过**。请求体与响应解析有单测（含 SSE 兜底），
  但「某家服务商的鉴权头怎么写」这种事只能实测。
- **没有 GPU 后端**（Vulkan / OpenCL），纯 CPU 推理仍是天花板。
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
