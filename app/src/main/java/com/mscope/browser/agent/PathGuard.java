package com.mscope.browser.agent;

import com.mscope.browser.core.FsKit;

import java.io.File;

/**
 * 终端命令的**执行前**检查（不是事后过滤）。
 *
 * <p>{@link Shell} 已经靠 Android 沙箱兜底：进程只能写应用私有目录。但那只是「写不到别处」，
 * 挡不住「把工作区自己删干净」或者「把模型下载目录连根拔掉」。这里有两条线：
 * <ol>
 *   <li>硬拒绝：{@code rm -rf /}、{@code rm -rf ~}、{@code dd of=/dev/…}、清空私有根目录；</li>
 *   <li>警告：递归删除、通配符、重定向到根目录、{@code :(){ :|:&};:} 这类 fork 炸弹写法。</li>
 * </ol>
 *
 * <p>纯字符串分析，无 Android 依赖，可单测。**不假装是沙箱**：真正的隔离在系统层，
 * 这里只是把「明显不可逆」的命令拦在确认弹窗之前。
 */
public final class PathGuard {

    /** 判定结果。 */
    public static final class Verdict {
        public final boolean allowed;
        /** 需要二次确认（危险但可能合理）。 */
        public final boolean warn;
        public final String reason;

        Verdict(boolean allowed, boolean warn, String reason) {
            this.allowed = allowed;
            this.warn = warn;
            this.reason = reason;
        }

        public static Verdict ok() {
            return new Verdict(true, false, "");
        }

        public static Verdict warn(String why) {
            return new Verdict(true, true, why);
        }

        public static Verdict deny(String why) {
            return new Verdict(false, true, why);
        }
    }

    private PathGuard() {
    }

    public static Verdict check(String command) {
        if (command == null || command.trim().isEmpty()) return Verdict.deny("empty");
        String c = command.replaceAll("\\s+", " ").trim();

        // ---- 硬拒绝
        if (c.matches(".*rm\\s+(-[a-zA-Z]*[rR][a-zA-Z]*\\s+)+(/|/\\*|~|\\$HOME|\\.\\.|\\.\\./\\*)(\\s|$).*")) {
            return Verdict.deny("rm on / ~ .. is not allowed");
        }
        if (c.contains(":(){") || c.matches(".*\\w+\\(\\)\\s*\\{\\s*\\w+\\s*\\|\\s*\\w+.*")) {
            return Verdict.deny("fork bomb pattern");
        }
        if (c.matches(".*>\\s*/dev/(block|sd|mmcblk).*") || c.matches(".*of=/dev/(block|sd|mmcblk).*")) {
            return Verdict.deny("raw block device write");
        }
        if (c.matches(".*\\b(mkfs|fdisk|mke2fs|format)\\b.*")) {
            return Verdict.deny("filesystem tooling");
        }
        if (c.matches(".*\\b(su|sudo)\\b.*")) {
            return Verdict.deny("privilege escalation is pointless inside the app sandbox");
        }
        if (c.matches(".*\\bchmod\\s+-R\\s+0?00\\s+.*") || c.matches(".*\\bchown\\s+-R\\s+.*")) {
            return Verdict.deny("recursive permission change");
        }
        if (c.matches(".*\\bpm\\s+(uninstall|clear)\\b.*") || c.matches(".*\\bam\\s+force-stop\\b.*")) {
            return Verdict.deny("package manager operations");
        }

        // ---- 警告（允许但要在确认弹窗里说清楚）
        if (c.matches(".*rm\\s+-[a-zA-Z]*[rR].*")) {
            return Verdict.warn("recursive delete");
        }
        if (c.matches(".*\\brm\\b.*\\*.*")) {
            return Verdict.warn("delete with wildcard");
        }
        if (c.matches(".*>\\s*(/|\\.\\.).*")) {
            return Verdict.warn("redirect outside workspace");
        }
        if (c.matches(".*\\b(curl|wget)\\b.*\\|\\s*(sh|bash|/system/bin/sh).*")) {
            return Verdict.warn("pipe download straight into a shell");
        }
        if (c.length() > 4000) {
            return Verdict.warn("very long command");
        }
        return Verdict.ok();
    }

    /**
     * 命令里出现的绝对路径是否都落在工作区内。
     *
     * @return 越界的第一个路径；全部合规返回 null
     */
    public static String offendingPath(String command, File root) {
        if (command == null || root == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:^|[\\s'\"=<>|;&(])(/[A-Za-z0-9_./\\-]{2,})").matcher(command);
        String rootPath;
        try {
            rootPath = root.getCanonicalPath();
        } catch (Exception e) {
            return null;
        }
        while (m.find()) {
            String p = m.group(1);
            if (p.startsWith("/dev/")) continue;
            if (p.startsWith("/system/bin") || p.startsWith("/system/xbin")) continue;
            if (p.startsWith("/proc/")) continue;
            File f = new File(p);
            try {
                String cp = f.getCanonicalPath();
                if (!cp.equals(rootPath) && !cp.startsWith(rootPath + File.separator)) return p;
            } catch (Exception e) {
                return p;
            }
        }
        return null;
    }

    /** 给用户看的说明。 */
    public static String explain(Verdict v, boolean zh) {
        if (v == null) return "";
        if (!v.allowed) {
            return zh ? "已阻止：这条命令会越过安全边界（" + v.reason + "）"
                    : "blocked: this command crosses a safety boundary (" + v.reason + ")";
        }
        if (v.warn) {
            return zh ? "注意：危险命令（" + v.reason + "），确认后才会执行"
                    : "careful: risky command (" + v.reason + "), needs confirmation";
        }
        return "";
    }

    /** 供 Shell 使用的便捷封装：真的要执行时才调，避免把拒绝当成结果回灌给模型混淆视听。 */
    public static boolean resolvable(File root, String rel) {
        return FsKit.resolveInside(root, rel) != null;
    }
}
