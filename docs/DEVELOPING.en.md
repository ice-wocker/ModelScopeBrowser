# Developer Guide

> 🌐 Language / 语言: English · [中文](DEVELOPING.md)

Getting this repo running from zero, changing one line, adding one feature, shipping one release.
Assumes you know Java and Gradle but nothing about this project.

## 1. Environment

| Component | Version | Notes |
|---|---|---|
| JDK | 17 | required by AGP 8.13; JDK 21 compiles too, but CI uses 17 |
| Android SDK Platform | 36 | `compileSdk` / `targetSdk` |
| Build Tools | 36.0.0 | |
| NDK | 27.2.12479018 | pinned in `app/build.gradle` |
| CMake | 3.22.1 | llama.cpp's build scripts want ≥ 3.14 |
| Gradle | 8.14.5 | provided by the wrapper; do not switch to a local version |

### 1.1 Installation

Android Studio ships an SDK Manager since Ladybug. On the command line:

```bash
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$PATH

sdkmanager "platforms;android-36" "build-tools;36.0.0" \
           "ndk;27.2.12479018" "cmake;3.22.1" \
           "platform-tools"
```

### 1.2 Local configuration

```bash
cd ModelScopeBrowser
echo "sdk.dir=$ANDROID_HOME" > local.properties
```

`local.properties` is excluded by `.gitignore`. Do not commit it.

## 2. First build

```bash
./gradlew assembleDebug
```

The first run goes through three phases:

1. download the Gradle distribution (~130 MB, depending on your network);
2. fetch llama.cpp (`FetchContent`, ~50 MB);
3. compile 7 CPU backends for arm64-v8a plus the static library for armeabi-v7a.

Twenty to forty minutes end to end, depending on the machine. Incremental builds after that
take tens of seconds unless you touched something under `cpp/`.

### 2.1 Speed-up: use a local llama.cpp

```bash
git clone --depth 1 --branch b11205 https://github.com/ggerganov/llama.cpp ~/llama.cpp
./gradlew assembleDebug -PllamaCppDir=$HOME/llama.cpp
```

`CMakeLists.txt` checks whether `LLAMA_CPP_DIR` contains a `CMakeLists.txt` and, if so, calls
`add_subdirectory` directly, skipping the download.

### 2.2 Build a single ABI (faster)

Temporarily edit `abiFilters` in `app/build.gradle`:

```gradle
ndk { abiFilters "arm64-v8a" }   // drop armeabi-v7a, saves about a third of the time
```

Remember not to commit that change — real 32-bit devices still need it.

## 3. Project structure at a glance

```text
app/src/main/java/com/mscope/browser/
├── MainActivity.java          list screen
├── DetailActivity.java        detail screen
├── WebActivity.java           web fallback
├── ModelApi.java              API + degradation
├── ModelItem / ModelFile / Format / Ui
├── core/            ★ pure logic, no Android deps, unit tested
├── agent/           agent: protocol, tools, shell, workspace, search, preview
├── llama/           engine, bridge declarations, chat screen, history
└── local/           download centre, foreground service, index, model library
```

Before changing anything, read [Module Deep Dive](MODULES.md); every class there documents
"what problem it solves / how / where it is easy to get wrong".

## 4. Changing a line of code

A real request as the example: **raise the context ceiling from 32768 to 65536**.

### 4.1 Find every related place

```bash
grep -rn "32768" app/src/main/java app/src/main/res
```

You will find:

| Location | Role |
|---|---|
| `Validators.CTX_MAX` | the clamp for sampling parameters |
| `MemoryBudget.STEPS` | candidate list for the picker |
| input validation in `ChatActivity` | now routed through Validators |
| `p_ctx_tip` in `strings.xml` | hint text |

### 4.2 Change

```java
public static final int CTX_MAX = 65536;

private static final int[] STEPS = {512, ..., 32768, 49152, 65536};
```

### 4.3 Add a test

```java
@Test
public void normalize_acceptsLargeCtx() {
    Validators.Sampling s = new Validators.Sampling(8192, 0.7f, 0.9f, 40, 40000);
    Validators.normalize(s);
    assertEquals(40000, s.nCtx);
}
```

### 4.4 Verify

```bash
./gradlew testDebugUnitTest   # logic is right
./gradlew assembleDebug       # it compiles
# on device: type 65536 into the parameter dialog, see whether it gets clamped back and whether the model loads
```

### 4.5 Update the docs

The memory table in `docs/BENCHMARKS.md` needs a 65536 row (with "not recommended on a phone").

## 5. Adding a tool

Say you want to add `time`: return the current phone time so the model knows what day it is —
exactly the information it lacks when answering "what's in the news".

### 5.1 Register the name

```java
// AgentTools.names()
return new HashSet<>(Arrays.asList(
        "shell", "web_search", "fetch_url", "time",     // ← new
        "read_file", "write_file", "list_files", "delete_file", "open_preview"));
```

### 5.2 Implement it

```java
case "time": {
    java.text.SimpleDateFormat f =
            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss EEEE", java.util.Locale.CHINA);
    return "Now: " + f.format(new java.util.Date());
}
```

Read-only, so it does not belong in `risky()`.

### 5.3 Mention it in the prompt

```java
// AgentTools.describe()
sb.append("- time: get the current date and time. Parameters: {} or none\n");
```

### 5.4 Add a test

The `ToolCall` parser needs no change (the format is generic), but do test that `time` is not
mistaken for plain prose:

