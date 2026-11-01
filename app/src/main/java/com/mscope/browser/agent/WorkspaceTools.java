package com.mscope.browser.agent;

import com.mscope.browser.core.Bm25;
import com.mscope.browser.core.DiffKit;
import com.mscope.browser.core.DocConverter;
import com.mscope.browser.core.DigestKit;
import com.mscope.browser.core.FsKit;
import com.mscope.browser.core.JsonAccess;
import com.mscope.browser.core.TextKit;
import com.mscope.browser.i18n.Lang;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 文件与文本类工具的具体实现。
 *
 * <p>从 {@code AgentTools} 里拆出来的原因很直接：那里原来是十二个 case 挤在一个方法里，
 * 现在工具数量翻了几倍，再堆下去就没法读了。这里只放「不碰 Android 界面、不碰网络」的实现，
 * 所以绝大部分逻辑可以在 JVM 里直接测。
 *
 * <p>所有方法都返回「给模型看的文本」，出错也返回文本而不是抛异常 —— 小模型看到
 * {@code error: file not found} 会自己换个路径重试，看到异常堆栈只会放弃。
 */
final class WorkspaceTools {

    /** 单个工具结果回灌给模型的字符上限。 */
    static final int MAX_RESULT = 6000;
    private static final int MAX_ZIP_ENTRIES = 2000;
    private static final long MAX_ZIP_BYTES = 256L * 1024 * 1024;

    private final Workspace ws;

    WorkspaceTools(Workspace ws) {
        this.ws = ws;
    }

    private static boolean zh() {
        return Lang.zh();
    }

    // ------------------------------------------------------------------ 文件

    String listFiles(String path) {
        String rel = Workspace.normalize(path == null ? "." : path);
        if (!ws.exists(rel)) return (zh() ? "目录不存在：" : "directory not found: ") + orDot(path);
        if (!ws.isDir(rel)) return (zh() ? "这不是目录：" : "not a directory: ") + path;
        List<Workspace.Entry> es = ws.list(rel);
        if (es.isEmpty()) return zh() ? "（空目录）" : "(empty directory)";
        StringBuilder sb = new StringBuilder();
        for (Workspace.Entry e : es) {
            sb.append(e.dir ? (zh() ? "[目录] " : "[dir] ") : (zh() ? "[文件] " : "[file] "))
              .append(e.relPath)
              .append(e.dir ? "/" : (zh() ? "  (" + e.size + " 字节)" : "  (" + e.size + " bytes)"))
              .append('\n');
        }
        return sb.toString().trim();
    }

    String fileTree(String path, int depth) {
        String rel = Workspace.normalize(path == null || path.isEmpty() ? "." : path);
        if (!ws.exists(rel)) return zh() ? "目录不存在：" + path : "directory not found: " + path;
        int max = Math.max(1, Math.min(depth <= 0 ? 3 : depth, 8));
        StringBuilder sb = new StringBuilder();
        int[] budget = {200};
        tree(rel, "", 0, max, sb, budget);
        return sb.length() == 0 ? (zh() ? "（空目录）" : "(empty directory)") : sb.toString().trim();
    }

    private void tree(String rel, String indent, int level, int max, StringBuilder sb, int[] budget) {
        if (level > max || budget[0] <= 0) return;
        List<Workspace.Entry> es = ws.list(rel);
        for (Workspace.Entry e : es) {
            if (budget[0]-- <= 0) {
                sb.append(indent).append("…\n");
                return;
            }
            sb.append(indent).append(e.name).append(e.dir ? "/" : "").append('\n');
            if (e.dir) tree(e.relPath, indent + "  ", level + 1, max, sb, budget);
        }
    }

