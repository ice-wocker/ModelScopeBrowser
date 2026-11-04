# 开发指南

> 🌐 语言 / Language: 中文 · [English](DEVELOPING.en.md)

从零把这仓库跑起来、改一处代码、加一个功能、出一版包。
假设你会 Java 与 Gradle，但不了解这个项目。

## 1. 环境

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 17 | AGP 8.13 要求；JDK 21 也能编，但 CI 用 17 |
| Android SDK Platform | 36 | `compileSdk` / `targetSdk` |
| Build Tools | 36.0.0 | |
| NDK | 27.2.12479018 | `app/build.gradle` 里锁定 |
| CMake | 3.22.1 | llama.cpp 构建脚本要求 ≥ 3.14 |
| Gradle | 8.14.5 | 由 wrapper 提供，不要改成本地版本 |

### 1.1 安装

Android Studio 从 Ladybug 起自带 SDK Manager。命令行的话：

```bash
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$PATH

sdkmanager "platforms;android-36" "build-tools;36.0.0" \
           "ndk;27.2.12479018" "cmake;3.22.1" \
           "platform-tools"
```

### 1.2 本地配置

```bash
cd ModelScopeBrowser
echo "sdk.dir=$ANDROID_HOME" > local.properties
```

`local.properties` 已被 `.gitignore` 排除，不要提交。

## 2. 第一次构建

```bash
./gradlew assembleDebug
```

首次会经历：

1. 下载 Gradle 发行版（约 130 MB，看网速）；
2. 拉取 llama.cpp（`FetchContent`，约 50 MB）；
3. 编 arm64-v8a 的 7 份 CPU 后端 + armeabi-v7a 的静态库。

全程 20~40 分钟，取决于机器。之后增量构建只要几十秒，除非改了 `cpp/` 下的东西。

### 2.1 加速：用本地 llama.cpp

```bash
git clone --depth 1 --branch b11205 https://github.com/ggerganov/llama.cpp ~/llama.cpp
./gradlew assembleDebug -PllamaCppDir=$HOME/llama.cpp
```

`CMakeLists.txt` 里判断了 `LLAMA_CPP_DIR` 有 `CMakeLists.txt` 就直接 `add_subdirectory`，跳过下载。

### 2.2 只编一个 ABI（更快）

临时改 `app/build.gradle` 的 `abiFilters`：

```gradle
ndk { abiFilters "arm64-v8a" }   // 去掉 armeabi-v7a，省约 1/3 时间
```

注意别提交这个改动——32 位真机还需要它。

## 3. 项目结构速览

```text
app/src/main/java/com/mscope/browser/
├── MainActivity.java          列表页
├── DetailActivity.java        详情页
├── WebActivity.java           网页兜底
├── ModelApi.java              接口 + 降级
├── ModelItem / ModelFile / Format / Ui
├── core/            ★ 纯逻辑，无 Android 依赖，有单测
├── agent/           智能体：协议、工具、shell、工作区、检索、预览
├── llama/           引擎、桥接声明、对话页、历史
└── local/           下载中心、前台服务、索引、模型库
```

改动前先看 [模块深入](MODULES.md)，那里每个类都写了「要解决什么 / 怎么做的 / 容易写错」。

## 4. 改一处代码

拿一个真实需求举例：**让上下文上限从 32768 提到 65536**。

### 4.1 找到所有相关处

```bash
grep -rn "32768" app/src/main/java app/src/main/res
```

会找到：

| 位置 | 作用 |
|---|---|
| `Validators.CTX_MAX` | 参数收敛上限 |
| `MemoryBudget.STEPS` | 候档位列表 |
| `ChatActivity` 的输入校验 | 已改为走 Validators |
| `strings.xml` 的 `p_ctx_tip` | 提示文案 |

### 4.2 改

```java
public static final int CTX_MAX = 65536;

private static final int[] STEPS = {512, ..., 32768, 49152, 65536};
```

### 4.3 补测试

```java
@Test
public void normalize_acceptsLargeCtx() {
    Validators.Sampling s = new Validators.Sampling(8192, 0.7f, 0.9f, 40, 40000);
    Validators.normalize(s);
    assertEquals(40000, s.nCtx);
}
```

### 4.4 验证

```bash
./gradlew testDebugUnitTest   # 逻辑对
./gradlew assembleDebug       # 能编
# 真机：参数弹窗里填 65536，看是否被压回、模型能不能加载
```

### 4.5 更新文档

`docs/BENCHMARKS.md` 的内存表要加一行 65536（并写清「不建议在手机上开」）。

## 5. 加一个新工具

假设要加 `time`：返回手机当前时间，让模型知道「今天几号」——这是它回答「最近新闻」时最缺的信息。

### 5.1 注册名字

```java
// AgentTools.names()
return new HashSet<>(Arrays.asList(
        "shell", "web_search", "fetch_url", "time",     // ← 新增
        "read_file", "write_file", "list_files", "delete_file", "open_preview"));
```

### 5.2 实现

```java
case "time": {
    java.text.SimpleDateFormat f =
            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss EEEE", java.util.Locale.CHINA);
    return "现在时间：" + f.format(new java.util.Date());
}
```

只读操作，不加进 `risky()`。

### 5.3 加进提示词

```java
// AgentTools.describe()
sb.append("- time：获取当前日期与时间。参数 {} 或无参\n");
```

### 5.4 补测试

`ToolCall` 的解析不需要改（格式通用）。但要测「`time` 不会被当普通文本漏掉」：

