package com.mscope.browser.llama;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.mscope.browser.core.Bench;
import com.mscope.browser.core.KvReplay;
import com.mscope.browser.core.LoopBreaker;
import com.mscope.browser.core.PerfAdvisor;
import com.mscope.browser.core.TextKit;
import com.mscope.browser.i18n.Lang;
import com.mscope.browser.local.LocalModel;

import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * llama.cpp 会话管理：单例、单线程串行生成，避免并发访问同一个 context。
 *
 * 性能相关的几处约定：
 * <ul>
 *   <li>解码线程只跑大核（小核会把每个 token 的延迟拖长），预填充用满核心；</li>
 *   <li>KV 公共前缀由原生层跨轮复用，多轮对话不重算历史；</li>
 *   <li>消息级裁剪：超长会话先丢最早的一轮，保住前缀命中率；</li>
 *   <li>token 回调按 40ms 合并，减少 JNI 与界面重绘开销。</li>
 * </ul>
 */
public class LlamaEngine {

    private static final String TAG = "LlamaEngine";

    /** 流式回调合并间隔：太小会让 UI 与推理抢 CPU，太大影响「逐字」手感。 */
    private static final long UI_FLUSH_MS = 40;

    /**
     * 最近一次实测解速（tok/s），跨会话保存在内存里。
     *
     * <p>不做持久化是有意的：速度跟设备状态、模型、上下文都相关，
     * 隔天复用一次旧数字比不给更糟 —— 冷启动慢、热起来快，是同一台手机上常见的现象。
     */
    private volatile double lastTps = 0;
    /** 是否按实测速度自动调采样参数（用户手动设过温度/输出上限时自动关闭）。 */
    private volatile boolean adaptive = true;

    public static class Params {
        /** 单次回复的最大生成长度。默认给到 4096，长文/代码基本够用。 */
        public int maxTokens = 4096;
        public float temp = 0.7f;
        public float topP = 0.9f;
        public int topK = 40;
        public int seed = -1;
        /**
         * 重复惩罚窗口（0 = 关闭）。
         *
         * <p>本地模型最典型的两种失败都在这里：工具没回结果时复读同一句，
         * 以及被模板里没闭合的标签带着一直重复同一个 token 直到 maxTokens。
         * 关掉它时这两种都会烧满整段输出预算，用户看到的是「卡住」。
         * 128 覆盖最近一两段对话，够打断循环又不会把该重复的括号、列表符号一起罚掉。
         */
        public int repeatLastN = 128;
        /** 重复惩罚系数；1.0 = 不惩罚。 */
        public float repeatPenalty = 1.1f;

        public static Params of(Params other) {
            Params p = new Params();
            if (other != null) {
                p.maxTokens = other.maxTokens;
                p.temp = other.temp;
                p.topP = other.topP;
                p.topK = other.topK;
                p.seed = other.seed;
                p.repeatPenalty = other.repeatPenalty;
                p.repeatLastN = other.repeatLastN;
            }
            return p;
        }
    }

    public interface LoadListener {
        void onLoaded(boolean ok, String message);
    }

    /**
     * 空回调：调用方不关心结果时用它，避免回调路径里到处判空。
     * load() 的每个分支都会回调，少一个判空就是一次崩溃。
     */
    private static final LoadListener NO_LOAD_LISTENER = (ok, message) -> {
    };

    public interface StreamListener {
        void onToken(String piece);

        /**
         * @param prefillMs    首 token 之前耗时（≈ 预填充时间），越小说明前缀复用越有效
         * @param tokensPerSec 解码速度（不含首字时间）
         */
        void onDone(String fullText, int tokens, long elapsedMs, long prefillMs, double tokensPerSec);

        void onError(String message);
    }

    private static volatile LlamaEngine instance;

