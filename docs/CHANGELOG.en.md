# Changelog

> 🌐 Language / 语言: English · [中文](CHANGELOG.md)

The full history, newest first.

## Merge record — collapsing the GitHub line and the CNB line into one tree

After `602fdab` (v2.7.0) the two lines moved independently and neither contained the other:

- **GitHub line** (15 commits): `f83e9fe` grew the tool set from 8 to 57 with `ToolRegistry` as the single source of
  truth, `f41c4b6` added native tool calling and cross-slot KV, plus site auto-update and a profile README.
- **CNB line** (7 commits): `e898b71`/`1c65f7b` step budgets, edit patches, a retrieval index and adaptive sampling,
  `0e7dd61` two hard bugs found by a real build, `516ad64` the "wrong answer" and "empty answer" defects.

Rule for the merge: **take the GitHub implementation, port the CNB-only judgements onto it.**

| Area | Kept from | Why |
|---|---|---|
| Tool table | GitHub (57 tools / `ToolRegistry`) | descriptions, whitelist and risky-action check already share one source; the 21 CNB tools are a subset |
| Context rebuild | GitHub (rebuild from history each turn) | the CNB version assumed the prompt only ever grows, which is false once the context is rebuilt |
| Cross-slot KV | GitHub | switching slots when a turn has two assistant messages is what makes chained tool calls work |
| Streaming repaint | CNB (80 ms + buffer) | the 40 ms path never finished throttling |
| Loop-breaker cancel | CNB | queueing `nativeCancel` on the single-thread `worker` puts it behind the generation it should cancel — a guaranteed empty reply |
| Repetition penalty | GitHub default (1.1) + CNB validation | `Validators` clamps window and factor into a legal range |
| Tool result feeding | GitHub `responseBlock` + CNB per-result truncation | the wrapper decides whether the model recognises the result; truncation decides whether the context survives |
| Per-turn tool context cap | both | 24000 chars per turn plus 6000 per single result |

**Verified** (JDK 17 + Android SDK 36 + build-tools 36 + NDK 27.2.12479018, a real build, no stubs):

- `testDebugUnitTest`: **282 green**;
- `lintDebug`: **0 errors** (120 warnings, the pre-existing baseline);
- `assembleRelease`: passes, including the NDK cross-build and R8. The APK reports `versionCode 17` /
  `versionName alpha 1.0`, `native-code: arm64-v8a armeabi-v7a`, with all eight arm64 multi-variant backends
  present (12 `.so` files in total).

Four unit tests that came with the CNB line were dropped (`AgentToolsTest` / `AgentToolsExecTest` /
`ToolSchemasTest` and the suites for the retired pure-logic classes): they pinned the 21-tool contract and would
only fight `ToolRegistry`.

**Still no real-device regression.** Two things deserve a device first: chat for five or six turns and check for
non-sequitur answers; and with a function-call-template model (Qwen2.5 / a DeepSeek-V3.1 distill) let it call
tools three times in a row and check for repeats.

## alpha 1.0 — from "it can call tools" to "it can finish the job"

The previous release made the tool-calling loop actually work. This one answers a more practical
question: **now that it works, what is missing?**

What was missing was not more buttons. It was four concrete things, each of which leaves a
multi-step task half-finished.

### 1. The hard-coded 8 steps was the biggest problem

`MAX_ROUNDS = 8` was never a safety boundary, just a number picked once. "Read a file → edit one
line → read it back to verify" already needs 3 steps; anything with a bit of exploration hits the
cap, and the user is told "per-turn tool call limit reached" with the work half done.

Now there are three limits; hitting any one ends the turn cleanly and says which:

| Level | Steps | Wall clock |
|---|---|---|
| Quick | 32 | 3 min |
| Standard | 64 | 8 min |
| Deep | 128 | 20 min |
| Extreme | 200 | 45 min |

The third limit is **context nearly full** (≥95%): one more round only pushes out the earliest
content, leaving the model with a fragment of history.

Plus an **idle detector**: three consecutive rounds of read-only calls with no change at all ends
the turn. This is subtler than "repeated the same call" — every call is legal and distinct, yet
nothing is ever written.

### 2. Once the context is trimmed, the model forgets what it did

After tool results are trimmed away the model re-reads the same file, or undoes its own fix.

- **Work journal** (`journal_read`): one line per tool call, append-only, costs nothing in the
  resident context, fetched on demand.
- **Plan list** (`todo_write`): the model writes it; each round the system feeds back
  "1/3 done + remaining items". This works better than telling it "make a plan" — that is advice,
  this is a fact.
