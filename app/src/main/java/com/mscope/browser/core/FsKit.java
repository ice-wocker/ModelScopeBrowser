package com.mscope.browser.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 文件系统工具：路径收敛、原子写、容器格式嗅探、磁盘清单。
 *
 * <p>核心约定只有一条：**任何写操作都必须先收敛到根目录内**。智能体生成的路径来自模型，
 * 一个 {@code ../../shared_prefs/x.xml} 就能越界；这里统一用 canonical path 前缀比较，
 * 并且拒绝软链接穿越（canonical 之后会解开链接，前缀比较自然挡住）。
 */
public final class FsKit {

    private FsKit() {
    }

    /**
     * 把用户/模型给的相对路径收敛成根目录下的绝对路径。
     *
     * @return 合法时返回规范化后的文件；越界或非法返回 null
     */
    public static File resolveInside(File root, String rel) {
        if (root == null || rel == null) return null;
        String cleaned = rel.trim().replace('\\', '/');
        if (cleaned.isEmpty()) return null;
        if (cleaned.indexOf('\0') >= 0) return null;
        File candidate = new File(cleaned);
        if (!candidate.isAbsolute()) candidate = new File(root, cleaned);
        try {
            String rootPath = root.getCanonicalPath();
            String target = candidate.getCanonicalPath();
            if (target.equals(rootPath)) return root;
            if (!target.startsWith(rootPath + File.separator)) return null;
            return candidate;
        } catch (IOException e) {
            return null;
        }
    }

    /** 原子写：先写同目录临时文件，fsync 后改名覆盖。掉电/被杀不会留下半截文件。 */
    public static boolean writeAtomic(File target, byte[] data) {
        if (target == null) return false;
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return false;
        File tmp = new File(dir, target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data == null ? new byte[0] : data);
            out.flush();
            out.getFD().sync();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
        if (target.exists() && !target.delete()) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
        return tmp.renameTo(target);
    }

    public static boolean writeAtomic(File target, String text) {
        return writeAtomic(target, text == null ? null : text.getBytes(StandardCharsets.UTF_8));
    }

