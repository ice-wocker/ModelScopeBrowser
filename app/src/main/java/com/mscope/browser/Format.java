package com.mscope.browser;

import java.util.Locale;

/** 通用展示格式化：体积、计数。原先在 LocalModel / ModelFile / LocalModelsActivity 各有一份，这里统一。 */
public final class Format {

    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB"};

    private Format() {
    }

    /** 体积，如 1.2 GB；非正数返回 "0 B"。 */
    public static String size(long bytes) {
        if (bytes <= 0) return "0 B";
        double v = bytes;
        int u = 0;
        while (v >= 1024 && u < UNITS.length - 1) {
            v /= 1024;
            u++;
        }
        return (u == 0 ? String.valueOf((long) v)
                : String.format(Locale.CHINA, "%.1f", v)) + " " + UNITS[u];
    }

    /** 体积；非正数返回 "-"（用于「未知/未下载」的占位）。 */
    public static String sizeOrDash(long bytes) {
        return bytes <= 0 ? "-" : size(bytes);
    }

    /** 大数缩写，如 1.2 万 / 3.4 亿。 */
    public static String count(long n) {
        if (n >= 100000000L) return String.format(Locale.CHINA, "%.1f亿", n / 100000000.0);
        if (n >= 10000L) return String.format(Locale.CHINA, "%.1f万", n / 10000.0);
        return String.valueOf(n);
    }
}