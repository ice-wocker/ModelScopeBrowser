package com.mscope.browser.core;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 时间：给模型与工具用的确定性格式化。
 *
 * <p>本地小模型几乎算不明白日期（问它「今天周几」十次错九次），所以日期类问题
 * 不该丢给模型推，而应该由工具直接算好再告诉它。这里集中所有时间换算，
 * 保证「同一次调用里 now 只取一次」，不会出现前后脚跨秒导致的矛盾输出。
 *
 * <p>纯 JDK，可单测（时区与 now 都可注入）。
 */
public final class TimeKit {

    public static final long MINUTE = 60_000L;
    public static final long HOUR = 60 * MINUTE;
    public static final long DAY = 24 * HOUR;

    private TimeKit() {
    }

    /** 当前时间戳；测试可覆盖。 */
    public static long now() {
        return System.currentTimeMillis();
    }

    public static String format(long ms, String pattern, TimeZone tz, Locale locale) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, locale == null ? Locale.ROOT : locale);
        if (tz != null) f.setTimeZone(tz);
        return f.format(new Date(ms));
    }

    public static String iso8601(long ms) {
        return format(ms, "yyyy-MM-dd'T'HH:mm:ssXXX", TimeZone.getDefault(), Locale.ROOT);
    }

    public static String date(long ms) {
        return format(ms, "yyyy-MM-dd", TimeZone.getDefault(), Locale.ROOT);
    }

    public static String time(long ms) {
        return format(ms, "HH:mm:ss", TimeZone.getDefault(), Locale.ROOT);
    }

    /** 周几，中文返回「周三」，英文返回「Wednesday」，不依赖系统 locale 的坑。 */
    public static String weekday(long ms, boolean zh) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        String[] zhNames = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        String[] enNames = {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};
        int idx = c.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY;
        if (idx < 0 || idx > 6) idx = 0;
        return zh ? zhNames[idx] : enNames[idx];
    }

    /** 一句话说清「现在几点、星期几、第几周」，直接回灌给模型。 */
    public static String describeNow(long ms, TimeZone tz, boolean zh) {
        TimeZone z = tz == null ? TimeZone.getDefault() : tz;
        Calendar c = Calendar.getInstance(z);
        c.setTimeInMillis(ms);
        String d = format(ms, "yyyy-MM-dd HH:mm:ss", z, Locale.ROOT);
        int week = c.get(Calendar.WEEK_OF_YEAR);
        String tzName = z.getDisplayName(z.inDaylightTime(new Date(ms)), TimeZone.SHORT, Locale.ROOT);
        return zh
                ? "现在：" + d + "（" + weekday(ms, true) + "，第 " + week + " 周，" + tzName + "）"
                : "Now: " + d + " (" + weekday(ms, false) + ", week " + week + ", " + tzName + ")";
    }

    /** 把「3d」「2h30m」「1500ms」「1w」这类相对时长解析成毫秒；解析不了返回 -1。 */
    public static long parseDuration(String s) {
        if (s == null) return -1;
        String t = s.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return -1;
        long total = 0;
        boolean any = false;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+)\\s*(ms|s|m|h|d|w|分钟|小时|天|周|秒)").matcher(t);
        while (m.find()) {
            long v;
            try {
                v = Long.parseLong(m.group(1));
            } catch (NumberFormatException e) {
                continue;
            }
            String u = m.group(2);
            long mul;
            switch (u) {
                case "ms":
                    mul = 1;
                    break;
                case "s":
                case "秒":
                    mul = 1000;
                    break;
                case "m":
                case "分钟":
                    mul = MINUTE;
                    break;
                case "h":
                case "小时":
                    mul = HOUR;
                    break;
                case "d":
                case "天":
                    mul = DAY;
                    break;
                case "w":
                case "周":
                    mul = 7 * DAY;
                    break;
                default:
                    continue;
            }
            total += v * mul;
            any = true;
        }
        return any ? total : -1;
    }

    /** 毫秒 → 「1天2小时」这样的口语化时长，用于元信息展示。 */
    public static String humanize(long ms, boolean zh) {
        long v = Math.abs(ms);
        if (v < 1000) return v + (zh ? " 毫秒" : " ms");
        long s = v / 1000;
        if (s < 60) return s + (zh ? " 秒" : "s");
        long m = s / 60;
        if (m < 60) return m + (zh ? " 分 " : "m ") + (s % 60) + (zh ? " 秒" : "s");
        long h = m / 60;
        if (h < 24) return h + (zh ? " 小时 " : "h ") + (m % 60) + (zh ? " 分" : "m");
        long d = h / 24;
        return d + (zh ? " 天 " : "d ") + (h % 24) + (zh ? " 小时" : "h");
    }

    /** 文件名用的紧凑时间戳，如 20260101-120000。 */
    public static String stamp(long ms) {
        return format(ms, "yyyyMMdd-HHmmss", TimeZone.getDefault(), Locale.ROOT);
    }
}
