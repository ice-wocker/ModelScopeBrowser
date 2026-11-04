package com.mscope.browser.core;

import com.mscope.browser.i18n.Lang;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 智能体循环的「防打转」闸门。
 *
 * <p>本地小模型拿到工具结果后重复同一个调用，是最常见的失败形态：它并不真的「记得」
 * 自己刚刚读过那个文件。指望模型自觉不现实，只能在宿主侧拦：
 * <ul>
 *   <li>同一个调用（工具名 + 参数，与键序无关）在一轮里最多真执行 {@link #MAX_TOTAL} 次；</li>
 *   <li>重复累计到 {@link #MAX_REPEATS} 次就让本轮收尾 —— 继续把「你重复了」喂回去，
 *       小模型往往会换个写法再试一遍，只会更糟。</li>
 * </ul>
 *
 * <p>纯逻辑、无 Android 依赖，可单测。
 */
public final class ToolGuard {

    /** 同一个调用的执行次数上限（含第一次）。 */
    public static final int MAX_TOTAL = 2;
    /** 单轮里累计的重复次数上限，达到就收尾。 */
    public static final int MAX_REPEATS = 2;
    /** 指纹里参数部分的最大长度：写大文件时参数很长，没必要全留。 */
    public static final int MAX_ARGS = 240;

    /** 连续多少轮「只查询、没有进展」就收尾。 */
    public static final int IDLE_BUDGET = 3;

    private final Set<String> seen = new LinkedHashSet<>();
    private int repeats;
    private int executed;
    private int idle;

    /** 一次判定结果。 */
    public static final class Verdict {
        /** 是否应该真的执行。 */
        public final boolean run;
        /** 本轮是否应该立即收尾（模型已经在原地打转）。 */
        public final boolean stop;
        public final String reason;

        Verdict(boolean run, boolean stop, String reason) {
            this.run = run;
            this.stop = stop;
            this.reason = reason;
        }
    }

    /**
     * 登记一次即将执行的调用。
     *
     * @param tool 工具名
     * @param args 参数指纹（用 {@link #fingerprint} 生成，与键序无关）
     */
    public Verdict check(String tool, String args) {
        return check(tool, args, Lang.zh());
    }

    public Verdict check(String tool, String args, boolean zh) {
        final String key = fingerprint(tool, args);
        if (seen.add(key)) {
            executed++;
            return new Verdict(true, false, "");
        }
        repeats++;
        final boolean stop = repeats >= MAX_REPEATS;
        String reason = zh
                ? "这个调用在本轮已经执行过，未重复执行。请直接使用上面的结果，或换一组参数。"
                : "This exact call already ran in this turn, so it was not executed again. "
                + "Use the result above, or change the arguments.";
        return new Verdict(false, stop, reason);
    }

    /**
     * 记一次「本轮没有产生改动」的往返。
     *
     * <p>和重复判定是两件事：重复是「同一个调用又来一次」，空转是「换着花样查，
     * 但一步也没往前走」。后者更隐蔽 —— 模型会反复 read_file / list_files / kb_search，
     * 每次都合法、都不重复，却永远不写。连续几次就说明它卡住了。
     *
     * @return 本轮的累计空转次数
     */
    public int markIdleRound() {
        return ++idle;
    }

    /** 有实际改动时清零连续计数（改过东西就不算空转）。 */
    public void markProgress() {
        idle = 0;
    }

    public int idleRounds() {
        return idle;
    }

    /** 本轮真正执行过多少次。 */
    public int executedCount() {
        return executed;
    }

    /** 本轮命中重复多少次。 */
    public int repeatCount() {
        return repeats;
    }

    public boolean shouldStop() {
        return repeats >= MAX_REPEATS;
    }

    public void reset() {
        seen.clear();
        repeats = 0;
        executed = 0;
        idle = 0;
    }

    /**
     * 把工具名与参数指纹压成稳定判重键。
     *
     * <p>超长参数按 <b>完整字符</b> 截断：直接 {@code substring} 可能把 emoji 或中文的代理对
     * 切一半，拼出来的键本身没意义（虽然判重仍能用，但调试时看到的是一堆乱码）。
     */
    public static String fingerprint(String tool, String args) {
        String n = tool == null ? "" : tool.trim().toLowerCase(Locale.ROOT);
        String a = args == null ? "" : args.trim();
        if (a.length() > MAX_ARGS) {
            int cut = MAX_ARGS;
            if (Character.isHighSurrogate(a.charAt(cut - 1))) cut--;      // 别把代理对切一半
            a = a.substring(0, cut);
        }
        return n + "|" + a;
    }

    /**
     * 把「键值对」排成与书写顺序无关的参数指纹。
     *
     * <p>模型给同一件事的参数排序千变万化（{@code {a,b}} 与 {@code {b,a}}），
     * 直接比 JSON 字符串会把同一个调用当成两个，闸门就形同虚设。
     *
     * @param keys   参数名（可乱序、可含重复）
     * @param values 与 keys 对齐的参数值
     */
    public static String canonicalArgs(java.util.List<String> keys, java.util.List<String> values) {
        if (keys == null || values == null) return "";
        java.util.List<String> pairs = new java.util.ArrayList<>();
        int n = Math.min(keys.size(), values.size());
        for (int i = 0; i < n; i++) {
            String k = keys.get(i) == null ? "" : keys.get(i).trim().toLowerCase(Locale.ROOT);
            if (k.isEmpty()) continue;
            String v = values.get(i) == null ? "" : values.get(i).trim();
            pairs.add(k + "=" + v);
        }
        java.util.Collections.sort(pairs);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pairs.size(); i++) {
            if (i > 0) sb.append(';');
            sb.append(pairs.get(i));
        }
        return sb.toString();
    }
}