    public static LlamaEngine get() {
        if (instance == null) {
            synchronized (LlamaEngine.class) {
                if (instance == null) instance = new LlamaEngine();
            }
        }
        return instance;
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private volatile long handle = 0;
    /**
     * 上一轮之后 KV 是否已不再对应「当前可见历史」。
     *
     * <p>为什么需要它：智能体的工具结果只在当轮回灌给模型，不写进历史（否则下一轮会把
     * 上一轮的工具输出再喂一遍，既浪费上下文，又诱导模型重复调用同一个工具）。于是
     * 「本轮请求」是历史的超集但不等于历史，原生层的前缀复用会把这段差额判成
     * 「上一轮输出不在新 prompt 里」，从而每轮都换一次槽位、整段重填。
     *
     * <p>把「工具结果」本轮剔除这件事，正好对应「把 KV 回滚到工具调用之前」：
     * 下一次请求是「历史 + 新的用户输入」，而 KV 里存的正是「历史里已生成的部分」。
     * 所以工具轮结束时给 KV 打一个脏标记，下一次生成只把公共前缀当作有效缓存
     * （{@link #markReplayable()}），不触发换槽位，也不损失正确性。
     * 脏标记与权限位的状态机收在 {@link KvReplay} 里。
     */
    private volatile LocalModel current;
    private volatile boolean loading;
    private volatile boolean generating;
    /** 实际使用的 CPU 后端描述（CPU 型号），加载成功后填充，供界面展示。 */
    private volatile String backend = "";

    private LlamaEngine() {
    }

    public boolean isLoaded() {
        return handle != 0;
    }

    public boolean isLoading() {
        return loading;
    }

    public boolean isGenerating() {
        return generating;
    }

    public LocalModel currentModel() {
        return current;
    }

    /** 当前 CPU 后端描述（CPU 型号）；未加载时返回空串。 */
    public String backendInfo() {
        return backend;
    }

    public int contextSize() {
        long h = handle;
        return h == 0 ? 0 : LlamaBridge.nativeContextSize(h);
    }

    /** 当前 KV 里已缓存的 token 数。 */
    public int cachedTokens() {
        long h = handle;
        return h == 0 ? 0 : LlamaBridge.nativeCachedTokens(h);
    }

    /* ------------------------------------------------------------ 线程数选择 */

    /**
     * 解码是逐 token 的串行过程，用满核心反而会被小核拖慢；
     * 预填充是吞吐型任务，多核并行收益明显。这里按各核最大频率区分大小核。
     *
     * 红线：线程数绝不能超过「本进程真正能用的 CPU 数」。实测（宿主端 3 核）把
     * n_threads 设成 4 时，ggml 的线程屏障自旋会让单次 decode 从 18ms 恶化到
     * 3970ms（约 200 倍），因为线程互相抢占后每次同步都要自旋等到时间片。
     * availableProcessors() 在 cgroup/cpuset 受限环境下会高报，因此还要看
     * /proc/self/status 的 Cpus_allowed_list。
     */
    private static int[] pickThreads() {
        final int reported = Math.max(1, Runtime.getRuntime().availableProcessors());
        final int usable = Math.max(1, Math.min(reported, allowedCpus()));

        int perfCores = 0;
        long maxFreq = 0;
        long[] freqs = new long[usable];
        for (int i = 0; i < usable; i++) {
            long f = readLong("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq");
            freqs[i] = f;
            if (f > maxFreq) maxFreq = f;
        }
        if (maxFreq > 0) {
            for (long f : freqs) {
                if (f == maxFreq) perfCores++;
            }
        }
        if (perfCores <= 0) perfCores = Math.min(4, usable);   // 读不到频率时保守取 4 核

        final int decode = Math.max(1, Math.min(perfCores, Math.min(usable, 6)));
        final int batch = Math.max(decode, Math.min(usable, 8));
        return new int[]{decode, batch};
    }

    /** 本进程被允许运行的 CPU 数量（cpuset 受限时会少于 availableProcessors()）。 */
    private static int allowedCpus() {
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.FileReader("/proc/self/status"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.startsWith("Cpus_allowed_list:")) continue;
                String list = line.substring("Cpus_allowed_list:".length()).trim();
                int n = 0;
                for (String part : list.split(",")) {
                    String p = part.trim();
                    if (p.isEmpty()) continue;
                    int dash = p.indexOf('-');
                    if (dash > 0) {
                        n += Integer.parseInt(p.substring(dash + 1))
                                - Integer.parseInt(p.substring(0, dash)) + 1;
                    } else {
                        n++;
                    }
                }
                if (n > 0) return n;
            }
        } catch (Exception ignored) {
        }
        return Runtime.getRuntime().availableProcessors();
    }

    private static long readLong(String path) {
        try (FileInputStream in = new FileInputStream(path)) {
            byte[] buf = new byte[32];
            int n = in.read(buf);
            if (n <= 0) return 0;
            String s = new String(buf, 0, n).trim();
            return Long.parseLong(s);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 最近一次实测解码速度（tok/s），0 表示还没测过。 */
    public double lastTokensPerSec() {
        return lastTps;
    }

    /** 开关「按实测速度调参数」。 */
    public void setAdaptive(boolean on) {
        adaptive = on;
    }

    public boolean adaptive() {
        return adaptive;
    }

    /**
     * 把用户给的参数收敛成「这台机器现在能跑得动」的参数。
     *
     * <p>为什么要动它：采样参数一旦越界就是静默失效 —— top-p 给 0 会让采样器除零，
     * top-k 给 0 直接崩，输出上限大于上下文则必然被截断。这些都不会弹错误，
     * 只会表现为「模型变笨了」。所以写入引擎前统一过一遍 {@link PerfAdvisor}。
     */
    public Params fit(Params p, int nCtx) {
        Params src = p == null ? new Params() : p;
        if (!adaptive) {
            PerfAdvisor.Sample s = PerfAdvisor.sanitize(
                    new PerfAdvisor.Sample(src.temp, src.topP, src.topK, src.maxTokens,
                            src.repeatPenalty, src.repeatLastN), nCtx, 0);
            Params out = new Params();
            out.temp = s.temp;
            out.topP = s.topP;
            out.topK = s.topK;
            out.maxTokens = s.maxTokens;
            out.repeatPenalty = s.repeatPenalty;
            out.repeatLastN = s.repeatLastN;
            out.seed = src.seed;
            return out;
        }
        PerfAdvisor.Sample s = PerfAdvisor.advise(lastTps, nCtx, src.temp, src.maxTokens);
        Params out = new Params();
        out.temp = s.temp;
        out.topP = s.topP;
        out.topK = s.topK;
        out.maxTokens = s.maxTokens;
        out.repeatPenalty = s.repeatPenalty;
        out.repeatLastN = s.repeatLastN;
        out.seed = src.seed;
        return out;
    }

    /** 给界面看：当前建议参数的一句话解释。 */
    public String advice(boolean zh) {
        PerfAdvisor.Sample s = PerfAdvisor.advise(lastTps, contextSize(), -1, 0);
        return PerfAdvisor.explain(lastTps, s, zh);
    }

    /* ------------------------------------------------------------- 模型生命周期 */

    /**
     * 加载模型（会先卸载当前模型）。nCtx 为上下文窗口，backendDir 为应用 native 库目录。
     *
     * <p>loading 的「检查 + 置位」必须原子完成：界面上「切换模型 → 取消 → 再确认」这类操作
     * 可能连着触发两次 load，两个都读到 false 就会各自往单线程里排一个任务，
     * 结果是后一个任务把前一个刚建好的会话立刻 closeHandle 掉，界面却显示「已加载」。</p>
     */
    public synchronized void load(LocalModel model, int nCtx, String backendDir, LoadListener maybeNull) {
        final LoadListener listener = maybeNull == null ? NO_LOAD_LISTENER : maybeNull;
        if (loading) {
            // 直接返回会让调用方一直停在“加载中”，这里显式告知，便于界面重试
            if (listener != null) {
                ui.post(() -> listener.onLoaded(false, Lang.zh()
                        ? "已有模型正在加载中，请稍候重试"
                        : "Another model is already loading, please retry in a moment"));
            }
            return;
        }
        loading = true;
        final int[] threads = pickThreads();
        worker.execute(() -> {
            try {
                closeHandle();

                // 先确认本机 CPU 是否支持编译时使用的指令集，避免原生崩溃
                String unsupported = null;
                try {
                    unsupported = LlamaBridge.nativeSupported();
                } catch (Throwable t) {
                    unsupported = (Lang.zh() ? "本地推理库加载失败：" : "Failed to load the native inference library: ")
                            + t.getMessage();
                }
                if (unsupported != null && !unsupported.isEmpty()) {
                    loading = false;
                    final String reason = unsupported;
                    ui.post(() -> listener.onLoaded(false, reason));
                    return;
                }

                long h = LlamaBridge.nativeInit(model.localPath, nCtx, threads[0], threads[1],
                        backendDir == null ? "" : backendDir);
                handle = h;
                current = h != 0 ? model : null;
                if (h != 0) {
                    try {
                        backend = LlamaBridge.nativeBackendInfo();
                    } catch (Throwable ignored) {
                        backend = "";
                    }
                }
                loading = false;
                final int decodeT = threads[0];
                final int batchT = threads[1];
                ui.post(() -> listener.onLoaded(h != 0,
                        h != 0
                                ? (Lang.zh()
                                ? "已加载：" + model.displayName() + "（" + decodeT + "/" + batchT + " 线程）"
                                : "Loaded: " + model.displayName() + " (" + decodeT + "/" + batchT + " threads)")
                                : loadFailureMessage()));
            } catch (Throwable t) {
                Log.e(TAG, "加载异常", t);
                loading = false;
                handle = 0;
                current = null;
                ui.post(() -> listener.onLoaded(false,
                        (Lang.zh() ? "加载异常：" : "Load failed: ") + t.getMessage()));
            }
        });
    }

    public void unload() {
        worker.execute(this::closeHandle);
    }


    /** 取原生的具体失败原因；取不到时退回通用文案。 */
    private static String loadFailureMessage() {
        try {
            String e = LlamaBridge.nativeLastError();
            if (e != null && !e.trim().isEmpty()) return e.trim();
        } catch (Throwable ignored) {
        }
        return Lang.zh()
                ? "模型加载失败（文件可能不完整或格式不受支持）"
                : "Model failed to load (the file may be incomplete or in an unsupported format)";
    }

    private void closeHandle() {
        long h = handle;
        handle = 0;
        current = null;
        backend = "";
        kvReplay.invalidate();
        if (h != 0) {
            try {
                LlamaBridge.nativeFree(h);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 清空上下文与 KV 缓存（开始新对话）。 */
    public void reset() {
        final long h = handle;
        if (h == 0) return;
        worker.execute(() -> {
            try {
                LlamaBridge.nativeReset(h);
            } catch (Throwable ignored) {
            }
        });
    }

    public void cancel() {
        final long h = handle;
        if (h == 0) return;
        try {
            LlamaBridge.nativeCancel(h);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 熔断自打断：只能从生成回调线程里调。
     *
     * <p>这里绝对不能走 {@code worker}。worker 是单线程池，此刻正被当前这次
     * {@code nativeGenerate} 占着，往它里面排任务等于排在这场生成自己后面——
     * 等它执行时生成早已结束，cancel 标志却还留着，下一轮 prefill 都不做就返回空串，
     * 表现为「点发送、转一圈、回你一个空回答」。
     *
     * <p>直接调是安全的：{@code nativeCancel} 只取 g_mu、写两个原子标志，
     * 不碰生成线程全程持有的 s->mu，所以不会自锁。</p>
     */
    private void requestCancel(long h) {
        try {
            LlamaBridge.nativeCancel(h);
        } catch (Throwable ignored) {
        }
    }

    /**
     * KV 公共前缀的回放许可。规则见 {@link KvReplay}，这里只做转发。
     *
     * <p>原生层拿不到准确的「上一轮产出有多长」这个信息，所以回放许可不是加速开关，
     * 而是给原生层的一个提示：允许它把这轮产出视为可丢弃。前缀匹配本身仍是逐 token
     * 严格比较，许可失效最多退化成多做一次 prefill。</p>
     */
    private final KvReplay kvReplay = new KvReplay();

    /** 历史被真正改动（编辑重发、删除消息、重新生成、切换会话）时必须调用。 */
    public void invalidateKv() {
        kvReplay.invalidate();
    }

    /** 标记「本轮的工具结果只在当轮可见，所以本轮产出不必进 KV」。 */
    public void markReplayable() {
        kvReplay.markReplayable();
    }

    /* --------------------------------------------------------------- 生成 */

    /**
     * 流式生成。messages 需包含 system（可选）与历史消息，最后一条为用户输入。
     *
     * <p>generating 的检查与置位放在 synchronized 里：两个线程同时发请求时，
     * 各自读到 false 就会一起把任务排进单线程队列，第二个请求白等一轮还会覆盖句柄状态。
     * 注意这里不是把整个生成过程上锁——生成仍在 worker 线程里跑。</p>
     */
    public void generate(List<ChatMessage> messages, Params params, StreamListener listener) {
        final long h = handle;
        if (h == 0) {
            listener.onError(Lang.zh() ? "尚未加载模型" : "No model loaded yet");
            return;
        }
        synchronized (this) {
            if (generating) {
                listener.onError(Lang.zh() ? "正在生成中" : "Already generating");
                return;
            }
            generating = true;
        }

        final List<ChatMessage> input = new ArrayList<>(messages);
        // 采样参数在这里统一收敛一次：越界值不会报错，只会让输出莫名变差
        final Params p = fit(params, contextSize());
        final int seed = p.seed >= 0 ? p.seed : (int) (System.nanoTime() & 0x7fffffff);
        // 回放判定只做一次，且必须在这里取：放到回调里取，第二轮就会把上一轮的许可再消费一遍
        final boolean replay = kvReplay.take();

        worker.execute(() -> {
            final long start = System.currentTimeMillis();
            final int[] count = {0};
            final long[] firstAt = {0};
            try {
                // 消息级裁剪：超长时先丢最早的一轮，避免原生层从头截断导致前缀全废
                final List<ChatMessage> msgs = fitMessages(h, input, p.maxTokens);
                final int n = msgs.size();
                String[] roles = new String[n];
                String[] contents = new String[n];
                for (int i = 0; i < n; i++) {
                    roles[i] = msgs.get(i).role;
                    contents[i] = msgs.get(i).content;
                }

                final StringBuilder buf = new StringBuilder();
                final long[] lastFlush = {start};
                // 复读熔断的状态机：跨 token 累计，所以放在回调外面。
                // 「上次看到的尾巴 / 尾巴长度 / 从什么时候开始没变」三者一比对就知道有没有新内容。
                final String[] seenTail = {""};
                final int[] seenLen = {-1};
                final boolean[] stuck = {false};
                final int[] since = {0};
                final long[] firstSeenAt = {0};
                // 回调里拿不到 nativeGenerate 的返回值（它要等整段生成结束才返回），
                // 所以这里用回调自己累积的文本判复读，不能引用 full。
                final StringBuilder sofar = new StringBuilder();
                // 内容级熔断：字节层面还在涨（有 token 产出），但内容是同一段话反复。
                // 这里按「距上次检查产出了多少字」触发，代价可忽略。
                final int[] sinceCheck = {0};
                final boolean[] contentStuck = {false};
                String full = LlamaBridge.nativeGenerate(h, roles, contents,
                        p.maxTokens, p.temp, p.topP, p.topK, seed,
                        p.repeatLastN, p.repeatPenalty, replay, piece -> {
                            count[0]++;
                            final long now = System.currentTimeMillis();
                            if (firstAt[0] == 0) firstAt[0] = now;
                            buf.append(piece);
                            sofar.append(piece);
                            if (now - lastFlush[0] >= UI_FLUSH_MS) {
                                lastFlush[0] = now;
                                emit(buf, listener);
                            }
                            since[0]++;
                            sinceCheck[0]++;
                            // 每 96 个 token 做一次内容级检查：换着花样重复同一段话，
                            // 「字节是否增长」这条路抓不到，只能看内容本身。
                            if (!stuck[0] && !contentStuck[0] && sinceCheck[0] >= 96) {
                                sinceCheck[0] = 0;
                                if (LoopBreaker.check(sofar).looping) {
                                    contentStuck[0] = true;
                                    stuck[0] = true;
                                    requestCancel(h);
                                }
                            }
                            if (!stuck[0] && since[0] >= LOOP_SAMPLES
                                    && isStuck(sofar, seenTail, seenLen, firstSeenAt, now)) {
                                // 立刻打断：等它自己跑完 maxTokens 才收手，用户要多等几十秒
                                stuck[0] = true;
                                requestCancel(h);
                            }
                        });
                emit(buf, listener);

                final long end = System.currentTimeMillis();
                final int nTokens = count[0];
                final long prefillMs = firstAt[0] > 0 ? firstAt[0] - start : end - start;
                final long decodeMs = firstAt[0] > 0 ? Math.max(1, end - firstAt[0]) : 1;
                final int genTokens = Math.max(0, nTokens - 1);
                final double tps = genTokens > 0 ? genTokens * 1000.0 / decodeMs : 0;
                final String text = full == null ? "" : full;
                // KV 里现在多出了这一轮的产出。它只在这一轮有意义（下一轮的上下文会重建），
                // 先记一笔；上层确认「可以丢」之后，下一轮才允许沿用前缀。
                kvReplay.finished(!text.isEmpty());

                generating = false;
                if (tps > 0) lastTps = lastTps <= 0 ? tps : lastTps * 0.7 + tps * 0.3;
                if (stuck[0]) Log.w(TAG, "检测到复读，已提前收尾");
                ui.post(() -> listener.onDone(text, nTokens, end - start, prefillMs, tps));
            } catch (Throwable t) {
                Log.e(TAG, "生成异常", t);
                // 异常路径下 KV 状态不明，宁可多算一次 prefill，也不能拿错前缀去续写
                kvReplay.invalidate();
                generating = false;
                ui.post(() -> listener.onError(String.valueOf(t.getMessage())));
            }
        });
    }

    /**
     * 跑一次端侧基准（阻塞，调用方应在后台线程）。
     *
     * <p>用固定的两个用例：短输入测冷启动/解码，长输入测预填充。
     * 每轮都走完整的生成路径，所以测出来的数字就是用户实际能感知的速度。
     *
     * @return 每个用例一条结果；模型没加载时返回空列表
     */
    public List<Bench.Result> runBench(boolean zh) {
        final List<Bench.Result> out = new ArrayList<>();
        final long h = handle;
        if (h == 0) return out;
        final int nCtx = contextSize();
        for (Bench.Case c : Bench.cases(zh)) {
            final long[] done = {0};
            final java.util.List<Bench.Result> box = new ArrayList<>();
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            Params p = new Params();
            p.maxTokens = c.maxTokens;
            p.temp = 0.3f;
            p.seed = 42;
            List<ChatMessage> msgs = new ArrayList<>();
            msgs.add(new ChatMessage(ChatMessage.USER, c.prompt));
            generate(msgs, p, new StreamListener() {
                @Override
                public void onToken(String piece) {
                }

                @Override
                public void onDone(String fullText, int tokens, long elapsedMs, long prefillMs, double tokensPerSec) {
                    box.add(new Bench.Result(c.name, prefillMs, Math.max(1, elapsedMs - prefillMs),
                            tokens, tokensPerSec));
                    latch.countDown();
                }

                @Override
                public void onError(String message) {
                    latch.countDown();
                }
            });
            try {
                latch.await(150, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (box.isEmpty()) {
                // 这一档 150 秒没出结果（模型太大 / 设备太慢 / 正在做别的生成）。
                // 不能 continue —— 后面每一档都会再等满 150 秒，而 tools 是单线程池，
                // 卡住的正是所有工具调用。到这儿就该认输返回已有结果。
                break;
            }
            out.addAll(box);
            if (out.get(out.size() - 1).tps > 0) {
                lastTps = out.get(out.size() - 1).tps;
            }
        }
        return out;
    }

    /** 复读判定用的尾巴长度：太短会把正常重复（表格、缩进）当成故障。 */
    private static final int LOOP_TAIL = 96;
    /** 多久没有变化就判定为复读（毫秒）。 */
    private static final long LOOP_IDLE_MS = 6000;
    /** 累计看到「拖尾重复」多少条之后才真的判定（条数阈值与视觉重复无关，纯粹是采样次数）。 */
    private static final int LOOP_SAMPLES = 40;

    /**
     * 复读熔断：模型的输出在 {@link #LOOP_IDLE_MS} 内一个字都没新增，就说明它绕不出来。
     *
     * <p>为什么看「是否还在增长」而不是看「有没有重复」：代码块、表格、列表本来就大量重复，
     * 按重复判会误杀正常回答。而正常情况下每秒至少吐几个 token，好几秒一个字都不涨就是异常。
     *
     * <p>为什么要等 {@link #LOOP_SAMPLES} 个采样：慢机型上两个 token 之间本来就可能隔几百毫秒，
     * 只比较相邻两次会把正常输出判成卡住。
     *
     * <p>状态都用数组传，避免每 token 分配对象；只在生成线程里访问，无需同步。
     */
    private static boolean isStuck(CharSequence sofar, String[] seenTail, int[] seenLen,
                                   long[] firstSeenAt, long now) {
        final String whole = sofar == null ? "" : sofar.toString();
        final String tail = TextKit.tail(whole, LOOP_TAIL);
        // 长度变了、或尾巴变了，都说明还在产出新内容：重置计时
        final boolean grew = whole.length() != seenLen[0] || !tail.equals(seenTail[0]);
        if (grew) {
            seenLen[0] = whole.length();
            seenTail[0] = tail;
            firstSeenAt[0] = now;
            return false;
        }
        return now - firstSeenAt[0] >= LOOP_IDLE_MS;
    }

    /** 把累积的片段合并后抛到界面线程。 */
    private void emit(StringBuilder buf, StreamListener listener) {
        final String chunk = buf.toString();
        buf.setLength(0);
        ui.post(() -> listener.onToken(chunk));
    }

    /* --------------------------------------------------------- 消息级裁剪 */

    /**
     * 保证 prompt 不超上下文：从最早的一轮开始丢。
     * 必须以「消息」为单位丢，而不是在原生层截断 token —— 后者会让公共前缀作废，每轮都要全量重算。
     */
    private static List<ChatMessage> fitMessages(long h, List<ChatMessage> messages, int maxNew) {
        if (messages == null) return new ArrayList<>();
        final List<ChatMessage> work = new ArrayList<>(messages);
        if (work.size() <= 1) return work;

        final int nCtx = LlamaBridge.nativeContextSize(h);
        if (nCtx <= 0) return work;
        int budget = nCtx - maxNew - 32;
        if (budget < 128) budget = Math.max(128, nCtx / 2);

        if (estimateTokens(h, work) <= budget) return work;

        // 每次「丢一条 + 重新分词」在长会话上就是几十次全量 tokenize，一次生成前白烧几百毫秒。
        // 改为：用「每条消息的粗略 token 数」先估一个该丢多少，再二分校验。
        // 估计偏保守（宁可多丢），最终仍以真实分词结果为准。
        int[] costs = new int[work.size()];
        int total = 0;
        for (int i = 0; i < work.size(); i++) {
            costs[i] = TextKit.roughTokens(work.get(i).content) + 8;
            total += costs[i];
        }
        int from = 0;
        while (from < work.size() - 1 && total > budget) {
            total -= costs[from];
            // 用户消息与其回复成对丢弃，避免留下孤立回答。
            // 只在「这一对之后还有别的内容」时成对丢：否则会把最后两条一起丢掉，
            // 连本轮用户输入都没了，模型会去回答一个不存在的提问。
            if (ChatMessage.USER.equals(work.get(from).role) && from + 2 < work.size()
                    && ChatMessage.ASSISTANT.equals(work.get(from + 1).role)) {
                total -= costs[from + 1];
                from += 2;
            } else {
                from += 1;
            }
        }
        if (from >= work.size() - 1) return work;      // 只剩最后一条，不能继续丢

        // 二分：在 [from, size) 里找「能放下且丢得最少」的起点（实时分词，结论精确）。
        List<ChatMessage> candidate = new ArrayList<>(work.subList(from, work.size()));
        if (estimateTokens(h, candidate) <= budget) return candidate;

        List<ChatMessage> best = candidate;
        int lo = from + 1, hi = work.size() - 2;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            List<ChatMessage> slice = new ArrayList<>(work.subList(mid, work.size()));
            if (estimateTokens(h, slice) <= budget) {
                best = slice;
                hi = mid - 1;
            } else {
                lo = mid + 1;
            }
        }
        if (best != candidate && estimateTokens(h, best) <= budget) return best;
        // 连「最后一条」都超预算（用户粘贴了一份巨大的文件）：只留最后一条，
        // 让原生层的 budgetInput 兜底截断 —— 至少模型能看见本轮提问的开头。
        return new ArrayList<>(work.subList(work.size() - 1, work.size()));
    }

    private static int estimateTokens(long h, List<ChatMessage> msgs) {
        final int n = msgs.size();
        String[] roles = new String[n];
        String[] contents = new String[n];
        for (int i = 0; i < n; i++) {
            roles[i] = msgs.get(i).role;
            contents[i] = msgs.get(i).content;
        }
        try {
            String prompt = LlamaBridge.nativeBuildPrompt(h, roles, contents);
            return LlamaBridge.nativeCountTokens(h, prompt);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static String version() {
        try {
            return LlamaBridge.nativeVersion();
        } catch (Throwable t) {
            return Lang.zh() ? "llama.cpp (未加载)" : "llama.cpp (not loaded)";
        }
    }
}