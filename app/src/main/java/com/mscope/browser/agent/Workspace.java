package com.mscope.browser.agent;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 应用私有工作区：模型与用户创建的文件都落在这里（filesDir/workspace）。
 *
 * <p>所有路径都会做规范化并强制限制在工作区根目录内，`../` 之类的越界访问会被拒绝。
 * 写入采用「临时文件 + 原子改名」，避免中途被杀留下半截文件。
 */
public class Workspace {

    /** 文本读取上限，防止把巨大文件整体读进内存。 */
    private static final long READ_LIMIT = 512 * 1024;

    public static class Entry {
        public final String name;
        public final String relPath;
        public final boolean dir;
        public final long size;
        public final long modified;

        Entry(String name, String relPath, boolean dir, long size, long modified) {
            this.name = name;
            this.relPath = relPath;
            this.dir = dir;
            this.size = size;
            this.modified = modified;
        }

        public boolean isText() {
            if (dir) return false;
            String n = name.toLowerCase();
            return n.endsWith(".html") || n.endsWith(".htm") || n.endsWith(".txt")
                    || n.endsWith(".md") || n.endsWith(".json") || n.endsWith(".csv")
                    || n.endsWith(".xml") || n.endsWith(".js") || n.endsWith(".css")
                    || n.endsWith(".java") || n.endsWith(".py") || n.endsWith(".kt")
                    || n.endsWith(".c") || n.endsWith(".cpp") || n.endsWith(".h")
                    || n.endsWith(".log") || n.endsWith(".yml") || n.endsWith(".yaml")
                    || n.endsWith(".sh") || n.endsWith(".sql") || n.endsWith(".ini");
        }

        public boolean isHtml() {
            if (dir) return false;
            String n = name.toLowerCase();
            return n.endsWith(".html") || n.endsWith(".htm");
        }
    }

    private final File root;

    public Workspace(Context ctx) {
        root = new File(ctx.getFilesDir(), "workspace");
        //noinspection ResultOfMethodCallIgnored
        root.mkdirs();
    }

    public File root() {
        return root;
    }

    /** 把工作区内的相对路径解析成 File，并保证不逃出根目录。 */
    public File resolve(String rel) {
        String r = rel == null ? "" : rel.trim();
        r = normalize(r);
        File f = new File(root, r);
        try {
            String base = root.getCanonicalPath();
            String path = f.getCanonicalPath();
            if (!path.equals(base) && !path.startsWith(base + File.separator)) {
                throw new IllegalArgumentException("路径越出工作区：" + rel);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("路径不合法：" + rel);
        }
        return f;
    }

    /** 计算相对路径；不在工作区内时退回文件名。 */
    public String relOf(File f) {
        try {
            String base = root.getCanonicalPath();
            String p = f.getCanonicalPath();
            if (p.equals(base)) return "";
            if (p.startsWith(base + File.separator)) return p.substring(base.length() + 1);
        } catch (IOException ignored) {
        }
        return f.getName();
    }

    /** 规范化：去掉开头斜杠、折叠 . 与 ..（不处理越界，交给 resolve 兜底）。 */
    public static String normalize(String rel) {
        String r = rel == null ? "" : rel.replace('\\', '/').trim();
        List<String> parts = new ArrayList<>();
        for (String seg : r.split("/")) {
            if (seg.isEmpty() || ".".equals(seg)) continue;
            if ("..".equals(seg)) {
                if (!parts.isEmpty()) parts.remove(parts.size() - 1);
                continue;
            }
            parts.add(seg);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append('/');
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    public boolean exists(String rel) {
        return resolve(rel).exists();
    }

    public boolean isDir(String rel) {
        return resolve(rel).isDirectory();
    }

    /** 列出目录内容，目录在前、再按名称排序。 */
    public List<Entry> list(String relDir) {
        File dir = resolve(relDir);
        List<Entry> out = new ArrayList<>();
        File[] fs = dir.listFiles();
        if (fs != null) {
            for (File f : fs) {
                if (f.getName().endsWith(".tmp")) continue;
                boolean d = f.isDirectory();
                out.add(new Entry(f.getName(), relOf(f), d, d ? 0 : f.length(), f.lastModified()));
            }
        }
        Collections.sort(out, (a, b) -> {
            if (a.dir != b.dir) return a.dir ? -1 : 1;
            return a.name.compareToIgnoreCase(b.name);
        });
        return out;
    }

    public String readText(String rel) throws IOException {
        File f = resolve(rel);
        if (!f.exists()) throw new IOException("文件不存在：" + rel);
        if (f.isDirectory()) throw new IOException("这是一个目录：" + rel);
        if (f.length() > READ_LIMIT) {
            throw new IOException("文件过大（" + f.length() + " 字节），仅支持读取 512KB 以内的文本");
        }
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        }
    }

    /** 覆盖写入（自动建父目录，原子落盘）。 */
    public File writeText(String rel, String content) throws IOException {
        File f = resolve(rel);
        if (f.isDirectory()) throw new IOException("目标是一个目录：" + rel);
        File parent = f.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建目录：" + rel);
        }
        byte[] bytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        File tmp = new File(parent, f.getName() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
            out.flush();
        }
        if (tmp.renameTo(f)) return f;
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        if (tmp.renameTo(f)) return f;
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(bytes);
        }
        return f;
    }

    public void appendText(String rel, String content) throws IOException {
        File f = resolve(rel);
        File parent = f.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("无法创建目录：" + rel);
        }
        try (OutputStream out = new FileOutputStream(f, true)) {
            out.write((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
        }
    }

    public boolean delete(String rel) {
        File f = resolve(rel);
        if (!f.exists()) return false;
        File r = f;
        return deleteRec(r);
    }

    private static boolean deleteRec(File f) {
        if (f.isDirectory()) {
            File[] cs = f.listFiles();
            if (cs != null) for (File c : cs) deleteRec(c);
        }
        return f.delete();
    }

    public File mkdir(String rel) throws IOException {
        File f = resolve(rel);
        if (!f.exists() && !f.mkdirs()) throw new IOException("无法创建目录：" + rel);
        return f;
    }

    /** 工作区总占用字节数。 */
    public long totalSize() {
        return sizeRec(root);
    }

    private static long sizeRec(File f) {
        if (f == null) return 0;
        if (f.isFile()) return f.length();
        long n = 0;
        File[] cs = f.listFiles();
        if (cs != null) for (File c : cs) n += sizeRec(c);
        return n;
    }
}