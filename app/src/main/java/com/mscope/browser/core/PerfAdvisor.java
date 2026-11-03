package com.mscope.browser.core;

import com.mscope.browser.i18n.Lang;

/**
 * 从实测的 tok/s 反推采样参数。
 *
 * <p>手机端小模型有个绕不开的现实：它比人慢，而且慢得不均匀。
 * 同一台机器上，速度会随温度、后台进程、上下文长度来回横跳，
 * 而采样参数是写死的 —— 于是要么在快的时候保守，要么在慢的时候让用户干等。
 *
 * <p>这里做一件很朴素的事：按实测速度选采样强度。
 * <ul>
 *   <li>慢（&lt;4 tok/s）：温度降一点、输出上限压一点，先保证能在可接受时间内说完；</li>
 *   <li>快（&gt;12 tok/s）：允许更长的输出与更高的多样性，同样时间内能多看几行；</li>
 *   <li>全程兜底：top-k ≥ 1、top-p 落在 (0,1]、温度非负 —— 这些值一旦越界，
 *       采样器会静默退化成贪心或直接除零，输出看着「怪」但没人报错。</li>
 * </ul>
 *
 * <p>纯函数，便于单测；真正调用在 {@code LlamaEngine} 里，且只在用户没手动覆盖时生效。
 */
public final class PerfAdvisor {

    private PerfAdvisor() {
    }

    /** 采样参数（与 {@code LlamaEngine.Params} 字段一一对应）。 */
    public static final class Sample {
        public float temp;
        public float topP;
        public int topK;
        public int maxTokens;
        public float repeatPenalty;
        public int repeatLastN;

        public Sample(float temp, float topP, int topK, int maxTokens,
                      float repeatPenalty, int repeatLastN) {
            this.temp = temp;
            this.topP = topP;
            this.topK = topK;
            this.maxTokens = maxTokens;
            this.repeatPenalty = repeatPenalty;
            this.repeatLastN = repeatLastN;
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT,
                    "temp=%.2f topP=%.2f topK=%d maxTokens=%d rep=%.2f/repN=%d",
                    temp, topP, topK, maxTokens, repeatPenalty, repeatLastN);
        }
    }

    /** 档位：从实测速度推出来的建议。 */
    public static String tier(double tps) {
        if (tps <= 0) return "unknown";
        if (tps < 4) return "slow";
        if (tps < 8) return "mid";
        if (tps < 16) return "fast";
        return "very-fast";
    }

    /**
     * 给建议参数。
     *
     * @param tps        实测解码速度（tok/s）；&lt;=0 表示还没测过，返回默认值
     * @param nCtx       上下文窗口
     * @param userTemp   用户显式设定的温度；&lt;0 表示「跟随建议」
     * @param userMaxTok 用户显式的输出上限；&lt;=0 表示「跟随建议」
     */
    public static Sample advise(double tps, int nCtx, float userTemp, int userMaxTok) {
        // 默认值 = 当前 App 的出厂值，保证「没测过」时行为不变
        float temp = 0.7f;
        float topP = 0.9f;
        int topK = 40;
        int maxTok = 1024;
        float rep = 1.08f;
        int repN = 128;

        String tier = tier(tps);
        switch (tier) {
            case "slow":
                // 慢机器：优先把话说完。温度低一点更少跑偏，输出短一点更早结束。
                temp = 0.55f;
                topP = 0.85f;
                topK = 30;
                maxTok = 640;
                rep = 1.10f;
                break;
            case "mid":
                temp = 0.65f;
                topP = 0.88f;
                topK = 36;
                maxTok = 960;
                break;
            case "fast":
                maxTok = 1536;
                break;
            case "very-fast":
                temp = 0.75f;
                topP = 0.92f;
                topK = 50;
                maxTok = 2048;
                break;
            default:
                maxTok = 1024;
        }

        if (userTemp >= 0) temp = userTemp;
        if (userMaxTok > 0) maxTok = userMaxTok;

        // 输出上限不能顶到窗口：还要留给输入与下一轮的工具结果。
        int room = nCtx > 0 ? Math.max(128, (int) (nCtx * 0.5)) : 2048;
        if (maxTok > room) maxTok = room;

        return new Sample(clampTemp(temp), clampP(topP), Math.max(1, topK),
                Math.max(16, maxTok), clampRep(rep), Math.max(16, repN));
    }

    private static float clampTemp(float t) {
        if (Float.isNaN(t) || t < 0f) return 0f;
        return Math.min(2f, t);
    }

    private static float clampP(float p) {
        if (Float.isNaN(p) || p <= 0f) return 1f;   // 0 会让 top-p 采样直接除零
        return Math.min(1f, p);
    }

    private static float clampRep(float r) {
        if (Float.isNaN(r) || r < 1f) return 1f;
        return Math.min(1.5f, r);
    }

    /** 采样参数收敛（写入引擎前的最后一道闸）。tps 传 0 表示「不按速度调，只做合法化」。 */
    public static Sample sanitize(Sample s, int nCtx, double tps) {
        if (s == null) return advise(tps, nCtx, -1, 0);
        return advise(tps, nCtx, s.temp, s.maxTokens);
    }

    /** 给界面看的解释：为什么这一轮换了参数。 */
    public static String explain(double tps, Sample s, boolean zh) {
        String tier = tier(tps);
        String speed = tps > 0 ? String.format(java.util.Locale.ROOT, "%.1f tok/s", tps) : (zh ? "未测速" : "not measured");
        switch (tier) {
            case "slow":
                return zh
                        ? speed + "：偏慢，已调低温度与输出上限，先保证能在可接受时间内答完。"
                        : speed + ": slow device — lower temperature and shorter output to finish in time.";
            case "fast":
            case "very-fast":
                return zh
                        ? speed + "：速度充足，已放宽输出上限与采样多样性。"
                        : speed + ": plenty of headroom — longer output and wider sampling.";
            case "mid":
                return zh ? speed + "：中等速度，使用平衡参数。" : speed + ": balanced parameters.";
            default:
                return Lang.zh() ? "尚无实测速度，使用默认参数。" : "No measured speed yet; using defaults.";
        }
    }
}