- Both are summarised into two or three lines of the system prompt.

### 3. Editing a big file meant rewriting all of it

`write_file` overwrites. To change one line in a 20KB file the model must reproduce the other
19.9KB byte for byte; if it runs out of output the write is truncated and the original is destroyed.

- `edit_file`: exact snippet replacement. Missing or ambiguous matches fail loudly with candidate
  line numbers — it never guesses.
- `apply_patch`: unified diff. Anchors must match literally, multiple matches are rejected, and all
  hunks are computed in memory before anything is written.

### 4. The model invents arithmetic and device facts

- `calc`: deterministic evaluation (`+ - * / % ^ ()`, `pi/e`, `sqrt`, and `15%` as a suffix
  percent). Three-digit multiplication is where small models get it wrong, invisibly.
- `world_info`: model, ABI, memory, storage, battery, loaded model, context usage, measured speed.
  If it can see them, it does not have to guess.
- `now` / `file_info`: time and file metadata, for the same reason.

### Also in this release

- **`kb_index` / `kb_search`**: a workspace search index (BM25 + CJK bigrams). A 40KB log is tens of
  thousands of tokens; the model only wants the line that failed.
- **Long-term memory** (`remember` / `recall`): structured `id | ts | fact`, updated by similarity
  rather than appended, and redacted on write (GitHub / OpenAI / AWS / Slack / JWT / private-key
  blocks / `password=xx` / `user:pass` in URLs).
- **Adaptive sampling** from measured decode speed, with `top-p ∈ (0,1]` and `top-k ≥ 1` always
  enforced — out-of-range values degrade or crash the sampler silently.
- **Content-level loop breaker**: the old check only saw "no new bytes for seconds", missing
  paraphrased repetition. A ≥16-char segment repeated 3 times inside the window now breaks the loop.
- **Benchmark** (`bench` / menu item): two fixed cases and a plain-language verdict.
- **One tool schema** (`ToolSchemas`) shared by the prompt, the parser whitelist and the JSON
  `tools` array for external APIs. It used to be written three times; one drift and the prompt
  advertises a tool that cannot run.
- Version is now **alpha 1.0** (versionCode 17). Alpha means the interfaces may still move.

### How far this was verified (stub compile → real build)

The first version was only verified by compiling against hand-written JDK 17 stubs, and said so:
native build and Lint were unverified. Running them for real broke immediately, twice:

- **The native build did not compile.** Since llama.cpp b11205, `llama_sampler_init_penalties` takes
  a leading `n_vocab` argument; `llama_bridge.cpp` still passed four. Both arm64-v8a and armeabi-v7a
  failed with `error: no matching function` — no APK at all, not a warning.
- **Memory crashed on Android 7.0/7.1.** `Memory.Store` used `File#toPath` and `java.nio.file.Files`,
  which are API 26, while `minSdk` is 24. On API 24/25 that is not a missing feature but a
  `NoClassDefFoundError` — touching long-term memory took the app down. Lint `NewApi` flagged 4 errors.

Both fixed. Measured afterwards (JDK 17 + Android SDK 36 + NDK 27.2.12479018, no stubs):

- `testDebugUnitTest`: **207 green** (204 + one regression pinning the in-memory round-trip through `java.io`, + two pinning the tool list).
- `lintDebug`: **0 errors** (101 pre-existing warnings, no new baseline).
- `assembleDebug` / `assembleRelease`: **real APKs**. `aapt2` reports
  `versionCode='17' versionName='alpha 1.0'` and `native-code: arm64-v8a armeabi-v7a`; all eight
  arm64 multi-variant backend `.so` files are present (armv8.0 / 8.2 / 8.6 / 9.0 / 9.2).

**Still unverified: real-device behaviour** — the only remaining item. Install it, pick the "deep"
tier, and give it a multi-file task that takes several steps.

One process lesson: **a stub compile does not prove you can ship.** Stubs let `src/main/java` pass
javac, but they never touch `cpp/` or the `minSdk` / API-level boundary — and those were exactly the
two places that broke.

### Still open

`ChatActivity` grew again (~2100 lines). This release added six UI surfaces to it: effort level,
todo, journal, tool catalogue, memory, benchmark. It should be split — but splitting the UI in the
same PR as adding capabilities makes the diff unreviewable. Next time, on its own.

## v2.8.0 — making "the agent can call tools" actually work on models with a function-call template

