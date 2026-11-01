package com.mscope.browser.core;

import java.io.File;

/**
 * 上下文窗口的「能开多大」估算器。
 *
 * <p>手机上跑 llama.cpp，最贵的不是模型本体而是 KV cache：它随「上下文长度 × 层数 × 头维」
 * 线性增长，而且必须整块驻留内存。给用户一个 32768 的输入框却不告诉他放不放得下，
 * 结果就是进程被系统低内存杀手直接干掉 —— 连报错都看不到。这里把这件事算清楚：
 * 读 {@code /proc/meminfo}，结合模型体积与量化类型，给出一个既安全又尽量大的建议值。
 *
 * <p>纯静态、无 Android 依赖，可以在 JVM 单测里跑。
 */
public final class MemoryBudget {

    /** 系统里留给自己的余量：低于这个可用内存就不再往上加了。 */
    public static final long SAFETY_MARGIN_KB = 320L * 1024L;
    /** 每个上下文 token 在 q8_0 KV 下的经验开销（KB），已含注意力元数据。 */
    private static final double KV_KB_PER_TOKEN = 0.0625;
    /** 上下文窗口的候选档位，避免出现 12345 这种看着就不可信的取值。 */
    private static final int[] STEPS = {512, 1024, 2048, 3072, 4096, 6144, 8192, 12288, 16384, 24576, 32768};

    private MemoryBudget() {
    }

    /** meminfo 里关心的几项，单位 KB。 */
    public static final class Snapshot {
        public final long totalKb;
        public final long availableKb;
        public final long thresholdKb;

        public Snapshot(long totalKb, long availableKb, long thresholdKb) {
            this.totalKb = totalKb;
            this.availableKb = availableKb;
            this.thresholdKb = thresholdKb;
        }

        @Override
        public String toString() {
            return "total=" + totalKb / 1024 + "MB available=" + availableKb / 1024
                    + "MB threshold=" + thresholdKb / 1024 + "MB";
        }
    }

    /** 解析 /proc/meminfo。缺项按 0 处理；available 缺失时用 MemFree 兜底。 */
    public static Snapshot parseMeminfo(String text) {
        long total = 0, avail = 0, free = 0, threshold = 0;
        if (text != null) {
            for (String line : text.split("\n")) {
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String key = line.substring(0, colon).trim();
                long kb = parseKb(line.substring(colon + 1));
                if ("MemTotal".equals(key)) {
                    total = kb;
                } else if ("MemAvailable".equals(key)) {
                    avail = kb;
                } else if ("MemFree".equals(key)) {
                    free = kb;
                }
            }
        }
        if (avail <= 0) avail = free;
        return new Snapshot(total, avail, threshold);
    }

    private static long parseKb(String raw) {
        if (raw == null) return 0;
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            } else if (digits.length() > 0) {
                break;
            }
        }
        try {
            return digits.length() == 0 ? 0 : Long.parseLong(digits.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 估算某档上下文需要的额外内存（KB）：KV cache + 计算缓冲区 + 词表。
     *
     * @param ctxTokens 上下文长度
     * @param kvQuantized KV 是否量化为 q8_0（未量化时开销翻倍）
     */
    public static long estimateExtraKb(int ctxTokens, boolean kvQuantized) {
        if (ctxTokens <= 0) return 0;
        double kv = ctxTokens * KV_KB_PER_TOKEN * (kvQuantized ? 1.0 : 2.0);
        // 计算缓冲区与上下文弱相关（n_ubatch 固定 128），给个随长度缓升的上限
        double compute = 96d * 1024d + Math.min(ctxTokens, 32768) * 0.02 * 1024L;
        double vocab = 24d * 1024d;
        return (long) (kv + compute + vocab);
    }

    /**
     * 在候选档位里挑一个「能放下且尽量大」的上下文。
     *
     * <p>判定用两条线同时卡：一是模型文件之外必须放得下 {@link #estimateExtraKb}，
     * 二是留下 {@link #SAFETY_MARGIN_KB} 给系统与界面，避免被 LMK 收拾。
     *
     * @param mem         当前设备内存快照（可为 null，表示读不到，按保守处理）
     * @param modelBytes  模型文件大小
     * @param kvQuantized KV 是否量化
     * @param fallback    一条信息都拿不到时的兜底值
     */
    public static int suggestCtx(Snapshot mem, long modelBytes, boolean kvQuantized, int fallback) {
        if (mem == null || mem.totalKb <= 0) return fallback;
        long modelKb = Math.max(0, modelBytes) / 1024L;
        // 权重之外的可支配预算：可用内存 - 模型（mmap 后仍需页缓存）- 系统余量
        long room = mem.availableKb - modelKb / 2 - SAFETY_MARGIN_KB;
        if (room < 256L * 1024L) {
            // 已经很紧张了，别再加码
            return Math.min(fallback, 512);
        }
        int best = 0;
        for (int step : STEPS) {
            if (estimateExtraKb(step, kvQuantized) <= room) best = step;
        }
        if (best == 0) return Math.min(fallback, 512);
        // 老设备上不要超过 total/8，留足页缓存给 mmap 的模型
        long capByTotal = Math.max(512, mem.totalKb / 8);
        while (best > STEPS[0] && best > capByTotal) {
            best = previousStep(best);
        }
        return best;
    }

    private static int previousStep(int v) {
        int prev = STEPS[0];
        for (int step : STEPS) {
            if (step >= v) break;
            prev = step;
        }
        return prev;
    }

    /** 面向界面的解释文案：为什么是这个值。 */
    public static String explain(Snapshot mem, long modelBytes, int chosenCtx) {
        if (mem == null || mem.totalKb <= 0) return "读不到设备内存信息，沿用上次设置";
        return String.format(java.util.Locale.ROOT,
                "设备 %d MB 内存，当前可用 %d MB，模型 %.1f GB；建议上下文 %d tokens（约需额外 %d MB）",
                mem.totalKb / 1024, mem.availableKb / 1024,
                modelBytes / 1024d / 1024d / 1024d, chosenCtx,
                estimateExtraKb(chosenCtx, true) / 1024);
    }

    /** 读本机 meminfo；失败返回 null。仅 Android / Linux 有意义。 */
    public static Snapshot read() {
        try {
            File f = new File("/proc/meminfo");
            if (!f.canRead()) return null;
            byte[] buf = new byte[4096];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int n = in.read(buf);
                if (n <= 0) return null;
                return parseMeminfo(new String(buf, 0, n, "UTF-8"));
            }
        } catch (Throwable t) {
            return null;
        }
    }
}