    String readFile(String path) {
        if (path == null || path.isEmpty()) return zh() ? "错误：缺少参数 path" : "error: missing argument path";
        File f = ws.resolve(path);
        if (f == null) return zh() ? "错误：路径越界" : "error: path escapes the workspace";
        if (!f.exists()) return (zh() ? "文件不存在：" : "file not found: ") + path;
        if (f.isDirectory()) return (zh() ? "这是一个目录：" : "this is a directory: ") + path;
        try {
            return truncate(ws.readText(path), MAX_RESULT);
        } catch (Exception e) {
            return (zh() ? "读取失败：" : "read failed: ") + e.getMessage();
        }
    }

    String writeFile(String path, String content) {
        if (path == null || path.isEmpty()) return zh() ? "错误：缺少参数 path" : "error: missing argument path";
        try {
            File f = ws.writeText(path, content == null ? "" : content);
            if (f == null) return zh() ? "错误：路径越界" : "error: path escapes the workspace";
            int bytes = content == null ? 0 : content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            return (zh() ? "已写入 /" : "wrote /") + Workspace.normalize(path) + "（" + bytes
                    + (zh() ? " 字节）" : " bytes)");
        } catch (Exception e) {
            return (zh() ? "写入失败：" : "write failed: ") + e.getMessage();
        }
    }

    String appendFile(String path, String content) {
        if (path == null || path.isEmpty()) return zh() ? "错误：缺少参数 path" : "error: missing argument path";
        try {
            ws.appendText(path, content == null ? "" : content);
            return (zh() ? "已追加到 /" : "appended to /") + Workspace.normalize(path);
        } catch (Exception e) {
            return (zh() ? "追加失败：" : "append failed: ") + e.getMessage();
        }
    }

    /** 片段替换：失败时把文件里最像的那段回给模型，让它自己把原文抄对。 */
    String applyPatch(String path, List<PatchKit.Hunk> hunks) {
        if (path == null || path.isEmpty()) return zh() ? "错误：缺少参数 path" : "error: missing argument path";
        File f = ws.resolve(path);
        if (f == null) return zh() ? "错误：路径越界" : "error: path escapes the workspace";
        if (!f.isFile()) return (zh() ? "文件不存在：" : "file not found: ") + path;
        String old = FsKit.readText(f, 2 * 1024 * 1024);
        PatchKit.Result r = PatchKit.apply(old, hunks, zh());
        if (!r.ok) {
            return r.error + (r.hint.isEmpty() ? "" : (zh() ? "\n文件里实际是这样的：\n" : "\nthe file actually looks like:\n") + r.hint);
        }
        try {
            ws.writeText(path, r.text);
        } catch (Exception e) {
            return (zh() ? "写入失败：" : "write failed: ") + e.getMessage();
        }
        List<DiffKit.Line> diff = DiffKit.diff(old, r.text);
        String summary = DiffKit.summary(diff, zh());
        String uni = DiffKit.unified(Workspace.normalize(path), diff, 2, 60, zh());
        return (zh() ? "已修改 /" : "patched /") + Workspace.normalize(path)
                + (zh() ? "（" : " (") + summary + (zh() ? "）\n" : ")\n") + uni;
    }

    String deleteFile(String path) {
        if (path == null || path.isEmpty()) return zh() ? "错误：缺少参数 path" : "error: missing argument path";
        return ws.delete(path)
                ? (zh() ? "已删除 /" : "deleted /") + Workspace.normalize(path)
                : (zh() ? "不存在：" : "not found: ") + path;
    }