No new features. This release is about one class of problem: **things that are claimed to work but
fail silently**. The clearest example is tool calling — the model calls a tool, the tool runs, and
the model never sees the result, so it calls again.

### Why the result never reached the model

Three causes stacked on top of each other; each one alone is enough to break the loop:

1. **Cross-slot KV** (native). After a turn, the KV cache still holds that turn's output tokens while
   `s->kv` only records the prompt. If the next prefix match also counts the generated tokens, the
   match lands inside the previous turn's output — the KV deletion removes the wrong range and decode
   positions drift away from the real contents. The symptom is "answers the wrong question, calls the
   same tool again", and it hits *harder* on templates that keep the whole history. Now `s->kv`
   records **only the prompt**; generated tokens are appended to the KV by `decodeNext` but stay out
   of the record, so the matched prefix can never be longer than the previous prompt.
2. **The native protocol was not honoured** (Java). Tool results were fed back with the `user` role,
   producing `assistant(call) / user(result) / user(next)`. Templates with function-call support
   (Qwen / DeepSeek / MiniCPM) render per *turn*; two messages of the same role make them emit
   "end of turn + start of new turn" in between, and the earlier message is cut off. The assistant
   output and the tool results are now **merged into a single assistant message** (results wrapped in
   `<tool_response>`), and adjacent same-role history messages are merged too.
3. **Context only grew** (Java). Results were fed back whole, so one `cat` or one fetched page could
   push the history out of the context window; the trimming then quietly dropped the oldest messages,
   and a model that suddenly sees a gap is *more* likely to re-issue a call. Results are now capped at
   6k characters (the card in the UI still shows everything) and repeated calls are gated.

### Three layers of anti-spin protection

- **Prompt**: `PromptTemplate.noRepeatRule()` states, in both languages, not to re-issue a call that
  already ran.
- **Host**: new `core/ToolGuard` — the same call (tool name plus a key-order-independent argument
  fingerprint) runs at most twice per turn, and two repeats end the turn. The fingerprint sorts the
  arguments, so reordering keys does not get around it.
- **Sampling**: a repetition penalty (window 128 / factor 1.08, adjustable in the parameters dialog)
  plus a loop breaker in the engine: if the output stops growing for 6 seconds it is cancelled
  asynchronously instead of burning all 4096 tokens.

### Other things made real

- **One source of truth for the tool list**: `AgentTools.enabled(web, files, shell)`; parsing,
  stripping and the prompt all read it. Turning the web off used to remove `web_search` from the
  prompt while the parser still accepted it — the model called an endpoint that could only fail.
- **Explicit prefix invalidation**: changing sampling parameters, the system prompt, or the web
  switch now calls `invalidatePrefix()`, instead of letting the engine recompute a long prefix from
  the first differing token.
- **Faster trimming**: dropping one message at a time and re-tokenising everything cost hundreds of
  milliseconds before a long turn. Now a rough token estimate decides how much to drop, then a binary
  search verifies it — and the current user message is never dropped.
- **Sampling validation**: `Validators.Sampling` gained the repetition window and factor; closing the
  window forces the factor to 1, and it is clamped to 1.0–2.0 (below 1 encourages repetition). The
  parameters dialog has a new "Repetition penalty" field.

### Tests

- New `core/ToolGuardTest` (10 cases), new `agent/AgentToolsTest` (6 cases),
  plus 3 in `CoreKitTest` and 1 in `Bi18nTest`.
- 99 → **118 unit tests, all green**.

### Docs

- `versionCode 15 → 16`, `versionName 2.7.0 → 2.8.0`.
- `ARCHITECTURE` now states the invariant "the same role must never appear twice in a row in the
  request"; `MODULES` has a new ToolGuard section.

## v2.8.0 (upstream line) — making native tool calling actually work on a real device

No new tools, no new buttons. This release fixes **three things that made tool calling not work at all**,
plus two that silently corrupted files or dropped results. All five were in places I assumed were fine.

### 1. Cross-slot KV: two assistant messages in one turn and the model loses "a new turn has started"

Tool calling inherently produces **two consecutive assistant messages** (one saying "I'll call a tool",
one continuing after the tool result), with a role=tool message in between. After the chat template is
applied, such a sequence does not necessarily end with an assistant start marker:

- **MiniCPM5**: the template already writes `<|im_start|>` on BOS and writes it again when appending,
  so the closing marker lands at the start and the append state is empty;
- **Gemma**: the template does not write `<end_of_turn>`; the append state is `<start_of_turn>model\n`;
- **DeepSeek-V3.1 / GLM-4.5** emit dedicated markers such as `<|Assistant|>`.

