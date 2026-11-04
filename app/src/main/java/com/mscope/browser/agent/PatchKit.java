package com.mscope.browser.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 按片段改文件：模型只给出「要替换的原文」和「替换成什么」，不必重写整个文件。
 *
 * <p>为什么需要它：小模型重写整个文件时几乎必丢内容 —— 一份 300 行的 HTML，改一个按钮
 * 回来只剩 120 行。片段替换把破坏面压到最小，而且天然带校验：原文找不到就说明模型
 * 记错了，此时**什么都不改**并告诉它实际内容长什么样，比默默写坏文件好得多。
 *
 * <p>容错做了三件：去首尾空白差异、全角半角引号混用、以及唯一的「模糊定位」
 * （去空白后唯一匹配才认）。模拟 apply_patch 的语义，但不依赖外部工具。
 */
public final class PatchKit {

    /** 一个替换片段。 */
    public static final class Hunk {
        public final String find;
        public final String replace;

        public Hunk(String find, String replace) {
            this.find = find == null ? "" : find;
            this.replace = replace == null ? "" : replace;
        }
    }

    /** 应用结果。 */
    public static final class Result {
        public final boolean ok;
        public final String text;
        public final int applied;
        public final String error;
        /** 相似度最高的候选片段，失败时给模型看，帮它把原文写对。 */
        public final String hint;

        Result(boolean ok, String text, int applied, String error, String hint) {
            this.ok = ok;
            this.text = text;
            this.applied = applied;
            this.error = error;
            this.hint = hint;
        }
    }

    private static final int HINT_WINDOW = 800;

    private PatchKit() {
    }

    /** 依次应用全部片段；任一片段失败则整体不生效（返回原文），避免半截改动。 */
    public static Result apply(String original, List<Hunk> hunks, boolean zh) {
        String text = original == null ? "" : original;
        if (hunks == null || hunks.isEmpty()) {
            return new Result(false, text, 0, zh ? "错误：没有给出替换片段" : "error: no hunks given", "");
        }
        String cur = text;
        int applied = 0;
        for (int i = 0; i < hunks.size(); i++) {
            Hunk h = hunks.get(i);
            if (h.find.isEmpty()) {
                return new Result(false, text, applied,
                        (zh ? "错误：第 " : "error: hunk #") + (i + 1)
                                + (zh ? " 个片段的 find 为空" : " has an empty find"), "");
            }
            String[] found = locate(cur, h.find);
            if (found == null) {
                return new Result(false, text, applied,
                        (zh ? "错误：第 " : "error: hunk #") + (i + 1)
                                + (zh ? " 个片段的 find 在文件里找不到，文件未改动" : " find text not found; file untouched"),
                        hintFor(cur, h.find));
            }
            String actual = found[0];
            int at = Integer.parseInt(found[1]);
            cur = cur.substring(0, at) + h.replace + cur.substring(at + actual.length());
            applied++;
        }
        return new Result(true, cur, applied, "", "");
    }

    /**
     * 定位 find。
     *
     * @return {实际匹配到的文本, 起始下标}；找不到返回 null
     */
    static String[] locate(String text, String find) {
        if (text == null || find == null || find.isEmpty()) return null;
        int at = text.indexOf(find);
        if (at >= 0 && text.indexOf(find, at + 1) < 0) return new String[]{find, String.valueOf(at)};
        if (at >= 0) {
            // 原样片段出现多处：只有能靠「按行唯一」定到某一处时才改，定不出来就让调用方失败。
            //
            // 旧的实现在这里直接 return 第一处，于是后面所有唯一性检查都成了摆设 ——
            // 模型给的片段在文件里出现两次时（模板代码、重复的 <div> 都是常态），
            // 改动会落在用户没预期的位置，而且界面上看不出来。
            return locateFuzzy(text, find);
        }
        return locateFuzzy(text, find);
    }

