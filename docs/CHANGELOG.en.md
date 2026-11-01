# Changelog

> 🌐 Language / 语言: English · [中文](CHANGELOG.md)

The full history, newest first.

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
