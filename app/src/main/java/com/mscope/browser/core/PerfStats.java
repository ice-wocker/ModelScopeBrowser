package com.mscope.browser.core;

import com.mscope.browser.i18n.Lang;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 推理性能的滚动统计。
 *
 * <p>单次 tok/s 抖动很大（后台任务、散热降频、系统调度都会影响），
 * 只显示最后一次数字基本没有参考价值。这里保留最近 N 次，给出均值与 P90，
 * 并在「明显劣化」时给一句提示 —— 用户至少知道该关掉后台应用，而不是觉得软件坏了。
 *
 * <p>纯内存运算，可单测。
 */
public final class PerfStats {

    /** 一次生成的样本。 */
    public static final class Sample {
        public final int tokens;
        public final long prefillMs;
        public final double tokensPerSec;
        public final long atMs;

        public Sample(int tokens, long prefillMs, double tokensPerSec, long atMs) {
            this.tokens = tokens;
            this.prefillMs = prefillMs;
            this.tokensPerSec = tokensPerSec;
            this.atMs = atMs;
        }
    }

    private final int capacity;
    private final List<Sample> samples = new ArrayList<>();

    public PerfStats(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    public synchronized void add(Sample s) {
        if (s == null) return;
        samples.add(s);
        while (samples.size() > capacity) samples.remove(0);
    }

    public synchronized void clear() {
        samples.clear();
    }

    public synchronized int size() {
        return samples.size();
    }

    public synchronized List<Sample> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(samples));
    }

    /** 最近一次 tok/s，没有样本返回 0。 */
    public synchronized double lastTps() {
        return samples.isEmpty() ? 0 : samples.get(samples.size() - 1).tokensPerSec;
    }

    /** 平均 tok/s（只计有效样本）。 */
    public synchronized double meanTps() {
        double sum = 0;
        int n = 0;
        for (Sample s : samples) {
            if (s.tokensPerSec > 0) {
                sum += s.tokensPerSec;
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    /** 分位数（0~1），用于看尾部劣化。 */
    public synchronized double percentileTps(double p) {
        List<Double> vals = new ArrayList<>();
        for (Sample s : samples) {
            if (s.tokensPerSec > 0) vals.add(s.tokensPerSec);
        }
        if (vals.isEmpty()) return 0;
        Collections.sort(vals);
        int idx = (int) Math.round((vals.size() - 1) * Math.max(0, Math.min(1, p)));
        return vals.get(idx);
    }

    /** 平均首字延迟（预填充耗时），毫秒。 */
    public synchronized long meanPrefillMs() {
        long sum = 0;
        int n = 0;
        for (Sample s : samples) {
            if (s.prefillMs > 0) {
                sum += s.prefillMs;
                n++;
            }
        }
        return n == 0 ? 0 : sum / n;
    }

    /**
     * 最近一次是否明显慢于历史（低于历史均值的 60%）。
     * 至少要有 3 个样本才判断，避免预热阶段误报。
     */
    public synchronized boolean degraded() {
        if (samples.size() < 3) return false;
        double last = lastTps();
        if (last <= 0) return false;
        double sum = 0;
        int n = 0;
        for (int i = 0; i < samples.size() - 1; i++) {
            double v = samples.get(i).tokensPerSec;
            if (v > 0) {
                sum += v;
                n++;
            }
        }
        if (n == 0) return false;
        return last < sum / n * 0.6;
    }

    /** 一句话摘要，直接放状态条。 */
    public synchronized String summary() {
        if (samples.isEmpty()) return "";
        if (!Lang.zh()) {
            return String.format(Locale.ROOT, "last %.1f tok/s · mean %.1f · P90 %.1f · first token %d ms",
                    lastTps(), meanTps(), percentileTps(0.9), meanPrefillMs());
        }
        return String.format(Locale.ROOT, "本次 %.1f tok/s · 均值 %.1f · P90 %.1f · 首字 %d ms",
                lastTps(), meanTps(), percentileTps(0.9), meanPrefillMs());
    }

    /** 劣化时的建议文案；正常返回空串。 */
    public synchronized String advice() {
        if (!degraded()) return "";
        return Lang.zh()
                ? "速度明显低于本次会话的平均水平，可能被后台任务或散热拖慢；关掉后台应用、降一档上下文通常能恢复。"
                : "Speed is clearly below this session's average — background work or thermal throttling is "
                + "likely. Closing background apps or lowering the context size usually restores it.";
    }
}