    /**
     * 模糊定位：只接受**唯一**命中。任何一处出现多个候选就返回 null，
     * 由调用方决定是退回原样片段还是报失败 —— 猜一次改错的代价，远高于让模型重试一次。
     */
    private static String[] locateFuzzy(String text, String find) {
        String trimmed = find.trim();
        // 单行片段先按「行」比一次：原文里同一段常常出现多次（重复的 <div>、模板代码），
        // 只有按行比对才可能看出模型指的是哪一处。判不出唯一就返回 null ——
        // 后面那几个「忽略空白 / 归一引号」的分支只会更宽松，绝不能让它们接手猜。
        if (!trimmed.isEmpty() && find.indexOf('\n') < 0) {
            String[] line = locateSingleLine(text, trimmed);
            if (line != null) return line;
            if (countOccurrences(text, trimmed) > 1) return null;
        }
        // 换行符差异。两条路都不靠换算下标：
        //   · 原文是 LF、片段是 CRLF → 把片段的 \r\n 折成 \n 后长度不变，直接用它重找；
        //   · 原文是 CRLF、片段是 LF → 长度会变，归一后的下标对不上原文，
        //     只能退回逐行定位，命中后用该行在原文中的**真实区间**。
        // 拿错下标切出来的片段会把文件写坏，那是比「找不到」严重得多的结果。
        String lf = find.replace("\r\n", "\n");
        String textLf = text.replace("\r\n", "\n");
        if (!lf.equals(find) && textLf.length() == text.length()) {
            int i = text.indexOf(lf);
            if (i >= 0 && text.indexOf(lf, i + 1) < 0) {
                return new String[]{lf, String.valueOf(i)};
            }
        }
        if (textLf.length() != text.length()) {
            // 原文含 CRLF：按行找，命中后用该行在原文中的真实区间
            String[] hit = locateByLines(text, lf);
            if (hit != null) return hit;
        }
        // 首尾空白差异的兜底（多行片段走这里）：同样只接受唯一命中
        if (!trimmed.isEmpty() && !trimmed.equals(find)) {
            int i = text.indexOf(trimmed);
            if (i >= 0 && text.indexOf(trimmed, i + 1) < 0) {
                return new String[]{trimmed, String.valueOf(i)};
            }
        }
        // 引号归一（模型常把 " 写成 “ 或 '）
        String normFind = normalizeQuotes(find);
        String normText = normalizeQuotes(text);
        if (normFind.length() == find.length() && normText.length() == text.length()) {
            int i = normText.indexOf(normFind);
            // 同样要求唯一命中：多处命中时宁可不改，也不猜
            if (i >= 0 && normText.indexOf(normFind, i + 1) < 0) {
                return new String[]{text.substring(i, i + normFind.length()), String.valueOf(i)};
            }
        }
        // 单行片段：忽略行内连续空白的差异再试一次（模型复制代码时经常把缩进换了）。
        // 只对单行做，多行时行边界本身就是对齐信息，按空白压缩反而容易错位。
        if (find.indexOf('\n') < 0) {
            String[] hit = locateSingleLine(text, find);
            if (hit != null) return hit;
        }
        return null;
    }

