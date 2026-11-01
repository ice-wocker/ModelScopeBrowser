package com.mscope.browser.core;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 存储巡检：在「下载 / 加载模型」这类会吃掉几百 MB 的动作之前，先把话说明白。
 *
 * <p>手机上最容易踩的坑不是下载失败，而是**下载成功但装不下**：文件写满分区，
 * 后续的原子改名、索引落盘、甚至 KV 申请都会失败，表现成一串莫名其妙的错误。
 * 这里把「剩余空间 - 模型大小 - 安全水位」算成一句人话，调用方直接展示即可。
 *
 * <p>同时提供「本地模型库体检」：找出索引里有但磁盘上没有的幽灵记录、
 * 磁盘上有但索引里没有的孤儿文件 —— 这两种都是历史版本并发写留下的。
 */
public final class StorageGuard {

    /** 系统侧安全水位：低于它就别再往里塞大文件了。 */
    public static final long SAFETY_BYTES = 256L * 1024L * 1024L;
    /** 低于这个剩余空间直接判定「不可下载」，不再看模型大小。 */
    public static final long HARD_FLOOR_BYTES = 128L * 1024L * 1024L;

    private StorageGuard() {
    }

    public enum Verdict {
        /** 空间充足 */
        OK,
        /** 放得下但会低于安全水位，建议提示用户 */
        TIGHT,
        /** 放不下 */
        INSUFFICIENT,
        /** 连硬下限都不满足 */
        CRITICAL
    }

    public static final class Check {
        public final Verdict verdict;
        public final long needBytes;
        public final long freeBytes;
        public final String message;

        Check(Verdict verdict, long needBytes, long freeBytes, String message) {
            this.verdict = verdict;
            this.needBytes = needBytes;
            this.freeBytes = freeBytes;
            this.message = message;
        }

        public boolean canStart() {
            return verdict == Verdict.OK || verdict == Verdict.TIGHT;
        }

        public boolean shouldWarn() {
            return verdict == Verdict.TIGHT;
        }
    }

    /**
     * 判定「要不要开始这次下载」。
     *
     * @param freeBytes    目标分区剩余字节
     * @param needBytes    本次要写入的字节（续传时应传「剩余部分」）
     * @param alreadyHave  本地已有的字节（续传进度，仅用于文案）
     */
    public static Check check(long freeBytes, long needBytes, long alreadyHave) {
        long need = Math.max(0, needBytes);
        if (freeBytes < HARD_FLOOR_BYTES) {
            return new Check(Verdict.CRITICAL, need, freeBytes, String.format(Locale.ROOT,
                    "存储几乎满了：仅剩 %s，至少要留 %s 才能继续下载。请先删掉不用的模型。",
                    human(freeBytes), human(HARD_FLOOR_BYTES)));
        }
        if (need == 0) {
            return new Check(Verdict.OK, 0, freeBytes, "无需额外空间");
        }
        if (freeBytes < need + SAFETY_BYTES) {
            if (freeBytes < need) {
                return new Check(Verdict.INSUFFICIENT, need, freeBytes, String.format(Locale.ROOT,
                        "空间不足：需要 %s，可用 %s，还差 %s。",
                        human(need), human(freeBytes), human(need - freeBytes)));
            }
            return new Check(Verdict.TIGHT, need, freeBytes, String.format(Locale.ROOT,
                    "空间紧张：下载后仅剩 %s（建议保留 %s 以上）。也可先删掉不用的模型。",
                    human(freeBytes - need), human(SAFETY_BYTES)));
        }
        String extra = alreadyHave > 0
                ? String.format(Locale.ROOT, "（已下载 %s，本次仍需 %s）", human(alreadyHave), human(need))
                : "";
        return new Check(Verdict.OK, need, freeBytes,
                String.format(Locale.ROOT, "空间充足：可用 %s，需要 %s%s", human(freeBytes), human(need), extra));
    }

