package com.mscope.browser.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 双语资源的对账工具。
 *
 * <p>这个 App 的中英文案是两份文件（{@code values/strings.xml} 与
 * {@code values-zh/strings.xml}）。少一条不会崩，只是那一句在中文界面下
 * 显示成英文 —— 而没人会为了一个单词去翻资源文件，于是它就一直在那儿。
 *
 * <p>所以把这件事交给单测：解析两份 XML，比对键集合与占位符，
 * 不一致就让测试失败。这里只做纯文本解析，不依赖 Android。
 */
public final class LangKey {

    private LangKey() {
    }

    /** 一条字符串资源。 */
    public static final class Entry {
        public final String name;
        public final String value;

        Entry(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }

    /**
     * 解析 {@code <string name="x">value</string>}。
     *
     * <p>不追求通用 XML：Android 的 strings.xml 里可能出现 {@code <b>}、
     * {@code <xliff:g>} 内联标签，所以这里只按「第一个 &gt; 到最后一个 &lt;/string&gt;」
     * 取内容，够用且不会被内联标签骗到。
     */
    public static List<Entry> parse(String xml) {
        List<Entry> out = new ArrayList<>();
        if (xml == null) return out;
        int at = 0;
        while (true) {
            int s = xml.indexOf("<string", at);
            if (s < 0) break;
            int nameAt = xml.indexOf("name=\"", s);
            if (nameAt < 0) break;
            int nameEnd = xml.indexOf('"', nameAt + 6);
            if (nameEnd < 0) break;
            // 属性区与内容区以第一个 '>' 为界，但必须跳过 "%1$s" 之类不受影响
            int tagEnd = xml.indexOf('>', nameEnd);
            if (tagEnd < 0) break;
            boolean selfClose = xml.charAt(tagEnd - 1) == '/';
            int close = xml.indexOf("</string>", tagEnd);
            String name = xml.substring(nameAt + 6, nameEnd).trim();
            if (selfClose || close < 0) {
                out.add(new Entry(name, ""));
                at = tagEnd + 1;
                continue;
            }
            out.add(new Entry(name, xml.substring(tagEnd + 1, close)));
            at = close + 9;
        }
        return out;
    }

    /** 提取格式化占位符（{@code %1$d}、{@code %s}、{@code %.2f}），排序去重。 */
    public static List<String> placeholders(String value) {
        List<String> out = new ArrayList<>();
        if (value == null) return out;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) != '%') continue;
            int j = i + 1;
            if (j < value.length() && value.charAt(j) == '%') {
                i = j;                      // 转义的百分号
                continue;
            }
            StringBuilder sb = new StringBuilder("%");
            while (j < value.length()) {
                char c = value.charAt(j);
                sb.append(c);
                j++;
                if ("sdfxXcbeEgGaAhHo".indexOf(c) >= 0) break;
            }
            String ph = sb.toString();
            if (!out.contains(ph)) out.add(ph);
            i = j - 1;
        }
        return out;
    }

    /** 对账结果。 */
    public static final class Diff {
        public final List<String> missingInZh = new ArrayList<>();
        public final List<String> extraInZh = new ArrayList<>();
        public final List<String> placeholderMismatch = new ArrayList<>();

        public boolean clean() {
            return missingInZh.isEmpty() && extraInZh.isEmpty() && placeholderMismatch.isEmpty();
        }

        public String report() {
            StringBuilder sb = new StringBuilder();
            if (!missingInZh.isEmpty()) sb.append("missing in values-zh: ").append(missingInZh).append('\n');
            if (!extraInZh.isEmpty()) sb.append("only in values-zh: ").append(extraInZh).append('\n');
            if (!placeholderMismatch.isEmpty()) sb.append("placeholder mismatch: ").append(placeholderMismatch).append('\n');
            return sb.length() == 0 ? "ok" : sb.toString().trim();
        }
    }

    /** 比对两份字符串资源。 */
    public static Diff diff(String baseXml, String zhXml) {
        List<Entry> base = parse(baseXml);
        List<Entry> zh = parse(zhXml);
        Diff d = new Diff();
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        for (Entry e : base) m.put(e.name, e.value);
        java.util.Map<String, String> z = new java.util.LinkedHashMap<>();
        for (Entry e : zh) z.put(e.name, e.value);

        for (String k : m.keySet()) if (!z.containsKey(k)) d.missingInZh.add(k);
        for (String k : z.keySet()) if (!m.containsKey(k)) d.extraInZh.add(k);
        for (String k : m.keySet()) {
            if (!z.containsKey(k)) continue;
            List<String> a = placeholders(m.get(k));
            List<String> b = placeholders(z.get(k));
            if (!a.equals(b)) d.placeholderMismatch.add(k + " " + a + " vs " + b);
        }
        return d;
    }
}