    /**
     * 单行片段的行内空白模糊定位。
     *
     * <p>逐行拿「压缩掉连续空白」后的内容比对，命中后用**该行在原文中的区间**，
     * 而不是片段本身的区间 —— 这一步是整段替换里最容易被写错的地方。
     * 命中多行时返回 null：宁可失败，也不猜是哪一行。
     */
    private static String[] locateSingleLine(String text, String find) {
        String want = collapse(find.trim());
        if (want.isEmpty()) return null;
        String found = null;
        int foundAt = -1;
        int lineStart = 0;
        while (lineStart <= text.length()) {
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0) lineEnd = text.length();
            String line = text.substring(lineStart, lineEnd).replace("\r", "");
            if (collapse(line.trim()).equals(want)) {
                if (found != null) return null;          // 多处命中
                found = text.substring(lineStart, lineEnd);
                foundAt = lineStart;
            }
            if (lineEnd >= text.length()) break;
            lineStart = lineEnd + 1;
        }
        return found == null ? null : new String[]{found, String.valueOf(foundAt)};
    }

    /** 子串出现次数（上限 2，只为判断「是否唯一」）。 */
    private static int countOccurrences(String text, String sub) {
        if (text == null || sub == null || sub.isEmpty()) return 0;
        int n = 0;
        int at = text.indexOf(sub);
        while (at >= 0) {
            n++;
            if (n > 1) return n;
            at = text.indexOf(sub, at + 1);
        }
        return n;
    }

    /** 把连续空白压成一个空格，用于单行模糊比对。 */
    private static String collapse(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean space = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\u3000') {
                space = true;
                continue;
            }
            if (space && sb.length() > 0) sb.append(' ');
            space = false;
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 按行定位，返回原文中的真实区间。用于原文含 CRLF、而模型给的是 LF 的情况。
     * 逐行比对去空白后的内容，连续命中才算。
     */
    private static String[] locateByLines(String text, String findLf) {
        String[] want = findLf.split("\n", -1);
        if (want.length == 0) return null;
        int lineStart = 0;
        while (lineStart <= text.length()) {
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0) lineEnd = text.length();
            String line = text.substring(lineStart, lineEnd);
            if (line.replace("\r", "").trim().equals(want[0].trim())) {
                int at = lineStart;
                int cur = lineEnd + 1;
                boolean ok = true;
                for (int k = 1; k < want.length; k++) {
                    int e = text.indexOf('\n', cur);
                    if (e < 0) e = text.length();
                    String next = text.substring(cur, e);
                    if (!next.replace("\r", "").trim().equals(want[k].trim())) {
                        ok = false;
                        break;
                    }
                    cur = Math.min(e + 1, text.length());
                }
                if (ok) {
                    int end = cur > 0 && cur <= text.length() ? cur : text.length();
                    if (end > at && text.charAt(end - 1) == '\n') end--;
                    return new String[]{text.substring(at, end), String.valueOf(at)};
                }
            }
            if (lineEnd >= text.length()) break;
            lineStart = lineEnd + 1;
        }
        return null;
    }

    /**
     * 引号/空白归一。**必须逐字符替换且不改变长度**，否则归一后的下标就对不上原文，
     * 按它切出来的片段会错位（这里踩过一次）。
     */
    private static String normalizeQuotes(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\u201c':
                case '\u201d':
                    sb.append('"');
                    break;
                case '\u2018':
                case '\u2019':
                    sb.append('\'');
                    break;
                case '\u3000':
                    sb.append(' ');
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 失败时找「最像」的一段，帮模型把原文抄对。按首行做锚点。 */
    static String hintFor(String text, String find) {
        if (text == null || text.isEmpty() || find == null) return "";
        String[] findLines = find.split("\n");
        String anchor = findLines[0].trim();
        if (anchor.length() < 4) {
            return text.length() > HINT_WINDOW ? text.substring(0, HINT_WINDOW) : text;
        }
        int i = text.indexOf(anchor);
        if (i < 0) {
            // 退一步：用最长的一行去凑
            String longest = "";
            for (String l : findLines) if (l.trim().length() > longest.length()) longest = l.trim();
            if (longest.length() >= 4) i = text.indexOf(longest);
        }
        if (i < 0) return text.length() > HINT_WINDOW ? text.substring(0, HINT_WINDOW) : text;
        int from = Math.max(0, i - 200);
        int to = Math.min(text.length(), i + HINT_WINDOW);
        return text.substring(from, to);
    }

    /** 从模型给的 JSON 里读 hunks：支持 {find,replace} 数组与 patch 文本两种。 */
    public static List<Hunk> parseHunks(Object json) {
        List<Hunk> out = new ArrayList<>();
        java.util.List<Object> arr = com.mscope.browser.core.JsonAccess.list(json, null);
        for (Object o : arr) {
            String find = com.mscope.browser.core.JsonAccess.str(o, "find", null);
            String replace = com.mscope.browser.core.JsonAccess.str(o, "replace", null);
            if (find == null) find = com.mscope.browser.core.JsonAccess.str(o, "old", null);
            if (replace == null) replace = com.mscope.browser.core.JsonAccess.str(o, "new", null);
            if (find != null) out.add(new Hunk(find, replace));
        }
        return out;
    }

    /**
     * 解析简化的 patch 文本，格式：
     * <pre>
     * &lt;&lt;&lt;&lt;&lt;&lt;&lt; FIND
     * 原文
     * =======
     * 替换
     * &gt;&gt;&gt;&gt;&gt;&gt;&gt; REPLACE
     * </pre>
     */
    public static List<Hunk> parsePatchText(String text) {
        List<Hunk> out = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return out;
        Pattern head = Pattern.compile("(?m)^<{4,}\\s*(?:FIND|SEARCH|OLD)?\\s*$");
        java.util.regex.Matcher m = head.matcher(text);
        List<int[]> marks = new ArrayList<>();
        while (m.find()) marks.add(new int[]{m.start(), m.end()});
        for (int i = 0; i < marks.size(); i++) {
            int from = marks.get(i)[1];
            int to = i + 1 < marks.size() ? marks.get(i + 1)[0] : text.length();
            String block = text.substring(from, to);
            String find = null;
            String replace = null;
            java.util.regex.Matcher eq = Pattern.compile("(?m)^={4,}\\s*$").matcher(block);
            if (eq.find()) {
                find = block.substring(0, eq.start());
                String rest = block.substring(eq.end());
                java.util.regex.Matcher end = Pattern.compile("(?m)^>{4,}.*$").matcher(rest);
                replace = end.find() ? rest.substring(0, end.start()) : rest;
            }
            if (find != null) out.add(new Hunk(stripOneLeadingNewline(find), stripOneLeadingNewline(replace)));
        }
        return out;
    }

    private static String stripOneLeadingNewline(String s) {
        if (s == null) return "";
        String t = s;
        if (t.startsWith("\r\n")) t = t.substring(2);
        else if (t.startsWith("\n")) t = t.substring(1);
        return t;
    }

    /** 给用户看的摘要。 */
    public static String describe(int hunks, int bytes, boolean zh) {
        return zh ? "应用 " + hunks + " 处片段替换（" + bytes + " 字节）"
                : "applied " + hunks + " hunk(s) (" + bytes + " bytes)";
    }

    /** 是否像 patch 文本（供工具自动识别）。 */
    public static boolean looksLikePatch(String s) {
        if (s == null) return false;
        String t = s.toLowerCase(Locale.ROOT);
        return t.contains("<<<<<<<") && t.contains("=======");
    }
}
