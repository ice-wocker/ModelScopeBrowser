package com.mscope.browser.core;

import com.mscope.browser.i18n.Lang;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 输入校验：把「用户能填的东西」与「引擎能接受的东西」之间的鸿沟补上。
 *
 * <p>推理参数看着无害，填错一个值就能让采样变成重复词或直接崩：
 * temperature=0 与 top-k=0 同时出现会让 softmax 除零，maxTokens 大于上下文则永远触不到 EOS。
 * 这里做确定性收敛，并把「为什么被改」用一句话说清楚。
 */
public final class Validators {

    private Validators() {
    }

    public static final int CTX_MIN = 512;
    public static final int CTX_MAX = 32768;
    public static final int MAX_TOKENS_MAX = 8192;
    public static final float TEMP_MAX = 2f;
    /** 重复惩罚窗口上限：窗口越大，越会压到「本就该重复」的内容（列表、代码缩进）。 */
    public static final int REPEAT_LAST_N_MAX = 512;
    public static final float REPEAT_PENALTY_MAX = 2f;

    /** 采样参数集合，与引擎字段一一对应。 */
    public static final class Sampling {
        public int maxTokens;
        public float temp;
        public float topP;
        public int topK;
        public int nCtx;
        /** 重复惩罚窗口（0 = 关闭）。 */
        public int repeatLastN;
        /** 重复惩罚系数（1.0 = 不惩罚）。 */
        public float repeatPenalty;

        public Sampling(int maxTokens, float temp, float topP, int topK, int nCtx) {
            this(maxTokens, temp, topP, topK, nCtx, 0, 1f);
        }

        public Sampling(int maxTokens, float temp, float topP, int topK, int nCtx,
                        int repeatLastN, float repeatPenalty) {
            this.maxTokens = maxTokens;
            this.temp = temp;
            this.topP = topP;
            this.topK = topK;
            this.nCtx = nCtx;
            this.repeatLastN = repeatLastN;
            this.repeatPenalty = repeatPenalty;
        }
    }

    /**
     * 收敛到引擎能安全接受的范围，并返回给人看的备注（无调整返回空串）。
     *
     * <p>顺序很重要：先定上下文，再让输出上限去适配它。
     */
    public static String normalize(Sampling s) {
        return normalize(s, Lang.zh());
    }

    /** 同 {@link #normalize(Sampling)}，可显式指定文案语言（便于单测与切换语言后立即重算）。 */
    public static String normalize(Sampling s, boolean zh) {
        if (s == null) return "";
        StringBuilder notes = new StringBuilder();
        int ctx = clamp(s.nCtx, CTX_MIN, CTX_MAX);
        if (ctx != s.nCtx) {
            notes.append(zh ? "上下文收敛到 " + ctx + "；" : "context clamped to " + ctx + "; ");
            s.nCtx = ctx;
        }
        int max = clamp(s.maxTokens, 1, MAX_TOKENS_MAX);
        // 输出必须给提示词留空间：最多占上下文 3/4，至少 64
        int cap = Math.max(64, ctx * 3 / 4);
        if (max > cap) {
            max = cap;
            notes.append(zh
                    ? "输出上限压到上下文的 3/4（" + cap + "）；"
                    : "max tokens capped at 3/4 of context (" + cap + "); ");
        }
        if (max != s.maxTokens) {
            s.maxTokens = max;
        }
        float temp = clamp(s.temp, 0f, TEMP_MAX);
        if (temp != s.temp) {
            notes.append(zh
                    ? "temperature 收敛到 " + fmt(temp) + "；"
                    : "temperature clamped to " + fmt(temp) + "; ");
            s.temp = temp;
        }
        // 温度极低的贪心采样 + top-k 0 会让分布退化，至少留 1
        int topK = clamp(s.topK, 0, 200);
        if (temp <= 0.01f && topK == 0) {
            topK = 1;
            notes.append(zh ? "贪心采样下 top-k 至少为 1；" : "greedy sampling needs top-k >= 1; ");
        }
        if (topK != s.topK) s.topK = topK;
        float topP = clamp(s.topP, 0f, 1f);
        if (topP <= 0f) {
            topP = 1f;
            notes.append(zh ? "top-p 必须大于 0，已改回 1；" : "top-p must be > 0, reset to 1; ");
        }
        if (topP != s.topP) s.topP = topP;

        // 重复惩罚：窗口 0 视为关闭；系数收到 1.0~2.0。
        // 低于 1.0 等于「鼓励重复」，那是另一种故障（复读），不开放给输入框。
        int lastN = clamp(s.repeatLastN, 0, REPEAT_LAST_N_MAX);
        if (lastN != s.repeatLastN) {
            notes.append(zh ? "重复惩罚窗口收敛到 " + lastN + "；"
                            : "repetition window clamped to " + lastN + "; ");
            s.repeatLastN = lastN;
        }
        if (Float.isNaN(s.repeatPenalty)) s.repeatPenalty = 1f;
        float penalty = clamp(s.repeatPenalty, 1f, REPEAT_PENALTY_MAX);
        if (penalty != s.repeatPenalty) {
            notes.append(zh
                    ? "重复惩罚收敛到 " + fmt(penalty) + "（1.00 表示不惩罚）；"
                    : "repetition penalty clamped to " + fmt(penalty) + " (1.00 means off); ");
            s.repeatPenalty = penalty;
        }
        if (s.repeatLastN == 0) s.repeatPenalty = 1f;      // 窗口关闭时系数无意义，统一归 1

        String out = notes.toString().trim();
        // 去掉末尾的分隔符（中文「；」/ 英文「;」）
        if (out.endsWith("；")) out = out.substring(0, out.length() - 1);
        if (out.endsWith(";")) out = out.substring(0, out.length() - 1);
        return out.trim();
    }

