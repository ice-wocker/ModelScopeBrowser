# Module Deep Dive

> 🌐 Language / 语言: English · [中文](MODULES.md)

One section per module, covering three things: **what it solves**, **how it does it**,
**where it is easy to get wrong**. After this page you should be able to change any piece of
logic in this repo without reading all the source first.

Contents:
[1 ModelApi](#1-modelapi--api-and-degradation) · [2 DownloadCenter](#2-downloadcenter--downloads-and-resume) · [3 LocalModelStore](#3-localmodelstore--index-and-atomic-writes) · [4 Workspace](#4-workspace--path-convergence) · [5 Shell](#5-shell--a-real-shell) · [6 ToolCall](#6-toolcall--protocol-parsing) · [7 AgentTools](#7-agenttools--tool-execution) · [8 WebSearch](#8-websearch--web-search) · [9 LlamaEngine](#9-llamaengine--session-management) · [10 llama_bridge](#10-llama_bridgecpp--the-jni-bridge) · [11 ChatActivity](#11-chatactivity--the-chat-screen) · [12 MemoryBudget](#12-memorybudget--context-budget) · [13 StorageGuard](#13-storageguard--storage-health-check) · [14 FsKit](#14-fskit--file-operations) · [15 Validators](#15-validators--input-convergence) · [16 PromptTemplate](#16-prompttemplate--prompts) · [17 TextKit](#17-textkit--text-utilities) · [18 PerfStats](#18-perfstats--performance-stats) · [19 MarkdownOutline](#19-markdownoutline--outline) · [20 ToolGuard](#20-toolguard--anti-spin-gate)

## 1 ModelApi · API and degradation

**What it solves**: ModelScope's public API has no official documentation; guessing parameter
names earns you a 404, and the server reshapes responses across versions. So the data layer does
three things: request, degrade, parse leniently.

### 1.1 Request

```java
public static String http(String method, String url, String body) throws IOException
```

A minimal HTTP wrapper: set a UA, timeouts, read the stream, turn error codes into exceptions.
No OkHttp — the project has a handful of calls, and a 200 KB dependency is not worth it.

### 1.2 Degradation chain

```text
Attempt 1: full parameters (paging + sorting + filtering)
    ↓ fails (404 / parameter rejected / gateway blocks PUT)
Attempt 2: drop filtering
    ↓ fails
Attempt 3: drop sorting
    ↓ fails
Attempt 4: homepage aggregate GET /api/v1/dolphin/agg/homepage
    ↓ fails
throw → UI shows the error state + retry + web mode as a last resort
```

Each degradation leaves a marker on the returned `Page`:

| Marker | Meaning | UI behaviour |
|---|---|---|
| `sortApplied` | sorting really took effect | when false, toast "this sort order is unavailable right now" |
| `filterApplied` | filtering really took effect | when false, toast "these filters are unavailable right now" |
| `fallback` | data came from the homepage aggregate | footer says "currently showing fallback data" |

Those three markers are the main reason this module exists: **silent failure is worse than
failure**. A user who thinks they filtered but did not draws completely wrong conclusions.

### 1.3 Lenient parsing

Rather than hard-coding `Data.Model.Models`, it looks for "the field that most looks like an
array of models":

```java
// pseudocode: try the known path first, then descend looking for an array
JSONArray arr = pickModelArray(root);
int total = pickTotalCount(root);   // prefer TotalCount next to the array
```

Cost: a few extra milliseconds per parse. Benefit: an API revision does not force a release.
The server has moved fields twice; both times the UI merely "lost a number" instead of going blank.

### 1.4 Easy to get wrong

- **The filter parameter is `Criterion`** (an array), not `SingleCriterion`; the latter is
  silently ignored and the server returns everything.
- **List requests must be PUT.** `GET` and `POST` both return 404 — this once burned a full
  debugging round.
- **Do not increment the page number yourself.** The classic cause of skipping pages is "the page
  number changed externally after the request went out"; now `nextPage` is maintained internally
  and read-only outside.

## 2 DownloadCenter · Downloads and resume

**What it solves**: getting files from a few hundred MB up to several GB onto the phone reliably —
survive a dropped network, survive a killed process, and confirm afterwards that it really is a model.

### 2.1 Why single-threaded

Several large downloads at once fight over bandwidth, and every extra long-lived connection costs
battery. Serial plus a queue means users see "one after another", which is more predictable.

### 2.2 The task table and "zombie tasks"

This is the most insidious bug in the project:

```java
// wrong order: dispatch, then register
pool.execute(run);         // a single-threaded pool may finish run() immediately
tasks.put(key, task);      // tasks.remove(key) inside run() already happened
// result: the table keeps an entry that forever says "downloading" while nothing is moving
```

```java
// right order: register, then dispatch
if (tasks.putIfAbsent(key, task) != null) return;   // already downloading
pool.execute(run);
```

There is a symmetric trap: if `run()` starts with an "early return when cancelled" branch, it
bypasses `tasks.remove()`, the task sticks in the table, and that file can never be tapped again.
Now **cancellation goes through the main path**, checked once before the request is made, so
`finish()` always runs.

### 2.3 Resume

```text
a .part file exists → read bytes downloaded → add Range: bytes=N- to the request
  ├─ server returns 206 Partial Content → append, total = N + Content-Length
  └─ server returns 200 OK             → no resume support, rewrite from scratch, total = Content-Length
```

ModelScope 302-redirects some files to a CDN, and `Range` survives the redirect — measured, so
resume works more often than you would expect.

### 2.4 Verification

Two gates:

1. **Length**: `part.length() == total` (only checked when the server sent `Content-Length`).
2. **Magic**: the first 4 bytes must be `GGUF`. This rejects "auth failed and we got an HTML page"
   and "we got a JSON error response".

Checking the extension alone is useless — an HTML file named `.gguf` is perfectly legal on disk.

### 2.5 Task persistence

`downloads.json` records unfinished tasks. After a restart the app reads the table and re-queues
them. That table is what makes "keep downloading after the system kills the process" work.

### 2.6 Easy to get wrong

- **Do not build progress strings with `String.format` inside the callback**: once every 400 ms,
  the allocations add up to GC pressure.
- **`Formatter.formatFileSize` needs a Context**, so grab one before calling from a worker thread.
- **Cancellation must abort the connection actively**, otherwise you wait for the next read timeout
  (20 s by default) before anything reacts.

## 3 LocalModelStore · Index and atomic writes

**What it solves**: remembering "which models are downloaded". Sounds simple, but this is the
high-risk area for "all my local models disappeared" incidents.

### 3.1 Location

```text
filesDir/models/
  ├── index.json          # the index
  └── <name>.gguf         # model files
```

The app-private directory, gone on uninstall, no storage permission needed. The cost is that the
user cannot browse it with a file manager — the workspace screen offers browsing and sharing instead.

### 3.2 Atomic write

```java
File tmp = new File(dir, name + ".tmp");
try (FileOutputStream out = new FileOutputStream(tmp)) {
    out.write(data);
    out.flush();
    out.getFD().sync();      // the important bit: make sure the bytes really reached flash
}
target.delete();
tmp.renameTo(target);        // renaming within a directory is atomic
```

A half-written index is serious: parse fails → the local model list goes empty → the user thinks
the models were deleted → downloads everything again.

### 3.3 Deduplication

`add()` deduplicates by `localPath`, keeping the newest record. Older versions could leave two
records for the same file under concurrency; reading the index now drops duplicates along the way.

### 3.4 Health check

`StorageGuard.audit()` cross-checks (see section 13). The harm of such "ghost entries": tapping one
reports "file not found" while it still sits in the list, which looks like the app is broken.

## 4 Workspace · Path convergence

**What it solves**: the model will hand you arbitrary paths, including
`../../shared_prefs/auth.xml`. The workspace is the only place writes are allowed, so it must be
the only place paths are converged.

### 4.1 Path convergence

```java
String rootPath = root.getCanonicalPath();      // resolve symlinks and collapse ..
String target = candidate.getCanonicalPath();
if (target.equals(rootPath)) return root;
if (!target.startsWith(rootPath + File.separator)) return null;   // escaped
```

Canonical paths rather than string concatenation, to block two things:

| Attack | String prefix check | Canonical check |
|---|---|---|
| `../x` | misses it (does not start with root) | blocks |
| `a/../../x` | implementation-dependent | blocks |
| `link -> /data/data/.../shared_prefs` | misses it (prefix is root) | blocks (escapes after resolution) |
| `/data/data/pkg/a` assembled with `..` into an absolute path | implementation-dependent | blocks |

### 4.2 Atomic write

Same as `3.2`. The workspace has one extra requirement: **create directories automatically**,
because the model often writes paths like `page/index.html` with subdirectories.

### 4.3 Listing and stats

`list()` returns first-level entries, directories first, names ascending; `usage()` recursively
counts files and total bytes. Recursion does not follow symlinks, avoiding cycles.

## 5 Shell · A real shell

**What it solves**: let the model actually run commands on the phone without handing over the phone.

### 5.1 Implementation

```java
ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", command);
pb.directory(workspace.dir());        // working directory pinned to the workspace
pb.redirectErrorStream(true);
Process p = pb.start();
// read output + 60 s timeout + truncate beyond 200K characters
```

### 5.2 Boundaries

| Limit | Value | Why |
|---|---|---|
| Working directory | the workspace | so `ls` and `cat` mean something |
| Timeout | 60 s | so `sleep 9999` cannot wedge the whole agent loop |
| Output ceiling | 200K characters | `cat` one big file and the UI layout collapses |
| Permissions | the app sandbox | cannot write `/sdcard` or `/system`, and does not need root |

### 5.3 Why not a pseudo-terminal

The v2.2 "terminal" was a restricted command parser that only ran a whitelist. v2.4 moved to a
real shell: once the model can execute commands, pretending it cannot is self-deception. The right
move is **a confirmation dialog**, not **gutting the feature**.

## 6 ToolCall · Protocol parsing

**What it solves**: local GGUF models have no function-call template, and each one invents its own
output format.

### 6.1 Four formats

```text
① MiniCPM5  <function name="shell"><param name="command">ls</param></function>
② Qwen3-Coder <function=shell><parameter=command>ls</parameter></function>
③ JSON block ```tool {"name":"shell","arguments":{"command":"ls"}} ```
④ tag / line <tool_call>{...}</tool_call>   /   TOOL_CALL: {...}
```

### 6.2 Fault tolerance

| Scenario | Handling |
|---|---|
| missing closing tag | the regex asserts `(?=<function|\z)` up to the next call or end of text |
| a value contains `</param>` | supports `<![CDATA[...]]>` wrapping, unwrapped during parsing |
| several calls in one reply | `parse()` returns a list, executed in order of appearance |
| the model writes an ordinary JSON example | only names in the registered set count as calls |
| tags inside the thinking block | strip `<think>...</think>` first (including unclosed) |
| parameter names differ in case | each tool accepts several aliases (`command` / `cmd` / `script`) |

### 6.3 Why not one big regex

Because of **nesting**. A parameter value may contain `<param`, and JSON may contain `</function>`.
The current approach is layered: split into blocks by structural features, extract fields inside a
block, then parse JSON forms with `JSONObject`.

### 6.4 The safety valve

```java
// only a name in the registered tool set counts as a call
if (!AgentTools.names().contains(call.name)) continue;
```

Without this line, a `{"name": "shell", "arguments": {...}}` example casually written in a reply
would actually be executed.

## 7 AgentTools · Tool execution

**What it solves**: translate "what the model wants" into "what really happens on the phone", and
turn the result into text the model can read.

### 7.1 One contract

```java
public String exec(ToolCall c)   // always returns text, never throws
```

Even errors come back as text (`"error: missing parameter command"`) so the model can correct
itself. Throwing would break the agent loop and leave the user with a dead bubble.

### 7.2 Result truncation

```text
MAX_RESULT = 6000 characters
```

Tool results go into the context. An untruncated `cat` of a large file would fill the context
immediately, trigger trimming on the next turn, and waste the entire prefix cache.

### 7.3 Dangerous operations

| Tool | Needs confirmation | Why |
|---|---|---|
| `shell` | ✅ | it is the terminal |
| `write_file` | ✅ | overwrites existing files |
| `delete_file` | ✅ | irreversible |
| `read_file` / `list_files` | ❌ | read-only within the workspace |
| `web_search` / `fetch_url` | ❌ | read-only public web |
| `open_preview` | ❌ | just opens a page |

"Allow for this session" does not carry across sessions — authorisation expires on purpose.

### 7.4 Feeding results back

```text
<tool_response>
$ ls -la
total 12
-rw-r--r-- 1 u0_a123 812 index.html
</tool_response>
```

`user` role wrapped in `<tool_response>` — exactly what the MiniCPM5 / Qwen templates expect for
tool returns, so compatibility is best. Feeding back as `system` makes some models treat it as
another instruction and start looping.

## 8 WebSearch · Web search

**What it solves**: web access without an API key.

### 8.1 Sources

| Order | Source | Parsing |
|---|---|---|
| 1 | DuckDuckGo lite HTML | `class="result__a"` + `result__snippet` |
| 2 | Bing web results | `<h2><a href=...>` + `b_lineclamp` |

Both failing raises, and the caller reports "web search failed".

### 8.2 Fetching page bodies

`fetchPage()` strips tags to get the text, truncated at a limit. Two details:

- truncation respects whole UTF-8 characters (`TextKit.trimUtf8`), otherwise emoji become question marks;
- only http/https is allowed, and local / intranet addresses are rejected (`Validators.isPrivateHost`).

The second is mandatory: a local model is entirely capable of emitting `http://localhost:8080/...`
to probe services on the user's phone, and this path is **model-controlled**.

### 8.3 Limits

No API key means you are at the mercy of anti-scraping: change the result page structure and
parsing fails; go too fast and you get rate limited. That is a known trade-off, documented under
"known limitations".

## 9 LlamaEngine · Session management

**What it solves**: keep the model resident, generate serially, call back to the UI, stay cancellable.

### 9.1 Singleton + single thread

```java
private final ExecutorService worker = Executors.newSingleThreadExecutor();
```

One llama context cannot be used by two threads at once. A single-threaded pool guarantees "one
generation at a time", but that **is not enough**: two threads can queue up in turn, the second
waits a full round and then overwrites the handle state. So the check-and-set of `generating` must
be inside the lock:

```java
synchronized (this) {
    if (generating) { listener.onError("already generating"); return; }
    generating = true;
}
```

Note that generation itself does **not** hold that lock — it runs on the worker; the lock only
protects the state flags.

### 9.2 Atomicity of loading

If "check whether loaded + set the loading flag" is not atomic, double-tapping load makes each tap
tear down the session the other just built.

### 9.3 Trimming

See [`DIAGRAMS.md`](DIAGRAMS.md#4-context-trimming-strategy). Core principle: **drop whole
messages; never truncate by token in the native layer**.

### 9.4 Callback coalescing

```java
if (now - lastFlush[0] >= UI_FLUSH_MS) { lastFlush[0] = now; emit(buf, listener); }
```

40 ms per batch. Too small and it fights the UI for CPU (one JNI hop plus one layout per token);
too large and the "typing" feel dies. 40 ms is about 25 fps, which reads as continuous.

## 10 llama_bridge.cpp · The JNI bridge

**What it solves**: wrap llama.cpp's C API into something Java can use — thread-safe and cancellable.

### 10.1 Session table and reference counting

```text
g_sessions: map<handle, shared_ptr<Session>>
g_mu:       guards the table
s->mu:      guards one session's generation state
```

`acquire(handle)` takes a **copy** of the `shared_ptr`, so it stays valid after leaving the table;
`nativeFree` only removes it from the table and drops its own reference, and whichever holder is
last destroys the context.

The old shape — "hold g_mu to check liveness → release g_mu → grab s->mu" — had a window: another
thread could `nativeFree` the session in that gap, and you would lock an already-destroyed mutex.

### 10.2 UTF-8 slicing

Token boundaries cut a Chinese character in half (a 3-byte UTF-8 sequence delivering only its first
2 bytes). Converting half a character to UTF-16 yields a replacement character. The fix: accumulate
bytes and convert only at whole-character boundaries:

```cpp
size_t valid = 0;
for (size_t i = 0; i < len; ) {
    size_t step = utf8_step(bytes[i]);         // 1..4
    if (i + step > len) break;                 // half a character, keep it for next time
    i += step;
    valid = i;
}
```

Java always receives a decodable byte stream. Unit tests cover "no replacement character at any
truncation offset".

### 10.3 KV reuse

```text
1. compute this round's prompt token sequence
2. find the common prefix length n with the previous round's sequence
3. rebuild a position-aligned sequence (the previous round's output still occupies KV positions)
4. llama_memory_seq_rm(pos=n, end=-1) to drop everything after n
5. decode only tokens[n..]
```

Step 3 is what v2.4.2 fixed. Skip it and step 4 removes the wrong range, after which decode
positions no longer line up.

### 10.4 Cancellation

`nativeCancel` only sets an `atomic<bool>` that the sampling loop checks once per token. No thread
killing, no demand for instant return — the guarantee is "at most one more token".

## 11 ChatActivity · The chat screen

**What it solves**: stitch everything together — engine, storage, tools, rendering, state.

This class is over 1500 lines, the largest file in the project. Its job is **orchestration**, not
**logic**: everything with real rules lives in `core/` and `agent/`.

### 11.1 The agent loop

```java
for (int step = 1; step <= MAX_ROUNDS; step++) {
    Gen gen = generateBlocking(ctx);
    if (stoppedBy(gen)) break;
    List<ToolCall> calls = ToolCall.parse(gen.text);
    if (calls.isEmpty()) break;
    for (ToolCall c : calls) {
        if (stoppedBy(gen)) break;          // checked at every safe point
        if (AgentTools.risky(c.name) && !confirm(c)) { feedBack(reason); continue; }
        feedBack(AgentTools.exec(c));
    }
}
```

### 11.2 Streaming rendering

Two things must happen at once:

1. strip tool tags and the thinking block (otherwise the user sees debris like `name="web_search">`);
2. keep Markdown rendering from falling over (half a code fence, half a table).

So the streaming phase renders "plain text with tags stripped", and a full Markdown pass happens
once it is done. There is one visual switch in the middle, which beats making users stare at raw tokens.

### 11.3 Message positions

During streaming the list keeps changing, so long-press using "the position at bind time" deletes
the wrong message. Use `getBindingAdapterPosition()` at the moment of the tap.

### 11.4 Lifecycle

```java
private boolean alive = true;
// onDestroy: alive = false; ui.removeCallbacksAndMessages(null);
```

Every callback starts with `if (!alive) return;`. Looks verbose, but it beats debugging
`IllegalStateException: Can't create handler inside thread that has not called Looper.prepare()`
by a factor of a hundred.

## 12 MemoryBudget · Context budget

**What it solves**: produce a default context size that will not blow up the process.

### 12.1 Inputs

| Input | Source |
|---|---|
| `MemTotal` / `MemAvailable` | `/proc/meminfo` |
| Model size | `LocalModel.size` |
| Whether KV is quantised | always true (q8_0 in code) |

### 12.2 Calculation

```text
room = available - modelSize/2 - 320MB          # weights are mmap'ed, half counts as page cache
try each STEPS tier (512…32768):
    estimateExtraKb(step) ≤ room → mark feasible
take the largest feasible tier; cap at total/8 (leave room for page cache)
if meminfo is unreadable → return fallback
room < 256MB             → return min(fallback, 512)
```

### 12.3 Why cap at `total/8`

`MemAvailable` is "right now"; after a long session other processes squeeze it. A proportional cap
is steadier than an absolute one because devices vary so much.

### 12.4 Limits

This is a **heuristic**, not a measurement. The goal is "the default should not be absurd", not
"predict precisely". Real behaviour still depends on the model's structure (layers, heads, vocab size).

## 13 StorageGuard · Storage health check

**What it solves**: two things — do not fill the disk before downloading, and do not let the index
and the disk drift apart silently.

### 13.1 Four tiers

| Verdict | Condition | UI |
|---|---|---|
| `OK` | ≥ 256 MB left after the download | start normally |
| `TIGHT` | fits, but < 256 MB left | start + toast warning |
| `INSUFFICIENT` | does not fit | refuse + say exactly how much is short |
| `CRITICAL` | < 128 MB free | refuse + suggest deleting a model first |

### 13.2 Audit

```java
List<Finding> f = StorageGuard.audit(entriesFromIndex, filesOnDisk);
String msg = StorageGuard.summarize(f);   // empty string means healthy
```

Three verdicts:

- `GHOST_ENTRY`: in the index, not on disk → tapping it errors
- `ORPHAN_FILE`: on disk, not in the index → wasted space
- `EMPTY_FILE`: zero bytes → leftover from an interrupted download

### 13.3 Why only warn when the result changes

The findings often persist (people do not clean up). Toasting every time becomes noise; mention it
once when first discovered and let the user remember.

## 14 FsKit · File operations

**What it solves**: gather path convergence, atomic writes, magic sniffing and directory stats in
one place instead of one copy per module.

| Method | Purpose |
|---|---|
| `resolveInside(root, rel)` | converge a path, return null when it escapes |
| `writeAtomic(file, data)` | temp file → fsync → rename |
| `readText(file, max)` | length-capped read, with a note when truncated |
| `sniff(file)` | read the first 4–16 bytes to identify the real type |
| `statDir(dir, prefix)` | recursively count files / dirs / bytes / largest file |
| `listSorted(dir)` | directories first, names ascending |
| `deleteTree(f, root)` | recursive delete that refuses to remove the root |

### 14.1 Magic table

| Magic | Type |
|---|---|
| `GGUF` | GGUF model |
| `PK\x03\x04` | ZIP / APK |
| `SQLite format 3` | SQLite |
| `<!DOCTYPE` / `<html` / `{` | web page or JSON (usually an error response) |

### 14.2 Why not the `Files` API

minSdk 24; `java.nio.file` starts at API 26. So it is `File` plus manual fsync.

## 15 Validators · Input convergence

**What it solves**: there is a gap between what a user can type and what the engine accepts, and one
wrong value can degrade sampling or crash it.

### 15.1 Convergence order

```text
1. pin the context first (clamp 512..32768)
2. output ceiling = min(input, context × 3/4), at least 64
3. temperature ∈ [0, 2]
4. top-k ∈ [0, 200]; when temperature ≤ 0.01, top-k is at least 1
5. top-p ∈ (0, 1]
```

Order matters: clamp the output before looking at the context and you can produce the illegal
combination "output ceiling larger than the context".

### 15.2 Why top-k must not be 0 with greedy sampling

`temperature=0` is greedy sampling; the distribution collapses to one-hot. `top-k=0` means "no
truncation" in some implementations, and the two combined send the sampler down a strange path that
emits long repeated spans. Keeping at least 1 is the stable choice.

### 15.3 URL whitelist

```java
safeUrl(url)      // only http:// and https://, strips CR/LF
isPrivateHost(h)  // localhost / 127.0.0.1 / ::1 / 10. / 192.168. / 172.16-31. / 169.254. / *.local
```

## 16 PromptTemplate · Prompts

**What it solves**: prompts used to be scattered string constants, so swapping models meant editing
code; and small models get derailed by long prompts.

### 16.1 Four presets

| Preset | Scenario | Traits |
|---|---|---|
| `GENERAL` | ordinary chat | concise, asks for fenced code blocks with file names |
| `AGENT` | agent mode | encourages calling tools before answering |
| `CODER` | programming | emphasises fenced code and writing files to disk |
| `MINIMAL` | ≤2.5B | very short, tool docs compressed to one line each |

### 16.2 Compressed tool docs

```text
Full:    <function name="tool"><param name="arg">value</param></function>
         + a description per tool (multiple lines)

Compact: only "format + tool name list", one per line
```

Small models have a tight attention budget; a verbose tool list makes them miss the format more often.

### 16.3 The tool list must not be hand-maintained

```java
String tools = PromptTemplate.toolSection(AgentTools.describe(web, files, shell), compact);
```

The list comes from `AgentTools`, the same source as the executable set. Otherwise you get "the
prompt mentions a tool that execution rejects as unregistered".

### 16.4 Guessing parameter count

```java
guessBillionParams("Qwen2.5-0.5B-Instruct-Q4_K_M.gguf")   // 0.5
guessBillionParams("qwen2.5-7b-instruct")                 // 7.0
guessBillionParams("model-fp16.gguf")                      // 0.0 (cannot tell)
```

A regex looks for `number + b` not followed by an alphanumeric. If it cannot tell, assume `AGENT` —
better to over-document than under-document.

## 17 TextKit · Text utilities

| Method | Purpose | Key point |
|---|---|---|
| `trimUtf8(s, max)` | truncate by bytes | never cuts mid-character |
| `endsWithCompleteChar(bytes, len)` | is the tail a whole character | used when appending streamed bytes |
| `safeFileName(raw, max)` | sanitise a file name | replaces `/\:*?"<>|`, strips control chars, avoids Windows device names |
| `shortHash(s, n)` | FNV-1a short hash | suffix long names so they stay under 255 bytes |
| `sha256Hex(s)` | full hash | when strong uniqueness is needed |
| `roughTokens(s)` | rough token estimate | 1 Chinese char ≈ 1, ~3.6 English chars ≈ 1 |
| `sentences(s)` | split sentences | used to judge "is it finished" |
| `looksComplete(s)` | does it end cleanly | terminal punctuation / closed fence / balanced brackets |

### 17.1 Why `safeFileName` is so fussy

Because every case it blocks really happens:

| Input | Problem | Output |
|---|---|---|
| `../../etc/passwd` | path traversal | `.._.._etc_passwd` → then caught by `resolveInside` |
| `CON` | Windows reserved device name | `_CON` |
| `a\u0000b` | control character | stripped |
| 200 Chinese characters | over 255 bytes | truncated + `~a1b2c3d4` |

### 17.2 What `looksComplete` is for

Before "continue generating", decide whether the previous passage actually finished. Continuing text
that already ends with `。` or a closing fence makes the model re-plan its wording, which reads as
duplication.

## 18 PerfStats · Performance stats

**What it solves**: single-run tok/s jitters wildly, so showing only the last number is useless.

### 18.1 Metrics

| Metric | Meaning |
|---|---|
| `lastTps()` | speed of the most recent generation |
| `meanTps()` | mean over the window |
| `percentileTps(0.9)` | P90, to see tail degradation |
| `meanPrefillMs()` | mean first-token latency |
| `degraded()` | was the last run below 60% of the historical mean |

### 18.2 Why median-ish thinking

The mean is dragged down by one anomaly (background switch, incoming call). P90 reflects "how fast
this normally goes" better. Show both and let the user judge.

### 18.3 Degradation test

```java
if (size < 3) return false;              // do not judge during warm-up
return last < meanOfPrevious * 0.6;
```

And give an actionable suggestion rather than just a number: "closing background apps and dropping
one context tier usually restores it".

## 19 MarkdownOutline · Outline

**What it solves**: scrolling back to find a section in a 2500-word reply on a phone is painful.

### 19.1 Implementation

Scan lines for ATX headings (`#` to `######`), skipping `#` comments inside fenced code blocks.

```text
```bash          ← enter code block
# not a heading   ← skipped
```              ← leave code block
# this is a heading  ← collected
```

### 19.2 Deciding whether an outline is worth it

```java
return markdown.length() > 800 && extract(markdown).size() >= 2;
```

Too short, or only one heading: the menu item does not appear at all — no "tapped it, nothing happened".

### 19.3 Stripping inline markup

`**bold**`, backticked code, link syntax and `<tag>` are all stripped, leaving plain text. Showing
`## **Notes**` in a menu looks awful.

---

## Afterword

Among these nineteen modules, there is not much genuinely "clever" code — most of it is **dealing
with reality**: undocumented APIs, a server that changes, a model that will not behave, memory that
is not enough, users who fat-finger, and processes that get killed.

If you take one lesson away: **turn "uncertain" into "checkable"**. Not sure about free space?
Write `StorageGuard`. Not sure the file is a model? Read the magic. Not sure the parameters are
legal? Run them through `Validators`. Every guess replaced by a check is one fewer
"sometimes wrong, never reproducible" bug.

## 20 ToolGuard · anti-spin gate

**Problem**: a small local model repeats the same tool call after receiving the result. It does not
really "remember" that it already read that file, so it asks again; without a gate, all 8 steps of a
turn go to the same call and the user gets no answer.

### 20.1 Fingerprints must be argument-order independent

The model orders arguments differently every time:

```text
{"path": "a.txt", "content": "hi"}   ← first
{"content": "hi", "path": "a.txt"}   ← second, the same call
```

Comparing raw JSON strings treats them as two calls and the gate does nothing.
`ToolGuard.canonicalArgs()` lowercases keys, trims values and sorts them into
`content=hi;path=a.txt`.

### 20.2 Three layers, each covering a different gap

| Layer | Mechanism | Effect |
|---|---|---|
| Prompt | `PromptTemplate.noRepeatRule()` | saves the model a step (one sentence) |
| Host | `ToolGuard` | the same call runs at most twice; two repeats end the turn |
| Sampling | repetition penalty + loop breaker | kills repetition at the token level too |

Any single layer is not enough: the prompt cannot stop a model that simply ignores it, the gate
cannot stop a model that rephrases the call, and the sampling penalty cannot end the turn.

### 20.3 Why the limit is 2, not 1

Some calls legitimately happen twice: read a file, change it, read it back to confirm.
Blocking at one would break the most ordinary workflow.

### 20.4 Very long arguments

A `write_file` payload can be tens of thousands of characters. The fingerprint keeps only the first
`MAX_ARGS` characters, **cut on a character boundary** (never splitting a surrogate pair): dedup does
not need the whole text, but the key still has to be readable.
