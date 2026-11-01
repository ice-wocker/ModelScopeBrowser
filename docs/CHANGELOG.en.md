# Changelog

> 🌐 Language / 语言: English · [中文](CHANGELOG.md)

The full history, newest first.

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
