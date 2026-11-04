package com.mscope.browser.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一个小的 unified-diff 应用器。
 *
 * <p>{@code write_file} 只能整文件覆盖，这在改大文件时是灾难：模型必须把没改的部分
 * 一字不差地重写一遍，几 KB 的文件它写不完就会截断，截断的写入又把原文件毁了。
 * {@code edit_file} 解决了「改一处」，这里解决「改多处」。
 *
 * <p>四条铁律，宁可失败也不猜：
 * <ol>
 *   <li>片段里的<b>定位行</b>（上下文行 + 删除行）必须逐字匹配，且顺序一致。
 *       行号只是提示：模型写的行号经常整体偏几行，以内容为准。</li>
 *   <li>定位块在原文里出现多次就报错，并指出候选行号 —— 静默改错行比不改更糟。</li>
 *   <li>纯插入（没有删除行）才允许按行号落点，因为此时没有内容可供校验。</li>
 *   <li>全部片段先在内存里试算成功，才一次性返回结果；中途失败不产生半成品。</li>
 * </ol>
 *
 * <p>实现上刻意不做「拼成一个大块再搜索」：那样会把不相邻的上下文行连在一起比，
 * 只要中间夹着没写进 diff 的行就永远匹配不上，报错还会指错地方。
 */
public final class Patcher {

    private Patcher() {
    }

    /** 一个 diff 片段。 */
    public static final class Hunk {
        public final int oldStart;
        public final int newStart;
        /** 原始行（带前缀 ' ' / '-' / '+'）。 */
        public final List<String> lines;

        Hunk(int oldStart, int newStart, List<String> lines) {
            this.oldStart = oldStart;
            this.newStart = newStart;
            this.lines = lines;
        }

        public int added() {
            int n = 0;
            for (String l : lines) if (l.startsWith("+")) n++;
            return n;
        }

        public int removed() {
            int n = 0;
            for (String l : lines) if (l.startsWith("-")) n++;
            return n;
        }
    }

    /** 一个文件的补丁。 */
    public static final class FilePatch {
        public final String path;
        public final List<Hunk> hunks = new ArrayList<>();

        FilePatch(String path) {
            this.path = path;
        }

        public int added() {
            int n = 0;
            for (Hunk h : hunks) n += h.added();
            return n;
        }

        public int removed() {
            int n = 0;
            for (Hunk h : hunks) n += h.removed();
            return n;
        }
    }

    /** 失败原因（人话），成功时为空。 */
    public static final class Failure extends Exception {
        private static final long serialVersionUID = 1L;

        public Failure(String msg) {
            super(msg);
        }
    }

    private static final Pattern HUNK_HEAD = Pattern.compile(
            "^@@\\s*-([0-9]+)(?:,([0-9]+))?\\s*\\+([0-9]+)(?:,([0-9]+))?\\s*@@.*$");

    /** 解析 diff 文本；支持多文件（---/+++ 成对出现，或只给 +++ 的单文件写法）。 */
    public static List<FilePatch> parse(String diff) throws Failure {
        List<FilePatch> out = new ArrayList<>();
        if (diff == null || diff.trim().isEmpty()) throw new Failure("empty patch");
        String[] raw = diff.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        FilePatch cur = null;
        Hunk hunk = null;
        int i = 0;
        while (i < raw.length) {
            String line = raw[i];
            if (line.startsWith("---")) {
                String plus = i + 1 < raw.length ? raw[i + 1] : "";
                if (!plus.startsWith("+++")) throw new Failure("line " + (i + 1) + ": '---' without '+++'");
                cur = new FilePatch(stripMarker(plus.substring(3)));
                out.add(cur);
                hunk = null;
                i += 2;
                continue;
            }
            if (line.startsWith("+++ ")) {
                cur = new FilePatch(stripMarker(line.substring(4)));
                out.add(cur);
                hunk = null;
                i++;
                continue;
            }
            if (line.startsWith("@@")) {
                if (cur == null) throw new Failure("line " + (i + 1) + ": hunk before file header");
                Matcher m = HUNK_HEAD.matcher(line);
                if (!m.find()) throw new Failure("line " + (i + 1) + ": bad hunk header: " + line);
                hunk = new Hunk(num(m.group(1)), num(m.group(3)), new ArrayList<>());
                cur.hunks.add(hunk);
                i++;
                continue;
            }
            if (hunk != null && (line.startsWith(" ") || line.startsWith("+") || line.startsWith("-"))) {
                hunk.lines.add(line);
                i++;
                continue;
            }
            // 无前缀的空行是格式化产物，不是内容（真正的空上下文行带一个前导空格）
            i++;
        }
        if (out.isEmpty()) throw new Failure("no file header found; expected '--- a/x' and '+++ b/x'");
        for (FilePatch fp : out) {
            if (fp.hunks.isEmpty()) throw new Failure("no hunk for " + fp.path);
        }
        return out;
    }

