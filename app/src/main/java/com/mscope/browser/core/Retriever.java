package com.mscope.browser.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 工作区/记忆的轻量检索：BM25 + 中文二元组切分，纯 Java、零模型、零依赖。
 *
 * <p>为什么不用向量：在手机上跑 embedding 模型意味着再多一份 .so、再多一块内存、
 * 再多几十毫秒的冷启动；而代码、配置、日志这类「用户真正想搜」的东西，
 * 关键词匹配的召回本来就不差。向量检索的价值在于语义近似（「怎么让手机不卡」
 * 命中「内存优化」），那类查询交给模型改写关键词更划算。
 *
 * <p>中文没有空格，直接按字切会把「内存」拆成「内」「存」两个高频字，
 * 于是「内」和「存」各自命中半篇文章。这里按 2-gram 切：
 * 「内存不足」→ 内存/存不/不足，既保留了词的边界，又不需要词典。
 */
public final class Retriever {

    private Retriever() {
    }

    /** 一篇可检索的文档（顺序即 id）。 */
    public static final class Doc {
        public final String source;
        public final String text;

        public Doc(String source, String text) {
            this.source = source == null ? "" : source;
            this.text = text == null ? "" : text;
        }
    }

    /** 一条检索结果。 */
    public static final class Result {
        public final String source;
        public final String snippet;
        public final double score;
        public final int index;

        Result(int index, String source, String snippet, double score) {
            this.index = index;
            this.source = source;
            this.snippet = snippet;
            this.score = score;
        }
    }

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    /**
     * 检索。切块在调用前完成（见 {@link #chunk}），这里只做打分。
     *
     * @param docs 文档块
     * @param query 查询串
     * @param topK 返回条数
     */
    public static List<Result> search(List<Doc> docs, String query, int topK) {
        List<Result> out = new ArrayList<>();
        if (docs == null || docs.isEmpty() || query == null || query.trim().isEmpty()) return out;

        List<List<String>> toks = new ArrayList<>(docs.size());
        Map<String, Integer> df = new HashMap<>();
        double totalLen = 0;
        for (Doc d : docs) {
            List<String> t = tokenize(d.source + "\n" + d.text);
            toks.add(t);
            totalLen += t.size();
            for (String u : new HashSet<>(t)) df.merge(u, 1, Integer::sum);
        }
        double avg = totalLen / Math.max(1, docs.size());
        Set<String> q = new java.util.LinkedHashSet<>(tokenize(query));
        if (q.isEmpty()) return out;

        int n = docs.size();
        for (int i = 0; i < n; i++) {
            List<String> t = toks.get(i);
            if (t.isEmpty()) continue;
            Map<String, Integer> tf = new HashMap<>();
            for (String u : t) tf.merge(u, 1, Integer::sum);
            double score = 0;
            for (String term : q) {
                Integer f = tf.get(term);
                if (f == null) continue;
                int d = df.getOrDefault(term, 0);
                double idf = Math.log(1 + (n - d + 0.5) / (d + 0.5));
                double denom = f + K1 * (1 - B + B * t.size() / Math.max(1e-9, avg));
                score += idf * (f * (K1 + 1)) / denom;
            }
            if (score > 0) out.add(new Result(i, docs.get(i).source, docs.get(i).text, score));
        }
        out.sort(Comparator.comparingDouble((Result r) -> r.score).reversed());
        if (out.size() > topK) return new ArrayList<>(out.subList(0, topK));
        return out;
    }

    /**
     * 切词：英文/数字按词，CJK 按 2-gram，其余符号当分隔。
     * 统一小写，长度 1 的 CJK 词条丢弃（基本都是高频虚词）。
     */
    public static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        StringBuilder word = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                flushWord(out, word);
                cjk.append(c);
                continue;
            }
            flushCjk(out, cjk);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '/') {
                word.append(Character.toLowerCase(c));
            } else {
                flushWord(out, word);
            }
        }
        flushCjk(out, cjk);
        flushWord(out, word);
        return out;
    }

    private static void flushWord(List<String> out, StringBuilder sb) {
        if (sb.length() >= 2) out.add(sb.toString());
        sb.setLength(0);
    }

    private static void flushCjk(List<String> out, StringBuilder sb) {
        String s = sb.toString();
        if (s.length() == 1) out.add(s);
        for (int i = 0; i + 1 < s.length(); i++) out.add(s.substring(i, i + 2));
        sb.setLength(0);
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)      // 基本汉字
                || (c >= 0x3400 && c <= 0x4DBF)  // 扩展 A
                || (c >= 0x3040 && c <= 0x30FF)  // 日文假名
                || (c >= 0xAC00 && c <= 0xD7AF); // 韩文
    }

    /**
     * 切块：按行滑动窗口，保留上下文，同时限制单块字符数。
     *
     * <p>切块按「行」而不是按字符，是因为工具/代码文件里一行通常就是一个语义单元，
     * 从中间切断的片段检索命中后读起来毫无意义。
     */
    public static List<Doc> chunk(String source, String text, int chunkChars, int overlapLines) {
        List<Doc> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        int max = Math.max(200, chunkChars);
        String[] lines = text.split("\n", -1);
        StringBuilder buf = new StringBuilder();
        int startLine = 0;
        for (int i = 0; i < lines.length; i++) {
            buf.append(lines[i]).append('\n');
            if (buf.length() >= max) {
                out.add(new Doc(source + "#L" + (startLine + 1), buf.toString()));
                // 回退几行做重叠，避免答案正好跨在块的接缝上
                int back = Math.min(overlapLines, i - startLine);
                int keepFrom = Math.max(startLine, i - back);
                buf.setLength(0);
                for (int k = keepFrom; k <= i; k++) buf.append(lines[k]).append('\n');
                startLine = keepFrom;
            }
        }
        if (buf.toString().trim().length() > 0) {
            out.add(new Doc(source + "#L" + (startLine + 1), buf.toString()));
        }
        return out;
    }

    /** 取片段：围绕第一个命中词向两侧扩，用于把结果压到几百字。 */
    public static String highlight(String text, String query, int radius) {
        if (text == null) return "";
        if (text.length() <= radius * 2) return text;
        List<String> terms = tokenize(query);
        int best = -1;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String t : terms) {
            int at = lower.indexOf(t.toLowerCase(Locale.ROOT));
            if (at >= 0 && (best < 0 || at < best)) best = at;
        }
        if (best < 0) return text.substring(0, radius * 2).trim() + "…";
        int from = Math.max(0, best - radius);
        int to = Math.min(text.length(), best + radius);
        return (from > 0 ? "…" : "") + text.substring(from, to).trim() + (to < text.length() ? "…" : "");
    }

    /** 排序后拼成给模型看的结果文本。 */
    public static String format(List<Result> results, boolean zh) {
        if (results == null || results.isEmpty()) {
            return zh ? "没有检索到相关片段。" : "No matching snippets.";
        }
        StringBuilder sb = new StringBuilder();
        for (Result r : results) {
            sb.append("[").append(r.source).append("] ").append(TextKit.trimUtf8(r.snippet, 700)).append("\n\n");
        }
        return sb.toString().trim();
    }
}
