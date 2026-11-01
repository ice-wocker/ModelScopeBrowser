package com.mscope.browser.agent;

import com.mscope.browser.core.DigestKit;
import com.mscope.browser.core.TimeKit;
import com.mscope.browser.i18n.Lang;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;

/**
 * 计算类工具：算式、时间、单位、编码、随机。
 *
 * <p>本地小模型算数很差，而这类需求极其常见（「3.5 GB 的模型还能下几个」「两周后是几号」）。
 * 与其指望模型算对，不如给它一个工具 —— 这也是这个类存在的理由。
 *
 * <p>算式求值用递归下降，不用 {@code javax.script}：后者在 Android 上根本没有 Nashorn，
 * 而自己写一个支持 + − × ÷ % ^ 与括号、并带步数上限的求值器只有一百多行，
 * 也不会因为表达式里塞脚本而变成任意代码执行。
 */
final class CalcTools {

    private CalcTools() {
    }

    private static boolean zh() {
        return Lang.zh();
    }

    // ------------------------------------------------------------------ 算式

    /** 求值入口。 */
    static String calc(String expr) {
        if (expr == null || expr.trim().isEmpty()) return zh() ? "错误：缺少参数 expr" : "error: missing argument expr";
        String e = normalizeExpr(expr);
        try {
            Eval ev = new Eval(e);
            double v = ev.parse();
            if (ev.pos < e.length()) {
                return (zh() ? "错误：无法解析「" : "error: cannot parse \"") + e.substring(ev.pos) + "\"";
            }
            return expr.trim() + " = " + format(v);
        } catch (ArithmeticException ex) {
            return (zh() ? "错误：数学错误（" : "error: math error (") + ex.getMessage() + ")";
        } catch (Exception ex) {
            return (zh() ? "错误：表达式无效 —— " : "error: invalid expression — ") + ex.getMessage();
        }
    }

    /** 全角转半角、× ÷ − 归一、百分比就地展开、常见单位后缀去除。 */
    static String normalizeExpr(String raw) {
        StringBuilder sb = new StringBuilder();
        for (char c : raw.toCharArray()) {
            if (c >= 0xFF01 && c <= 0xFF5E) sb.append((char) (c - 0xFEE0));
            else if (c == 0x3000) sb.append(' ');
            else sb.append(c);
        }
        String s = sb.toString()
                .replace('×', '*').replace('÷', '/')
                .replace('−', '-').replace('—', '-')
                .replace('（', '(').replace('）', ')')
                .replace('，', ',').replace('。', '.')
                .replaceAll("(?i)\\b(km|kg|gb|mb|tb|kb|ms|cm|mm)\\b", "")
                .replaceAll("\\s+", "");
        // 「200的15%」这类写法：把裸百分比变成 /100
        s = s.replaceAll("(\\d+(?:\\.\\d+)?)%", "($1/100)");
        return s;
    }

    static final class Eval {
        final String s;
        int pos;
        int steps;
        private static final int MAX_STEPS = 20000;

        Eval(String s) {
            this.s = s;
        }

        double parse() {
            double v = expr();
            return v;
        }

        private void tick() {
            if (++steps > MAX_STEPS) throw new ArithmeticException("expression too complex");
        }

