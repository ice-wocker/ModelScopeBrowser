package com.mscope.browser.core;

import com.mscope.browser.i18n.Lang;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 智能体单轮的步数与时间预算。
 *
 * <p>以前 {@code MAX_ROUNDS} 是写死的 8：模型读完文件想改一下、再读回来核对，
 * 光「读→改→验」就要 3 步，稍微复杂的任务必然撞上限，用户看到的是
 * 「已达到单轮工具调用上限」，而事情只做了一半。8 这个数字不是安全边界，
 * 只是当初随手写的。
 *
 * <p>真正的安全边界应该是三条，而不是一个总步数：
 * <ul>
 *   <li>步数：按难度给到 32~200，够跑完一个多文件任务；</li>
 *   <li>墙钟时间：再多的步数也不该让用户盯着一分钟没动静；</li>
 *   <li>上下文：每步都会往历史里塞工具结果，超出窗口就无意义了。</li>
 * </ul>
 * 任何一条到顶就体面收尾，并告知原因 —— 而不是静默停在半路。
 */
public final class Rounds {

    private Rounds() {
    }

    public enum Effort {
        QUICK(32, 3 * 60_000L),
        NORMAL(64, 8 * 60_000L),
        DEEP(128, 20 * 60_000L),
        EXTREME(200, 45 * 60_000L);

        public final int rounds;
        public final long budgetMs;

        Effort(int rounds, long budgetMs) {
            this.rounds = rounds;
            this.budgetMs = budgetMs;
        }

        public String label() {
            boolean zh = Lang.zh();
            switch (this) {
                case QUICK:
                    return zh ? "快速" : "Quick";
                case DEEP:
                    return zh ? "深度" : "Deep";
                case EXTREME:
                    return zh ? "极限" : "Extreme";
                default:
                    return zh ? "标准" : "Standard";
            }
        }
    }

    /** 停下来的原因，用来决定收尾提示的措辞。 */
    public enum Stop {
        NONE,
        ROUNDS,
        TIME,
        CONTEXT
    }

    /** 单轮预算快照：轮数上限 + 起始时刻 + 墙钟上限。 */
    public static final class Budget {
        public final Effort effort;
        public final int rounds;
        public final long startedAt;
        public final long limitMs;

        public Budget(Effort effort, long nowMs) {
            this.effort = effort == null ? Effort.NORMAL : effort;
            this.rounds = this.effort.rounds;
            this.startedAt = nowMs;
            this.limitMs = this.effort.budgetMs;
        }

        public long elapsed(long nowMs) {
            return Math.max(0, nowMs - startedAt);
        }

        public long remainingMs(long nowMs) {
            return Math.max(0, limitMs - elapsed(nowMs));
        }

        public boolean expired(long nowMs) {
            return elapsed(nowMs) >= limitMs;
        }
    }

    /**
     * 综合判断该不该停。
     *
     * @param round     已经跑完的步数（0 基）
     * @param budget    本轮预算
     * @param nowMs     当前时刻
     * @param ctxUse    上下文占用比例（0~1），未知时传 0
     */
    public static Stop stopReason(int round, Budget budget, long nowMs, double ctxUse) {
        if (budget == null) return Stop.NONE;
        if (round + 1 >= budget.rounds) return Stop.ROUNDS;
        if (budget.expired(nowMs)) return Stop.TIME;
        if (ctxUse >= 0.95) return Stop.CONTEXT;
        return Stop.NONE;
    }

    /** 收尾提示：告诉用户为什么停、停在哪儿，而不是只丢一句「已达上限」。 */
    public static String stopMessage(Stop why, Budget budget, int round, long nowMs) {
        boolean zh = Lang.zh();
        int used = round + 1;
        switch (why) {
            case ROUNDS:
                return zh
                        ? "已用完本轮步数预算（" + used + "/" + budget.rounds + " 步，"
                        + budget.effort.label() + "）。可以回复「继续」接着做，或把目标拆小一些。"
                        : "Step budget for this turn is used up (" + used + "/" + budget.rounds
                        + " steps, " + budget.effort.label()
                        + "). Say \"continue\" to keep going, or split the goal into smaller parts.";
            case TIME:
                return zh
                        ? "本轮已跑 " + (budget.elapsed(nowMs) / 1000) + " 秒，到时间预算了，先给当前结论。"
                        : "This turn already ran " + (budget.elapsed(nowMs) / 1000)
                        + "s and hit the time budget; here is what we have so far.";
            case CONTEXT:
                return zh
                        ? "上下文快满了（工具结果占满窗口）。建议新开一个会话，或让我先总结再继续。"
                        : "Context window is nearly full (tool results filled it). "
                        + "Start a new chat, or ask me to summarise before continuing.";
            default:
                return "";
        }
    }

    /** 供设置项展示：难度 → 「64 步 / 8 分钟」。 */
    public static Map<String, String> describeAll() {
        Map<String, String> out = new LinkedHashMap<>();
        boolean zh = Lang.zh();
        for (Effort e : Effort.values()) {
            out.put(e.name(), e.label() + " · " + e.rounds
                    + (zh ? " 步 / " : " steps / ") + (e.budgetMs / 60_000) + (zh ? " 分钟" : " min"));
        }
        return out;
    }

    /** 从名字解析；未知时回退 NORMAL（老配置文件不会因为多一个枚举值就崩）。 */
    public static Effort parse(String name) {
        if (name == null) return Effort.NORMAL;
        for (Effort e : Effort.values()) {
            if (e.name().equalsIgnoreCase(name.trim())) return e;
        }
        return Effort.NORMAL;
    }
}