The old implementation appended the next chunk directly after the previous assistant output, which either
produces `assistant\n assistant` or makes the model treat the tool call as a continuation of its own
previous sentence — visible as **tool calls never being emitted, or the same call repeating forever**.

Fix: give each new stream its own **KV sequence slot (`llama_seq_id`)**. When the previous turn's trailing
tokens are not a prefix of the new prompt (i.e. a genuinely new continuation), switch to a different empty
slot with `pos` restarting at 0, so the two sequences never overwrite each other. The old slot keeps its
data, so switching back still hits the prefix.

### 2. Native tool calls: `<|DSML|tool_calls>`, `[TOOL_CALLS]`, `<tool_call>` were all unrecognised

Only the prompt protocol was understood (the model copying our `<function name="x">`). But GGUF models that
ship a function-call template **use the format they were trained on**; asking a small model to imitate a
made-up format fails far more often. New `agent/ServerToolCall` recognises four native protocols,
strongest signal first:

| Protocol | Who uses it | Shape |
|---|---|---|
| DSML | DeepSeek-V3.1 / GLM-4.5 | `<|DSML|invoke name="x"><|DSML|parameter name="k" string="true">v` |
| JSON | Hermes / Qwen2.5 / Llama-3.x | `<tool_call>{"name":…,"arguments":…}</tool_call>` |
| Mistral | Mistral family | `[TOOL_CALLS][{…}]` |
| Bare JSON | a few models | `{"name":…,"arguments":…}` |

Those DSML tags end with a **single `>`** — not a typo, that is what the official template emits, so it is
implemented verbatim. Native also now writes the template's assistant start marker once on the first step
(`<start_of_turn>model`, `<|Assistant|>`, …) so the model sees `assistant\n` instead of inventing it.

> Also fixed a bug only a test would find: the `]` inside `[TOOL_CALLS]` balanced the bracket counter, so
> "take a balanced JSON array" returned `[TOOL_CALLS]` itself and not a single real argument was read.

### 3. Context grew forever: tool results are now visible only within the turn

`runAgent` used to accumulate "model output + tool results" into ctx; over 8 steps two or three `read_file`
calls would eat an 8k context, after which native truncated the prompt from the front — destroying the
shared prefix and getting slower.

Now every round rebuilds the context from **history**, so tool results are inherently "visible this turn,
gone the next". Two payoffs:

- tool output no longer piles up, and no longer nudges the model to re-call the same tool;
- "dropping the tool results" is exactly "roll the KV back to before the tool call", so native can treat it
  as **replayable**: delete only what follows the shared prefix — no slot switch, no full re-prefill.
  `LlamaEngine` wires this with a dirty flag, and `invalidateKv()` voids it on edit-resend / delete /
  continue / session switch.

A total budget (24 000 chars) for tool results per turn was added too: past it, the oldest results become a
one-line placeholder, so the model still knows the call happened but the output was dropped.

### 4. The silent file corrupter: fuzzy matching in `apply_patch`

The old `locate()` had three branches that **sliced the original text with the wrong offsets**:

- CRLF→LF normalisation changes length, yet the normalised index was used to slice the original;
- the quote-normalisation branch checked the length of `find` but not of `text`;
- when the exact snippet occurred several times it just `return`ed the first hit, making every uniqueness
  check below dead code.

A snippet occurring twice is normal (boilerplate, repeated `<div>`), and the edit would land somewhere the
user did not expect — invisibly. Now every fuzzy strategy accepts **unique matches only**; when uniqueness
cannot be established the model is asked to retry. CRLF falls back to line-wise location and then slices by
the **real range in the original**.

### 5. Smaller fixes

- **`Http.download` creates the directory before writing the temp file**: for `<workspace>/downloads/a.bin`
  the parent may not exist; the old order threw `FileNotFoundException` and the user only saw "download failed".
- **`Http.download` follows redirects manually, re-checking every hop**: automatic following skips the
  address check, so a public URL that 302s to a private address turns the phone into a proxy.
- **Resume validates `Content-Range`**: when the remote file was replaced under the same name, the 206 range
  does not line up with the local partial file, producing a GGUF that passes the length check and crashes on
  load. A mismatched start now restarts the download (once only, so it cannot loop).
- **Repetition penalty (default 1.1)**: 3B-class models loop on long CPU-only output; temperature and
  top-k/top-p cannot stop that. Set 1.0 in the params to disable.

### Tests and artefacts

