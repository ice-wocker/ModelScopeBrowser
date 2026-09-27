package com.mscope.browser.llama;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

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

    public static class Params {
        public int maxTokens = 256;
        public float temp = 0.7f;
        public float topP = 0.9f;
        public int topK = 40;
        public int seed = -1;
    }

    public interface LoadListener {
        void onLoaded(boolean ok, String message);
    }

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
    private volatile LocalModel current;
    private volatile boolean loading;
    private volatile boolean generating;

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

    /* ------------------------------------------------------------- 模型生命周期 */

    /** 加载模型（会先卸载当前模型）。nCtx 为上下文窗口。 */
    public void load(Context ctx, LocalModel model, int nCtx, LoadListener listener) {
        if (loading) {
            // 直接返回会让调用方一直停在“加载中”，这里显式告知，便于界面重试
            if (listener != null) {
                ui.post(() -> listener.onLoaded(false, "已有模型正在加载中，请稍候重试"));
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
                    unsupported = "本地推理库加载失败：" + t.getMessage();
                }
                if (unsupported != null && !unsupported.isEmpty()) {
                    loading = false;
                    final String reason = unsupported;
                    ui.post(() -> listener.onLoaded(false, reason));
                    return;
                }

                long h = LlamaBridge.nativeInit(model.localPath, nCtx, threads[0], threads[1]);
                handle = h;
                current = h != 0 ? model : null;
                loading = false;
                final int decodeT = threads[0];
                final int batchT = threads[1];
                ui.post(() -> listener.onLoaded(h != 0,
                        h != 0
                                ? "已加载：" + model.displayName() + "（" + decodeT + "/" + batchT + " 线程）"
                                : "模型加载失败（文件可能不完整或格式不受支持）"));
            } catch (Throwable t) {
                Log.e(TAG, "加载异常", t);
                loading = false;
                handle = 0;
                current = null;
                ui.post(() -> listener.onLoaded(false, "加载异常：" + t.getMessage()));
            }
        });
    }

    public void unload() {
        worker.execute(this::closeHandle);
    }

    private void closeHandle() {
        long h = handle;
        handle = 0;
        current = null;
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

    /* --------------------------------------------------------------- 生成 */

    /** 流式生成。messages 需包含 system（可选）与历史消息，最后一条为用户输入。 */
    public void generate(List<ChatMessage> messages, Params params, StreamListener listener) {
        final long h = handle;
        if (h == 0) {
            listener.onError("尚未加载模型");
            return;
        }
        if (generating) {
            listener.onError("正在生成中");
            return;
        }
        generating = true;

        final List<ChatMessage> input = new ArrayList<>(messages);
        final Params p = params == null ? new Params() : params;
        final int seed = p.seed >= 0 ? p.seed : (int) (System.nanoTime() & 0x7fffffff);

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
                String full = LlamaBridge.nativeGenerate(h, roles, contents,
                        p.maxTokens, p.temp, p.topP, p.topK, seed, piece -> {
                            count[0]++;
                            final long now = System.currentTimeMillis();
                            if (firstAt[0] == 0) firstAt[0] = now;
                            buf.append(piece);
                            if (now - lastFlush[0] >= UI_FLUSH_MS) {
                                lastFlush[0] = now;
                                emit(buf, listener);
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

                generating = false;
                ui.post(() -> listener.onDone(text, nTokens, end - start, prefillMs, tps));
            } catch (Throwable t) {
                Log.e(TAG, "生成异常", t);
                generating = false;
                ui.post(() -> listener.onError(String.valueOf(t.getMessage())));
            }
        });
    }

    /** 把累积的片段合并后抛到界面线程。 */
    private void emit(StringBuilder buf, StreamListener listener) {
        if (buf.length() == 0) return;
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
        List<ChatMessage> work = new ArrayList<>(messages);
        if (work.size() <= 1) return work;

        final int nCtx = LlamaBridge.nativeContextSize(h);
        if (nCtx <= 0) return work;
        int budget = nCtx - maxNew - 32;
        if (budget < 128) budget = Math.max(128, nCtx / 2);

        if (estimateTokens(h, work) <= budget) return work;

        while (work.size() > 1) {
            int idx = -1;
            for (int i = 0; i < work.size() - 1; i++) {        // 保留最后一条（本轮用户输入）
                if (!ChatMessage.SYSTEM.equals(work.get(i).role)) { idx = i; break; }
            }
            if (idx < 0) break;
            final boolean wasUser = ChatMessage.USER.equals(work.get(idx).role);
            work.remove(idx);
            // 用户消息与其回复成对丢弃，避免留下孤立回答
            if (wasUser && idx < work.size() - 1
                    && ChatMessage.ASSISTANT.equals(work.get(idx).role)) {
                work.remove(idx);
            }
            if (estimateTokens(h, work) <= budget) break;
        }
        return work;
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
            return "llama.cpp (未加载)";
        }
    }
}