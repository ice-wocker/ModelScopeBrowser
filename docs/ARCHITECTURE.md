# 架构与数据流

本文把「一次点击到一次回答」的完整路径拆开。想看行为说明去 `README.md`，
想看正在踩的坑去 [`TROUBLESHOOTING.md`](TROUBLESHOOTING.md)。

## 0. 全景

![架构](assets/arch.png)

## 1. 四个层次，职责边界

| 层 | 包 | 依赖 | 能做什么 | 不能做什么 |
|---|---|---|---|---|
| 界面 | `com.mscope.browser*Activity` | Android 框架 + 领域层 | 布局、交互、生命周期 | 不做 IO、不做解析 |
| 领域 | `agent` `llama` `local` `core` | 仅 JDK + 少量 Android API | 业务规则、协议、持久化 | 不 new Activity |
| 原生 | `src/main/cpp` | llama.cpp / NDK | 推理、分词、采样 | 不感知 UI 状态 |
| 外部 | 魔搭接口 / 搜索引擎 | HTTP | 数据 | 不信任 |

`core/` 是唯一的「零 Android 依赖」层，因此它的测试跑在 JVM 上，几十毫秒出结果。
一个规则能不能下沉到 `core/`，判断标准很简单：**它需要 Context 吗？** 需要就留在上层。

## 2. 依赖方向

```text
Activity ──► agent / llama / local ──► core
    │                │
    │                └──► llama.cpp (JNI)
    └──► ModelApi ──► HTTP
```

不允许反向：`core` 不知道 `agent` 存在，`agent` 不知道哪个 Activity 在调用它。
唯一的回调通道是接口：`AgentTools.Host`、`DownloadCenter.Listener`、`LlamaEngine.StreamListener`。

## 3. 对话链路（详细）

### 3.1 进入页面

```text
ChatActivity.onCreate()
  ├─ MemoryBudget.read()            读 /proc/meminfo
  ├─ MemoryBudget.suggestCtx()      没有历史设置时，用设备内存算默认上下文
  ├─ Markwon 构建（表格 / 删除线 / 代码字体）
  ├─ Workspace / Shell / AgentTools 装配
  ├─ ChatStore.latest(model)        打开该模型最近一次会话
  └─ loadModel()
        ├─ 同模型已在内存 → onReady()
        ├─ 别的模型在内存 → 弹窗确认后重建
        └─ engine.load(model, nCtx, nativeLibraryDir, cb)
```

`engine.load` 内部：

```text
worker 线程
  ├─ nativeInit(path, nCtx, nThreads, nThreadsBatch, backendDir)
  │     ├─ llama_model_load_from_file
  │     ├─ 读模型自带 chat template
  │     ├─ 建 context：n_ctx / type_k=q8_0 / type_v=q8_0
  │     ├─ 尝试 flash_attn → 失败则关闭重试
  │     ├─ 检查 ggml_backend 注册数（0 说明 .so 没释放）
  │     └─ 返回 handle
  ├─ 成功 → 记下 backend 描述（CPU 型号）
  └─ 失败 → nativeLastError() 带回具体原因
```

### 3.2 发一条消息

```text
onSendClicked()
  ├─ 斜杠命令？→ 走命令通道，不发模型
  ├─ 智能体模式？→ agentLoop()
  └─ 普通对话 → generateBlocking()

agentLoop()
  for step in 1..8:
    gen = generateBlocking(ctx)              ← 阻塞等这一轮生成完
    若已停止 → break
    calls = ToolCall.parse(gen.text)
    若无调用 → 这就是最终回答，break
    对每个调用：
      命中已注册工具？ 否 → 忽略（防止普通 JSON 被误执行）
      需要确认？ → 弹窗（允许 / 拒绝 / 本会话都允许）
      执行 → 得到文本结果
      结果以 <tool_response> 追加进 ctx
```

### 3.3 生成一轮

```text
LlamaEngine.generate(msgs, params, listener)
  ├─ handle == 0 → onError("尚未加载模型")
  ├─ synchronized: generating 已是 true → onError("正在生成中")
  └─ worker.execute:
        ├─ fitMessages(h, msgs, maxTokens)   ← 消息级裁剪
        ├─ LlamaBridge.nativeGenerate(...)  ← 阻塞，逐 token 回调
        │     回调里：累积 + 40ms 合并 + ui.post
        ├─ 统计 tokens / prefillMs / tokensPerSec
        └─ ui.post(onDone(...))
```