- Unit tests 157 → **189**, all green. New `ServerToolCallTest` (16 cases, strings copied verbatim from each
  vendor's template docs), `PatchKitEdgeTest` (12 cases aimed at "edits the wrong place" and "prefers not to
  edit"), `ContentRangeTest` (4 cases).
- `lintDebug` 0 errors; `assembleRelease` passes (R8 + 12 `.so`, all seven arm64 backends present).
- `versionCode 15 → 16`, `versionName 2.7.0 → 2.8.0`.

### Straight talk

All of the above is compile-time + 189 unit tests + static reasoning, with **no device regression**. The item
most worth verifying on hardware is #1: install a model with a function-call template (Qwen2.5, a
DeepSeek-V3.1 distill) and let it call tools three times in a row — watch for repeated calls or answers that
drift off topic.

---

---

## v2.7.1 — five branches, one source of version truth

No new features in this release. It is a merge-and-reconcile pass: `fix/v2.6.1`,
`fix/version-consistency`, `feat/agent-toolkit-57`, `feat/site-autoupdate` and
`auto/profile-readme-1777` were each developed in isolation and none had reached `main`.
Version numbers disagreed (2.6.1 in the docs, 2.7.0 on the release page, 2.6.1 in
`app/build.gradle`). Everything now lands in one place with a single source of truth.

### Merged

- **Agent tools grew from 8 to 57** (`feat/agent-toolkit-57`): `agent/ToolRegistry` is now the
  single source for tool descriptions, the tool-name allowlist and the dangerous-action check,
  so adding a tool no longer means editing three lists. Seven groups (files, text, math & time,
  device, network, memory & todo, external models) can be toggled per group; `PathGuard` gates
  shell execution and `SecureRedactor` strips secrets. The pure-logic helpers — `DigestKit`,
  `TimeKit`, `JsonAccess`, `SseParser`, `Bm25`, `DiffKit`, `DocConverter` — are unit-testable on the JVM.
- **Documented-but-missing behaviour fixed** (`fix/v2.6.1`): chat history wiped by short reads,
  no write cap on session files, cancel throwing away all download progress, resumable downloads
  splicing mismatched ranges, `backup_rules.xml` never matching anything, missing foreground
  service type on Android 14, no fallback for unknown-length downloads, NPE on a null load
  callback in `LlamaEngine.load()`, and a data race on the native `g_last_error`.
- **Site auto-update pipeline** (`feat/site-autoupdate`): build and check scripts under `site-src/`,
  `update.json` plus staleness detection, home-page filters and a release history page.
- **GitHub profile README** (`auto/profile-readme-1777`).

### Version alignment

- `app/build.gradle` now carries `versionCode 15` / `versionName "2.7.0"`, matching the README,
  the docs and the release artefact name. Previously the installed app reported 2.6.1 while the
  release page advertised 2.7.0.
- Merge conflicts were resolved newest-version-wins: version lines in the READMEs take 2.7.0 and
  both changelog sections are kept.

### Verification

- `./gradlew testDebugUnitTest`: 110 tests, all green.
- `./gradlew assembleRelease`: dual-ABI build (arm64-v8a multi-variant, armeabi-v7a).

## v2.7.0 — 57 tools, and one table behind all of them

### Agent (the bulk of this release)

There were 8 tools, and **three separately hand-maintained lists**: the description shown to the model,
the name whitelist, and the risky-action check. Adding a tool meant editing three places, and missing one
produced either "callable but the model never hears about it" or "risky action without confirmation".
This release fixes that first, then adds volume.

- **`agent/ToolRegistry` is now the single source**: every tool declares its name, group, arguments,
  risk level and one line in each language. `AgentTools.describe`, the `ToolCall` whitelist and
  `AgentTools.risky` all derive from it.
- **8 → 57 tools**, switchable by group (files / text / calc / device / web / shell / agent state / external model).
  A disabled group is invisible to the model, not "visible but broken".
- **`apply_patch`**: the model gives "find this" and "replace with that". Small models lose content when
  they rewrite a whole file (a 300-line HTML comes back as 120 after one button change); hunk edits keep
  the blast radius small, and when the find text is missing the file is **left byte-for-byte untouched**
  while the closest matching region is handed back for self-correction.
- **`grep` / `find_files` / `file_tree` / `file_stat`**: find things without pulling whole files into context.
- **`todo_*`**: the plan lives in `/todo.json`, so a long task no longer "forgets step one by step four".
- **`memory_*`**: long-term preferences in `/memory.txt`, injected every turn, capped by `PromptTemplate`.
- **`skill_*`**: 7 built-in procedures (summary, code review, debugging, single-file page, translation,
  small-step refactor, safe commands). BM25 picks one, and **only that body** enters the context.
