package com.mscope.browser.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 格式转换：正文与表格之间、以及「有损也能用」的预览文本。
 *
 * <p>为什么值得单独一件：本地小模型最爱输出 Markdown 表格，而手机屏幕放不下；
 * 反过来用户贴进来的 CSV 又常常带引号与换行。这里统一在纯逻辑层处理，
 * 界面和工具都只调一处，不会出现「工具里算对了、界面上又错一次」。
 *
 * <p>刻意不引第三方库：CSV 与 Markdown 表格的规范里，真正会用到的部分很少，
 * 完整实现反而会把体积和踩坑面一起放大。
 */
public final class DocConverter {

    private DocConverter() {
    }

    /**
     * 解析 CSV（RFC 4180：双引号包裹、内部双写引号表示转义、字段内允许换行）。
     *
     * @param delimiter 分隔符，逗号或制表符
     */
    public static List<List<String>> parseCsv(String text, char delimiter) {
        List<List<String>> rows = new ArrayList<>();
        if (text == null || text.isEmpty()) return rows;
        String t = text.replace("\r\n", "\n").replace('\r', '\n');
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < t.length() && t.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
                continue;
            }
            if (c == '"' && cell.length() == 0) {
                quoted = true;
            } else if (c == delimiter) {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n') {
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    /** CSV → Markdown 表格。列数按最宽行对齐，短行补空。 */
    public static String csvToMarkdown(String csv, char delimiter) {
        List<List<String>> rows = parseCsv(csv, delimiter);
        if (rows.isEmpty()) return "";
        int cols = 0;
        for (List<String> r : rows) cols = Math.max(cols, r.size());
        if (cols == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            List<String> r = rows.get(i);
            sb.append('|');
            for (int c = 0; c < cols; c++) {
                String v = c < r.size() ? r.get(c) : "";
                sb.append(' ').append(escapeCell(v)).append(" |");
            }
            sb.append('\n');
            if (i == 0) {
                sb.append('|');
                for (int c = 0; c < cols; c++) sb.append(" --- |");
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static String escapeCell(String v) {
        return v == null ? "" : v.replace("|", "\\|").replace("\n", " ");
    }

    /** Markdown 表格 → CSV，便于导出后在表格软件里打开。 */
    public static String markdownToCsv(String table) {
        List<List<String>> rows = parseMarkdownTable(table);
        StringBuilder sb = new StringBuilder();
        for (List<String> r : rows) {
            for (int i = 0; i < r.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(quoteCsv(r.get(i)));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String quoteCsv(String v) {
        String s = v == null ? "" : v;
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    /** 解析 Markdown 里的第一张表格（跳过分隔行）。 */
    public static List<List<String>> parseMarkdownTable(String md) {
        List<List<String>> rows = new ArrayList<>();
        if (md == null) return rows;
        for (String line : md.split("\n")) {
            String t = line.trim();
            if (!t.startsWith("|")) {
                if (!rows.isEmpty()) break;
                continue;
            }
            if (t.matches("^\\|[\\s:|-]+\\|?$")) continue;   // 分隔行
            String inner = t;
            if (inner.startsWith("|")) inner = inner.substring(1);
            if (inner.endsWith("|")) inner = inner.substring(0, inner.length() - 1);
            List<String> cells = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            for (int i = 0; i < inner.length(); i++) {
                char c = inner.charAt(i);
                if (c == '\\' && i + 1 < inner.length() && inner.charAt(i + 1) == '|') {
                    cur.append('|');
                    i++;
                } else if (c == '|') {
                    cells.add(cur.toString().trim());
                    cur.setLength(0);
                } else {
                    cur.append(c);
                }
            }
            cells.add(cur.toString().trim());
            rows.add(cells);
        }
        return rows;
    }

    /** 按宽字符估算的显示列宽（CJK 算 2），用于在等宽环境里对齐表格。 */
    public static int displayWidth(String s) {
        if (s == null) return 0;
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            w += isWide(cp) ? 2 : 1;
        }
        return w;
    }

    private static boolean isWide(int cp) {
        return (cp >= 0x1100 && cp <= 0x115F)
                || (cp >= 0x2E80 && cp <= 0xA4CF)
                || (cp >= 0xAC00 && cp <= 0xD7A3)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0xFE30 && cp <= 0xFE6F)
                || (cp >= 0xFF00 && cp <= 0xFF60)
                || (cp >= 0xFFE0 && cp <= 0xFFE6)
                || (cp >= 0x1F300 && cp <= 0x1FAFF);
    }

    /** 把表格压成「等宽文本表」，手机上比 Markdown 好读，也便于塞进终端。 */
    public static String toTextTable(List<List<String>> rows) {
        if (rows == null || rows.isEmpty()) return "";
        int cols = 0;
        for (List<String> r : rows) cols = Math.max(cols, r.size());
        int[] width = new int[cols];
        for (List<String> r : rows) {
            for (int c = 0; c < r.size(); c++) width[c] = Math.max(width[c], displayWidth(r.get(c)));
        }
        StringBuilder sb = new StringBuilder();
        for (int ri = 0; ri < rows.size(); ri++) {
            List<String> r = rows.get(ri);
            StringBuilder line = new StringBuilder();
            for (int c = 0; c < cols; c++) {
                String v = c < r.size() ? r.get(c) : "";
                line.append(v);
                int pad = width[c] - displayWidth(v);
                for (int k = 0; k < pad; k++) line.append(' ');
                if (c < cols - 1) line.append("  ");
            }
            sb.append(line.toString().trim()).append('\n');
            if (ri == 0) {
                StringBuilder sep = new StringBuilder();
                for (int c = 0; c < cols; c++) {
                    for (int k = 0; k < width[c]; k++) sep.append('-');
                    if (c < cols - 1) sep.append("  ");
                }
                sb.append(sep).append('\n');
            }
        }
        return sb.toString();
    }

    /** 生成 Mermaid 折线图源码（不给模型画，直接给用户可复制的图）。 */
    public static String lineChart(String title, List<String> labels, List<Double> values) {
        if (labels == null || values == null || labels.isEmpty() || labels.size() != values.size()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("```mermaid\nxychart-beta\n");
        sb.append("    title \"").append(safe(title)).append("\"\n");
        sb.append("    x-axis [");
        for (int i = 0; i < labels.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append('"').append(safe(labels.get(i))).append('"');
        }
        sb.append("]\n    y-axis \"value\"\n    line [");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(fmt(values.get(i)));
        }
        sb.append("]\n```\n");
        return sb.toString();
    }

    private static String fmt(Double d) {
        if (d == null || d.isNaN() || d.isInfinite()) return "0";
        return d == Math.floor(d) ? String.valueOf(d.longValue()) : String.format(Locale.ROOT, "%.2f", d);
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace("\"", "'").replace("\n", " ");
    }

    /** 字节 → 「1.2 KB」这类紧凑写法（与 Format.size 区分：这里不带空格，用于表头）。 */
    public static String compactSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        double v = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        for (String u : units) {
            v /= 1024;
            if (v < 1024) return String.format(Locale.ROOT, v < 10 ? "%.1f%s" : "%.0f%s", v, u);
        }
        return String.format(Locale.ROOT, "%.0fPB", v / 1024);
    }

    /** 把任意文本转成「可安全放进 Markdown 代码块」的形式（反引号围栏加长）。 */
    public static String fenceFor(String content) {
        String body = content == null ? "" : content;
        int ticks = 3;
        String fence = "```";
        while (body.contains(fence)) {
            ticks++;
            fence = "`".repeat(ticks);
        }
        return fence + "\n" + body + (body.endsWith("\n") ? "" : "\n") + fence;
    }

    /** 纯文本按字节截断后补省略号，用于通知等有长度限制的地方。 */
    public static String clip(String s, int maxBytes) {
        String t = s == null ? "" : s;
        byte[] b = t.getBytes(StandardCharsets.UTF_8);
        if (b.length <= maxBytes) return t;
        return TextKit.trimUtf8(t, Math.max(0, maxBytes - 3)) + "…";
    }
}
