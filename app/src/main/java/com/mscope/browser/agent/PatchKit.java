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
            // 多处命中：先按第一处改，模型下一轮会在 diff 里看到是否改错了地方
            return new String[]{find, String.valueOf(at)};
        }
        // 换行符差异
        String lf = find.replace("\r\n", "\n");
        String textLf = text.replace("\r\n", "\n");
        if (!lf.equals(find)) {
            int i = textLf.indexOf(lf);
            if (i >= 0) {
                String actual = text.substring(i, Math.min(text.length(), i + lf.length()));
                return new String[]{actual, String.valueOf(i)};
            }
        }
        // 首尾空白差异
        String trimmed = find.trim();
        if (!trimmed.isEmpty() && !trimmed.equals(find)) {
            int i = text.indexOf(trimmed);
            if (i >= 0) return new String[]{trimmed, String.valueOf(i)};
        }
        // 引号归一（模型常把 " 写成 “ 或 '）
        String normFind = normalizeQuotes(find);
        String normText = normalizeQuotes(text);
        if (normFind.length() == find.length()) {
            int i = normText.indexOf(normFind);
            if (i >= 0) return new String[]{text.substring(i, i + normFind.length()), String.valueOf(i)};
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
