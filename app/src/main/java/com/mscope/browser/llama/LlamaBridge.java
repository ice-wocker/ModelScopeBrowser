package com.mscope.browser.llama;

/**
 * llama.cpp 的 JNI 接口（对应 cpp/llama_bridge.cpp）。
 * 只做薄封装，线程安全由 {@link LlamaEngine} 保证。
 */
public final class LlamaBridge {

    static {
        System.loadLibrary("mscope_llama");
    }

    private LlamaBridge() {
    }

    /** 每次生成一个 token 时回调（在主调线程上）。 */
    public interface TokenCallback {
        void onToken(String piece);
    }

    /**
     * 加载 GGUF 模型。
     *
     * @return 会话句柄；0 表示失败
     */
    public static native long nativeInit(String modelPath, int nCtx, int nThreads);

    public static native void nativeFree(long handle);

    /** 清空 KV 缓存（开始新对话时调用）。 */
    public static native void nativeReset(long handle);

    /** 请求中断当前生成。 */
    public static native void nativeCancel(long handle);

    public static native int nativeContextSize(long handle);

    /** 套用模型自带的对话模板，返回实际送入模型的 prompt（便于调试）。 */
    public static native String nativeBuildPrompt(long handle, String[] roles, String[] contents);

    /**
     * 流式生成。
     *
     * @param maxTokens 最多新生成多少 token
     * @param temp      温度，&lt;=0 表示贪婪解码
     * @return 完整回复文本；失败返回空串
     */
    public static native String nativeGenerate(long handle, String[] roles, String[] contents,
                                               int maxTokens, float temp, float topP, int topK,
                                               int seed, TokenCallback callback);

    public static native String nativeVersion();
}