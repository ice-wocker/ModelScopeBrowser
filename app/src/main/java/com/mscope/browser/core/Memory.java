package com.mscope.browser.core;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 长期事实的「记忆文件」：一行一条，靠相似度更新、靠时间淘汰。
 *
 * <p>为什么不用自由文本：自由文本没法去重也没法淘汰，写上半年就变成一坨互相矛盾的句子
 * （「用户喜欢简短」+「用户喜欢详细」），而且每次都要整段塞进提示词。
 *
 * <p>这里把它做成有结构的一条条：{@code id | ts | fact}。新写入时按相似度覆盖旧条目
 * （同一件事只留最新的说法），取用时可检索、可限量。
 * 密钥脱敏在 {@link SecretVault} 里做，这里只负责组织与落盘。
 */
public final class Memory {

    private Memory() {
    }

    /** 一条记忆。 */
    public static final class Fact {
        public final String id;
        public final long ts;
        public final String text;

        public Fact(String id, long ts, String text) {
            this.id = id;
            this.ts = ts;
            this.text = text == null ? "" : text;
        }

        public String line() {
            return id + "|" + ts + "|" + text.replace('\n', ' ');
        }
    }

    public static final int MAX_FACTS = 120;
    private static final double SAME_THRESHOLD = 0.6;

    /** 解析整个文件（容错：坏行直接跳过，不让一条脏数据废掉全部记忆）。 */
    public static List<Fact> parse(String body) {
        List<Fact> out = new ArrayList<>();
        if (body == null) return out;
        for (String raw : body.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\|", 3);
            if (parts.length < 3) continue;
            long ts;
            try {
                ts = Long.parseLong(parts[1].trim());
            } catch (NumberFormatException e) {
                ts = 0;
            }
            String text = parts[2].trim();
            if (text.isEmpty()) continue;
            out.add(new Fact(parts[0].trim(), ts, text));
        }
        return out;
    }

    public static String render(List<Fact> facts) {
        StringBuilder sb = new StringBuilder();
        for (Fact f : facts) sb.append(f.line()).append('\n');
        return sb.toString();
    }

    /**
     * 加一条记忆。返回新列表（不改入参，便于单测与「写失败不污染内存」）。
     *
     * <p>去重规则：与已有条目相似度 ≥ {@link #SAME_THRESHOLD} 视为同一件事，
     * 用新说法覆盖旧条目（相当于「更新」），而不是并列两条互相打架的记录。
     */
    public static List<Fact> add(List<Fact> facts, String text, long now) {
        List<Fact> out = new ArrayList<>(facts == null ? new ArrayList<>() : facts);
        String clean = SecretVault.redact(text == null ? "" : text.trim()).replace('\n', ' ');
        if (clean.isEmpty()) return out;
        Set<String> tok = new LinkedHashSet<>(Retriever.tokenize(clean));
        int bestAt = -1;
        double best = 0;
        for (int i = 0; i < out.size(); i++) {
            double sim = jaccard(tok, new LinkedHashSet<>(Retriever.tokenize(out.get(i).text)));
            if (sim > best) {
                best = sim;
                bestAt = i;
            }
        }
        String id = mkId(clean, now);
        if (bestAt >= 0 && best >= SAME_THRESHOLD) {
            out.set(bestAt, new Fact(out.get(bestAt).id, now, clean));
        } else {
            out.add(new Fact(id, now, clean));
        }
        while (out.size() > MAX_FACTS) out.remove(0);
        return out;
    }

    /** 删除一条（按 id）。 */
    public static List<Fact> remove(List<Fact> facts, String id) {
        List<Fact> out = new ArrayList<>();
        for (Fact f : facts) if (!f.id.equals(id)) out.add(f);
        return out;
    }

    /** 检索：按查询词打分，返回最相关的若干条（空查询则返回最近几条）。 */
    public static List<Fact> search(List<Fact> facts, String query, int limit) {
        List<Fact> out = new ArrayList<>();
        if (facts == null || facts.isEmpty()) return out;
        if (query == null || query.trim().isEmpty()) {
            for (int i = facts.size() - 1; i >= 0 && out.size() < limit; i--) out.add(facts.get(i));
            return out;
        }
        List<Retriever.Doc> docs = new ArrayList<>();
        for (Fact f : facts) docs.add(new Retriever.Doc(f.id, f.text));
        for (Retriever.Result r : Retriever.search(docs, query, limit)) {
            out.add(facts.get(r.index));
        }
        return out;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> inter = new LinkedHashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new LinkedHashSet<>(a);
        union.addAll(b);
        return union.isEmpty() ? 0 : (double) inter.size() / union.size();
    }

    private static String mkId(String text, long now) {
        return Long.toString(Math.abs((text + now).hashCode()), 36).toLowerCase(Locale.ROOT);
    }

    /**
     * 落盘容器：一个文本文件 + 内存缓存。
     *
     * <p>写入用「临时文件 + 改名」，中途被杀不会留下半截记忆文件；
     * 读失败（文件损坏）时退回空列表，而不是让整个对话跟着崩。
     * file 传 null 时退化为纯内存（单测与「没挂存储」的场景）。
     */
    public static final class Store {
        private final File file;
        private List<Fact> facts = new ArrayList<>();
        private boolean loaded = false;

        public Store(File file) {
            this.file = file;
        }

        public synchronized List<Fact> all() {
            ensure();
            return new ArrayList<>(facts);
        }

        public synchronized int size() {
            ensure();
            return facts.size();
        }

        public synchronized void add(String text) {
            ensure();
            facts = Memory.add(facts, text, System.currentTimeMillis());
            flush();
        }

        public synchronized void remove(String id) {
            ensure();
            facts = Memory.remove(facts, id);
            flush();
        }

        public synchronized void clear() {
            ensure();
            facts = new ArrayList<>();
            flush();
        }

        /** 按查询检索（空查询返回最近几条）。 */
        public synchronized List<Fact> search(String query, int limit) {
            ensure();
            return Memory.search(facts, query, limit);
        }

        /** 进提示词的那一小段：最多 6 条最相关的。 */
        public synchronized String brief(String query, int maxChars) {
            ensure();
            if (facts.isEmpty()) return "";
            List<Fact> pick = Memory.search(facts, query, 6);
            StringBuilder sb = new StringBuilder();
            for (Fact f : pick) {
                sb.append("· ").append(f.text).append('\n');
                if (sb.length() >= maxChars) break;
            }
            String out = sb.toString().trim();
            return out.length() > maxChars ? out.substring(0, maxChars) : out;
        }

        private void ensure() {
            if (loaded) return;
            loaded = true;
            if (file == null || !file.exists()) return;
            try {
                byte[] b = Files.readAllBytes(file.toPath());
                facts = Memory.parse(new String(b, StandardCharsets.UTF_8));
            } catch (Exception e) {
                facts = new ArrayList<>();
            }
        }

        private void flush() {
            if (file == null) return;
            try {
                File parent = file.getParentFile();
                if (parent != null) {
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                }
                File tmp = new File(file.getPath() + ".tmp");
                Files.write(tmp.toPath(), render(facts).getBytes(StandardCharsets.UTF_8));
                if (!tmp.renameTo(file)) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                    //noinspection ResultOfMethodCallIgnored
                    tmp.renameTo(file);
                }
            } catch (Exception ignored) {
                // 写不进去就只留内存态：记忆不是关键路径，不能因此打断对话
            }
        }
    }
}