- **`llm_ask`**: optional OpenAI-compatible channel. The on-device model decides; hard sub-problems get
  outsourced. The key stays on the device and never reaches the transcript or a tool result.
- **`redact`**: strips keys, tokens, private keys and URL passwords before they are fed back.

### New pure-logic classes (no Android deps, JVM-testable)

`DigestKit` (streaming digests, no RAM blow-up on 4 GB files) · `TimeKit` · `JsonAccess` (`a.b[0].c` tolerant reads) ·
`SseParser` (survives events split across chunks) · `Bm25` (CJK bigrams + ranking) · `DiffKit` (LCS diff, unified rendering) ·
`DocConverter` (CSV ↔ Markdown tables, wide-char alignment, Mermaid charts).

### Safety boundaries

- **`PathGuard`** runs before any `shell` command. Hard rejects: `rm -rf /`, `dd of=/dev/block/…`, fork bombs,
  `mkfs`, `su`, `pm uninstall`, recursive chmod/chown. Warnings: recursive delete, wildcards, redirects to root,
  "download piped straight into a shell". Absolute paths outside the workspace are rejected outright.
- **Redirects are followed manually**, re-checking the address allow-list on every hop; the default automatic
  following would bypass the first-hop check.
- **Download and unzip are capped**, with canonical-path checks per entry to stop zip slip.

### UI

- A "capabilities" entry on the status bar switches tool groups and shows how many are on.
- New slash commands: `/caps` `/ext` `/memo` `/todo` `/skill`.
- External model is configured in-app (base URL / key / model), not hard-coded.

### Verification

```
testDebugUnitTest  ->  157 green (99 -> 157; +26 ToolKitTest, +32 AgentKitTest)
lintDebug          ->  0 errors (6 NewApi errors introduced and all fixed:
                       java.util.Base64 and Files.readAllBytes do not exist at minSdk 24)
assembleDebug      ->  passes, including NDK cross-compilation of llama.cpp and JNI linking
values / values-zh ->  247 keys each, one for one
```

A few of the new tests exist because they caught bugs I wrote: quote normalisation has to be
length-preserving (otherwise slicing the original by the normalised index lands off by one);
the loose and strict redaction rules shared one list, silently defeating `includePair=false`;
calendar-day arithmetic must go through `Calendar` or the timezone pushes it a day off; and
tests asserting Chinese strings must pin the language, or they fail falsely on an English machine.

### Not done (not pretending otherwise)

- **Still no on-device regression.** It is compile-time + unit tests + static reasoning.
  `apply_patch`, `notify`, `clipboard` and `share` only mean anything on a real phone.
- **`llm_ask` was never run against a live provider.** Request shape and response parsing are unit-tested,
  but "how exactly this vendor wants its auth header" can only be settled by trying it.
- **No GPU backend** (Vulkan / OpenCL). Pure CPU inference is still the ceiling.
## v2.6.1 — making the documented behaviour actually true

No new features. Every item below was found by comparing what the docs claim with what the code does,
and each one points at a concrete failure path.

### Data loss / wasted work

- **Chat history could vanish entirely**: `ChatStore.readAll()` called `in.read(buf)` exactly once.
  `InputStream#read` only guarantees a single byte, so a short read under storage pressure truncated
  the JSON, parsing failed, and `readAll()` returned an empty list — the user sees "all my chats are gone",
  and the next `save()` persists that empty list. The model index and the pending-download record used
  the same pattern and are fixed the same way.
- **No upper bound on the session file**: reads were capped at 16 MB, writes were not. Beyond that the
  app produced a file it could not read back — equivalent to wiping history. Oldest sessions are now
  evicted before writing.
- **Cancel threw away all progress**: cancelling deleted the `.part` file and the resume record, so the
  next attempt started from zero. Cancel now only stops the task; the breakpoint survives.
- **Resume could splice two generations of a file**: when the server replaces a file it still answers 206,
  so the new tail got appended to the old breakpoint. `Content-Range` is now parsed and a mismatched
  start discards the breakpoint.
- **Auto Backup could never succeed**: `backup_rules.xml` only excluded `external/models`, but the models
  live under `getExternalFilesDir()` — the rule never matched. Multi-GB models blew the 24 MB quota, which
  also meant chats and the language setting were never backed up. Both `root` and `file` domains are now
  excluded, together with the unbounded `workspace/`.

