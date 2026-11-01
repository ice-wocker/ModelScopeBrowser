package com.mscope.browser.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * BM25 检索：给「本地知识库 / 工作区全文搜索 / 会话内检索」用的排序内核。
 *
 * <p>手机上不能塞向量库（体积、内存、耗电都不划算），但关键词检索排序做得好，
 * 对「我记得之前写过一段关于 X 的东西」这类需求的命中率已经够用。BM25 比 TF-IDF 强的地方
 * 是它同时惩罚「词太长」和「文档太长」，短文档命中不会被长文档淹没。
 *
 * <p>中文没有空格，这里用二元切分（bigram）+ 单字兜底：不引入词典，但「推理」能命中「本地推理」，
 * 「模型」能命中「大模型」。英文按空白与标点切词并小写化。
 *
 * <p>纯内存，可单测。
 */
public final class Bm25 {

    /** 一篇文档。 */
    public static final class Doc {
        public final String id;
        public final String text;
        final Map<String, Integer> tf = new HashMap<>();
        int length;

        public Doc(String id, String text) {
            this.id = id == null ? "" : id;
            this.text = text == null ? "" : text;
        }
    }

    /** 命中。 */
    public static final class Hit {
        public final String id;
        public final double score;

        public Hit(String id, double score) {
            this.id = id;
            this.score = score;
        }
    }

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private final List<Doc> docs = new ArrayList<>();
    private final Map<String, Integer> df = new HashMap<>();
    private double avgLen;

    public Bm25() {
    }

    public Bm25 add(String id, String text) {
        Doc d = new Doc(id, text);
        for (String t : tokens(d.text)) {
            Integer c = d.tf.get(t);
            d.tf.put(t, c == null ? 1 : c + 1);
            d.length++;
        }
        docs.add(d);
        return this;
    }

    public int size() {
        return docs.size();
    }

    /** 建索引（加完文档后调一次；重复调用是幂等的）。 */
    public void index() {
        df.clear();
        long total = 0;
        for (Doc d : docs) {
            total += d.length;
            for (String t : d.tf.keySet()) {
                Integer c = df.get(t);
                df.put(t, c == null ? 1 : c + 1);
            }
        }
        avgLen = docs.isEmpty() ? 0 : (double) total / docs.size();
    }

    /** 查询，返回按分数降序（同分按 id）的命中列表。 */
    public List<Hit> search(String query, int limit) {
        if (docs.isEmpty() || query == null || query.trim().isEmpty()) return Collections.emptyList();
        if (avgLen <= 0) index();
        Set<String> qs = tokens(query);
        List<Hit> hits = new ArrayList<>();
        int n = docs.size();
        for (Doc d : docs) {
            double score = 0;
            for (String t : qs) {
                Integer f = d.tf.get(t);
                if (f == null || f == 0) continue;
                Integer dfv = df.get(t);
                int dfi = dfv == null ? 0 : dfv;
                double idf = Math.log(1 + (n - dfi + 0.5) / (dfi + 0.5));
                double denom = f + K1 * (1 - B + B * (avgLen <= 0 ? 0 : d.length / avgLen));
                score += idf * (f * (K1 + 1)) / (denom <= 0 ? 1 : denom);
            }
            if (score > 0) hits.add(new Hit(d.id, score));
        }
        hits.sort((a, b) -> {
            int c = Double.compare(b.score, a.score);
            return c != 0 ? c : a.id.compareTo(b.id);
        });
        if (limit > 0 && hits.size() > limit) return new ArrayList<>(hits.subList(0, limit));
        return hits;
    }

    /**
     * 切词：英文按非字母数字切并小写；CJK 连续段做二元切分，长度 1 时保留单字。
     *
     * <p>暴露成公开方法是为了让单测能直接盯着切词行为，检索效果好不好八成取决于它。
     */
    public static Set<String> tokens(String text) {
        Set<String> out = new HashSet<>();
        if (text == null) return out;
        StringBuilder word = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (isCjk(cp)) {
                flushWord(word, out);
                cjk.appendCodePoint(cp);
            } else if (Character.isLetterOrDigit(cp)) {
                flushCjk(cjk, out);
                word.appendCodePoint(Character.toLowerCase(cp));
            } else {
                flushWord(word, out);
                flushCjk(cjk, out);
            }
        }
        flushWord(word, out);
        flushCjk(cjk, out);
        return out;
    }

    private static void flushWord(StringBuilder sb, Set<String> out) {
        if (sb.length() == 0) return;
        String w = sb.toString();
        sb.setLength(0);
        // 单字母噪声太大，直接丢；数字保留（版本号、参数量都是检索目标）
        if (w.length() == 1 && !Character.isDigit(w.charAt(0))) return;
        out.add(w);
    }

    private static void flushCjk(StringBuilder sb, Set<String> out) {
        if (sb.length() == 0) return;
        String s = sb.toString();
        sb.setLength(0);
        if (s.length() == 1) {
            out.add(s);
            return;
        }
        for (int i = 0; i + 1 < s.length(); i++) out.add(s.substring(i, i + 2));
        for (int i = 0; i < s.length(); i++) out.add(String.valueOf(s.charAt(i)));
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0x3040 && cp <= 0x30FF)
                || (cp >= 0xAC00 && cp <= 0xD7AF);
    }

    /** 把一段长文本按标题/空行切块，供「知识库」逐块建索引（块太大命中后不好定位）。 */
    public static List<String> chunk(String text, int maxChars) {
        List<String> out = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return out;
        int limit = maxChars <= 0 ? 800 : maxChars;
        StringBuilder cur = new StringBuilder();
        for (String para : text.split("\n\\s*\n")) {
            String p = para.trim();
            if (p.isEmpty()) continue;
            if (cur.length() + p.length() + 2 > limit && cur.length() > 0) {
                out.add(cur.toString().trim());
                cur.setLength(0);
            }
            if (p.length() > limit) {
                String rest = p;
                while (rest.length() > limit) {
                    int cut = cutPoint(rest, limit);
                    out.add(rest.substring(0, cut).trim());
                    rest = rest.substring(cut);
                }
                cur.append(rest);
            } else if (cur.length() > 0) {
                cur.append("\n\n").append(p);
            } else {
                cur.append(p);
            }
        }
        if (cur.length() > 0) out.add(cur.toString().trim());
        return out;
    }

    private static int cutPoint(String s, int limit) {
        int from = Math.max(0, limit - 200);
        for (int i = limit; i > from; i--) {
            char c = s.charAt(i - 1);
            if (c == '\n' || c == '。' || c == '.' || c == '!' || c == '?' || c == '；' || c == ';') return i;
        }
        return limit;
    }

    /** 小写化 + 去重排序的辅助，给调试输出用。 */
    public static String tokenSummary(String text) {
        List<String> ts = new ArrayList<>(tokens(text));
        Collections.sort(ts);
        return String.join(",", ts.toArray(new String[0])).toLowerCase(Locale.ROOT);
    }
}
