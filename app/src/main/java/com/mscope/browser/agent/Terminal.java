package com.mscope.browser.agent;

import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 本机终端：在应用私有工作区内执行一组受限命令。
 *
 * <p>运行环境就是「本机」——命令直接读写手机上的工作区目录（{@link Workspace}），
 * 不联网、不调用外部进程，因此无需任何系统权限，也不会影响工作区以外的文件。
 * 支持的命令见 {@link #help()}。
 */
public class Terminal {

    public static class Out {
        public final String text;
        public final int code;

        public Out(String text, int code) {
            this.text = text;
            this.code = code;
        }
    }

    private final Workspace ws;
    /** 当前目录（工作区内相对路径，空串表示根）。 */
    private String cwd = "";

    public Terminal(Workspace ws) {
        this.ws = ws;
    }

    public String cwd() {
        return cwd.isEmpty() ? "/" : "/" + cwd;
    }

    public String help() {
        return "可用命令（工作区根目录，本机环境）：\n"
                + "  help                 显示本帮助\n"
                + "  pwd                  显示当前目录\n"
                + "  ls [目录]            列出目录内容\n"
                + "  cd <目录>            切换目录（cd .. 返回上级）\n"
                + "  cat <文件>           显示文本文件内容\n"
                + "  head <文件> [行数]   显示文件开头若干行（默认 20）\n"
                + "  wc <文件>            统计行数 / 字数 / 字节\n"
                + "  write <文件> <内容>  覆盖写入文件（自动建目录）\n"
                + "  append <文件> <内容> 追加内容到文件\n"
                + "  mkdir <目录>         新建目录\n"
                + "  rm [-r] <路径>       删除文件 / 目录\n"
                + "  mv <源> <目标>        移动 / 重命名\n"
                + "  cp <源> <目标>        复制\n"
                + "  find <关键字>        按文件名递归查找\n"
                + "  tree [目录]          以树形显示目录结构\n"
                + "  echo <文本>          回显文本\n"
                + "  date                 显示本机时间\n"
                + "  uname                显示本机系统信息\n"
                + "  df                   显示工作区占用\n"
                + "  clear                清屏";
    }

    /** 执行一行命令，返回输出与退出码（0 成功、非 0 失败）。 */
    public Out run(String line) {
        List<String> a = split(line == null ? "" : line.trim());
        if (a.isEmpty()) return new Out("", 0);
        String cmd = a.get(0).toLowerCase();
        try {
            switch (cmd) {
                case "help":
                    return ok(help());
                case "pwd":
                    return ok(cwd());
                case "ls":
                    return ok(ls(a.size() > 1 ? a.get(1) : ""));
                case "cd":
                    return cd(a.size() > 1 ? a.get(1) : "");
                case "cat":
                    require(a, 2, "cat <文件>");
                    return ok(ws.readText(abs(a.get(1))));
                case "head": {
                    require(a, 2, "head <文件> [行数]");
                    int n = a.size() > 2 ? parseInt(a.get(2), 20) : 20;
                    String t = ws.readText(abs(a.get(1)));
                    String[] lines = t.split("\n", -1);
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < Math.min(n, lines.length); i++) sb.append(lines[i]).append('\n');
                    return ok(sb.toString().trim());
                }
                case "wc": {
                    require(a, 2, "wc <文件>");
                    File wf = ws.resolve(abs(a.get(1)));
                    String t = ws.readText(abs(a.get(1)));
                    int lines = t.isEmpty() ? 0 : t.split("\n", -1).length;
                    return ok(lines + " 行  " + t.length() + " 字符  " + wf.length() + " 字节");
                }
                case "write": {
                    require(a, 3, "write <文件> <内容>");
                    ws.writeText(abs(a.get(1)), rest(line, 2));
                    return ok("已写入 " + rel(abs(a.get(1))));
                }
                case "append": {
                    require(a, 3, "append <文件> <内容>");
                    ws.appendText(abs(a.get(1)), rest(line, 2));
                    return ok("已追加到 " + rel(abs(a.get(1))));
                }
                case "mkdir": {
                    require(a, 2, "mkdir <目录>");
                    ws.mkdir(abs(a.get(1)));
                    return ok("已创建 " + rel(abs(a.get(1))));
                }
                case "rm": {
                    boolean rec = a.size() > 1 && a.get(1).startsWith("-");
                    String target = rec ? (a.size() > 2 ? a.get(2) : "") : (a.size() > 1 ? a.get(1) : "");
                    if (target.isEmpty()) return err("用法：rm [-r] <路径>");
                    File f = ws.resolve(abs(target));
                    if (f.isDirectory() && !rec) return err("这是目录，请用 rm -r " + target);
                    return ws.delete(abs(target)) ? ok("已删除 " + target) : err("不存在：" + target);
                }
                case "mv":
                    require(a, 3, "mv <源> <目标>");
                    return move(abs(a.get(1)), abs(a.get(2)), false);
                case "cp":
                    require(a, 3, "cp <源> <目标>");
                    return move(abs(a.get(1)), abs(a.get(2)), true);
                case "find": {
                    require(a, 2, "find <关键字>");
                    List<String> hits = new ArrayList<>();
                    find(ws.root(), a.get(1).toLowerCase(), hits, 200);
                    if (hits.isEmpty()) return ok("未找到匹配文件");
                    StringBuilder hs = new StringBuilder();
                    for (String h : hits) hs.append(h).append('\n');
                    return ok(hs.toString().trim());
                }
                case "tree":
                    return ok(tree(a.size() > 1 ? abs(a.get(1)) : "", 0));
                case "echo":
                    return ok(a.size() > 1 ? rest(line, 1) : "");
                case "date":
                    return ok(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                            java.util.Locale.getDefault()).format(new java.util.Date()));
                case "uname":
                    return ok("Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") · "
                            + Build.MANUFACTURER + " " + Build.MODEL + " · " + Build.SUPPORTED_ABIS[0]);
                case "df":
                    return ok("工作区占用 " + ws.totalSize() + " 字节（本机私有目录）");
                case "clear":
                    return new Out("\f", 0);
                default:
                    return err("未知命令：" + cmd + "（输入 help 查看可用命令）");
            }
        } catch (IllegalArgumentException e) {
            return err(e.getMessage());
        } catch (IOException e) {
            return err(e.getMessage());
        } catch (Exception e) {
            return err(String.valueOf(e.getMessage()));
        }
    }

    /* ------------------------------------------------------------------ 内部 */

    private Out ok(String s) {
        return new Out(s == null ? "" : s, 0);
    }

    private Out err(String s) {
        return new Out(s == null ? "" : s, 1);
    }

    private static void require(List<String> a, int n, String usage) {
        if (a.size() < n) throw new IllegalArgumentException("用法：" + usage);
    }

    private String ls(String arg) throws IOException {
        String dir = abs(arg.isEmpty() ? "." : arg);
        File f = ws.resolve(dir);
        if (!f.exists()) return "不存在：" + (arg.isEmpty() ? cwd() : arg);
        if (f.isFile()) return ws.relOf(f) + "  (" + f.length() + " 字节)";
        List<Workspace.Entry> es = ws.list(dir);
        if (es.isEmpty()) return "（空目录）";
        StringBuilder sb = new StringBuilder();
        for (Workspace.Entry e : es) {
            sb.append(e.dir ? "d " : "- ")
              .append(pad(String.valueOf(e.dir ? 0 : e.size), 8))
              .append(e.name)
              .append(e.dir ? "/" : "")
              .append('\n');
        }
        return sb.toString().trim();
    }

    private Out cd(String arg) {
        if (arg.isEmpty() || "~".equals(arg) || "/".equals(arg)) {
            cwd = "";
            return ok(cwd());
        }
        if ("..".equals(arg)) {
            int i = cwd.lastIndexOf('/');
            cwd = i < 0 ? "" : cwd.substring(0, i);
            return ok(cwd());
        }
        String target = Workspace.normalize(abs(arg));
        if (!ws.exists(target) || !ws.isDir(target)) return err("目录不存在：" + arg);
        cwd = target;
        return ok(cwd());
    }

    private Out move(String src, String dst, boolean copy) {
        File s = ws.resolve(src);
        if (!s.exists()) return err("源不存在：" + rel(src));
        File d = ws.resolve(dst);
        if (d.isDirectory()) d = new File(d, s.getName());
        try {
            File parent = d.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return err("无法创建目标目录：" + rel(dst));
            }
            if (copy) {
                copyRec(s, d);
                return ok("已复制到 " + ws.relOf(d));
            }
            if (s.renameTo(d)) return ok("已移动到 " + ws.relOf(d));
            copyRec(s, d);
            ws.delete(src);
            return ok("已移动到 " + ws.relOf(d));
        } catch (IOException e) {
            return err(e.getMessage());
        }
    }

    private static void copyRec(File s, File d) throws IOException {
        if (s.isDirectory()) {
            if (!d.exists() && !d.mkdirs()) throw new IOException("无法创建目录：" + d.getName());
            File[] cs = s.listFiles();
            if (cs != null) for (File c : cs) copyRec(c, new File(d, c.getName()));
            return;
        }
        try (java.io.InputStream in = new java.io.FileInputStream(s);
             java.io.OutputStream out = new java.io.FileOutputStream(d)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static void find(File dir, String key, List<String> out, int limit) {
        if (out.size() >= limit) return;
        File[] cs = dir.listFiles();
        if (cs == null) return;
        for (File c : cs) {
            if (c.getName().toLowerCase().contains(key)) {
                out.add(c.isDirectory() ? c.getName() + "/" : c.getName());
            }
            if (c.isDirectory()) find(c, key, out, limit);
        }
    }

    private String tree(String rel, int depth) {
        if (depth > 6) return "";
        File dir = ws.resolve(rel);
        if (!dir.isDirectory()) return "";
        List<Workspace.Entry> es = ws.list(rel);
        StringBuilder sb = new StringBuilder();
        for (Workspace.Entry e : es) {
            sb.append(indent(depth)).append(e.dir ? "├─ " : "│  ").append(e.name)
              .append(e.dir ? "/" : "").append('\n');
            if (e.dir) sb.append(tree(e.relPath, depth + 1));
        }
        return sb.toString().trim();
    }

    private static String indent(int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("  ");
        return sb.toString();
    }

    /** 相对路径 → 相对工作区根的净路径（用于 Workspace 调用）。 */
    private String abs(String p) {
        if (p == null) return cwd;
        String t = p.trim();
        if (t.isEmpty() || ".".equals(t)) return cwd;
        if ("~".equals(t)) return "";
        if (t.startsWith("/")) return Workspace.normalize(t);
        return Workspace.normalize(cwd.isEmpty() ? t : cwd + "/" + t);
    }

    private String rel(String absRel) {
        return "/" + Workspace.normalize(absRel);
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static String pad(String s, int n) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < n) sb.append(' ');
        return sb.toString();
    }

    /** 取第 idx 个 token 之后的原始文本（保留空格，供 write/append/echo 用）。 */
    private static String rest(String line, int idx) {
        String t = line.trim();
        for (int i = 0; i < idx; i++) {
            int sp = t.indexOf(' ');
            if (sp < 0) return "";
            t = t.substring(sp + 1).trim();
        }
        return t;
    }

    /** 按空白切分，支持单/双引号包裹的参数。 */
    private static List<String> split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        boolean started = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                else cur.append(c);
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                started = true;
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (started || cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    started = false;
                }
                continue;
            }
            cur.append(c);
            started = true;
        }
        if (started || cur.length() > 0) out.add(cur.toString());
        return out;
    }
}