```java
@Test
public void parsesTimeCall() {
    String s = "<function name=\"time\"><param name=\"\"></param></function>";
    assertEquals(1, ToolCall.parse(s, AgentTools.names()).size());
}
```

### 5.5 验证

真机对话：「现在几点？」—— 应该看到一张 `time` 工具卡片，然后模型基于真实时间回答。

## 6. 调试技巧

### 6.1 日志

```bash
adb logcat -s LlamaEngine LlamaBridge DownloadCenter AgentTools ToolCall ChatActivity
```

每个关键类都有 TAG，方便单独过滤。

### 6.2 看 native 报错

```java
LlamaBridge.nativeLastError()   // 界面加载失败时会直接显示
```

常见值：`backendCount=0`（.so 没释放）、`failed to load model`（文件损坏或架构不支持）。

### 6.3 不重装应用就换代码

Android Studio 的 Apply Changes 对纯 Java 改动有效；改了 C++ 就必须重装（要重新编 native）。

### 6.4 检查 .so 是否落盘

```bash
adb shell run-as com.mscope.browser ls -l lib/
adb shell ls -l /data/app/*/com.mscope.browser-*/lib/arm64/
```

应该看到 7 个 `libggml-cpu-android_*.so`。

### 6.5 模拟低内存设备

```bash
# 开发者选项 → 后台进程限制；或直接改 meminfo 不可行，用低端真机更靠谱
adb shell settings put global activity_manager_constants max_cached_processes=2
```

## 7. 出包与发布

### 7.1 本地出 release 包

```bash
# 生成 keystore（只做一次，之后好好保存）
keytool -genkeypair -v -keystore release.keystore -alias mscope \
        -keyalg RSA -keysize 2048 -validity 10000

# 写 keystore.properties
cat > keystore.properties <<EOF
storeFile=/absolute/path/to/release.keystore
storePassword=******
keyAlias=mscope
keyPassword=******
EOF

./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

### 7.2 走 CI 发布

```bash
# 1. 确认版本号
grep versionCode app/build.gradle

# 2. 打 tag（必须 v 开头，CI 只认这个）
git tag v2.7.0
git push origin v2.6.0
```

CI 会：单测 → Lint → 构建 debug+release → 校验签名与 ABI → 创建 Release 并附 APK。

**前提**：仓库 Secrets 里配好四个签名变量，否则 tag 构建会直接失败（这是刻意的）。

### 7.3 版本号规则

| 位 | 规则 |
|---|---|
| `versionCode` | 单调递增整数，每次发版 +1 |
| `versionName` | 语义化：修 bug 改补丁位，加功能改次版本位，加模块层改主版本位 |

## 8. 提 PR

1. Fork / 新建分支（不要直接推 main）；
2. 改代码 + 改测试 + 改文档（三件一起）；
3. 本地跑 `./gradlew testDebugUnitTest lintDebug assembleDebug`；
4. 提交信息写清「为什么」，不写「改了什么」——后者看 diff 就知道；
5. 开 PR，描述里附上验证方式（真机截图 / 日志）。

### 8.1 提交信息风格

```text
fix: 修复并发下载时任务表残留导致的「永远下载中」

start() 先投递、后登记，单线程池可能在上半段执行前就跑完 run()，
把任务从表里摘掉，随后任务又被塞回去。改为先 putIfAbsent 再投递。
```

前缀：`feat` / `fix` / `docs` / `chore` / `ci` / `perf`。

## 9. 常见编译问题

| 错误 | 原因 | 处理 |
|---|---|---|
| `NDK not configured` | 没装指定版本 NDK | `sdkmanager "ndk;27.2.12479018"` |
| `CMake 3.22.1 not found` | 同上 | `sdkmanager "cmake;3.22.1"` |
| `Failed to resolve: androidx...` | 网络 / 镜像问题 | 配 `settings.gradle` 的仓库或代理 |
| `Unsupported class file major version` | 用了 JDK 21+ 编 AGP 要求的字节码 | 换 JDK 17 |
| `Task :app:buildCMakeDebug FAILED` 且报 `vld1q_f16` | `GGML_LLAMAFILE` 没关 | 检查 `CMakeLists.txt` |
| 编译通过但运行时 `UnsatisfiedLinkError` | ABI 过滤掉了真机架构 | 检查 `abiFilters` 与真机 ABI |

## 10. 代码地图（按「想改什么」索引）

| 想改 | 去这里 |
|---|---|
| 列表的排序 / 筛选项 | `MainActivity` + `ModelApi.facetLabel` |
| 卡片样式 | `res/layout/item_model.xml` + `ModelAdapter` |
| 下载行为 | `DownloadCenter` + `DownloadService` |
| 对话气泡 | `res/layout/item_chat_*.xml` + `ChatActivity.Adapter` |
| 工具协议 | `ToolCall` |
| 工具实现 | `AgentTools` |
| 提示词 | `PromptTemplate` |
| 推理参数 | `LlamaEngine.Params` + `Validators` |
| native 行为 | `llama_bridge.cpp` |
| 上下文默认值 | `MemoryBudget` + `ChatActivity.DEFAULT_CTX` |
| 存储判定 | `StorageGuard` |
| 文件操作 | `FsKit` / `Workspace` |
| 颜色 / 主题 | `res/values/colors.xml` / `themes.xml` |
| CI | `.github/workflows/android.yml` |

---

看完这一页还是不知道从哪下手？去 [模块深入](MODULES.md) 找到对应的类读一遍，
每个类开头都写了它解决的问题。
