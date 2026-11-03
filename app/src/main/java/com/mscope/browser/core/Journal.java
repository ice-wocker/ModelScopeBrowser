package com.mscope.browser.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 单轮/单会话的工作日志（append-only）。
 *
 * <p>上下文窗口就那么大，工具结果一旦被裁掉，模型就「忘了自己刚才改过什么」——
 * 表现是反复重读同一个文件、或者把已经改好的东西又改回去。
 *
 * <p>日志把每一步压成一行（序号 · 工具 · 参数摘要 · 结果首行），
 * 由 {@code journal_read} 工具按需取回。因为它是外部的、按需读的，
 * 所以既不占常驻上下文，也不会被裁剪弄丢。
 */
public final class Journal {

    /** 一条记录。 */
    public static final class Entry {
        public final int seq;
        public final long at;
        public final String tool;
        public final String args;
        public final String result;
        public final boolean ok;

        public Entry(int seq, long at, String tool, String args, String result, boolean ok) {
            this.seq = seq;
            this.at = at;
            this.tool = tool == null ? "" : tool;
            this.args = args == null ? "" : args;
            this.result = result == null ? "" : result;
            this.ok = ok;
        }

        public String line(boolean zh) {
            return "#" + seq + " [" + tool + "] " + args
                    + (ok ? "" : (zh ? "  ✗ " : "  FAILED ")) + " → " + result;
        }
    }

    private static final int MAX = 400;
    private static final int ARG_MAX = 120;
    private static final int RESULT_MAX = 160;

    private final List<Entry> entries = new ArrayList<>();
    private int seq = 0;

    /**
     * 记一条。
     *
     * <p>参数与结果都先过一遍 {@link SecretVault}：日志会被回灌进上下文、
     * 也会被用户导出，密钥留在这里等于二次泄露 —— 而且它看起来「只是一行日志」，
     * 没人会想到去删。
     */
    public synchronized Entry add(String tool, String args, String result, boolean ok) {
        seq++;
        Entry e = new Entry(seq, System.currentTimeMillis(), tool,
                oneLine(SecretVault.redact(args), ARG_MAX),
                oneLine(SecretVault.redact(result), RESULT_MAX), ok);
        entries.add(e);
        if (entries.size() > MAX) entries.remove(0);
        return e;
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized List<Entry> all() {
        return new ArrayList<>(entries);
    }

    /**
     * 取最近若干条；返回「给人看」的文本，可直接作为工具结果回灌给模型。
     *
     * @param limit 0 或负数表示全部
     */
    public synchronized String render(int limit, boolean zh) {
        if (entries.isEmpty()) return zh ? "（日志为空：本会话还没有执行过工具）" : "(empty: no tool has run in this chat yet)";
        int from = limit > 0 ? Math.max(0, entries.size() - limit) : 0;
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < entries.size(); i++) {
            sb.append(entries.get(i).line(zh)).append('\n');
        }
        return sb.toString().trim();
    }

    public synchronized void clear() {
        entries.clear();
        seq = 0;
    }

    /**
     * 结果摘要：只保留首行 + 长度，避免日志本身又变成一大坨上下文。
     */
    static String oneLine(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        int nl = t.indexOf('\n');
        boolean multi = nl >= 0;
        String head = multi ? t.substring(0, nl) : t;
        if (head.length() > max) head = head.substring(0, max) + "…";
        if (multi) head = head + (head.endsWith("…") ? "" : " …");
        return head.trim().isEmpty() ? "(empty)" : head.trim();
    }

    /* -------------------------------------------------- 压缩摘要（进提示词用） */

    /**
     * 生成「工作摘要」：仅列出改动过的路径与用过的工具计数。
     *
     * <p>这块会进系统提示词，所以要极短：它是让模型知道自己*已经*动过哪些文件，
     * 从而不再重复做同一件事。超过预算就只留最近几条路径。
     */
    public synchronized String summary(int maxChars, boolean zh) {
        if (entries.isEmpty()) return "";
        List<String> paths = new ArrayList<>();
        int calls = entries.size();
        for (int i = entries.size() - 1; i >= 0 && paths.size() < 12; i--) {
            Entry e = entries.get(i);
            if (!"write_file".equals(e.tool) && !"edit_file".equals(e.tool)
                    && !"apply_patch".equals(e.tool) && !"delete_file".equals(e.tool)) continue;
            String p = e.args;
            if (!paths.contains(p)) paths.add(p);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(zh ? "本会话已执行 " : "tools already run in this chat: ")
          .append(calls).append(zh ? " 次工具调用。" : "\n");
        if (!paths.isEmpty()) {
            sb.append(zh ? "已改动的路径：" : "paths already modified: ");
            sb.append(String.join(zh ? "、" : ", ", paths));
        }
        String out = sb.toString().trim();
        return out.length() > maxChars ? out.substring(0, maxChars) + "…" : out;
    }
}
