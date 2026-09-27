package com.mscope.browser.llama;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.mscope.browser.local.LocalModel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * llama.cpp 会话管理：单例、单线程串行生成，避免并发访问同一个 context。
 */
public class LlamaEngine {

    private static final String TAG = "LlamaEngine";

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

        void onDone(String fullText, int tokens, long elapsedMs);

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
        final int threads = Math.max(2, Math.min(6, Runtime.getRuntime().availableProcessors()));
        worker.execute(() -> {
            try {
                closeHandle();
                long h = LlamaBridge.nativeInit(model.localPath, nCtx, threads);
                handle = h;
                current = h != 0 ? model : null;
                loading = false;
                ui.post(() -> listener.onLoaded(h != 0,
                        h != 0 ? "已加载：" + model.displayName() : "模型加载失败（文件可能不完整或格式不受支持）"));
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

    /** 清空上下文（开始新对话）。 */
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

        final List<ChatMessage> msgs = new ArrayList<>(messages);
        final Params p = params == null ? new Params() : params;
        final int seed = p.seed >= 0 ? p.seed : (int) (System.nanoTime() & 0x7fffffff);

        worker.execute(() -> {
            final long start = System.currentTimeMillis();
            final int[] count = {0};
            try {
                String[] roles = new String[msgs.size()];
                String[] contents = new String[msgs.size()];
                for (int i = 0; i < msgs.size(); i++) {
                    roles[i] = msgs.get(i).role;
                    contents[i] = msgs.get(i).content;
                }
                String full = LlamaBridge.nativeGenerate(h, roles, contents,
                        p.maxTokens, p.temp, p.topP, p.topK, seed, piece -> {
                            count[0]++;
                            ui.post(() -> listener.onToken(piece));
                        });
                long elapsed = System.currentTimeMillis() - start;
                generating = false;
                ui.post(() -> listener.onDone(full == null ? "" : full, count[0], elapsed));
            } catch (Throwable t) {
                Log.e(TAG, "生成异常", t);
                generating = false;
                ui.post(() -> listener.onError(String.valueOf(t.getMessage())));
            }
        });
    }

    public static String version() {
        try {
            return LlamaBridge.nativeVersion();
        } catch (Throwable t) {
            return "llama.cpp (未加载)";
        }
    }
}