        private double expr() {
            double v = term();
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '+') {
                    pos++;
                    v += term();
                } else if (c == '-') {
                    pos++;
                    v -= term();
                } else {
                    break;
                }
                tick();
            }
            return v;
        }

        private double term() {
            double v = factor();
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '*' || c == 'x' || c == 'X') {
                    pos++;
                    v *= factor();
                } else if (c == '/') {
                    pos++;
                    double d = factor();
                    if (d == 0) throw new ArithmeticException("division by zero");
                    v /= d;
                } else if (c == '%') {
                    pos++;
                    double d = factor();
                    if (d == 0) throw new ArithmeticException("modulo by zero");
                    v %= d;
                } else {
                    break;
                }
                tick();
            }
            return v;
        }

        private double factor() {
            double base = unary();
            if (pos < s.length() && (s.charAt(pos) == '^' || s.charAt(pos) == 'p')
                    && !s.startsWith("pi", pos)) {
                pos++;
                double exp = factor();
                return Math.pow(base, exp);
            }
            return base;
        }

        private double unary() {
            if (pos < s.length() && (s.charAt(pos) == '-' || s.charAt(pos) == '+')) {
                char c = s.charAt(pos);
                pos++;
                double v = unary();
                return c == '-' ? -v : v;
            }
            return atom();
        }

        private double atom() {
            tick();
            if (pos >= s.length()) throw new IllegalArgumentException("unexpected end");
            char c = s.charAt(pos);
            if (c == '(') {
                pos++;
                double v = expr();
                if (pos < s.length() && s.charAt(pos) == ')') pos++;
                return v;
            }
            if (Character.isDigit(c) || c == '.') {
                int start = pos;
                while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.')) pos++;
                return Double.parseDouble(s.substring(start, pos));
            }
            // 常量与函数
            if (s.startsWith("pi", pos)) {
                pos += 2;
                return Math.PI;
            }
            if (s.startsWith("e", pos) && !s.startsWith("exp", pos)) {
                pos += 1;
                return Math.E;
            }
            if (s.startsWith("sqrt", pos)) {
                pos += 4;
                return Math.sqrt(bracket());
            }
            if (s.startsWith("abs", pos)) {
                pos += 3;
                return Math.abs(bracket());
            }
            if (s.startsWith("log", pos)) {
                pos += 3;
                return Math.log10(bracket());
            }
            if (s.startsWith("ln", pos)) {
                pos += 2;
                return Math.log(bracket());
            }
            if (s.startsWith("round", pos)) {
                pos += 5;
                return Math.round(bracket());
            }
            if (s.startsWith("floor", pos)) {
                pos += 5;
                return Math.floor(bracket());
            }
            if (s.startsWith("ceil", pos)) {
                pos += 4;
                return Math.ceil(bracket());
            }
            throw new IllegalArgumentException("unexpected char '" + c + "'");
        }

        private double bracket() {
            if (pos < s.length() && s.charAt(pos) == '(') {
                pos++;
                double v = expr();
                if (pos < s.length() && s.charAt(pos) == ')') pos++;
                return v;
            }
            return unary();
        }
    }

    /** 数字格式化：整数不带小数点，小数最多 10 位并去掉尾零。 */
    static String format(double v) {
        if (Double.isNaN(v)) return "NaN";
        if (Double.isInfinite(v)) return v > 0 ? "∞" : "-∞";
        if (Math.abs(v - Math.rint(v)) < 1e-9 && Math.abs(v) < 1e15) return String.valueOf((long) Math.rint(v));
        String s = String.format(Locale.ROOT, "%.10f", v);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    // ------------------------------------------------------------------ 时间

    static String now(String tz) {
        TimeZone z = TimeZone.getDefault();
        if (tz != null && !tz.trim().isEmpty()) {
            TimeZone parsed = TimeZone.getTimeZone(tz.trim());
            if (!"GMT".equals(parsed.getID()) || tz.trim().toUpperCase(Locale.ROOT).startsWith("GMT")
                    || "UTC".equalsIgnoreCase(tz.trim())) {
                z = parsed;
            }
        }
        return TimeKit.describeNow(TimeKit.now(), z, zh());
    }

    static String dateMath(String base, String delta) {
        long t = TimeKit.now();
        if (base != null && !base.trim().isEmpty()) {
            String b = base.trim();
            long parsed = parseDate(b);
            if (parsed > 0) t = parsed;
            else if (!"now".equalsIgnoreCase(b) && !"today".equalsIgnoreCase(b)) {
                return (zh() ? "错误：看不懂这个日期「" : "error: cannot parse date \"") + base + "\"（支持 yyyy-MM-dd）";
            }
        }
        if (delta == null || delta.trim().isEmpty()) {
            return (zh() ? "错误：缺少参数 delta（如 +3d、-2h）" : "error: missing delta (e.g. +3d, -2h)");
        }
        String d = delta.trim().replace(" ", "");
        boolean negative = d.startsWith("-");
        String sign = d.startsWith("+") || d.startsWith("-") ? d.substring(1) : d;
        long ms = TimeKit.parseDuration(sign);
        if (ms <= 0) ms = TimeKit.parseDuration(d);
        if (ms <= 0) return (zh() ? "错误：看不懂这个时长「" : "error: cannot parse duration \"") + delta + "\"";
        long target0 = negative ? t - ms : t + ms;
        long target = target0;
        if (ms % TimeKit.DAY == 0) {
            // 按天/周加减时保持同一个「时刻」，避免时区与夏令时把日期推偏一天
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.setTimeInMillis(t);
            cal.add(java.util.Calendar.DAY_OF_MONTH, (int) ((negative ? -ms : ms) / TimeKit.DAY));
            target = cal.getTimeInMillis();
        }
        return (zh() ? "结果：" : "result: ") + TimeKit.date(target) + " " + TimeKit.time(target)
                + "（" + TimeKit.weekday(target, zh()) + (zh() ? "，" : ", ") + delta.trim() + "）";
    }

    /** 支持 yyyy-MM-dd、yyyy/MM/dd、yyyy-MM-dd HH:mm。 */
    static long parseDate(String s) {
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT);
            f.setLenient(false);
            return f.parse(s.replace('/', '-')).getTime();
        } catch (Exception ignored) {
            // 落到只有日期的形式
        }
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
            f.setLenient(false);
            String t = s.replace('/', '-');
            if (t.length() > 10) t = t.substring(0, 10);
            return f.parse(t).getTime();
        } catch (Exception e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ 随机

    static String random(String kind, String a, String b) {
        Random r = new Random();
        String k = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
        if ("uuid".equals(k) || "guid".equals(k)) {
            java.util.UUID u = java.util.UUID.randomUUID();
            return u.toString();
        }
        if ("choice".equals(k) || "pick".equals(k)) {
            if (a == null || a.trim().isEmpty()) return zh() ? "错误：缺少候选列表" : "error: missing candidates";
            String[] items = a.split("[,，|\\n]");
            List<String> cleaned = new ArrayList<>();
            for (String s : items) if (!s.trim().isEmpty()) cleaned.add(s.trim());
            if (cleaned.isEmpty()) return zh() ? "错误：候选列表为空" : "error: empty candidate list";
            return cleaned.get(r.nextInt(cleaned.size()));
        }
        if ("shuffle".equals(k)) {
            if (a == null || a.trim().isEmpty()) return zh() ? "错误：缺少列表" : "error: missing list";
            List<String> items = new ArrayList<>();
            for (String s : a.split("[,，|\\n]")) if (!s.trim().isEmpty()) items.add(s.trim());
            java.util.Collections.shuffle(items, r);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < items.size(); i++) sb.append(i + 1).append(". ").append(items.get(i)).append('\n');
            return sb.toString();
        }
        int lo = parseInt(a, 1);
        int hi = parseInt(b, 100);
        if (hi < lo) {
            int t = lo;
            lo = hi;
            hi = t;
        }
        int v = lo + r.nextInt(Math.max(1, hi - lo + 1));
        return (zh() ? "随机数（" : "random number (") + lo + (zh() ? "–" : "-") + hi + (zh() ? "）：" : "): ") + v;
    }

    private static int parseInt(String s, int def) {
        if (s == null) return def;
        try {
            return (int) Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ------------------------------------------------------------------ 摘要与编码

    static String hashText(String text, String algo) {
        if (text == null) return zh() ? "错误：缺少参数 text" : "error: missing argument text";
        String a = algo == null || algo.trim().isEmpty() ? "sha256" : algo.trim().toLowerCase(Locale.ROOT);
        switch (a) {
            case "md5":
                return DigestKit.md5(text);
            case "sha1":
            case "sha-1":
                return DigestKit.sha256(text).isEmpty() ? "" : sha1(text);
            default:
                return DigestKit.sha256(text);
        }
    }

    private static String sha1(String text) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    static String base64(String text, String mode) {
        if (text == null) return zh() ? "错误：缺少参数 text" : "error: missing argument text";
        boolean decode = mode != null && mode.toLowerCase(Locale.ROOT).startsWith("dec");
        try {
            // 用 android.util.Base64 而不是 java.util.Base64：后者 API 26 才有，而本项目 minSdk 24
            if (decode) {
                byte[] raw = android.util.Base64.decode(text.trim().replaceAll("\\s+", ""), android.util.Base64.DEFAULT);
                return new String(raw, StandardCharsets.UTF_8);
            }
            return android.util.Base64.encodeToString(text.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            return (zh() ? "错误：解码失败 —— " : "error: decode failed — ") + e.getMessage();
        }
    }

    static String urlCodec(String text, String mode) {
        if (text == null) return zh() ? "错误：缺少参数 text" : "error: missing argument text";
        boolean decode = mode != null && mode.toLowerCase(Locale.ROOT).startsWith("dec");
        try {
            return decode
                    ? java.net.URLDecoder.decode(text, "UTF-8")
                    : java.net.URLEncoder.encode(text, "UTF-8");
        } catch (Exception e) {
            return (zh() ? "错误：" : "error: ") + e.getMessage();
        }
    }

    // ------------------------------------------------------------------ 单位

    /** 支持长度、质量、温度、存储、时间；同族之间换算。 */
    static String unitConvert(String rawValue, String from, String to) {
        if (rawValue == null || from == null || to == null) {
            return zh() ? "错误：需要 value、from、to 三个参数" : "error: value, from and to are required";
        }
        double v;
        try {
            v = Double.parseDouble(rawValue.trim().replaceAll("[^0-9eE+.-]", ""));
        } catch (NumberFormatException e) {
            return (zh() ? "错误：数值无效「" : "error: invalid number \"") + rawValue + "\"";
        }
        // 温度单位保留大小写：'C' 是摄氏度、'c' 也是，但 'K' 与 'k' 都要能认
        String f = from.trim().toLowerCase(Locale.ROOT);
        String t = to.trim().toLowerCase(Locale.ROOT);
        if (factor(f) == null || factor(t) == null) {
            if (!(isTemp(f) && isTemp(t))) {
                return (zh() ? "错误：不认识的单位「" : "error: unknown unit \"")
                        + (factor(f) == null ? from : to) + "\""
                        + (zh() ? "（支持 m/km/cm/mm、g/kg/t/lb、B/KB/MB/GB/TB、s/min/h/d、℃/℉/K）"
                             : " (supported: m/km/cm/mm, g/kg/t/lb, B/KB/MB/GB/TB, s/min/h/d, C/F/K)");
            }
        }
        // 温度单独处理
        if (isTemp(f) && isTemp(t)) {
            double c = toCelsius(v, f);
            double out = fromCelsius(c, t);
            return rawValue.trim() + " " + from + " = " + format(out) + " " + to;
        }
        Double factorFrom = factor(f);
        Double factorTo = factor(t);
        if (factorFrom == null || factorTo == null) {
            return (zh() ? "错误：不认识的单位「" : "error: unknown unit \"")
                    + (factorFrom == null ? from : to) + "\""
                    + (zh() ? "（支持 m/km/cm/mm/km2、g/kg/t/lb、B/KB/MB/GB/TB、s/min/h/d、℃/℉/K）"
                         : " (supported: m/km/cm/mm, g/kg/t/lb, B/KB/MB/GB/TB, s/min/h/d, C/F/K)");
        }
        // 同族才允许
        if (!family(f).equals(family(t))) {
            return zh() ? "错误：这两个单位不是同一类，无法换算" : "error: these units are not in the same family";
        }
        double out = v * factorFrom / factorTo;
        return rawValue.trim() + " " + from + " = " + format(out) + " " + to;
    }

    private static boolean isTemp(String u) {
        return u.equals("c") || u.equals("°c") || u.equals("℃") || u.equals("celsius")
                || u.equals("f") || u.equals("°f") || u.equals("℉") || u.equals("fahrenheit")
                || u.equals("k") || u.equals("kelvin");
    }

    private static double toCelsius(double v, String u) {
        if (u.startsWith("f") || u.equals("°f") || u.contains("fahren")) return (v - 32) * 5 / 9.0;
        if (u.startsWith("k") || u.contains("kelvin")) return v - 273.15;
        return v;
    }

    private static double fromCelsius(double c, String u) {
        if (u.startsWith("f") || u.equals("°f") || u.contains("fahren")) return c * 9 / 5.0 + 32;
        if (u.startsWith("k") || u.contains("kelvin")) return c + 273.15;
        return c;
    }

    private static String family(String u) {
        if (u.contains("b") && (u.equals("b") || u.endsWith("b"))) {
            if (u.equals("b") || u.equals("kb") || u.equals("mb") || u.equals("gb") || u.equals("tb")
                    || u.equals("kib") || u.equals("mib") || u.equals("gib") || u.equals("tib")) return "data";
        }
        switch (u) {
            case "m":
            case "km":
            case "cm":
            case "mm":
            case "mi":
            case "ft":
            case "in":
            case "yd":
                return "length";
            case "g":
            case "kg":
            case "mg":
            case "t":
            case "lb":
            case "oz":
                return "mass";
            case "s":
            case "sec":
            case "min":
            case "h":
            case "d":
            case "ms":
                return "time";
            default:
                return "data";
        }
    }

    private static Double factor(String u) {
        switch (u) {
            case "m":
                return 1.0;
            case "km":
                return 1000.0;
            case "cm":
                return 0.01;
            case "mm":
                return 0.001;
            case "mi":
                return 1609.344;
            case "ft":
                return 0.3048;
            case "in":
                return 0.0254;
            case "yd":
                return 0.9144;
            case "g":
                return 1.0;
            case "kg":
                return 1000.0;
            case "mg":
                return 0.001;
            case "t":
                return 1_000_000.0;
            case "lb":
                return 453.59237;
            case "oz":
                return 28.349523125;
            case "b":
                return 1.0;
            case "kb":
                return 1024.0;
            case "mb":
                return 1024.0 * 1024;
            case "gb":
                return 1024.0 * 1024 * 1024;
            case "tb":
                return 1024.0 * 1024 * 1024 * 1024;
            case "kib":
                return 1024.0;
            case "mib":
                return 1024.0 * 1024;
            case "gib":
                return 1024.0 * 1024 * 1024;
            case "tib":
                return 1024.0 * 1024 * 1024 * 1024;
            case "ms":
                return 0.001;
            case "s":
            case "sec":
                return 1.0;
            case "min":
                return 60.0;
            case "h":
                return 3600.0;
            case "d":
                return 86400.0;
            default:
                return null;
        }
    }
}
