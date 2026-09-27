package com.mscope.browser.agent;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从模型回复里解析「工具调用」。
 *
 * <p>本地 GGUF 模型不一定带 function-call 模板，所以这里不依赖模型原生能力，而是约定模型用
 * 一段结构化文本表达调用，App 端做宽松解析。首选格式：
 * <pre>
 * ```tool
 * {"name": "shell", "arguments": {"command": "ls -la"}}
 * ```
 * </pre>
 * 同时兼容 &lt;tool_call&gt;{...}&lt;/tool_call&gt; 与单独的 {@code TOOL_CALL: {...}} 行。
 *
 * <p>只有 name 命中已注册工具名才会被当作调用，避免把回复里的普通 JSON 示例误判成工具调用。
 */
public class ToolCall {

    private static final Pattern FENCE = Pattern.compile("```[ \\t]*([^\\n`]*)\\n([\\s\\S]*?)```");
    private static final Pattern TAG =
            Pattern.compile("<tool_call>([\\s\\S]*?)</tool_call>", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINE =
            Pattern.compile("(?m)^[ \\t]*TOOL_CALL[ \\t]*:[ \\t]*", Pattern.CASE_INSENSITIVE);

    public final String name;
    public final JSONObject args;

    private ToolCall(String name, JSONObject args) {
        this.name = name;
        this.args = args;
    }

    /** 取字符串参数；缺失时返回 def。 */
    public String arg(String key, String def) {
        if (args == null) return def;
        Object v = args.opt(key);
        if (v == null || v == JSONObject.NULL) return def;
        String s = String.valueOf(v);
        return s.isEmpty() ? def : s;
    }

    /** 依次尝试多个参数名，取第一个非空的。 */
    public String arg(String[] keys, String def) {
        for (String k : keys) {
            String v = arg(k, "");
            if (!v.isEmpty()) return v;
        }
        return def;
    }

    /* ------------------------------------------------------------------ 解析 */

    /** 解析出全部有效工具调用（按出现顺序）。 */
    public static List<ToolCall> parse(String text, Set<String> known) {
        List<ToolCall> out = new ArrayList<>();
        for (Hit h : scan(text, known)) {
            if (h.call != null) out.add(h.call);
        }
        return out;
    }

    /**
     * 去掉工具调用片段后的「可见正文」。带工具标记但解析失败的块也会被去掉，
     * 免得界面上出现一坨坏 JSON。
     */
    public static String strip(String text, Set<String> known) {
        List<Hit> hits = scan(text, known);
        if (hits.isEmpty()) return text == null ? "" : text;
        StringBuilder sb = new StringBuilder();
        int at = 0;
        for (Hit h : hits) {
            if (h.start < at) continue;             // 重叠，跳过
            sb.append(text, at, h.start);
            at = h.end;
        }
        sb.append(text, at, text.length());
        return sb.toString();
    }

    /** 带工具标记的片段，解析失败时 call 为 null（仍会被 strip 掉）。 */
    private static class Hit {
        final int start;
        final int end;
        final ToolCall call;

        Hit(int start, int end, ToolCall call) {
            this.start = start;
            this.end = end;
            this.call = call;
        }
    }

    private static List<Hit> scan(String text, Set<String> known) {
        List<Hit> hits = new ArrayList<>();
        if (text == null || text.isEmpty()) return hits;

        Matcher f = FENCE.matcher(text);
        while (f.find()) {
            String info = f.group(1) == null ? "" : f.group(1).trim().toLowerCase(Locale.ROOT);
            if (!info.contains("tool") && !info.contains("function") && !info.contains("json")) continue;
            hits.add(new Hit(f.start(), f.end(), fromJson(f.group(2), known)));
        }

        Matcher t = TAG.matcher(text);
        while (t.find()) {
            hits.add(new Hit(t.start(), t.end(), fromJson(t.group(1), known)));
        }

        Matcher l = LINE.matcher(text);
        while (l.find()) {
            String json = balanced(text, l.end());
            if (json == null) continue;
            hits.add(new Hit(l.start(), l.end() + json.length(), fromJson(json, known)));
        }

        hits.sort((a, b) -> Integer.compare(a.start, b.start));
        return hits;
    }

    private static ToolCall fromJson(String body, Set<String> known) {
        if (body == null) return null;
        String json = balanced(body, 0);
        if (json == null) return null;
        try {
            JSONObject o = new JSONObject(json);
            String name = normalize(firstString(o, "name", "tool", "function", "action", "tool_name"));
            if (name == null || name.isEmpty()) return null;
            if (known != null && !known.contains(name)) return null;

            JSONObject args = firstObject(o, "arguments", "args", "parameters", "params", "input");
            if (args == null) {
                // 参数平铺在顶层：把非元数据字段都当参数
                args = new JSONObject();
                for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                    String k = it.next();
                    if (isMeta(k)) continue;
                    args.put(k, o.get(k));
                }
            }
            return new ToolCall(name, args);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isMeta(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return k.equals("name") || k.equals("tool") || k.equals("function")
                || k.equals("action") || k.equals("tool_name") || k.equals("type");
    }

    private static String firstString(JSONObject o, String... keys) {
        for (String k : keys) {
            Object v = o.opt(k);
            if (v instanceof String && !((String) v).isEmpty()) return (String) v;
        }
        return null;
    }

    private static JSONObject firstObject(JSONObject o, String... keys) {
        for (String k : keys) {
            Object v = o.opt(k);
            if (v instanceof JSONObject) return (JSONObject) v;
            if (v instanceof String) {                   // 有些模型把参数写成字符串里的 JSON
                try {
                    JSONObject inner = new JSONObject((String) v);
                    return inner;
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    /** 取 name 字段对应的工具规范名，并兼容常见别名。 */
    private static String normalize(String raw) {
        if (raw == null) return null;
        String n = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        switch (n) {
            case "shell": case "terminal": case "term": case "run": case "exec":
            case "bash": case "sh": case "cmd": case "command":
                return "shell";
            case "web_search": case "search": case "websearch": case "google":
                return "web_search";
            case "fetch_url": case "fetch": case "open_url": case "read_url": case "http_get":
                return "fetch_url";
            case "read_file": case "read": case "cat": case "open_file":
                return "read_file";
            case "write_file": case "write": case "save_file": case "create_file":
                return "write_file";
            case "list_files": case "ls": case "list": case "list_dir": case "dir":
                return "list_files";
            case "delete_file": case "delete": case "rm": case "remove": case "del":
                return "delete_file";
            case "open_preview": case "preview": case "show_preview":
                return "open_preview";
            default:
                return n;
        }
    }

    /** 从 from 开始，取出第一个括号平衡的 JSON 对象（正确处理字符串与转义）。 */
    private static String balanced(String s, int from) {
        if (s == null) return null;
        int i = s.indexOf('{', from);
        if (i < 0) return null;
        int depth = 0;
        boolean inStr = false;
        char quote = 0;
        for (int j = i; j < s.length(); j++) {
            char c = s.charAt(j);
            if (inStr) {
                if (c == '\\') {
                    j++;
                } else if (c == quote) {
                    inStr = false;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                inStr = true;
                quote = c;
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return s.substring(i, j + 1);
            }
        }
        return null;
    }
}