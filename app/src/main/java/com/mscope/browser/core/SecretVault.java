package com.mscope.browser.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 长期记忆的入口闸门：写进去之前先把密钥类东西抹掉。
 *
 * <p>「跨会话记住这个用户」这件事本身有风险：用户贴一次 token 让你帮忙排错，
 * 你顺手记住了，它就永久躺在 memory 文件里。所以脱敏必须在入口做，
 * 而不是指望调用方记得。
 *
 * <p>规则刻意保守：宁可多抹（把疑似的东西打成 {@code <redacted>}），
 * 也不留一条真密钥。格式识别覆盖 GitHub / OpenAI / AWS / Slack / 私钥块 /
 * JWT / 敏感键值对（password=、api_key:）。
 */
public final class SecretVault {

    private SecretVault() {
    }

    /** 命中一条规则。 */
    public static final class Hit {
        public final String kind;
        public final int start;
        public final int end;

        Hit(String kind, int start, int end) {
            this.kind = kind;
            this.start = start;
            this.end = end;
        }
    }

    private static final Object[][] RULES = {
            {"github", Pattern.compile("gh[pousr]_[A-Za-z0-9]{16,}", Pattern.MULTILINE)},
            {"openai", Pattern.compile("sk-[A-Za-z0-9_\\-]{20,}")},
            {"aws", Pattern.compile("AKIA[0-9A-Z]{16}")},
            {"slack", Pattern.compile("xox[abprs]-[A-Za-z0-9\\-]{10,}")},
            {"jwt", Pattern.compile("eyJ[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}")},
            {"private_key", Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----")},
            {"kv", Pattern.compile("(?i)\\b(password|passwd|secret|token|api[_\\-]?key|access[_\\-]?key)\\b\\s*[:=]\\s*[\"']?([^\\s\"',;]{6,})")},
            {"url_cred", Pattern.compile("://[^/\\s:@]+:[^/\\s@]+@")},
    };

    /** 找出全部命中（按位置排序、去重叠）。 */
    public static List<Hit> scan(String text) {
        List<Hit> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        for (Object[] rule : RULES) {
            String kind = (String) rule[0];
            Matcher m = ((Pattern) rule[1]).matcher(text);
            while (m.find()) {
                int s = m.start();
                int e = m.end();
                if ("kv".equals(kind) && m.groupCount() >= 2 && m.group(2) != null) {
                    // 只抹值，保留键名，否则「password=<redacted>」变成一句废话
                    s = m.start(2);
                    e = m.end(2);
                }
                if ("url_cred".equals(kind)) {
                    // 保留协议与 @，抹掉中间的 user:pass
                    s = m.start() + 3;
                }
                out.add(new Hit(kind, s, e));
            }
        }
        out.sort((a, b) -> Integer.compare(a.start, b.start));
        List<Hit> merged = new ArrayList<>();
        int at = -1;
        for (Hit h : out) {
            if (h.start < at) continue;
            merged.add(h);
            at = h.end;
        }
        return merged;
    }

    /** 抹掉密钥，返回可安全落盘的文本。 */
    public static String redact(String text) {
        if (text == null) return "";
        List<Hit> hits = scan(text);
        if (hits.isEmpty()) return text;
        StringBuilder sb = new StringBuilder();
        int at = 0;
        for (Hit h : hits) {
            sb.append(text, at, h.start).append("<redacted:").append(h.kind).append('>');
            at = h.end;
        }
        sb.append(text, at, text.length());
        return sb.toString();
    }

    /** 是否含有疑似密钥（用于在界面上给出提示，而不是静默丢弃）。 */
    public static boolean containsSecret(String text) {
        return !scan(text).isEmpty();
    }
}
