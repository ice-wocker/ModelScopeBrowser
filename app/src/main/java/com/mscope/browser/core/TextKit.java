package com.mscope.browser.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 与业务无关的文本小工具。
 *
 * <p>都是为了修一类具体的坑：UTF-8 截断（token 边界把中文切成半个字符）、
 * 文件名清洗（模型生成的标题带 /\:*?"<>| 直接写盘会失败）、
 * 以及给长文件名补哈希前缀（部分文件系统限制 255 字节）。
 */
public final class TextKit {

    private TextKit() {
    }

    /**
     * 按「完整字符」截断 UTF-8 字符串，绝不留半个字符。
     *
     * @param maxBytes 目标字节上限
     */
    public static String trimUtf8(String s, int maxBytes) {
        if (s == null) return "";
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) return s;
        // 注意 cut 必须先夹到合法下标：maxBytes 等于或大于实际长度时这里不会进来，
        // 但一旦传进来的上限比数组还大就会 ArrayIndexOutOfBounds。
        int limit = Math.min(Math.max(0, maxBytes), bytes.length);
        // 从上限往前退，直到不落在多字节序列中间
        int cut = limit;
        while (cut > 0 && (bytes[cut] & 0xC0) == 0x80) cut--;
        return new String(bytes, 0, cut, StandardCharsets.UTF_8);
    }

    /** 判断一段字节的末尾是否是一个完整字符（用于流式拼接时决定要不要等下一块）。 */
    public static boolean endsWithCompleteChar(byte[] bytes, int len) {
        if (bytes == null || len <= 0) return true;
        int i = len - 1;
        int back = 0;
        while (i >= 0 && (bytes[i] & 0xC0) == 0x80) {
            i--;
            back++;
        }
        if (i < 0) return false;
        int lead = bytes[i] & 0xFF;
        int need;
        if ((lead & 0x80) == 0) {
            need = 1;
        } else if ((lead & 0xE0) == 0xC0) {
            need = 2;
        } else if ((lead & 0xF0) == 0xE0) {
            need = 3;
        } else if ((lead & 0xF8) == 0xF0) {
            need = 4;
        } else {
            return false;
        }
        return back + 1 >= need;
    }

    /**
     * 把模型给的标题清洗成安全文件名。
     *
     * <p>替换路径分隔符与控制字符，去掉 Windows 设备名与尾部点号，压掉连续下划线。
     * 结果可能为空，调用方需兜底。
     */
    public static String safeFileName(String raw, int maxBytes) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 0x20 || c == 0x7F) continue;
            switch (c) {
                case '/':
                case '\\':
                case ':':
                case '*':
                case '?':
                case '"':
                case '<':
                case '>':
                case '|':
                    sb.append('_');
                    break;
                default:
                    sb.append(c);
            }
        }
        String s = sb.toString().trim();
        s = s.replaceAll("_+", "_").replaceAll("^[._]+", "").replaceAll("[. ]+$", "");
        if (s.isEmpty()) return "";
        String lower = s.toLowerCase(Locale.ROOT);
        if (isReservedWinName(lower)) s = "_" + s;
        int limit = maxBytes > 0 ? maxBytes : 120;
        if (s.getBytes(StandardCharsets.UTF_8).length > limit) {
            String hash = shortHash(s, 8);
            String keep = trimUtf8(s, Math.max(8, limit - 9));
            s = keep + "~" + hash;
        }
        return s;
    }

    private static boolean isReservedWinName(String lower) {
        String base = lower;
        int dot = base.indexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        switch (base) {
            case "con":
            case "prn":
            case "aux":
            case "nul":
                return true;
            default:
                break;
        }
        return base.matches("com[1-9]") || base.matches("lpt[1-9]");
    }

    /** 短哈希（FNV-1a 32 位，十六进制）。不需要密码学强度，只要稳定且够散。 */
    public static String shortHash(String s, int hexLen) {
        if (s == null) return "0";
        int hash = 0x811C9DC5;
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            hash ^= b & 0xFF;
            hash *= 0x01000193;
        }
        String hex = Integer.toHexString(hash);
        while (hex.length() < 8) hex = "0" + hex;
        int n = Math.max(1, Math.min(hexLen, 8));
        return hex.substring(hex.length() - n);
    }

    /** SHA-256 十六进制，用于校验与去重标记。 */
    public static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 估算混合中英文文本的 token 数（中文约 1 字 1 token，英文约 4 字符 1 token）。 */
    public static int roughTokens(String s) {
        if (s == null || s.isEmpty()) return 0;
        int cjk = 0, other = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (isCjk(cp)) {
                cjk++;
            } else {
                other++;
            }
            i += Character.charCount(cp);
        }
        return cjk + (int) Math.ceil(other / 3.6);
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF)      // 基本区
                || (cp >= 0x3400 && cp <= 0x4DBF)  // 扩展 A
                || (cp >= 0x3000 && cp <= 0x303F)  // 标点
                || (cp >= 0xFF00 && cp <= 0xFFEF); // 全角
    }

    /** 用正则把文本切成句子，保留标点；用于「继续生成」时判断是否已收尾。 */
    public static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return out;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean end = c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?'
                    || c == '\n';
            if (end) {
                String seg = text.substring(start, i + 1).trim();
                if (!seg.isEmpty()) out.add(seg);
                start = i + 1;
            }
        }
        if (start < text.length()) {
            String seg = text.substring(start).trim();
            if (!seg.isEmpty()) out.add(seg);
        }
        return out;
    }

    /** 文本看起来是否「已经写完」（以句末标点或代码块闭合成结尾）。 */
    public static boolean looksComplete(String text) {
        if (text == null) return false;
        String t = text.trim();
        if (t.isEmpty()) return false;
        if (t.endsWith("```")) return true;
        char last = t.charAt(t.length() - 1);
        return last == '。' || last == '.' || last == '！' || last == '!' || last == '？' || last == '?'
                || last == ')' || last == '）' || last == '}' || last == ']' || last == '；' || last == ';';
    }
}