    /** 读整个文件（限长，超长截断并加提示），避免 cat 一个大文件把内存吃穿。 */
    public static String readText(File f, int maxBytes) {
        if (f == null || !f.isFile()) return "";
        int limit = maxBytes > 0 ? maxBytes : 512 * 1024;
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.min(limit, 8192));
            byte[] buf = new byte[8192];
            int n;
            boolean truncated = false;
            while ((n = in.read(buf)) > 0) {
                if (bos.size() + n > limit) {
                    bos.write(buf, 0, limit - bos.size());
                    truncated = true;
                    break;
                }
                bos.write(buf, 0, n);
            }
            String s = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            return truncated ? s + "\n…（内容过长，已截断）" : s;
        } catch (IOException e) {
            return "";
        }
    }

    /* ------------------------------------------------- 容器 / 文件格式嗅探 */

    /** 嗅探结果，供「这文件真是 GGUF 吗」这类判断使用。 */
    public static final class Magic {
        public final String kind;
        public final String detail;
        public final boolean matches;

        Magic(String kind, String detail, boolean matches) {
            this.kind = kind;
            this.detail = detail;
            this.matches = matches;
        }
    }

    private static final byte[] MAGIC_GGUF = {'G', 'G', 'U', 'F'};
    private static final byte[] MAGIC_ZIP = {'P', 'K', 3, 4};
    private static final byte[] MAGIC_SQLITE = "SQLite format 3".getBytes(StandardCharsets.US_ASCII);

    /**
     * 读文件头判断真实类型。判断「是不是 GGUF」比只看后缀可靠得多：
     * 魔搭在鉴权失败时可能返回一个 HTML 错误页并命名为 .gguf。
     */
    public static Magic sniff(File f) {
        if (f == null || !f.isFile()) return new Magic("missing", "文件不存在", false);
        byte[] head = new byte[16];
        int n;
        try (InputStream in = new FileInputStream(f)) {
            n = in.read(head);
        } catch (IOException e) {
            return new Magic("unreadable", "无法读取", false);
        }
        if (n <= 0) return new Magic("empty", "空文件", false);
        String text = new String(head, 0, Math.min(n, 12), StandardCharsets.ISO_8859_1).trim();
        String lower = text.toLowerCase(Locale.ROOT);
        if (startsWith(head, n, MAGIC_GGUF)) return new Magic("gguf", "GGUF 模型文件", true);
        if (startsWith(head, n, MAGIC_ZIP)) return new Magic("zip", "ZIP / APK / GGUF 打包", false);
        if (startsWith(head, n, MAGIC_SQLITE)) return new Magic("sqlite", "SQLite 数据库", false);
        if (lower.startsWith("<!doctype") || lower.startsWith("<html") || lower.startsWith("{")) {
            return new Magic("text", "网页或 JSON（多半是错误响应）", false);
        }
        return new Magic("unknown", "未知类型", false);
    }

    private static boolean startsWith(byte[] buf, int len, byte[] magic) {
        if (len < magic.length) return false;
        for (int i = 0; i < magic.length; i++) {
            if (buf[i] != magic[i]) return false;
        }
        return true;
    }

    /* ------------------------------------------------------------- 磁盘清单 */

    /** 目录统计结果。 */
    public static final class DirStat {
        public final int files;
        public final int dirs;
        public final long bytes;
        public final int maxDepth;
        /** 最大的那个文件路径（可能是相对路径） */
        public final String biggest;

        public DirStat(int files, int dirs, long bytes, int maxDepth, String biggest) {
            this.files = files;
            this.dirs = dirs;
            this.bytes = bytes;
            this.maxDepth = maxDepth;
            this.biggest = biggest;
        }
    }

    /** 递归统计目录（含子目录）。符号链接不跟随，避免环。 */
    public static DirStat statDir(File dir, String relPrefix) {
        int files = 0, dirs = 0, depth = 0;
        long bytes = 0;
        String biggest = "";
        long biggestBytes = -1;
        if (dir == null || !dir.isDirectory()) return new DirStat(0, 0, 0, 0, "");
        File[] children = dir.listFiles();
        if (children == null) return new DirStat(0, 0, 0, 0, "");
        Arrays.sort(children, Comparator.comparing(File::getName));
        for (File c : children) {
            String rel = (relPrefix == null || relPrefix.isEmpty() ? "" : relPrefix + "/") + c.getName();
            if (c.isDirectory()) {
                dirs++;
                DirStat sub = statDir(c, rel);
                files += sub.files;
                dirs += sub.dirs;
                bytes += sub.bytes;
                depth = Math.max(depth, sub.maxDepth + 1);
                if (sub.bytes > biggestBytes) {
                    biggestBytes = sub.bytes;
                    if (!sub.biggest.isEmpty()) biggest = sub.biggest;
                }
            } else {
                files++;
                long len = c.length();
                bytes += len;
                if (len > biggestBytes) {
                    biggestBytes = len;
                    biggest = rel;
                }
            }
        }
        return new DirStat(files, dirs, bytes, depth, biggest);
    }

    /** 列出目录下的一级条目，按「目录在前、名字升序」排序。 */
    public static List<File> listSorted(File dir) {
        List<File> out = new ArrayList<>();
        if (dir == null || !dir.isDirectory()) return out;
        File[] children = dir.listFiles();
        if (children == null) return out;
        out.addAll(Arrays.asList(children));
        out.sort((a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        return out;
    }

    /** 递归删除（拒绝删除根目录本身）。 */
    public static boolean deleteTree(File f, File root) {
        if (f == null || !f.exists()) return false;
        if (root != null) {
            try {
                if (f.getCanonicalPath().equals(root.getCanonicalPath())) return false;
            } catch (IOException e) {
                return false;
            }
        }
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteTree(c, root);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        return f.delete();
    }
}
