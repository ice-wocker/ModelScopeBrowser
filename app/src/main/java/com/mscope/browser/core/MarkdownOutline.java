package com.mscope.browser.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 从 Markdown 文本里抽标题大纲，给「长回复」加一张导航图。
 *
 * <p>本地小模型经常一口气写两三千字，手机上翻回去找某一节很难受。
 * 这里只做一件小事：扫一遍 ATX 标题与围栏代码块（跳过代码块里的 # 注释），
 * 按层级压平成一个列表，界面用一个下拉菜单展示即可。
 */
public final class MarkdownOutline {

    public static final class Heading {
        public final int level;
        public final String text;
        public final int line;

        public Heading(int level, String text, int line) {
            this.level = level;
            this.text = text;
            this.line = line;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < level; i++) sb.append("  ");
            return sb.append(text).toString();
        }
    }

    private MarkdownOutline() {
    }

    /** 抽取标题；代码块内的 # 不算标题。 */
    public static List<Heading> extract(String markdown) {
        List<Heading> out = new ArrayList<>();
        if (markdown == null || markdown.isEmpty()) return out;
        String[] lines = markdown.split("\n", -1);
        boolean inFence = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFence = !inFence;
                continue;
            }
            if (inFence) continue;
            if (!trimmed.startsWith("#")) continue;
            int level = 0;
            while (level < trimmed.length() && trimmed.charAt(level) == '#') level++;
            if (level > 6) continue;
            if (level >= trimmed.length()) continue;
            char sep = trimmed.charAt(level);
            if (sep != ' ' && sep != '\t') continue;
            String text = trimmed.substring(level).trim();
            if (text.isEmpty()) continue;
            out.add(new Heading(level, stripInline(text), i));
        }
        return out;
    }

    /** 去掉行内的 ** __ ` [](...) 之类标记，留下纯文字。 */
    private static String stripInline(String s) {
        String t = s.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
        t = t.replaceAll("[*_`~]", "");
        t = t.replaceAll("<[^>]+>", "");
        t = t.replaceAll("\\s+", " ").trim();
        return t;
    }

    /** 文本是否「够长到值得给大纲」。 */
    public static boolean worthOutline(String markdown) {
        if (markdown == null) return false;
        return markdown.length() > 800 && extract(markdown).size() >= 2;
    }
}