    private static int num(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 去掉 'a/' 'b/' 前缀与时间戳尾巴。 */
    private static String stripMarker(String s) {
        String t = s == null ? "" : s.trim();
        int tab = t.indexOf('\t');
        if (tab >= 0) t = t.substring(0, tab);
        if (t.startsWith("a/") || t.startsWith("b/")) t = t.substring(2);
        return t.trim();
    }

    /** 一行在原文里的角色。 */
    private enum Kind { CTX, DEL, ADD }

    /**
     * 把补丁应用到原文，返回新文本。
     *
     * @throws Failure 任一片段对不上；此时原文不产生任何修改
     */
    public static String apply(String original, FilePatch patch) throws Failure {
        List<String> cur = split(original);

        for (Hunk h : patch.hunks) {
            List<Kind> kinds = new ArrayList<>();
            List<String> texts = new ArrayList<>();
            for (String l : h.lines) {
                if (l.startsWith("+")) {
                    kinds.add(Kind.ADD);
                    texts.add(l.substring(1));
                } else if (l.startsWith("-")) {
                    kinds.add(Kind.DEL);
                    texts.add(l.substring(1));
                } else if (l.startsWith(" ")) {
                    kinds.add(Kind.CTX);
                    texts.add(l.substring(1));
                }
            }
            // 定位块 = 去掉新增行后的序列（上下文 + 删除）
            List<String> anchors = new ArrayList<>();
            for (int k = 0; k < kinds.size(); k++) {
                if (kinds.get(k) != Kind.ADD) anchors.add(texts.get(k));
            }
            List<Integer> at = findAll(cur, anchors);
            if (at.isEmpty()) {
                String first = anchors.isEmpty() ? "(no context)" : anchors.get(0);
                throw new Failure("hunk @" + h.oldStart + " does not match any line in " + patch.path
                        + " (looking for \"" + clip(first) + "\"; the file changed since it was read?)");
            }
            if (at.size() > 1) {
                StringBuilder sb = new StringBuilder();
                for (int k = 0; k < Math.min(5, at.size()); k++) {
                    if (k > 0) sb.append(", ");
                    sb.append(at.get(k) + 1);
                }
                throw new Failure("hunk @" + h.oldStart + " matches " + at.size() + " places in "
                        + patch.path + " (lines " + sb + "); add more context lines to make it unique");
            }
            int start = at.get(0);
            // 逐行重写这一段：删除的跳过，新增的插入，上下文原样保留
            List<String> rebuilt = new ArrayList<>(cur.subList(0, start));
            int pos = start;
            for (int k = 0; k < kinds.size(); k++) {
                switch (kinds.get(k)) {
                    case CTX:
                        rebuilt.add(cur.get(pos));
                        pos++;
                        break;
                    case DEL:
                        pos++;
                        break;
                    default:
                        rebuilt.add(texts.get(k));
                }
            }
            rebuilt.addAll(cur.subList(pos, cur.size()));
            cur = rebuilt;
        }
        return String.join("\n", cur);
    }

    private static String clip(String s) {
        String t = s.length() > 40 ? s.substring(0, 40) + "…" : s;
        return t.replace("\n", "\\n");
    }

    private static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        String n = s.replace("\r\n", "\n").replace('\r', '\n');
        String[] parts = n.split("\n", -1);
        for (String p : parts) out.add(p);
        return out;
    }

    /** 在原文里找出锚点序列的全部起点。 */
    private static List<Integer> findAll(List<String> cur, List<String> anchors) {
        List<Integer> out = new ArrayList<>();
        if (anchors.isEmpty()) return out;
        int n = cur.size(), m = anchors.size();
        for (int i = 0; i + m <= n; i++) {
            boolean ok = true;
            for (int k = 0; k < m; k++) {
                if (!eq(cur.get(i + k), anchors.get(k))) {
                    ok = false;
                    break;
                }
            }
            if (ok) out.add(i);
        }
        return out;
    }

    /** 比较一行：忽略行尾空格与行首尾空白差异（模型复述原文时常丢/多空格）。 */
    private static boolean eq(String a, String b) {
        return a.equals(b) || a.stripTrailing().equals(b.stripTrailing())
                || a.trim().equals(b.trim());
    }

    /** 给界面看的摘要：「a.txt +3 -1」。 */
    public static String summary(List<FilePatch> patches) {
        StringBuilder sb = new StringBuilder();
        for (FilePatch p : patches) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(p.path).append(" +").append(p.added()).append(" -").append(p.removed());
        }
        return sb.toString();
    }

    /**
     * 单文件、单处替换（{@code edit_file} 用）：old 必须唯一命中。
     *
     * @return 替换后的文本
     * @throws Failure old 为空 / 未命中 / 多处命中
     */
    public static String replaceOnce(String original, String old, String neu, boolean zh) throws Failure {
        String src = original == null ? "" : original;
        if (old == null || old.isEmpty()) {
            throw new Failure(zh ? "old 不能为空" : "old must not be empty");
        }
        int first = src.indexOf(old);
        if (first < 0) {
            throw new Failure(zh
                    ? "没找到要替换的原文（可能已被改过，或换行/空格不一致），先 read_file 看一下当前内容"
                    : "snippet not found (maybe already changed, or whitespace differs); read_file first");
        }
        int second = src.indexOf(old, first + 1);
        if (second >= 0) {
            throw new Failure(zh
                    ? "原文在文件里出现多次（首次在第 " + lineOf(src, first) + " 行），请多带几行上下文让它唯一"
                    : "snippet appears more than once (first at line " + lineOf(src, first)
                    + "); include more context to make it unique");
        }
        return src.substring(0, first) + (neu == null ? "" : neu) + src.substring(first + old.length());
    }

    private static int lineOf(String s, int at) {
        int n = 1;
        for (int i = 0; i < at && i < s.length(); i++) if (s.charAt(i) == '\n') n++;
        return n;
    }
}
