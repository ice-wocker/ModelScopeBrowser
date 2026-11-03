package com.mscope.browser.core;

import java.util.List;
import java.util.Locale;

/**
 * 端侧推理基准的「尺子」：固定一组 prompt，比速度，不看内容对不对。
 *
 * <p>为什么需要它：用户问「我这个模型能跑吗」，唯一靠谱的答案是自己测一遍。
 * 参数表、参数量、量化格式都只是参考，真正决定体验的是这台机器上
 * 预填充（读输入）和解码（吐字）各自多少 tok/s。
 *
 * <p>这里只定义测什么与怎么判：短 prompt 测冷启动开销，长 prompt 测吞吐，
 * 固定输出上限保证可比。执行在 {@code LlamaEngine}。
 */
public final class Bench {

    private Bench() {
    }

    /** 一个测试用例。 */
    public static final class Case {
        public final String name;
        public final String prompt;
        public final int maxTokens;

        public Case(String name, String prompt, int maxTokens) {
            this.name = name;
            this.prompt = prompt;
            this.maxTokens = maxTokens;
        }
    }

    /** 单次测量结果。 */
    public static final class Result {
        public final String name;
        public final long prefillMs;
        public final long decodeMs;
        public final int tokens;
        public final double tps;

        public Result(String name, long prefillMs, long decodeMs, int tokens, double tps) {
            this.name = name;
            this.prefillMs = prefillMs;
            this.decodeMs = decodeMs;
            this.tokens = tokens;
            this.tps = tps;
        }

        public String line(boolean zh) {
            return String.format(Locale.ROOT, "%s: %s %.1f tok/s, %s %d ms, %d tokens",
                    name, zh ? "解码" : "decode", tps, zh ? "预填充" : "prefill", prefillMs, tokens);
        }
    }

    /**
     * 固定用例。输出上限故意小：基准不是测「能写多长」，是测「每秒多少」。
     * 上限一大，慢机器上一轮就要几分钟，用户不会等。
     */
    public static List<Case> cases(boolean zh) {
        List<Case> out = new java.util.ArrayList<>();
        out.add(new Case(zh ? "短输入" : "short",
                zh ? "用一句话解释什么是内存。"
                   : "Explain what memory is in one sentence.", 64));
        StringBuilder sb = new StringBuilder();
        sb.append(zh ? "下面是待总结的内容：\n" : "Summarise the following:\n");
        for (int i = 0; i < 40; i++) {
            sb.append(zh
                    ? "第 " + (i + 1) + " 行：手机端本地推理需要权衡内存、速度与上下文长度。\n"
                    : "Line " + (i + 1) + ": on-device inference trades memory against speed and context.\n");
        }
        sb.append(zh ? "用两句话总结。" : "Summarise in two sentences.");
        out.add(new Case(zh ? "长输入" : "long", sb.toString(), 96));
        return out;
    }

    /** 综合评分：以解码速度为主（用户感知最强），预填充次之。 */
    public static double score(List<Result> results) {
        if (results == null || results.isEmpty()) return 0;
        double tps = 0;
        double prefill = 0;
        int n = 0;
        for (Result r : results) {
            tps += r.tps;
            if (r.prefillMs > 0) prefill += r.tokens * 1000.0 / r.prefillMs;
            n++;
        }
        if (n == 0) return 0;
        return tps / n * 0.8 + prefill / n * 0.2;
    }

    /** 给用户的一句话结论。 */
    public static String verdict(List<Result> results, boolean zh) {
        double tps = 0;
        int n = 0;
        for (Result r : results) {
            tps += r.tps;
            n++;
        }
        double avg = n == 0 ? 0 : tps / n;
        if (avg <= 0) return zh ? "没测到有效数据。" : "No usable measurements.";
        if (avg >= 20) {
            return zh ? String.format(Locale.ROOT, "%.1f tok/s：很流畅，可以放心用大一点的上下文。", avg)
                      : String.format(Locale.ROOT, "%.1f tok/s: very smooth; you can afford a larger context.", avg);
        }
        if (avg >= 10) {
            return zh ? String.format(Locale.ROOT, "%.1f tok/s：够用，日常问答与短任务没问题。", avg)
                      : String.format(Locale.ROOT, "%.1f tok/s: usable for everyday Q&A and short tasks.", avg);
        }
        if (avg >= 4) {
            return zh ? String.format(Locale.ROOT, "%.1f tok/s：偏慢，建议换更小的量化模型或减少上下文。", avg)
                      : String.format(Locale.ROOT, "%.1f tok/s: slow; try a smaller quant or a shorter context.", avg);
        }
        return zh ? String.format(Locale.ROOT, "%.1f tok/s：这台机器跑这个模型很吃力，建议换 1B 以下的模型。", avg)
                  : String.format(Locale.ROOT, "%.1f tok/s: this model is too heavy for this device; try sub-1B.", avg);
    }
}
