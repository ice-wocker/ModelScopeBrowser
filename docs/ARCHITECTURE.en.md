# Architecture and Data Flow

> 🌐 Language / 语言: English · [中文](ARCHITECTURE.md)

This document takes the whole path from "one tap" to "one answer" apart. For behaviour
descriptions see `README.md`; for the traps we are currently stepping on see
[`TROUBLESHOOTING.md`](TROUBLESHOOTING.md).

## 0. The whole picture

![Architecture](assets/arch.png)

## 1. Four layers, clear boundaries

| Layer | Packages | Depends on | Allowed to | Not allowed to |
|---|---|---|---|---|
| UI | `com.mscope.browser*Activity` | Android framework + domain layer | layout, interaction, lifecycle | do IO, do parsing |
| Domain | `agent` `llama` `local` `core` | JDK only + a little Android API | business rules, protocols, persistence | construct Activities |
| Native | `src/main/cpp` | llama.cpp / NDK | inference, tokenisation, sampling | be aware of UI state |
| External | ModelScope API / search engines | HTTP | provide data | be trusted |

`core/` is the only layer with zero Android dependencies, so its tests run on the JVM and
finish in tens of milliseconds. Whether a rule can sink into `core/` has a one-line test:
**does it need a Context?** If it does, it stays upstairs.

## 2. Dependency direction

```text
Activity ──► agent / llama / local ──► core
    │                │
    │                └──► llama.cpp (JNI)
    └──► ModelApi ──► HTTP
```

The reverse is not allowed: `core` does not know `agent` exists, and `agent` does not know
which Activity is calling it. The only callback channel is interfaces:
`AgentTools.Host`, `DownloadCenter.Listener`, `LlamaEngine.StreamListener`.

## 3. The conversation path (in detail)

### 3.1 Entering the screen

```text
ChatActivity.onCreate()
  ├─ MemoryBudget.read()            reads /proc/meminfo
  ├─ MemoryBudget.suggestCtx()      if never configured, derive the default context from device memory
  ├─ build Markwon (tables / strikethrough / monospace)
  ├─ wire up Workspace / Shell / AgentTools
  ├─ ChatStore.latest(model)        open the most recent session for that model
  └─ loadModel()
        ├─ same model already in memory → onReady()
        ├─ another model in memory → confirm, then rebuild
        └─ engine.load(model, nCtx, nativeLibraryDir, cb)
```

Inside `engine.load`:

```text
worker thread
  ├─ nativeInit(path, nCtx, nThreads, nThreadsBatch, backendDir)
  │     ├─ llama_model_load_from_file
  │     ├─ read the chat template shipped with the model
  │     ├─ create context: n_ctx / type_k=q8_0 / type_v=q8_0
  │     ├─ try flash_attn → on failure, disable and retry
  │     ├─ check how many ggml backends registered (0 means the .so files were never extracted)
  │     └─ return handle
  ├─ success → remember the backend description (CPU model)
  └─ failure → nativeLastError() carries the real reason back
```

### 3.2 Sending one message

```text
onSendClicked()
  ├─ slash command? → command channel, never reaches the model
  ├─ agent mode? → agentLoop()
  └─ plain chat → generateBlocking()

agentLoop()
  for step in 1..8:
    gen = generateBlocking(ctx)              ← block until this round is generated
    if stopped → break
    calls = ToolCall.parse(gen.text)
    if no calls → this is the final answer, break
    for each call:
      matches a registered tool? no → ignore (so ordinary JSON is never executed by accident)
      needs confirmation? → dialog (allow / deny / always allow this session)
      execute → get a text result
      append the result to ctx as <tool_response>
```

### 3.3 Generating one round

```text
LlamaEngine.generate(msgs, params, listener)
  ├─ handle == 0 → onError("model not loaded yet")
  ├─ synchronized: generating already true → onError("already generating")
  └─ worker.execute:
        ├─ fitMessages(h, msgs, maxTokens)   ← message-level trimming
        ├─ LlamaBridge.nativeGenerate(...)  ← blocking, one callback per token
        │     inside the callback: accumulate + coalesce 40ms + ui.post
        ├─ gather tokens / prefillMs / tokensPerSec
        └─ ui.post(onDone(...))
```

