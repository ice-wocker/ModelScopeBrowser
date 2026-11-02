package com.mscope.browser.agent;

import com.mscope.browser.core.JsonAccess;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.mscope.browser.core.FsKit;

/**
 * 待办板：让模型在多步任务里记得住「做到哪一步了」。
 *
 * <p>小模型的上下文很短，长任务跑到第四步就忘了第一步的结论。待办板把计划外化成
 * 一个 JSON 文件（工作区 {@code /todo.json}），每轮都作为工具结果回灌，
 * 既省 token 又不会因为裁剪历史而丢失。
 *
 * <p>状态只允许 pending / doing / done / blocked 四档：档位一多，模型就会自己发明新状态。
 */
public class TodoBoard {

    public static final String FILE = "todo.json";
    private static final int MAX_ITEMS = 40;
    private static final int MAX_TITLE = 160;

    private final Workspace ws;

    public TodoBoard(Workspace ws) {
        this.ws = ws;
    }

    private File file() {
        return new File(ws.root(), FILE);
    }

    /** 读出全部条目（原始 JSON 数组，不动）。 */
    public synchronized List<JSONObject> items() {
        List<JSONObject> out = new ArrayList<>();
        String text = FsKit.readText(file(), 64 * 1024);
        Object root = JsonAccess.parse(text);
        if (!(root instanceof JSONArray)) {
            // 也接受 {"items":[...]} 形式
            Object alt = JsonAccess.get(root, "items");
            if (alt instanceof JSONArray) root = alt;
        }
        if (!(root instanceof JSONArray)) return out;
        JSONArray arr = (JSONArray) root;
        for (int i = 0; i < arr.length(); i++) {
            Object e = arr.opt(i);
            if (e instanceof JSONObject) out.add((JSONObject) e);
        }
        return out;
    }

    /** 加一条。title 为空返回 false。 */
    public synchronized boolean add(String title) {
        String t = clean(title);
        if (t.isEmpty()) return false;
        List<JSONObject> list = items();
        if (list.size() >= MAX_ITEMS) return false;
        JSONObject o = new JSONObject();
        try {
            o.put("title", t);
            o.put("status", "pending");
        } catch (Exception ignored) {
            return false;
        }
        list.add(o);
        return save(list);
    }

    /**
     * 更新第 index 条（从 1 开始，跟人说话的习惯一致）的状态。
     *
     * @return 更新后的描述，失败返回错误文本
     */
    public synchronized String setStatus(int index, String status, boolean zh) {
        String st = normalizeStatus(status);
        if (st == null) {
            return zh ? "错误：status 只能是 pending / doing / done / blocked"
                    : "error: status must be one of pending / doing / done / blocked";
        }
        List<JSONObject> list = items();
        if (index < 1 || index > list.size()) {
            return (zh ? "错误：没有第 " : "error: no item #") + index + (zh ? " 条待办" : "");
        }
        JSONObject o = list.get(index - 1);
        try {
            o.put("status", st);
        } catch (Exception e) {
            return zh ? "错误：写入失败" : "error: write failed";
        }
        return save(list) ? render(zh) : (zh ? "错误：保存失败" : "error: save failed");
    }

    /** 清空全部。 */
    public synchronized boolean clear() {
        return FsKit.writeAtomic(file(), "[]");
    }

    /** 给模型看的一览。空板返回一句提示，避免模型对着空白发呆。 */
    public synchronized String render(boolean zh) {
        List<JSONObject> list = items();
        if (list.isEmpty()) {
            return zh ? "待办板为空。用 todo_add 添加条目，用 todo_done 标记完成。"
                    : "The todo board is empty. Use todo_add to add items and todo_done to close them.";
        }
        StringBuilder sb = new StringBuilder();
        int done = 0;
        for (int i = 0; i < list.size(); i++) {
            JSONObject o = list.get(i);
            String st = JsonAccess.str(o, "status", "pending");
            if ("done".equals(st)) done++;
            sb.append(i + 1).append(". [").append(mark(st)).append("] ")
              .append(JsonAccess.str(o, "title", "")).append('\n');
        }
        sb.append(zh ? "进度 " + done + "/" + list.size() : "progress " + done + "/" + list.size());
        return sb.toString();
    }

    private static String mark(String status) {
        if ("done".equals(status)) return "x";
        if ("doing".equals(status)) return ">";
        if ("blocked".equals(status)) return "!";
        return " ";
    }

    private static String normalizeStatus(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase(java.util.Locale.ROOT);
        if (t.startsWith("done") || t.startsWith("complete") || t.contains("完成")) return "done";
        if (t.startsWith("doing") || t.startsWith("progress") || t.contains("进行")) return "doing";
        if (t.startsWith("block") || t.contains("阻塞")) return "blocked";
        if (t.startsWith("pend") || t.startsWith("todo") || t.contains("待办")) return "pending";
        return null;
    }

    private static String clean(String s) {
        if (s == null) return "";
        String t = s.trim().replaceAll("\\s+", " ");
        if (t.length() > MAX_TITLE) t = t.substring(0, MAX_TITLE) + "…";
        return t;
    }

    private boolean save(List<JSONObject> list) {
        JSONArray arr = new JSONArray();
        for (JSONObject o : list) arr.put(o);
        return FsKit.writeAtomic(file(), arr.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 界面展示用的一行摘要。 */
    public synchronized String badge(boolean zh) {
        List<JSONObject> list = items();
        if (list.isEmpty()) return "";
        int done = 0;
        for (JSONObject o : list) if ("done".equals(JsonAccess.str(o, "status", ""))) done++;
        return (zh ? "待办 " : "todo ") + done + "/" + list.size();
    }

    /** 语言无关的摘要，便于测试断言不依赖文案。 */
    @Override
    public String toString() {
        return "TodoBoard(" + items().size() + " items)";
    }
}