    public static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public static float clamp(float v, float min, float max) {
        if (Float.isNaN(v)) return min;
        return Math.max(min, Math.min(max, v));
    }

    private static String fmt(float f) {
        return String.format(Locale.ROOT, "%.2f", f);
    }

    /** 宽松解析整数：允许空白、允许全角数字，失败返回 def。 */
    public static int parseInt(String s, int def) {
        if (s == null) return def;
        String t = s.trim().replace('　', ' ');
        if (t.isEmpty()) return def;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c >= '０' && c <= '９') {
                sb.append((char) ('0' + (c - '０')));
            } else if ((c >= '0' && c <= '9') || c == '-' || c == '+') {
                sb.append(c);
            }
        }
        try {
            return Integer.parseInt(sb.toString());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static float parseFloat(String s, float def) {
        if (s == null) return def;
        String t = s.trim().replace('　', ' ');
        if (t.isEmpty()) return def;
        t = t.replace('．', '.').replace(',', '.');
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.') sb.append(c);
        }
        try {
            float v = Float.parseFloat(sb.toString());
            return Float.isNaN(v) ? def : v;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /* --------------------------------------------------------------- 搜索 */

    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]");

    /**
     * 搜索关键词清洗：去控制字符、压空白、限制长度。
     * 关键词会被拼进 URL 与检索请求，长度不收会撑爆请求行。
     */
    public static String cleanQuery(String q, int maxLen) {
        if (q == null) return "";
        String s = CONTROL.matcher(q).replaceAll(" ");
        s = s.replaceAll("\\s+", " ").trim();
        if (maxLen > 0 && s.length() > maxLen) s = s.substring(0, maxLen).trim();
        return s;
    }

    /**
     * 网页链接收敛：只放行 http/https，挡掉 javascript:、file:、intent: 这类
     * 由模型产出、交给 WebView 就会变成真行为的东西。
     */
    public static String safeUrl(String url) {
        if (url == null) return null;
        String u = url.trim();
        if (u.isEmpty()) return null;
        String lower = u.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            // 去掉 URL 里的空白与换行，避免请求行被注入
            return u.replaceAll("[\\r\\n\\t]", "");
        }
        return null;
    }

    /** 主机名是否属于本机/内网（用于挡住 SSRF 式的本地探测）。 */
    public static boolean isPrivateHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1") || h.equals("0.0.0.0")) {
            return true;
        }
        if (h.startsWith("10.") || h.startsWith("192.168.")) return true;
        if (h.startsWith("172.")) {
            String[] parts = h.split("\\.");
            if (parts.length > 1) {
                int second = parseInt(parts[1], -1);
                return second >= 16 && second <= 31;
            }
        }
        return h.startsWith("169.254.") || h.endsWith(".local");
    }

    /** 从 URL 里取主机名，取不到返回空串。 */
    public static String hostOf(String url) {
        if (url == null) return "";
        int start = url.indexOf("://");
        if (start < 0) return "";
        start += 3;
        int end = url.length();
        for (int i = start; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        String authority = url.substring(start, end);
        int at = authority.lastIndexOf('@');
        if (at >= 0) authority = authority.substring(at + 1);
        int colon = authority.indexOf(':');
        if (colon >= 0) authority = authority.substring(0, colon);
        return authority;
    }
}