```java
@Test
public void parsesTimeCall() {
    String s = "<function name=\"time\"><param name=\"\"></param></function>";
    assertEquals(1, ToolCall.parse(s, AgentTools.names()).size());
}
```

### 5.5 Verify

Ask on device: "what time is it?" — you should see a `time` tool card, then an answer grounded
in the real clock.

## 6. Debugging techniques

### 6.1 Logs

```bash
adb logcat -s LlamaEngine LlamaBridge DownloadCenter AgentTools ToolCall ChatActivity
```

Every key class has its own TAG, so you can filter individually.

### 6.2 Reading native errors

```java
LlamaBridge.nativeLastError()   // shown directly in the UI when loading fails
```

Common values: `backendCount=0` (the `.so` files were not extracted), `failed to load model`
(corrupt file or unsupported architecture).

### 6.3 Swapping code without reinstalling

Android Studio's Apply Changes works for pure Java edits; any C++ change requires a reinstall
because native has to be rebuilt.

### 6.4 Checking whether the `.so` files landed

```bash
adb shell run-as com.mscope.browser ls -l lib/
adb shell ls -l /data/app/*/com.mscope.browser-*/lib/arm64/
```

You should see 7 `libggml-cpu-android_*.so` files.

### 6.5 Simulating a low-memory device

```bash
# Developer options → background process limit; editing meminfo is not feasible, a cheap real device is more honest
adb shell settings put global activity_manager_constants max_cached_processes=2
```

## 7. Packaging and releasing

### 7.1 Building a release locally

```bash
# generate a keystore (once, then guard it with your life)
keytool -genkeypair -v -keystore release.keystore -alias mscope \
        -keyalg RSA -keysize 2048 -validity 10000

# write keystore.properties
cat > keystore.properties <<'PROPS'
storeFile=/absolute/path/to/release.keystore
storePassword=******
keyAlias=mscope
keyPassword=******
PROPS

./gradlew assembleRelease
# output: app/build/outputs/apk/release/app-release.apk
```

### 7.2 Releasing through CI

```bash
# 1. check the version
grep versionCode app/build.gradle

# 2. tag it (must start with v; CI only recognises that)
git tag v2.7.0
git push origin v2.6.0
```

CI does: unit tests -> Lint -> build debug + release -> verify signature and ABI -> create the
Release with the APK attached.

**Prerequisite**: the four signing secrets must be configured, otherwise a tag build fails
outright. That is intentional.

### 7.3 Version numbering

| Field | Rule |
|---|---|
| `versionCode` | monotonically increasing integer, +1 per release |
| `versionName` | semantic: patch for bug fixes, minor for features, major for new module layers |

## 8. Sending a PR

1. Fork / create a branch (never push straight to main);
2. change code + tests + docs (all three together);
3. run `./gradlew testDebugUnitTest lintDebug assembleDebug` locally;
4. write *why* in the commit message, not *what* -- the diff already says what;
5. open the PR with the verification method in the description (device screenshot / log).

### 8.1 Commit message style

```text
fix: fix the lingering "always downloading" task left by concurrent downloads

start() dispatched before registering, so a single-threaded pool could finish run()
before the first half executed, remove the task, and then have it put back. Now it
registers with putIfAbsent before dispatch.
```

Prefixes: `feat` / `fix` / `docs` / `chore` / `ci` / `perf`.

## 9. Common build problems

| Error | Cause | Handling |
|---|---|---|
| `NDK not configured` | the pinned NDK is not installed | `sdkmanager "ndk;27.2.12479018"` |
| `CMake 3.22.1 not found` | same | `sdkmanager "cmake;3.22.1"` |
| `Failed to resolve: androidx...` | network / mirror issue | configure repositories or a proxy in `settings.gradle` |
| `Unsupported class file major version` | compiled with JDK 21+ targeting bytecode AGP rejects | switch to JDK 17 |
| `Task :app:buildCMakeDebug FAILED` mentioning `vld1q_f16` | `GGML_LLAMAFILE` was not turned off | check `CMakeLists.txt` |
| Compiles, but `UnsatisfiedLinkError` at runtime | the ABI filter dropped the device's architecture | check `abiFilters` against the device ABI |

## 10. Code map (indexed by "what you want to change")

| Want to change | Go here |
|---|---|
| List sorting / facets | `MainActivity` + `ModelApi.facetLabel` |
| Card styling | `res/layout/item_model.xml` + `ModelAdapter` |
| Download behaviour | `DownloadCenter` + `DownloadService` |
| Chat bubbles | `res/layout/item_chat_*.xml` + `ChatActivity.Adapter` |
| Tool protocol | `ToolCall` |
| Tool implementations | `AgentTools` |
| Prompts | `PromptTemplate` |
| Inference parameters | `LlamaEngine.Params` + `Validators` |
| Native behaviour | `llama_bridge.cpp` |
| Default context | `MemoryBudget` + `ChatActivity.DEFAULT_CTX` |
| Storage decisions | `StorageGuard` |
| File operations | `FsKit` / `Workspace` |
| Colours / theme | `res/values/colors.xml` / `themes.xml` |
| CI | `.github/workflows/android.yml` |
| Strings (EN / ZH) | `res/values/strings.xml` / `res/values-zh/strings.xml` |

---

Still unsure where to start? Open [Module Deep Dive](MODULES.md), find the class, and read it --
each one begins with the problem it exists to solve.