### Crashes / hangs

- **Foreground service failed on Android 14**: the manifest declares `foregroundServiceType="dataSync"`,
  but `startForeground()` passed no type, which throws `MissingForegroundServiceTypeException` on Android
  14 — downloads degraded to a plain background thread and were killed on switching away.
- **No fallback when the length is unknown**: the initial space check was skipped entirely when
  `Content-Length` was missing, so the failure only surfaced as an opaque `IOException` once the partition
  filled up. The safety watermark is now re-checked every 5 s while downloading.
- **`LlamaEngine.load()` dereferenced a nullable listener** on every callback path — exactly the path taken
  when a model fails to load.
- **Native `g_last_error` was read and written from different threads**: a data race on a `std::string`.
  Now guarded, and written through a single `setLastError()`.

### Jank / wasted cycles

- **Every streamed chunk rebound the whole bubble**: the engine already coalesces tokens per 40 ms, then the
  UI called `notifyItemChanged` per chunk, pitting Markdown rendering against the inference thread.
  Repaints are now throttled to 80 ms with a forced flush at the end.
- **Network tools had no hard timeout**: `fetch_url` / `web_search` run on the agent thread, where "stop"
  only takes effect at the next callback. Timeouts tightened to 8 s connect / 12 s read, and reads are
  byte-capped (the old line-based guard let a single multi-MB line through).
- **`Lang.zh()` was called thousands of times per second and boxed a Boolean**: now a flag plus a primitive.
- **`Workspace.list()` sorted by code point**: Chinese filenames came out in arbitrary order; now `Collator`.
- **Added `.nomedia`** so the media scanner stops walking gigabytes of `.gguf` in external storage.

### Tests

- New `DownloadCenterTest` (4) covering `Content-Range` parsing.
- `CoreKitTest` gained 7: UTF-8 safe truncation, storage audit findings, sampling clamp rules.
- 99 → **110 unit tests, all green**; `lintDebug` 0 errors; `assembleRelease` passes (R8 + NDK).

---

## v2.6.0 — English/Chinese bilingual everywhere

### UI and strings

- **The default language is now English**: `values/strings.xml` rewritten in English, Chinese moved
  to `values-zh/strings.xml` (216 entries, one for one). The reason is simple: `values` is the
  fallback directory, so putting Chinese there means every language without a `values-xx` folder
  reads Chinese.
- **`MissingTranslation` turned back on in Lint** (it had been off): missing or extra translations
  now fail `lintDebug`, so bilingual support is a build-time constraint rather than a good intention.
- **In-app language switch**: "About → Language", choosing follow-system / Chinese / English. A
  `LocaleManager` swaps the Configuration in `attachBaseContext`, then `recreate()` — **no process
  restart**, so the model already in memory and any running download survive.

### Strings that live in code (not in resources)

- New `i18n/Lang`: prompts, tool descriptions, tool results, errors and suggestions — anything
  without a `Context` now goes through it. Each Activity sets it when binding the language, so the
  **UI language and the model language are always the same**.
- `PromptTemplate`'s four presets now exist in both languages; the English versions are not literal
  translations: same short sentences, fewer clauses, and an explicit "answer in the user's language".
- Copy in `AgentTools.describe/exec`, `WebSearch`, `Workspace`, `Shell`, `LlamaEngine` and
  `DownloadCenter` is fully bilingual.
- `Validators`, `StorageGuard`, `PerfStats`, `MemoryBudget` and `FsKit` gained `boolean zh` overloads;
  the pure-logic layer still has zero Android dependencies.

### Localisation details

- `Format.count` is no longer hard-wired to Chinese: Chinese uses 万 / 亿, English uses K / M — the
  whole scale changes, because swapping symbols alone is not localisation.
- `ModelApi.facetLabel`, tool card titles, the message export prefix and the performance stats line
  all come from resources now.
- Sort options moved from a static array to resource ids, so they cannot be frozen before the
  language is known.

### Tests

- New `core/Bi18nTest` (11 cases): every preset is non-empty and language-consistent in both
  languages, tool docs are bilingual per section, the numeric verdicts of parameter convergence are
  language-independent, and `Lang` follows the system while explicit settings win.
- `FormatTest` gained English K/M assertions; existing tests that assert Chinese text now pin the
  language with an explicit `Lang.setChinese(true)`, so an English CI machine cannot produce a
  "looks like it is broken" false failure.
- Unit tests 87 → **99, all green**.