    String moveFile(String from, String to) {
        File a = ws.resolve(from == null ? "" : from);
        File b = ws.resolve(to == null ? "" : to);
        if (a == null || b == null) return zh() ? "错误：路径越界" : "error: path escapes the workspace";
        if (!a.exists()) return (zh() ? "文件不存在：" : "file not found: ") + from;
        File parent = b.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return zh() ? "错误：无法创建目标目录" : "error: cannot create target directory";
        }
        if (b.exists() && !b.delete()) return zh() ? "错误：目标已存在且无法覆盖" : "error: target exists and cannot be replaced";
        return a.renameTo(b)
                ? (zh() ? "已移动 → /" : "moved → /") + Workspace.normalize(to)
                : (zh() ? "错误：移动失败（跨目录请先复制）" : "error: move failed");
    }

    String copyFile(String from, String to) {
        File a = ws.resolve(from == null ? "" : from);
        File b = ws.resolve(to == null ? "" : to);
        if (a == null || b == null) return zh() ? "错误：路径越界" : "error: path escapes the workspace";
        if (!a.isFile()) return (zh() ? "文件不存在：" : "file not found: ") + from;
        try {
            ws.writeText(to, FsKit.readText(a, 8 * 1024 * 1024));
            return (zh() ? "已复制 → /" : "copied → /") + Workspace.normalize(to);
        } catch (Exception e) {
            return (zh() ? "复制失败：" : "copy failed: ") + e.getMessage();
        }
    }

    String mkdir(String path) {
        try {
            File d = ws.mkdir(Workspace.normalize(path == null ? "" : path));
            return d == null ? (zh() ? "错误：创建失败" : "error: cannot create directory")
                    : (zh() ? "已创建目录 /" : "created /") + Workspace.normalize(path) + "/";
        } catch (Exception e) {
            return (zh() ? "创建失败：" : "mkdir failed: ") + e.getMessage();
        }
    }

    String fileStat(String path) {
        File f = ws.resolve(path == null ? "" : path);
        if (f == null) return zh() ? "错误：路径越界" : "error: path escapes the workspace";
        if (!f.exists()) return (zh() ? "不存在：" : "not found: ") + path;
        StringBuilder sb = new StringBuilder();
        sb.append(f.isDirectory() ? (zh() ? "目录" : "directory") : (zh() ? "文件" : "file"))
          .append(" /").append(Workspace.normalize(path)).append('\n');
        if (f.isFile()) {
            long size = f.length();
            sb.append(zh() ? "大小：" : "size: ").append(size).append(" B (")
              .append(DocConverter.compactSize(size)).append(")\n");
            String text = FsKit.readText(f, 1024 * 1024);
            int lines = text.isEmpty() ? 0 : DocConverter.parseCsv("", ',').size() + countLines(text);
            sb.append(zh() ? "行数：" : "lines: ").append(lines).append('\n');
            sb.append(zh() ? "按 text/plain 估算（最多看 1 MB）\n" : "text/plain estimate (first 1 MB)\n");
            String head = DigestKit.sha256File(f);
            if (!head.isEmpty()) sb.append("sha256: ").append(head).append('\n');
        }
        sb.append(zh() ? "修改时间：" : "modified: ").append(com.mscope.browser.core.TimeKit.iso8601(f.lastModified()));
        return sb.toString();
    }

    private static int countLines(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') n++;
        return n + 1;
    }

    String grep(String pattern, String path) {
        if (pattern == null || pattern.isEmpty()) return zh() ? "错误：缺少参数 pattern" : "error: missing argument pattern";
        java.util.regex.Pattern p;
        try {
            p = java.util.regex.Pattern.compile(pattern, java.util.regex.Pattern.CASE_INSENSITIVE);
        } catch (Exception e) {
            return (zh() ? "错误：正则表达式无效 —— " : "error: invalid regex — ") + e.getMessage();
        }
        String rel = Workspace.normalize(path == null || path.isEmpty() ? "." : path);
        List<File> files = new ArrayList<>();
        collect(ws.resolve(rel), files, 0);
        StringBuilder sb = new StringBuilder();
        int hits = 0;
        for (File f : files) {
            if (hits >= 200) break;
            String text = FsKit.readText(f, 512 * 1024);
            if (text.isEmpty()) continue;
            String[] lines = text.split("\n");
            String relName = ws.relOf(f);
            for (int i = 0; i < lines.length && hits < 200; i++) {
                if (p.matcher(lines[i]).find()) {
                    String line = lines[i];
                    if (line.length() > 240) line = line.substring(0, 240) + "…";
                    sb.append(relName).append(':').append(i + 1).append(": ").append(line).append('\n');
                    hits++;
                }
            }
        }
        if (hits == 0) return zh() ? "没有匹配「" + pattern + "」的内容。" : "No matches for \"" + pattern + "\".";
        return (zh() ? "匹配 " + hits + " 处（最多 200）：\n" : hits + " match(es), capped at 200:\n") + sb;
    }

    private void collect(File dir, List<File> out, int depth) {
        if (dir == null || depth > 6 || out.size() >= 400) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        java.util.Arrays.sort(fs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File f : fs) {
            if (f.isDirectory()) collect(f, out, depth + 1);
            else if (f.length() <= 4L * 1024 * 1024) out.add(f);
            if (out.size() >= 400) return;
        }
    }

    String findFiles(String pattern) {
        String glob = pattern == null ? "*" : pattern.trim();
        java.util.regex.Pattern p;
        try {
            p = java.util.regex.Pattern.compile(globToRegex(glob), java.util.regex.Pattern.CASE_INSENSITIVE);
        } catch (Exception e) {
            return zh() ? "错误：模式无效" : "error: invalid pattern";
        }
        List<File> files = new ArrayList<>();
        collect(ws.root(), files, 0);
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (File f : files) {
            if (p.matcher(f.getName()).matches()) {
                sb.append('/').append(ws.relOf(f)).append("  (").append(f.length()).append(" B)\n");
                if (++n >= 100) break;
            }
        }
        if (n == 0) return zh() ? "没有找到匹配「" + glob + "」的文件。" : "no files match \"" + glob + "\".";
        return (zh() ? "找到 " + n + " 个：\n" : n + " file(s):\n") + sb;
    }

    private static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder("^");
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*':
                    sb.append(".*");
                    break;
                case '?':
                    sb.append('.');
                    break;
                case '.':
                case '(':
                case ')':
                case '+':
                case '|':
                case '^':
                case '$':
                case '@':
                case '%':
                case '[':
                case ']':
                case '{':
                case '}':
                case '\\':
                    sb.append('\\').append(c);
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.append('$').toString();
    }

    String checksum(String path, String algo) {
        File f = ws.resolve(path == null ? "" : path);
        if (f == null) return zh() ? "错误：路径越界" : "error: path escapes the workspace";
        if (!f.isFile()) return (zh() ? "文件不存在：" : "file not found: ") + path;
        String a = algo == null || algo.trim().isEmpty() ? "sha256" : algo.trim().toLowerCase(Locale.ROOT);
        String v;
        switch (a) {
            case "md5":
                v = DigestKit.md5File(f);
                break;
            case "sha1":
            case "sha-1":
                v = DigestKit.fileDigest(f, "SHA-1");
                break;
            case "crc32":
                v = DigestKit.crc32(f);
                break;
            default:
                a = "sha256";
                v = DigestKit.sha256File(f);
                break;
        }
        if (v.isEmpty()) return zh() ? "错误：无法读取文件" : "error: cannot read file";
        return a + " /" + Workspace.normalize(path) + "\n" + v;
    }

    String zip(String path, String output) {
        File src = ws.resolve(path == null || path.isEmpty() ? "." : path);
        if (src == null || !src.isDirectory()) return zh() ? "错误：需要指定一个目录" : "error: a directory is required";
        String outRel = Workspace.normalize(output == null || output.isEmpty()
                ? "pack-" + TextKit.shortHash(String.valueOf(System.currentTimeMillis()), 6) + ".zip" : output);
        File out = ws.resolve(outRel);
        if (out == null) return zh() ? "错误：路径越界" : "error: path escapes the workspace";
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            int n = zipInto(src, src.getName(), zos, 0);
            zos.finish();
            return (zh() ? "已打包 " + n + " 个文件 → /" : "packed " + n + " file(s) → /") + outRel
                    + "（" + DocConverter.compactSize(out.length()) + (zh() ? "）" : ")");
        } catch (Exception e) {
            return (zh() ? "打包失败：" : "zip failed: ") + e.getMessage();
        }
    }

    private int zipInto(File dir, String base, ZipOutputStream zos, int depth) throws Exception {
        if (depth > 8) return 0;
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        int n = 0;
        java.util.Arrays.sort(fs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File f : fs) {
            if (f.getName().endsWith(".zip") && f.getParentFile().equals(ws.root())) continue;
            if (f.isDirectory()) {
                zos.putNextEntry(new ZipEntry(base + "/" + f.getName() + "/"));
                zos.closeEntry();
                n += zipInto(f, base + "/" + f.getName(), zos, depth + 1);
            } else {
                if (f.length() > 64L * 1024 * 1024) continue;
                zos.putNextEntry(new ZipEntry(base + "/" + f.getName()));
                zos.write(readBytes(f));
                zos.closeEntry();
                n++;
            }
        }
        return n;
    }

    /** 解压：逐条校验 canonical 路径，拒绝 ../ 逃逸与超大条目（zip slip）。 */
    String unzip(String path, String output) {
        File zf = ws.resolve(path == null ? "" : path);
        if (zf == null || !zf.isFile()) return (zh() ? "文件不存在：" : "file not found: ") + path;
        String outRel = Workspace.normalize(output == null || output.isEmpty() ? "unzip" : output);
        File dest = ws.resolve(outRel);
        if (dest == null) return zh() ? "错误：路径越界" : "error: path escapes the workspace";
        int n = 0;
        long total = 0;
        int skipped = 0;
        try (ZipInputStream zis = new ZipInputStream(new java.io.FileInputStream(zf))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (n + skipped >= MAX_ZIP_ENTRIES) break;
                File target = FsKit.resolveInside(dest, e.getName());
                if (target == null) {
                    skipped++;
                    continue;
                }
                if (e.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    target.mkdirs();
                    continue;
                }
                byte[] data = readLimited(zis, 32 * 1024 * 1024);
                total += data.length;
                if (total > MAX_ZIP_BYTES) break;
                if (FsKit.writeAtomic(target, data)) n++;
                else skipped++;
            }
        } catch (Exception ex) {
            return (zh() ? "解压失败：" : "unzip failed: ") + ex.getMessage();
        }
        return (zh() ? "已解压 " + n + " 个文件 → /" + outRel
                + (skipped > 0 ? "（跳过 " + skipped + " 个越界/失败条目）" : "")
                : "extracted " + n + " file(s) → /" + outRel
                  + (skipped > 0 ? " (skipped " + skipped + " unsafe entry/ies)" : ""));
    }

    /** 整份读入（限 64 MB）。用管道而不是 Files.readAllBytes：后者 API 26 才有。 */
    private static byte[] readBytes(File f) throws Exception {
        if (f.length() > 64L * 1024 * 1024) throw new IllegalStateException("file too large");
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream((int) Math.max(16, f.length()));
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static byte[] readLimited(ZipInputStream in, int cap) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (bos.size() + n > cap) throw new IllegalStateException("entry too large");
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    // ------------------------------------------------------------------ 文本

    String replaceText(String text, String pattern, String replacement) {
        if (text == null) return zh() ? "错误：缺少参数 text" : "error: missing argument text";
        if (pattern == null || pattern.isEmpty()) return zh() ? "错误：缺少参数 pattern" : "error: missing argument pattern";
        try {
            String out = text.replaceAll(pattern, replacement == null ? "" : replacement);
            int diff = Math.abs(out.length() - text.length());
            return (zh() ? "替换完成（长度变化 " + diff + " 字节）\n" : "replaced (length changed by " + diff + ")\n")
                    + truncate(out, MAX_RESULT);
        } catch (Exception e) {
            return (zh() ? "错误：正则无效 —— " : "error: invalid regex — ") + e.getMessage();
        }
    }

    String extract(String text, String kind, boolean zh) {
        if (text == null || text.isEmpty()) return zh() ? "错误：缺少参数 text" : "error: missing argument text";
        java.util.regex.Pattern p;
        if ("email".equals(kind)) {
            p = java.util.regex.Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+");
        } else if ("code".equals(kind)) {
            p = java.util.regex.Pattern.compile("```[ \\t]*([^\\n`]*)\\n([\\s\\S]*?)```");
        } else {
            p = java.util.regex.Pattern.compile("https?://[^\\s<>\"')]+");
        }
        java.util.regex.Matcher m = p.matcher(text);
        List<String> found = new ArrayList<>();
        while (m.find() && found.size() < 100) {
            if ("code".equals(kind)) {
                String lang = m.group(1).trim();
                String body = m.group(2);
                found.add((lang.isEmpty() ? "(no lang)" : lang) + " · " + body.length() + (zh() ? " 字节" : " bytes"));
            } else {
                String v = m.group().replaceAll("[.,;:!?]+$", "");
                if (!found.contains(v)) found.add(v);
            }
        }
        if (found.isEmpty()) return zh() ? "没有找到。" : "nothing found.";
        StringBuilder sb = new StringBuilder(zh() ? "找到 " + found.size() + " 项：\n" : found.size() + " item(s):\n");
        for (String s : found) sb.append("- ").append(s).append('\n');
        return sb.toString();
    }

    String convertTable(String text, String to) {
        if (text == null || text.trim().isEmpty()) return zh() ? "错误：缺少参数 text" : "error: missing argument text";
        boolean toCsv = to != null && to.toLowerCase(Locale.ROOT).startsWith("csv");
        if (toCsv) {
            String csv = DocConverter.markdownToCsv(text);
            return csv.isEmpty() ? (zh() ? "没有识别到 Markdown 表格。" : "no Markdown table found.")
                    : truncate(csv, MAX_RESULT);
        }
        char delim = text.indexOf('\t') >= 0 && text.indexOf(',') < 0 ? '\t' : ',';
        String md = DocConverter.csvToMarkdown(text, delim);
        return md.isEmpty() ? (zh() ? "没有识别到表格数据。" : "no table data found.") : truncate(md, MAX_RESULT);
    }

    String textStats(String text) {
        if (text == null) return zh() ? "错误：缺少参数 text" : "error: missing argument text";
        int chars = text.length();
        int cjk = 0;
        int words = 0;
        boolean inWord = false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp >= 0x4E00 && cp <= 0x9FFF) {
                cjk++;
                inWord = false;
            } else if (Character.isLetterOrDigit(cp)) {
                if (!inWord) words++;
                inWord = true;
            } else {
                inWord = false;
            }
        }
        int lines = text.isEmpty() ? 0 : text.split("\n", -1).length;
        int bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        return (zh() ? "字符 " : "chars ") + chars
                + (zh() ? "，汉字 " : ", CJK ") + cjk
                + (zh() ? "，词 " : ", words ") + words
                + (zh() ? "，行 " : ", lines ") + lines
                + (zh() ? "，字节 " : ", bytes ") + bytes
                + (zh() ? "，约 " : ", ~") + TextKit.roughTokens(text) + (zh() ? " token" : " tokens");
    }

    String diffText(String before, String after) {
        List<DiffKit.Line> d = DiffKit.diff(before == null ? "" : before, after == null ? "" : after);
        String s = DiffKit.summary(d, zh());
        String uni = DiffKit.unified("text", d, 3, 200, zh());
        return (zh() ? "变化：" : "changes: ") + s + "\n" + uni;
    }

    String outline(String text) {
        List<com.mscope.browser.core.MarkdownOutline.Heading> hs =
                com.mscope.browser.core.MarkdownOutline.extract(text == null ? "" : text);
        if (hs.isEmpty()) return zh() ? "这段文本没有 Markdown 标题，无法生成大纲。" : "no Markdown headings found.";
        StringBuilder sb = new StringBuilder();
        for (com.mscope.browser.core.MarkdownOutline.Heading h : hs) sb.append(h.toString()).append('\n');
        return sb.toString();
    }

    /** 抽取式摘要：按句子打分（关键词覆盖 + 长度惩罚），不调用模型。 */
    String summarize(String text, int want) {
        if (text == null || text.trim().isEmpty()) return zh() ? "错误：缺少参数 text" : "error: missing argument text";
        List<String> sents = TextKit.sentences(text);
        if (sents.size() <= 3) return text.trim();
        int n = want <= 0 ? 5 : Math.min(want, 20);
        Bm25 index = new Bm25();
        index.add("doc", text);
        index.index();
        // 用整段高频词作为查询，句子按命中密度排序
        String probe = topTerms(text, 12);
        List<Bm25.Hit> hits = index.search(probe, 1);
        double base = hits.isEmpty() ? 1 : Math.max(0.0001, hits.get(0).score);
        List<double[]> scored = new ArrayList<>();
        for (int i = 0; i < sents.size(); i++) {
            Bm25 sub = new Bm25();
            sub.add("s", sents.get(i));
            sub.index();
            List<Bm25.Hit> h = sub.search(probe, 1);
            double score = h.isEmpty() ? 0 : h.get(0).score;
            // 位置加权：开头与结尾的句子更可能是结论
            double pos = i == 0 ? 1.25 : (i == sents.size() - 1 ? 1.1 : 1.0);
            double lenPenalty = sents.get(i).length() > 200 ? 0.8 : 1.0;
            scored.add(new double[]{i, score * pos * lenPenalty / base});
        }
        scored.sort((a, b) -> Double.compare(b[1], a[1]));
        List<Integer> keep = new ArrayList<>();
        for (int i = 0; i < Math.min(n, scored.size()); i++) keep.add((int) scored.get(i)[0]);
        java.util.Collections.sort(keep);
        StringBuilder sb = new StringBuilder();
        for (int i : keep) sb.append("- ").append(sents.get(i)).append('\n');
        return (zh() ? "抽取式摘要（" + keep.size() + "/" + sents.size() + " 句，按原文顺序）：\n"
                : "Extractive summary (" + keep.size() + "/" + sents.size() + " sentences, source order):\n")
                + sb;
    }

    private static String topTerms(String text, int k) {
        java.util.Map<String, Integer> freq = new java.util.HashMap<>();
        for (String t : Bm25.tokens(text)) freq.merge(t, 1, Integer::sum);
        List<java.util.Map.Entry<String, Integer>> es = new ArrayList<>(freq.entrySet());
        es.sort((a, b) -> b.getValue().equals(a.getValue())
                ? a.getKey().compareTo(b.getKey()) : b.getValue() - a.getValue());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(k, es.size()); i++) sb.append(es.get(i).getKey()).append(' ');
        return sb.toString();
    }

    String jsonQuery(String json, String path) {
        Object root = JsonAccess.parse(json);
        if (root == null) return zh() ? "错误：不是合法的 JSON" : "error: not valid JSON";
        Object v = JsonAccess.get(root, path);
        if (v == null) return (zh() ? "该路径没有值：" : "no value at path: ") + path;
        return v instanceof JSONObject || v instanceof org.json.JSONArray
                ? truncate(String.valueOf(v), MAX_RESULT) : String.valueOf(v);
    }

    String jsonFormat(String json) {
        if (json == null || json.trim().isEmpty()) return zh() ? "错误：缺少参数 json" : "error: missing argument json";
        try {
            String t = json.trim();
            if (t.startsWith("{")) return new JSONObject(t).toString(2);
            if (t.startsWith("[")) return new org.json.JSONArray(t).toString(2);
            return zh() ? "错误：JSON 必须以 { 或 [ 开头" : "error: JSON must start with { or [";
        } catch (Exception e) {
            return (zh() ? "错误：不是合法 JSON —— " : "error: not valid JSON — ") + e.getMessage();
        }
    }

    static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + (zh() ? "\n…（内容过长，已截断）" : "\n… (truncated: too long)");
    }

    private static String orDot(String s) {
        return s == null || s.isEmpty() ? "." : s;
    }
}
