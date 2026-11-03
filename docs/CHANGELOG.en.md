# Changelog

> 🌐 Language / 语言: English · [中文](CHANGELOG.md)

The full history, newest first.

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