## 4. Native interface

| JNI method | Purpose | Notes |
|---|---|---|
| `nativeInit(modelPath, nCtx, nThreads, nBatchThreads, backendDir)` | load model + build context | returns a handle, 0 means failure |
| `nativeFree(handle)` | release | reference counted; the last holder destroys |
| `nativeGenerate(handle, roles, contents, maxTokens, temp, topP, topK, seed, cb)` | generate | blocks until finished or cancelled |
| `nativeCancel(handle)` | cancel | sets a flag, checked once per token in the sampling loop |
| `nativeReset(handle)` | clear KV | used for new sessions |
| `nativeBuildPrompt(handle, roles, contents)` | apply template | reused for token estimation |
| `nativeCountTokens(handle, text)` | count tokens | |
| `nativeContextSize(handle)` | effective context | may have been shrunk automatically for memory |
| `nativeVersion()` | llama.cpp version string | |
| `nativeLastError()` | most recent failure reason | shown directly in the UI |

Lifecycle on the JNI side: **live-session table + reference counting + one lock discipline**.
`nativeFree` only removes the session from the table and drops its own reference; the
reference held by the generating thread stays valid, and whoever is last calls `delete`.
No use-after-free, and no deleting a ctx while somebody is still sampling on it.

## 5. Data flow: download

```text
User taps download in DetailActivity
  └─ DownloadCenter.start(model, url)
        ├─ putIfAbsent registers the task (register before dispatch, so no "zombie task")
        └─ pool.execute(run)
              ├─ target path: filesDir/models/<name>, temp file <name>.part
              ├─ a .part exists → resume with a Range header
              ├─ space check: StorageGuard.check(usable, need, done)
              ├─ read stream to disk, refresh the notification every 400ms
              ├─ done: verify length → FsKit.sniff checks the GGUF magic
              ├─ atomic rename .part → .gguf
              ├─ LocalModelStore.add (atomic write of index.json)
              └─ clear the record from downloads.json
```

Persisting the task (`downloads.json`) is what makes "resume after the process is killed"
work: on next launch `DownloadCenter` reads the table and re-queues unfinished work.

## 6. Data flow: workspace and tools

```text
model emits <function name="write_file">…
  └─ ToolCall.parse → AgentTools.exec
        ├─ Workspace.resolve(path)      ← FsKit.resolveInside converges the path
        ├─ confirmation dialog (destructive operations)
        ├─ atomic write
        └─ text result returned to the model
```

Tapping an `.html` file in the workspace opens `HtmlPreviewActivity`: the WebView turns
`allowUniversalAccessFromFileURLs` off but keeps `allowFileAccessFromFileURLs` (relative
assets still load), and can switch to a desktop UA or hand the file to the system browser.

## 7. Lifecycle and concurrency

| Scenario | Handling |
|---|---|
| Leaving the chat screen mid-generation | `alive=false`; callbacks check liveness before touching the UI; native side cancels and joins |
| Switching models mid-generation | `engine.cancel()` → wait for the worker to go idle → `load` the new model |
| Leaving the app mid-download | foreground service holds the notification; tasks are persisted and resume on restart |
| Screen rotation | no recreation (`configChanges`), so generation is not interrupted |
| Low memory | suggested context converges on meminfo; KV cache is quantised to halve its size |

## 8. Extension points

Where to touch things when you want to add something:

| Want to add | Change these |
|---|---|
| A new tool | `AgentTools.names()` / `exec()` / `describe()`; `ToolCall` needs no change |
| A new data source | `ModelApi`; keep the `Page` shape intact |
| A new inference backend | `CMakeLists.txt` + `llama_bridge.cpp` |
| A new prompt preset | `PromptTemplate.Preset` + `body()` |
| A new validation rule | a new class under `Validators` / `core`, plus unit tests |

The acceptance bar for a new rule is a single line: **it can reproduce the scenario it is
meant to block in a JVM unit test**.