### Documentation

- Complete handbook in English: `docs/README.en.md` (12 chapters + API cheat sheet + glossary).
- Focused English documents: `MODULES.en.md`, `ARCHITECTURE.en.md`, `DEVELOPING.en.md`,
  `TROUBLESHOOTING.en.md`, `FAQ.en.md`, `BENCHMARKS.en.md`, `DIAGRAMS.en.md`.
- `README.md` and `README.en.md` cross-link at the top; document navigation lists ZH and EN.

---

## v2.5.0 — adding what "should have been there all along"

### The new `core/` pure-logic layer

| File | What it solves |
|---|---|
| `MemoryBudget` | context size decided by device memory, instead of a hard-coded 8192 that gets low-memory devices killed by the LMK |
| `StorageGuard` | turns "will it fit" into one plain sentence, plus a model-library health check |
| `FsKit` | path convergence, atomic writes, magic sniffing, directory stats in one place |
| `Validators` | sampling-parameter convergence, lenient number parsing, URL whitelist, intranet detection |
| `PromptTemplate` | templated prompts, automatically compact tool docs for small models |
| `TextKit` | UTF-8-safe truncation, file-name sanitising, rough token estimates, sentence splitting |
| `PerfStats` | rolling mean / P90, with an actionable sentence when performance clearly degrades |
| `MarkdownOutline` | heading outline for long replies |

### Integration points (not decoration)

| Location | Change |
|---|---|
| `ChatActivity` | default context derived from device memory; the parameter dialog goes through `Validators` and echoes why a value was corrected; status bar shows mean / P90; new menu items "outline" and "performance" |
| `ChatActivity.buildSystemPrompt` | uses `PromptTemplate`; small models get the compact tool docs |
| `DownloadCenter` | space decisions via `StorageGuard`; GGUF verification via `FsKit.sniff` (header, not extension) |
| `LocalModelsActivity` | audits index vs disk on entry, reporting ghosts / orphans / empties once |
| `WebSearch` | `fetch_url` gained intranet blocking; truncation is UTF-8-safe |
| `AgentTools` | `fetch_url` goes through the unified whitelist |

### CI and engineering

- `verify` / `build` split into two jobs, the fast one first;
- native build cache (`app/.cxx`) added: a second build drops from ~40 minutes to a few;
- artifact verification: `apksigner` prints the cert, `aapt2` prints ABI and version; debug signatures
  are rejected on tags;
- concurrency group: a new push to the same branch cancels the old run;
- documentation grew from 34 KB to a full handbook plus focused documents and illustrations.

### Version numbers

`versionCode 12 → 13`, `versionName 2.4.1 → 2.5.0`; the minor digit jumped because a module layer was added.

---

## v2.4.2 — nailing down what looked fine but occasionally was not

- **KV prefix reuse was positionally misaligned**: the KV still held the previous round's output
  tokens while the bookkeeping only recorded the prompt, so the match landed at the wrong offset and
  `seq_rm` removed the wrong range. Multi-turn answers drifted, with occasional crashes.
- **Two concurrency holes in the native layer**: `acquire()` could lock a freed mutex in the gap
  between "check liveness" and "grab the session lock"; `nativeFree` deleted while another thread was
  sampling. Both fixed with reference counting.
- **Chinese mangled by JNI**: `std::u16string(s.begin(), s.end())` widened byte by byte, turning
  Chinese into mojibake and making JNI reject emoji.
- **Stop did not stop tools**: the agent loop only checked cancellation during generation, so
  already-parsed tool calls (including real commands) still ran.
- **"Continue generating" duplicated output**: the continuation context omitted what had already been
  written, so the model restarted from the top.
- **The preview page could read app-private files**: `allowUniversalAccessFromFileURLs` was on while
  rendering model-generated HTML, so one `fetch('file:///…')` could read and exfiltrate the chat
  history.
- Plus about ten smaller ones: per-row object rebuilds on the detail screen's progress callback,
  long-press deleting the wrong message, a non-atomic check-and-set in `LlamaEngine.load()`, message
  trimming skipping the system message, unbounded terminal buffer, non-deduplicated index, models
  that could not be deleted.

---

## v2.4.1 and earlier

See the Chinese [CHANGELOG.md](CHANGELOG.md) for the full history back to v1.0 — it is a line-by-line
record of the API discovery (`PUT`, `Criterion`), the native build traps (`useLegacyPackaging`,
`GGML_LLAMAFILE`), the instruction-set tiers, and the signing incident in v1.2.
