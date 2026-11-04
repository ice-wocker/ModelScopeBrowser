package com.mscope.browser.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 敏感信息脱敏。
 *
 * <p>场景很具体：用户把一段配置、一份日志、或者一整个文件丢给模型，
 * 里面可能带着 API Key、数据库密码、私钥。这些东西一旦进了对话历史，
 * 就会跟着导出、分享、以及下一轮的 prompt 一起走。这里在**回灌给模型之前**先抹掉。
 *
 * <p>只做「高置信度」的模式（私钥块、常见厂商 Key 前缀、URL 里的 user:pass、Bearer 头）。
 * 刻意不去猜「看起来像密码的普通单词」—— 误伤比漏报更让人不敢用。
 */
public final class SecureRedactor {

    /** 一次替换的统计。 */
    public static final class Result {
        public final String text;
        public final int hits;
        public final List<String> kinds;

        Result(String text, int hits, List<String> kinds) {
            this.text = text;
            this.hits = hits;
            this.kinds = kinds;
        }

        public boolean changed() {
            return hits > 0;
        }
    }

    /** 规则：名字 + 正则 + 保留的头尾。 */
    private static final class Rule {
        final String kind;
        final Pattern pattern;
        final int keepHead;
        final int keepTail;

        Rule(String kind, String regex, int keepHead, int keepTail) {
            this.kind = kind;
            this.pattern = Pattern.compile(regex);
            this.keepHead = keepHead;
            this.keepTail = keepTail;
        }
    }

    private static final List<Rule> RULES = new ArrayList<>();
    /** 宽松规则：只有 includePair 打开时才生效。 */
    private static final List<Rule> STRICT = new ArrayList<>();

    static {
        // PEM 私钥整块抹掉（只留类型行，方便用户知道自己漏了什么）
        RULES.add(new Rule("private-key",
                "-----BEGIN ([A-Z ]*PRIVATE KEY)-----[\\s\\S]*?-----END \\1-----", 0, 0));
        // AWS
        RULES.add(new Rule("aws-key", "\\bAKIA[0-9A-Z]{16}\\b", 4, 4));
        // OpenAI / Anthropic / GitHub / Slack / Google / 阿里云 / 魔搭 等常见前缀
        RULES.add(new Rule("api-key", "\\b(sk|rk|pk|ghp|gho|ghs|ghu|github_pat|xoxb|xoxp|AIza)[-_A-Za-z0-9]{12,}\\b", 6, 4));
        // JWT
        RULES.add(new Rule("jwt", "\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b", 6, 4));
        // URL 里的凭据
        RULES.add(new Rule("url-credential",
                "(?i)\\b([a-z][a-z0-9+.-]*://)([^\\s/:@]+):([^\\s/@]+)@", 0, 0));
        // Authorization 头
        RULES.add(new Rule("auth-header", "(?i)(authorization\\s*[:=]\\s*)(bearer|basic|token)\\s+[A-Za-z0-9._~+/=-]{8,}", 0, 0));
        // 明确的凭据字段名（password/secret/token/api_key…）在等号/冒号后面跟着长串值：
        // 字段名本身就是强信号，所以不算「宽松规则」，默认就抹
        RULES.add(new Rule("secret-pair",
                "(?i)\\b([a-z0-9_.-]*(?:api[_-]?key|secret|token|passwd|password|access[_-]?key)[a-z0-9_.-]*)\\s*[:=]\\s*[\"']?([^\\s\"',;]{8,})[\"']?", 0, 0));
        // 更长的自由形式密钥（无字段名，只靠形状）：这条最宽松，默认不开
        STRICT.add(new Rule("secret-blob",
                "\\b[A-Za-z0-9+/=_-]{32,}\\b", 4, 2));
    }

    private SecureRedactor() {
    }

    /** 默认脱敏。 */
    public static Result redact(String text) {
        return redact(text, true);
    }

    /**
     * @param includePair 是否处理「key = value」这类宽松形式；写日志时打开，展示代码时可能想关掉
     */
    public static Result redact(String text, boolean includePair) {
        List<String> kinds = new ArrayList<>();
        if (text == null || text.isEmpty()) return new Result("", 0, kinds);
        String out = text;
        int total = 0;
        List<Rule> rules = new ArrayList<>(RULES);
        if (includePair) rules.addAll(STRICT);
        for (Rule r : rules) {
            Matcher m = r.pattern.matcher(out);
            StringBuffer sb = new StringBuffer();
            int n = 0;
            while (m.find()) {
                n++;
                m.appendReplacement(sb, Matcher.quoteReplacement(matchReplacement(m.group(), r)));
            }
            if (n > 0) {
                m.appendTail(sb);
                out = sb.toString();
                total += n;
                if (!kinds.contains(r.kind)) kinds.add(r.kind);
            }
        }
        return new Result(out, total, kinds);
    }

    private static String matchReplacement(String hit, Rule r) {
        if ("url-credential".equals(r.kind)) {
            // 保留协议与用户名，抹掉密码
            Matcher m = Pattern.compile("(?i)^([a-z][a-z0-9+.-]*://)([^\\s/:@]+):([^\\s/@]+)@$").matcher(hit);
            if (m.matches()) return m.group(1) + m.group(2) + ":***@";
            return "***";
        }
        if ("auth-header".equals(r.kind)) {
            int sp = hit.lastIndexOf(' ');
            if (sp > 0) return hit.substring(0, sp + 1) + "***";
            return "***";
        }
        if ("secret-pair".equals(r.kind)) {
            Matcher m = Pattern.compile("^([a-zA-Z0-9_.-]+)(\\s*[:=]\\s*)([\"']?)(.*)$").matcher(hit);
            if (m.matches()) {
                return m.group(1) + m.group(2) + m.group(3) + mask(m.group(4), 0, 0);
            }
            return "***";
        }
        if ("private-key".equals(r.kind)) {
            return "-----BEGIN " + (hit.contains("RSA") ? "RSA " : "") + "PRIVATE KEY----- [REDACTED] -----END PRIVATE KEY-----";
        }
        return mask(hit, r.keepHead, r.keepTail);
    }

    private static String mask(String s, int head, int tail) {
        if (s == null) return "***";
        if (s.length() <= head + tail + 2) return "***";
        return s.substring(0, head) + "***" + s.substring(s.length() - tail);
    }

    /** 文本里有没有「无字段名的长随机串」（宽松规则）。单独判，避免被贪婪匹配影响。 */
    static boolean hasBlob(String text) {
        if (text == null) return false;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("[A-Za-z0-9+/=_-]{32,}").matcher(text);
        while (m.find()) {
            String hit = m.group();
            // 纯字母或纯数字的长串多半是普通文本（如长单词、时间戳），要求混合
            boolean digit = hit.matches(".*\\d.*");
            boolean alpha = hit.matches(".*[A-Za-z].*");
            if (digit && alpha) return true;
        }
        return false;
    }

    /** 一段话告诉用户「抹掉了什么」，用于工具结果卡片。 */
    public static String describe(Result r, boolean zh) {
        if (r == null || !r.changed()) return "";
        String kinds = String.join(", ", r.kinds);
        return zh
                ? "（已脱敏 " + r.hits + " 处敏感信息：" + kinds + "）"
                : "(redacted " + r.hits + " secret(s): " + kinds + ")";
    }

    /**
     * 只判断「像不像含密钥」，不修改。给「要不要提醒用户」用。
     */
    public static boolean containsSecret(String text) {
        return redact(text).changed() || hasBlob(text);
    }
}
