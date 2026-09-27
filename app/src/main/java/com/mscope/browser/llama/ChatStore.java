package com.mscope.browser.llama;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 对话历史持久化。会话都不大，直接整份写成单个 JSON 文件最简单可靠。
 * 每个模型可有多个会话，按更新时间倒序排列；同一模型只保留最近 {@link #MAX_PER_MODEL} 条。
 */
public class ChatStore {

    private static final String TAG = "ChatStore";
    private static final String FILE = "chats.json";
    private static final int MAX_PER_MODEL = 50;

    /** 一个会话：一段连续对话 + 每条回复的统计文案。 */
    public static class Session {
        public String id = "";
        public String modelPath = "";
        public String title = "";
        public long createdAt = 0;
        public long updatedAt = 0;
        public final List<ChatMessage> messages = new ArrayList<>();
        public final List<String> stats = new ArrayList<>();

        /** 保证 stats 与 messages 等长，避免界面按下标取值越界。 */
        public void align() {
            while (stats.size() < messages.size()) stats.add("");
            while (stats.size() > messages.size()) stats.remove(stats.size() - 1);
        }

        /** 标题：取第一条用户消息的前若干字，作为会话列表里的辨识名。 */
        public String titleText() {
            if (title != null && !title.isEmpty()) return title;
            for (ChatMessage m : messages) {
                if (m.isUser() && m.content != null && !m.content.trim().isEmpty()) {
                    String t = m.content.trim().replace('\n', ' ');
                    return t.length() > 18 ? t.substring(0, 18) + "…" : t;
                }
            }
            return "";
        }
    }

    private final File file;

    public ChatStore(Context ctx) {
        file = new File(ctx.getFilesDir(), FILE);
    }

    /** 某模型下的全部会话，按更新时间倒序。 */
    public synchronized List<Session> list(String modelPath) {
        List<Session> out = new ArrayList<>();
        for (Session s : readAll()) {
            if (modelPath == null || modelPath.equals(s.modelPath)) out.add(s);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        return out;
    }

    /** 某模型最近一次会话（没有则返回 null）。 */
    public synchronized Session latest(String modelPath) {
        List<Session> l = list(modelPath);
        return l.isEmpty() ? null : l.get(0);
    }

    /** 新建一个（尚未落盘的）空会话。 */
    public synchronized Session create(String modelPath) {
        Session s = new Session();
        s.id = UUID.randomUUID().toString();
        s.modelPath = modelPath == null ? "" : modelPath;
        s.createdAt = System.currentTimeMillis();
        s.updatedAt = s.createdAt;
        return s;
    }

    public synchronized void save(Session s) {
        if (s == null || s.id.isEmpty()) return;
        s.updatedAt = System.currentTimeMillis();
        s.align();
        List<Session> all = readAll();
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(s.id)) {
                all.set(i, s);
                replaced = true;
                break;
            }
        }
        if (!replaced) all.add(s);
        prune(all);
        writeAll(all);
    }

    public synchronized void delete(String id) {
        if (id == null) return;
        List<Session> all = readAll();
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).id.equals(id)) all.remove(i);
        }
        writeAll(all);
    }

    /** 同一模型只保留最近 N 条，避免历史无限增长。 */
    private static void prune(List<Session> all) {
        Map<String, List<Session>> byModel = new HashMap<>();
        for (Session s : all) {
            List<Session> l = byModel.get(s.modelPath);
            if (l == null) {
                l = new ArrayList<>();
                byModel.put(s.modelPath, l);
            }
            l.add(s);
        }
        List<Session> keep = new ArrayList<>();
        for (List<Session> group : byModel.values()) {
            Collections.sort(group, (a, b) -> Long.compare(b.updatedAt, a.updatedAt));
            keep.addAll(group.subList(0, Math.min(MAX_PER_MODEL, group.size())));
        }
        all.clear();
        all.addAll(keep);
    }

    /* ------------------------------------------------------------------ IO */

    private List<Session> readAll() {
        List<Session> out = new ArrayList<>();
        if (!file.exists()) return out;
        final long len = file.length();
        if (len <= 0 || len > 16L * 1024 * 1024) return out;   // 异常大小当作空，避免 OOM
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) len];
            int n = in.read(buf);
            if (n <= 0) return out;
            JSONObject root = new JSONObject(new String(buf, 0, n, StandardCharsets.UTF_8));
            JSONArray arr = root.optJSONArray("sessions");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Session s = new Session();
                s.id = o.optString("id", "");
                if (s.id.isEmpty()) continue;
                s.modelPath = o.optString("modelPath", "");
                s.title = o.optString("title", "");
                s.createdAt = o.optLong("createdAt", 0);
                s.updatedAt = o.optLong("updatedAt", 0);
                JSONArray ms = o.optJSONArray("messages");
                if (ms != null) {
                    for (int j = 0; j < ms.length(); j++) {
                        JSONObject mo = ms.optJSONObject(j);
                        if (mo == null) continue;
                        ChatMessage m = new ChatMessage(
                                mo.optString("role", ChatMessage.ASSISTANT),
                                mo.optString("content", ""));
                        m.title = mo.optString("title", "");
                        m.kind = mo.optString("kind", "");
                        s.messages.add(m);
                    }
                }
                JSONArray st = o.optJSONArray("stats");
                if (st != null) {
                    for (int j = 0; j < st.length(); j++) s.stats.add(st.optString(j, ""));
                }
                s.align();
                out.add(s);
            }
        } catch (Exception e) {
            Log.w(TAG, "读取会话失败", e);
        }
        return out;
    }

    private void writeAll(List<Session> all) {
        JSONArray arr = new JSONArray();
        for (Session s : all) arr.put(toJson(s));
        JSONObject root = new JSONObject();
        try {
            root.put("sessions", arr);
        } catch (Exception ignored) {
        }
        final byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
        final File tmp = new File(file.getParentFile(), FILE + ".tmp");
        // 先写临时文件再原子改名，避免中途被杀留下半截文件导致历史全丢
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
            out.flush();
        } catch (Exception e) {
            Log.w(TAG, "写入会话（临时文件）失败", e);
            return;
        }
        if (tmp.renameTo(file)) return;
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        if (tmp.renameTo(file)) return;
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        } catch (Exception e) {
            Log.w(TAG, "写入会话失败", e);
        }
    }

    private static JSONObject toJson(Session s) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", s.id);
            o.put("modelPath", s.modelPath);
            o.put("title", s.title);
            o.put("createdAt", s.createdAt);
            o.put("updatedAt", s.updatedAt);
            JSONArray ms = new JSONArray();
            for (ChatMessage m : s.messages) {
                JSONObject mo = new JSONObject();
                mo.put("role", m.role);
                mo.put("content", m.content == null ? "" : m.content);
                if (m.title != null && !m.title.isEmpty()) mo.put("title", m.title);
                if (m.kind != null && !m.kind.isEmpty()) mo.put("kind", m.kind);
                ms.put(mo);
            }
            o.put("messages", ms);
            JSONArray st = new JSONArray();
            for (String t : s.stats) st.put(t == null ? "" : t);
            o.put("stats", st);
        } catch (Exception ignored) {
        }
        return o;
    }
}