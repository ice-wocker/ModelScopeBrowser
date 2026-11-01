# Deep Q&A (FAQ)

> 🌐 Language / 语言: English · [中文](FAQ.md)

The questions people actually ask after using it. Basics live in
[section 11 of the handbook](README.md#11-frequently-asked-questions); errors live in
[Troubleshooting](TROUBLESHOOTING.md).

---

## I. Choosing a model

**Q1. How big a model should I download?**

Pick by available RAM, not by "bigger parameter count is smarter". The bottleneck on a phone
is memory and bandwidth, not compute.

| Device RAM | Suggested size | Suggested quant | Context | Expected speed |
|---|---|---|---|---|
| 4 GB | 0.5B | Q4_0 / Q4_K_M | 2048–4096 | 10–20 tok/s |
| 6 GB | 0.5B–1.5B | Q4_K_M | 4096–8192 | 12–25 tok/s |
| 8 GB | 1.5B–3B | Q4_K_M | 8192 | 10–20 tok/s |
| 12 GB+ | 3B–4B | Q4_K_M | 8192–16384 | 8–15 tok/s |
| 16 GB+ | 7B (barely) | Q4_0 | 4096 | 2–5 tok/s |

Beyond 7B is not usable on a phone: it loads, but two or three characters per second is
effectively unusable.

**Q2. Q4_K_M, Q4_0, Q8_0 — which one?**

| Quant | Size | Quality | Recommendation |
|---|---|---|---|
| `Q4_0` | smallest | fair | when memory is very tight |
| `Q4_K_M` | small | good | **first choice**, the size/quality sweet spot |
| `Q5_K_M` | medium | better | when you have headroom |
| `Q8_0` | large | near original | basically pointless on a phone |

`K_M` means "K-quant, medium mixed precision": sensitive layers keep more bits. Same bit count,
better quality than the older formats.

**Q3. What is the difference between instruct and base?**

`instruct` (or `chat`) has been fine-tuned to follow instructions, so it understands "summarise
this for me". A `base` model only continues text — ask it a question and it writes more questions.

**Agent mode depends heavily on instruct** — a base model will not emit tool calls in the
expected shape, so the whole loop falls apart.

**Q4. Why does some model "refuse to act"?**

Three causes, most likely first:

1. **The model is too small** (<1B): limited instruction following, so it answers instead of
   calling a tool. 1.5B–3B is noticeably better.
2. **Out of its training distribution**: some models have seen function-call formats, some have
   not. Qwen and MiniCPM families are friendly to tool calls.
3. **The prompt is too long**: models under 2.5B automatically get the compressed prompt. If you
   edited the system prompt by hand, try restoring the default.

Diagnostic order: first type `/run ls` in the input box to confirm the terminal channel itself
works, then compare against another instruct model.

**Q5. A model ships dozens of GGUF files. Which one?**

Look at the quant tag in the file name. One repo usually offers `Q2_K`, `Q3_K_M`, `Q4_K_M`,
`Q5_K_M`, `Q8_0`, `FP16`.

Take `Q4_K_M`. If only `Q4_0` and `Q8_0` exist, take `Q4_0`.

Also note that files starting with `mmproj` are vision projection layers (for multimodal use);
you can download them separately, but this project does not accept multimodal input, so skip them.

---

## II. Speed

**Q6. Why am I only getting 3 tok/s?**

Check in this order:

| Check | How to confirm |
|---|---|
| Model too large | 3B+ is inherently slow on an 8 GB device |
| Old chip | arm64 without dotprod can only run the baseline tier, about 40% slower |
| Context too large | a 32768 prefill takes tens of seconds |
| Background apps | kill everything else and retest |
| Thermal throttling | sustained generation over ~10 minutes slows down noticeably |
| Battery mode | power saving limits CPU frequency |

**Q7. First token is slow, then it speeds up. Normal?**

Yes. First-token latency is the prefill: the whole prompt (system message + history + this turn)
goes through the model. After that each token needs one forward pass, hence the speed-up.

Prefix reuse cuts prefill dramatically in multi-turn chat: three rounds go from 655 to 328 tokens
cumulative.

**Q8. Why does agent mode make it slower?**

Because every tool round is a fresh generation. The 8-step ceiling means up to 8 generations in
the worst case.

Mitigations: split the task across several messages, or turn agent mode off and use slash commands.

**Q9. What is the 90th percentile in the status bar?**

P90 is the 90th-percentile speed over the last 12 rounds. It reflects "how fast this normally goes"
better than the mean: a single trip to the background drags the mean down but barely moves P90.

If P90 is also clearly lower than before, the device state changed (thermals, background load) —
it is not a one-off.

---

## III. Memory and storage

**Q10. Why is the default context 8192, and can I go bigger?**

Yes, the ceiling is 32768. But look at this table:

| Context | q8_0 KV extra memory | Suitable for |
|---|---|---|
| 8192 | ~320 MB | 8 GB devices |
| 16384 | ~560 MB | 12 GB devices |
| 32768 | ~1040 MB | not recommended on a phone |

The larger the context, the slower the prefill too. Unless you really need long documents,
8192 is enough.

**Q11. The system killed the app. Do I have to re-download the model?**

No. Model files live in the private directory and do not disappear with the process. Reopening
the app loads it automatically (may take a few seconds).

**Q12. If the app is killed mid-download, does it resume?**

Yes. Unfinished tasks are recorded in `downloads.json`, re-queued on next launch, and resumed with
a `Range` header.

That requires the server to honour `Range` — most ModelScope files do (still valid after the 302
to the CDN).

**Q13. It says "not enough space, X short", but the system shows plenty free?**

Two possibilities:

1. The partition holding the private directory (usually `/data`) is not the same thing as the
   "internal storage" the system UI reports;
2. The app keeps a 256 MB safety waterline — after download comes an atomic rename and an index
   write, and the system needs room for page cache.

**Q14. How do I wipe everything?**

Uninstall the app. All data (models, chats, workspace) is in the private directory.

Only chats: chat screen → menu → history → delete one by one.

Only models: local model library → long press → delete.

---

## IV. The agent

**Q15. Why do I have to confirm every time? Annoying.**

Confirmation applies to exactly three state-changing operations: `shell`, `write_file`,
`delete_file`. The dialog offers "allow for this session", and after one tap it stops asking.

A new session resets it — authorisation is time-boxed on purpose.

**Q16. A tool card says "denied", but I never tapped deny?**

Several cases:

- you pressed stop, and the loop marked that tool as not executed when it hit a safe point;
- the tool name did not match the registered set (the model produced a well-formed but
  non-existent tool);
- the path escaped the workspace and was blocked by `resolveInside`.

**Q17. Running a command returns "Permission denied"?**

That is the Android sandbox. The app can only reach its own private directory; it cannot write
`/sdcard` or `/system`.

This is a platform limit, not a bug. Touching shared storage would need `MANAGE_EXTERNAL_STORAGE`,
which the app does not request (and should not).

**Q18. Web search returns nothing?**

First, anti-scraping: DuckDuckGo / Bing answer high-frequency requests with empty pages or a CAPTCHA.
Wait a few minutes and retry.

Second, the network: `fetch_url` refuses intranet addresses, so `192.168.x.x` gets blocked.

**Q19. Fetched pages come back as mojibake?**

The page is not UTF-8 (common on older Chinese sites using GBK). Everything is decoded as UTF-8
right now, so non-UTF-8 pages come out garbled — a known limitation.

**Q20. How do I get a file out of the workspace?**

Workspace screen → long press the file → share. That goes through the system share sheet, so you
can send it to a messenger, mail it, or save it to cloud storage.

For `.html` files you can also tap "open in browser" on the preview screen to hand it to the
system browser.

---

## V. Building and debugging

**Q21. The first build hangs on "fetching llama.cpp"?**

CMake uses `FetchContent` to pull b11205 from GitHub, which can be slow. Two options:

```bash
# 1. clone it yourself and point at that path
git clone --depth 1 --branch b11205 https://github.com/ggerganov/llama.cpp /tmp/llama.cpp
./gradlew assembleDebug -PllamaCppDir=/tmp/llama.cpp

# 2. give git a proxy
git config --global http.proxy http://127.0.0.1:7890
```

**Q22. The build complains about an NDK version mismatch?**

The project pins `ndkVersion "27.2.12479018"`. Install that, or change the version in
`app/build.gradle` (and verify compatibility yourself).

**Q23. The release build has fewer features than debug?**

Most likely R8 obfuscation. Reproduce with `./gradlew assembleDebug`; if debug is fine, it is
obfuscation — check whether `proguard-rules.pro` is missing a new JNI symbol or reflected class.

**Q24. How do I add my own tool?**

Three steps:

```java
// 1. register the name (used by ToolCall validation)
AgentTools.names().add("my_tool");

// 2. implement execution
case "my_tool": {
    String arg = c.arg(new String[]{"param"}, "");
    return "result";                     // always return text
}

// 3. add it to the prompt (otherwise the model does not know it exists)
AgentTools.describe(...)  // append a line
```

If it should require confirmation, add the name to `AgentTools.risky()`.

**Q25. Why are the unit tests so fast?**

Because `core/` has no Android dependency. `./gradlew testDebugUnitTest` compiles only pure Java,
and 99 cases finish within a second (excluding Gradle start-up).

That is the extra benefit of sinking rules into core: fast feedback means people actually write tests.

---

## VI. Privacy and security

**Q26. Are my conversations uploaded?**

No. Conversations are generated locally and stored locally (`filesDir/chats.json`).

The app only makes two kinds of network request:

1. the list / detail / file endpoints while browsing ModelScope (never your chat content);
2. once you enable web access, the search keywords and the URLs to fetch.

**Q27. Can the model read my SMS or photos?**

No. The app requests no such permissions, and the Android sandbox confines the process to its own
private directory.

The `shell` tool is a real shell, but it is subject to the same sandbox — `ls /sdcard` returns
Permission denied.

**Q28. Is the preview screen safe?**

Since v2.4.2 `allowUniversalAccessFromFileURLs` is off, so HTML written by the model cannot use
`fetch('file:///…')` to read app-private files. Relative `<img>` / `<script>` / `<link>` still work.

Note: the preview can still reach the internet (model-generated pages may load CDN assets).
If you do not want that, cut the network at the system level.

**Q29. Why is fetching blocked for intranet addresses?**

SSRF defence. A local model is entirely capable of emitting `http://127.0.0.1:8080/admin` to probe
services on your phone.

Blocked ranges: `localhost`, `127.0.0.1`, `::1`, `0.0.0.0`, `10.*`, `192.168.*`, `172.16-31.*`,
`169.254.*`, `*.local`.

---

## VII. Development and contribution

**Q30. Code style?**

Match the code that is already there:

- 4 spaces, no tabs;
- comments explain *why*, not *what* — `i++; // increment i` is noise;
- any class with real rules gets a Javadoc block saying what problem it solves;
- new pure logic goes into `core/` first, with unit tests.

**Q31. What must pass before a PR?**

```bash
./gradlew testDebugUnitTest   # must be all green
./gradlew lintDebug           # abortOnError, zero errors allowed
./gradlew assembleDebug       # produces an APK
```

CI runs the first two automatically, and the third too (in the `build` job).

**Q32. What if the docs disagree with the code?**

Code wins; fix the docs. [Appendix A · API cheat sheet](README.md#appendix-a--api-cheat-sheet) is
extracted from source; the rest is hand-written and therefore can go stale — send a PR when you
spot it.

**Q33. Why not add more dependencies?**

Every dependency makes the APK bigger, the build slower, and the security surface wider. There are
only 5 direct dependencies today (mandatory AndroidX + Material + Markwon); HTTP, JSON, files and
concurrency all use what the JDK ships.

If you want one, answer first: could I write it myself in under 200 lines?

---

## VIII. Design "whys"

**Q34. Why not OkHttp / Retrofit?**

The whole project has 5 endpoints; `HttpURLConnection` is enough. Pulling in OkHttp adds more than
1 MB to the APK.

**Q35. Why not store chats in Room?**

Chats are read and written as a whole: load a session, save a session. One JSON file with atomic
writes is simpler and has no schema migrations. A database only earns its keep when you need paged
queries — phone-scale chats never reach that.

**Q36. Why Activities instead of Compose?**

The project started in 2024 as a bare ListView implementation and evolved to Material 3 + XML.
Migrating to Compose is a rewrite whose only return is developer experience, which is not worth it
at this size.

**Q37. Why not support iOS / Desktop?**

The core value is "run offline on a phone", and llama.cpp's Android cross-compilation,
instruction-set tiers, sandboxed filesystem and foreground services are all platform-specific.
Going cross-platform would dilute exactly those details.

**Q38. Why not add more model sources (HuggingFace / Ollama)?**

This project is "a good client for ModelScope", not "a universal model manager".

That said, `ModelApi`'s `Page` shape is generic; a new source only needs the four operations
list / detail / file list / download URL.

---

_Still stuck? Open an issue. Include the diagnostics from
[section 14 of Troubleshooting](TROUBLESHOOTING.md#14-collecting-diagnostics) and you save a round trip._