## 4. 原生层接口

| JNI 方法 | 作用 | 备注 |
|---|---|---|
| `nativeInit(modelPath, nCtx, nThreads, nBatchThreads, backendDir)` | 加载模型 + 建 context | 返回 handle，0 表示失败 |
| `nativeFree(handle)` | 释放 | 引用计数，最后一个持有者销毁 |
| `nativeGenerate(handle, roles, contents, maxTokens, temp, topP, topK, seed, cb)` | 生成 | 阻塞直到结束或被取消 |
| `nativeCancel(handle)` | 取消 | 置标志位，采样循环每个 token 检查 |
| `nativeReset(handle)` | 清空 KV | 新会话用 |
| `nativeBuildPrompt(handle, roles, contents)` | 套模板 | 供 token 估算复用 |
| `nativeCountTokens(handle, text)` | 计数 | |
| `nativeContextSize(handle)` | 实际上下文 | 可能因内存被自动调小 |
| `nativeVersion()` | llama.cpp 版本串 | |
| `nativeLastError()` | 最近一次失败原因 | 界面直接展示 |

JNI 侧的生命周期：**存活会话表 + 引用计数 + 统一加锁**。
`nativeFree` 只把会话从表里摘掉并放掉自己那份引用，生成线程握着的那份继续有效，
最后一个持有者负责 `delete`。这样既没有 use-after-free，也不会在别人还在采样时把 ctx 删掉。

## 5. 数据流：下载

```text
DetailActivity 点下载
  └─ DownloadCenter.start(model, url)
        ├─ putIfAbsent 登记任务表（先登记再投递，避免「僵尸任务」）
        └─ pool.execute(run)
              ├─ 目标路径：filesDir/models/<name>，临时文件 <name>.part
              ├─ 有 .part → 带 Range 头续传
              ├─ 空间体检：StorageGuard.check(usable, need, done)
              ├─ 读流写盘，每 400ms 更新通知
              ├─ 完成：校验长度 → FsKit.sniff 校验 GGUF 魔数
              ├─ 原子改名 .part → .gguf
              ├─ LocalModelStore.add（index.json 原子写）
              └─ 清 downloads.json 里的记录
```

任务落盘（`downloads.json`）是为了「进程被杀后自动接着下」：
下次启动时 `DownloadCenter` 读这张表，把未完成的任务重新排进队列。

## 6. 数据流：工作区与工具

```text
模型输出 <function name="write_file">…
  └─ ToolCall.parse → AgentTools.exec
        ├─ Workspace.resolve(path)      ← FsKit.resolveInside 收敛
        ├─ 确认弹窗（危险操作）
        ├─ 原子写
        └─ 返回给模型看的文本结果
```

工作区里点 `.html` → `HtmlPreviewActivity`：WebView 关闭 `allowUniversalAccessFromFileURLs`，
保留 `allowFileAccessFromFileURLs`（相对资源仍可加载），并支持切桌面 UA / 转系统浏览器。

## 7. 生命周期与并发

| 场景 | 处理 |
|---|---|
| 生成中退出对话页 | `alive=false`，回调里先判存活再动 UI；native 侧取消并等待 |
| 生成中切模型 | `engine.cancel()` → 等 worker 空出 → `load` 新模型 |
| 下载中退出应用 | 前台服务持有通知；任务落盘，重启后续传 |
| 旋转屏幕 | 不重建（`configChanges`），避免生成被打断 |
| 低内存 | 建议上下文按 meminfo 收敛；KV 量化减半 |

## 8. 扩展点

想加东西时该动哪里：

| 想加 | 改这些 |
|---|---|
| 新工具 | `AgentTools.names()` / `exec()` / `describe()`，然后 `ToolCall` 不用改 |
| 新数据源 | `ModelApi`，保持 `Page` 结构不变 |
| 新推理后端 | `CMakeLists.txt` + `llama_bridge.cpp` |
| 新预设提示词 | `PromptTemplate.Preset` + `body()` |
| 新校验规则 | `Validators` / `core` 下新建类，并补单测 |

新增规则的验收标准只有一条：**能在 JVM 单测里复现它要挡的场景**。
