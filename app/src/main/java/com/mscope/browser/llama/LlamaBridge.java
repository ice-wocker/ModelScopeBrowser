package com.mscope.browser.llama;

/** llama.cpp 的 native 接口声明，实现在 app/src/main/cpp/llama_bridge.cpp。 */
public final class LlamaBridge {

    static {
        System.loadLibrary("mscope_llama");
    }

    private LlamaBridge() {
    }

    /** 流式回调：每个 token 片段都会调用一次。 */
    public interface TokenCallback {
        void onToken(String piece);
    }

    /** 本机 CPU 是否满足编译所用指令集；空串表示可用，否则返回不可用原因。 */
    public static native String nativeSupported();

    /**
     * 加载模型。
     *
     * @param nThreads      解码线程数（只跑大核，降低每 token 延迟）
     * @param nThreadsBatch 预填充线程数（吞吐型任务，可用满核心）
     */
    public static native long nativeInit(String modelPath, int nCtx, int nThreads, int nThreadsBatch);

    public static native void nativeFree(long handle);

    /** 清空 KV 与上下文缓存（开始新对话）。 */
    public static native void nativeReset(long handle);

    public static native void nativeCancel(long handle);

    public static native int nativeContextSize(long handle);

    /** 当前 KV 中缓存的 token 数。 */
    public static native int nativeCachedTokens(long handle);

    /** 统计文本的分词长度。 */
    public static native int nativeCountTokens(long handle, String text);

    public static native String nativeBuildPrompt(long handle, String[] roles, String[] contents);

    /**
     * 流式生成。内部会复用上一轮的 KV 公共前缀，只解码新增 token。
     *
     * @return 完整回复文本；失败返回空串
     */
    public static native String nativeGenerate(long handle, String[] roles, String[] contents,
                                               int maxTokens, float temp, float topP, int topK,
                                               int seed, TokenCallback callback);

    public static native String nativeVersion();
}