    /** 目标目录所在分区剩余字节；拿不到时返回 -1。 */
    public static long freeBytes(File dir) {
        try {
            if (dir == null) return -1;
            File probe = dir;
            while (probe != null && !probe.exists()) probe = probe.getParentFile();
            if (probe == null) return -1;
            long usable = probe.getUsableSpace();
            return usable > 0 ? usable : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /* ------------------------------------------------------- 模型库体检 */

    /** 一条体检结论。 */
    public static final class Finding {
        public final Kind kind;
        public final String path;
        public final long bytes;

        public Finding(Kind kind, String path, long bytes) {
            this.kind = kind;
            this.path = path;
            this.bytes = bytes;
        }

        @Override
        public String toString() {
            return kind + " " + path + " (" + human(bytes) + ")";
        }
    }

    public enum Kind {
        /** 索引里有、磁盘上没有：点了会报「文件不存在」的幽灵条目 */
        GHOST_ENTRY,
        /** 磁盘上有、索引里没有：白占空间的孤儿文件 */
        ORPHAN_FILE,
        /** 体积为 0 或极小：多半是下载中断留下的空壳 */
        EMPTY_FILE
    }

    /** 一个模型记录的最小必要信息。 */
    public static final class Entry {
        public final String path;
        public final long bytes;

        public Entry(String path, long bytes) {
            this.path = path;
            this.bytes = bytes;
        }
    }

    /**
     * 交叉比对索引与磁盘。
     *
     * @param entries  索引里的记录
     * @param existing 磁盘上实际存在的 .gguf 路径 → 字节数
     */
    public static List<Finding> audit(List<Entry> entries, List<Entry> existing) {
        List<Finding> out = new ArrayList<>();
        if (entries == null) entries = Collections.emptyList();
        if (existing == null) existing = Collections.emptyList();

        List<String> keys = new ArrayList<>();
        for (Entry e : entries) {
            if (e == null || e.path == null) continue;
            keys.add(normalize(e.path));
        }
        for (Entry e : existing) {
            if (e == null || e.path == null) continue;
            if (keys.contains(normalize(e.path))) continue;
            out.add(new Finding(Kind.ORPHAN_FILE, e.path, e.bytes));
        }

        for (Entry e : entries) {
            if (e == null || e.path == null) continue;
            Entry hit = null;
            for (Entry x : existing) {
                if (x != null && x.path != null && normalize(x.path).equals(normalize(e.path))) {
                    hit = x;
                    break;
                }
            }
            if (hit == null) {
                out.add(new Finding(Kind.GHOST_ENTRY, e.path, e.bytes));
            } else if (hit.bytes <= 0) {
                out.add(new Finding(Kind.EMPTY_FILE, hit.path, hit.bytes));
            }
        }
        return out;
    }

    private static String normalize(String path) {
        String p = path.replace('\\', '/');
        while (p.contains("//")) p = p.replace("//", "/");
        return p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
    }

    /** 体检结论汇总成一句人话；没问题时返回空串。 */
    public static String summarize(List<Finding> findings) {
        if (findings == null || findings.isEmpty()) return "";
        int ghost = 0, orphan = 0, empty = 0;
        long waste = 0;
        for (Finding f : findings) {
            if (f.kind == Kind.GHOST_ENTRY) {
                ghost++;
            } else if (f.kind == Kind.ORPHAN_FILE) {
                orphan++;
                waste += Math.max(0, f.bytes);
            } else {
                empty++;
            }
        }
        List<String> parts = new ArrayList<>();
        if (ghost > 0) parts.add(ghost + " 条记录找不到文件");
        if (orphan > 0) parts.add(orphan + " 个文件没被索引（可回收 " + human(waste) + "）");
        if (empty > 0) parts.add(empty + " 个空文件");
        return "本地模型库体检：" + String.join("；", parts);
    }

    /** 人类可读体积。刻意不复用 Format，避免 UI 依赖。 */
    public static String human(long b) {
        if (b < 0) return "未知";
        if (b < 1024) return b + " B";
        double kb = b / 1024d;
        if (kb < 1024) return String.format(Locale.ROOT, "%.0f KB", kb);
        double mb = kb / 1024d;
        if (mb < 1024) return String.format(Locale.ROOT, "%.1f MB", mb);
        return String.format(Locale.ROOT, "%.2f GB", mb / 1024d);
    }
}
