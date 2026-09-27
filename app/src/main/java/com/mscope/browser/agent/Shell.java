package com.mscope.browser.agent;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 本机终端：真正执行 /system/bin/sh 命令，用手机本机环境运行。
 *
 * <p>工作目录固定在应用工作区，所以命令只能读写应用私有目录（Android 沙箱限制，无需 root、
 * 也不需要任何权限）。stdout 与 stderr 合并捕获；超时或输出过大都会被截断，避免刷屏。
 */
public class Shell {

    /** 单条命令最长执行时间（秒）。 */
    private static final int TIMEOUT_SEC = 60;
    /** 单条命令最多回传的字符数，防止把上下文撑爆。 */
    private static final int MAX_CHARS = 32 * 1024;

    public static class Out {
        public final String text;
        public final int code;

        public Out(String text, int code) {
            this.text = text;
            this.code = code;
        }
    }

    private final File cwd;

    public Shell(Workspace ws) {
        this.cwd = ws.root();
    }

    public File cwd() {
        return cwd;
    }

    /** 执行一条 shell 命令。 */
    public Out run(String command) {
        final String cmd = command == null ? "" : command.trim();
        if (cmd.isEmpty()) return new Out("", 0);

        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", cmd);
            pb.directory(cwd);
            pb.redirectErrorStream(true);       // stderr 并入 stdout，AI 能看到报错
            Map<String, String> env = pb.environment();
            env.put("PATH", "/system/bin:/system/xbin:/product/bin");
            env.put("HOME", cwd.getAbsolutePath());
            env.put("PWD", cwd.getAbsolutePath());
            env.put("TMPDIR", cwd.getAbsolutePath());

            p = pb.start();
            final Process proc = p;

            // 看门狗：超时强杀。Process.waitFor(timeout) 需要 API 26，这里用 exitValue 轮询兼容 minSdk 24。
            Thread watchdog = new Thread(() -> {
                long deadline = System.currentTimeMillis() + TIMEOUT_SEC * 1000L;
                while (System.currentTimeMillis() < deadline) {
                    if (!isAlive(proc)) return;
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                proc.destroy();
            });
            watchdog.setDaemon(true);
            watchdog.start();

            StringBuilder sb = new StringBuilder();
            boolean truncated = false;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8), 8192)) {
                char[] buf = new char[4096];
                int n;
                while ((n = r.read(buf)) > 0) {
                    int room = MAX_CHARS - sb.length();
                    if (room > 0) sb.append(buf, 0, Math.min(n, room));
                    else truncated = true;
                }
            }

            int code = waitFor(proc);
            watchdog.interrupt();

            String text = sb.toString();
            if (truncated) text += "\n…（输出过长，已截断）";
            return new Out(text, code);
        } catch (Exception e) {
            if (p != null) p.destroy();
            return new Out(String.valueOf(e.getMessage()), 1);
        }
    }

    /** 进程是否还在运行。 */
    private static boolean isAlive(Process p) {
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    /** 等进程结束并取退出码；再给一段宽限期，仍不退出就强杀。 */
    private static int waitFor(Process p) {
        long deadline = System.currentTimeMillis() + (TIMEOUT_SEC + 5) * 1000L;
        while (true) {
            try {
                return p.exitValue();
            } catch (IllegalThreadStateException e) {
                if (System.currentTimeMillis() > deadline) {
                    p.destroy();
                    return -1;
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    p.destroy();
                    return -1;
                }
            }
        }
    }
}