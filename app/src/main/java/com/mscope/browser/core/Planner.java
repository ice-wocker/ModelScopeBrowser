package com.mscope.browser.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 会话待办清单：模型自己维护的「我现在做到哪了」。
 *
 * <p>多步任务最容易出的问题不是做错，而是**忘了做到哪**：小模型跑到第 5 步
 * 就开始重复第 2 步，或者把已完成的又做一遍。上下文一被裁剪就更严重。
 *
 * <p>所以把计划放到模型外部：它用 {@code todo_write} 写下来，系统每轮把清单
 * （很短，只有标题）放回提示词，它就能看见「1 done / 2 doing / 3 pending」。
 * 这比在提示词里写「请先制定计划」有效得多 —— 后者只是建议，前者是事实回灌。
 */
public final class Planner {

    private Planner() {
    }

    /** 一项待办。 */
    public static final class Item {
        public final boolean done;
        public final String text;

        public Item(boolean done, String text) {
            this.done = done;
            this.text = text == null ? "" : text.trim();
        }

        public String line() {
            return (done ? "[x] " : "[ ] ") + text;
        }
    }

    /** 解析模型写的清单：每行一项，前缀 [x]/[X] 表示完成，其余视为未完成。 */
    public static List<Item> parse(String body) {
        List<Item> out = new ArrayList<>();
        if (body == null) return out;
        for (String raw : body.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            // 去掉常见列表符号
            line = line.replaceFirst("^[-*+•]\\s*", "");
            boolean done = false;
            String t = line;
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("[x]")) {
                done = true;
                t = line.substring(3);
            } else if (lower.startsWith("[ ]")) {
                t = line.substring(3);
            } else if (lower.startsWith("x ")) {
                done = true;
                t = line.substring(2);
            }
            t = t.trim();
            if (t.isEmpty()) continue;
            out.add(new Item(done, t));
            if (out.size() >= 50) break;      // 清单本身也会占上下文，50 项足够
        }
        return out;
    }

    public static String render(List<Item> items) {
        StringBuilder sb = new StringBuilder();
        for (Item i : items) sb.append(i.line()).append('\n');
        return sb.toString().trim();
    }

    public static int doneCount(List<Item> items) {
        int n = 0;
        for (Item i : items) if (i.done) n++;
        return n;
    }

    public static boolean allDone(List<Item> items) {
        return !items.isEmpty() && doneCount(items) == items.size();
    }

    /**
     * 进提示词的极短形式：只列未完成的前 5 项 + 进度。
     *
     * <p>为什么不列已完成的：它们对「接下来做什么」没有帮助，
     * 却要占掉宝贵的位置；进度用数字表达即可。
     */
    public static String brief(List<Item> items, boolean zh, int maxChars) {
        if (items == null || items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(zh ? "待办进度 " : "todo progress ")
          .append(doneCount(items)).append('/').append(items.size()).append('\n');
        int shown = 0;
        for (Item i : items) {
            if (i.done) continue;
            sb.append("· ").append(i.text).append('\n');
            if (++shown >= 5) break;
        }
        String out = sb.toString().trim();
        if (out.length() > maxChars) out = TextKit.trimUtf8(out, maxChars);
        return out;
    }
}
