package com.mscope.browser.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 行级差分与 unified diff 渲染。
 *
 * <p>智能体改文件时必须能说清「改了什么」。只回一句「已写入」不算交代：
 * 用户看不见改动，模型下一轮也不知道自己上一轮动了哪几行。这里给出
 * 「最长公共子序列 → 增删片段 → unified 格式」的最小实现，行数上限由调用方控制。
 *
 * <p>复杂度 O(n·m)，只用于几百行的文本；超长输入直接退化为「整体替换」，
 * 不能让一段 10 万行的文件把手机卡住。
 */
public final class DiffKit {

    /** 一行的差分结果。 */
    public static final class Line {
        public static final char SAME = ' ';
        public static final char ADD = '+';
        public static final char DEL = '-';

        public final char op;
        public final String text;

        public Line(char op, String text) {
            this.op = op;
            this.text = text == null ? "" : text;
        }

        @Override
        public String toString() {
            return op + text;
        }
    }

    /** 差分统计。 */
    public static final class Stat {
        public final int added;
        public final int removed;
        public final int same;

        Stat(int added, int removed, int same) {
            this.added = added;
            this.removed = removed;
            this.same = same;
        }

        public int changed() {
            return added + removed;
        }
    }

    /** LCS 上限，超过就退化成整体替换，避免 O(n·m) 卡死。 */
    private static final int MAX_CELLS = 400 * 400;

    private DiffKit() {
    }

    public static List<Line> diff(String before, String after) {
        List<String> a = lines(before);
        List<String> b = lines(after);
        List<Line> out = new ArrayList<>();
        if ((long) a.size() * (long) b.size() > MAX_CELLS) {
            for (String s : a) out.add(new Line(Line.DEL, s));
            for (String s : b) out.add(new Line(Line.ADD, s));
            return out;
        }
        int n = a.size();
        int m = b.size();
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j))
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (a.get(i).equals(b.get(j))) {
                out.add(new Line(Line.SAME, a.get(i)));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                out.add(new Line(Line.DEL, a.get(i)));
                i++;
            } else {
                out.add(new Line(Line.ADD, b.get(j)));
                j++;
            }
        }
        while (i < n) out.add(new Line(Line.DEL, a.get(i++)));
        while (j < m) out.add(new Line(Line.ADD, b.get(j++)));
        return out;
    }

    public static Stat stat(List<Line> diff) {
        int add = 0;
        int del = 0;
        int same = 0;
        if (diff != null) {
            for (Line l : diff) {
                if (l.op == Line.ADD) add++;
                else if (l.op == Line.DEL) del++;
                else same++;
            }
        }
        return new Stat(add, del, same);
    }

    /** 一句话摘要，如「+12 −3」，给界面用。 */
    public static String summary(List<Line> diff, boolean zh) {
        Stat s = stat(diff);
        if (s.changed() == 0) return zh ? "内容未变化" : "no change";
        return "+" + s.added + " −" + s.removed;
    }

    /**
     * 渲染 unified diff，含 {@code @@ -a,b +c,d @@} 头与上下文行。
     *
     * @param context 每个片段保留的上下文行数（通常 3）
     * @param maxLines 输出最多行数，超出截断并注明
     */
    public static String unified(String path, List<Line> diff, int context, int maxLines, boolean zh) {
        List<Line> ds = diff == null ? new ArrayList<>() : diff;
        String name = path == null ? "file" : path;
        StringBuilder sb = new StringBuilder();
        sb.append("--- a/").append(name).append('\n');
        sb.append("+++ b/").append(name).append('\n');
        if (stat(ds).changed() == 0) {
            sb.append(zh ? "（内容未变化）" : "(no change)").append('\n');
            return sb.toString();
        }
        int ctx = Math.max(0, context);
        int limit = maxLines <= 0 ? 400 : maxLines;
        int emitted = 0;
        int n = ds.size();
        int[] oldNo = new int[n];
        int[] newNo = new int[n];
        int o = 1;
        int w = 1;
        for (int k = 0; k < n; k++) {
            oldNo[k] = o;
            newNo[k] = w;
            if (ds.get(k).op != Line.ADD) o++;
            if (ds.get(k).op != Line.DEL) w++;
        }
        int i = 0;
        while (i < n) {
            if (ds.get(i).op == Line.SAME) {
                i++;
                continue;
            }
            int start = Math.max(0, i - ctx);
            int lastChange = i;
            int end = i;
            while (end < n) {
                if (ds.get(end).op != Line.SAME) {
                    lastChange = end;
                    end++;
                    continue;
                }
                if (end - lastChange > ctx * 2) break;
                end++;
            }
            end = Math.min(n, lastChange + 1 + ctx);
            int oldCount = 0;
            int newCount = 0;
            for (int k = start; k < end; k++) {
                if (ds.get(k).op != Line.ADD) oldCount++;
                if (ds.get(k).op != Line.DEL) newCount++;
            }
            sb.append("@@ -").append(oldNo[start]).append(',').append(oldCount)
              .append(" +").append(newNo[start]).append(',').append(newCount).append(" @@\n");
            for (int k = start; k < end; k++) {
                if (emitted >= limit) {
                    sb.append(zh ? "…（差分过长，已截断）\n" : "… (diff truncated)\n");
                    return sb.toString();
                }
                Line l = ds.get(k);
                sb.append(l.op).append(l.text).append('\n');
                emitted++;
            }
            i = end;
        }
        return sb.toString();
    }

    /** 原始全文比对：是否完全一致（含末尾换行差异）。 */
    public static boolean same(String a, String b) {
        return nz(a).equals(nz(b));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 按 \n 切行，保留末尾空行语义（"a\n" 与 "a" 视为不同内容）。 */
    public static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        String t = nz(text);
        if (t.isEmpty()) return out;
        int start = 0;
        for (int i = 0; i < t.length(); i++) {
            if (t.charAt(i) == '\n') {
                out.add(t.substring(start, i));
                start = i + 1;
            }
        }
        if (start < t.length()) out.add(t.substring(start));
        return out;
    }
}
