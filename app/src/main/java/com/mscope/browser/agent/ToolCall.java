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
 * <p>本地 GGUF 模型不一定带 function-call 模板，所以这里不依赖模型原生能力，而是把各家模型
 * 习惯输出的几种写法都认下来：
 * <ul>
 *   <li>MiniCPM5 原生：{@code <function name="shell"><param name="command">ls</param></function>}</li>
 *   <li>Qwen3-Coder 风格：{@code <function=shell><parameter=command>ls</parameter></function>}</li>
 *   <li>JSON 代码块：{@code ```tool {"name": "shell", "arguments": {"command": "ls"}} ```}</li>
 *   <li>{@code <tool_call>{...}</tool_call>} 与单独的 {@code TOOL_CALL: {...}} 行</li>
 * </ul>
 *
 * <p>只有 name 命中已注册工具名才会被当作调用，避免把回复里的普通 JSON/XML 示例误判成工具调用。
 */
public class ToolCall {

    private static final Pattern FENCE = Pattern.compile("```[ \\t]*([^\\n`]*)\\n([\\s\\S]*?)```");
    private static final Pattern TAG =
            Pattern.compile("<tool_call>([\\s\\S]*?)</tool_call>", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINE =
            Pattern.compile("(?m)^[ \\t]*TOOL_CALL[ \\t]*:[ \\t]*", Pattern.CASE_INSENSITIVE);
    /** MiniCPM5：&lt;function name="x"&gt;&lt;param name="k"&gt;v&lt;/param&gt;&lt;/function&gt; */
    private static final Pattern FN_NAME = Pattern.compile(
            "<function\\s+name\\s*=\\s*[\"']([^\"']+)[\"']\\s*>(.*?)"
                    + "(?:</function\\s*>|(?=<function\\s|\\z))",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern PARAM_NAME = Pattern.compile(
            "<param\\s+name\\s*=\\s*[\"']([^\"']+)[\"']\\s*>(.*?)"
                    + "(?:</param\\s*>|(?=<param\\s|</function|\\z))",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    /** Qwen3-Coder 风格：&lt;function=x&gt;&lt;parameter=k&gt;v&lt;/parameter&gt;&lt;/function&gt; */
    private static final Pattern FN_EQ = Pattern.compile(
            "<function\\s*=\\s*([A-Za-z0-9_.\\-]+)\\s*>(.*?)"
                    + "(?:</function\\s*>|(?=<function\\s*=|\\z))",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern PARAM_EQ = Pattern.compile(
            "<parameter\\s*=\\s*([A-Za-z0-9_.\\-]+)\\s*>(.*?)"
                    + "(?:</parameter\\s*>|(?=<parameter\\s*=|</function|\\z))",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /** 推理模型的思考块；标签拆开拼接，避免字面量被工具链过滤掉。 */
    private static final String THINK_L = "<thi" + "nk>";
    private static final String THINK_R = "</thi" + "nk>";
    private static final Pattern THINK_CLOSED = Pattern.compile("(?s)" + THINK_L + ".*?" + THINK_R);
    private static final Pattern THINK_OPEN = Pattern.compile("(?s)" + THINK_L + ".*\\z");

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
     * 免得界面上出现一坨坏 JSON / 半截标签。
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

    /** 去掉推理模型的思考块（MiniCPM5 等），免得把内心独白当成回答。 */
    public static String stripThinking(String text) {
        if (text == null) return "";
        String out = THINK_CLOSED.matcher(text).replaceAll("");
        return THINK_OPEN.matcher(out).replaceAll("");   // 思考未闭合（被截断）
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
        List<int[]> claimed = new ArrayList<>();

        // 1) <tool_call>…</tool_call>：先按 JSON 解析（Qwen），失败再按 XML 解析（Qwen3-Coder）
        Matcher t = TAG.matcher(text);
        while (t.find()) {
            ToolCall c = fromJson(t.group(1), known);
            if (c == null) c = fromXml(t.group(1), known);
            hits.add(new Hit(t.start(), t.end(), c));
            claimed.add(new int[]{t.start(), t.end()});
        }

        // 2) 裸露的 XML 调用（MiniCPM5 原生格式没有 <tool_call> 外壳）
        Matcher fn = FN_NAME.matcher(text);
        while (fn.find()) {
            if (inside(claimed, fn.start())) continue;
            hits.add(new Hit(fn.start(), fn.end(),
                    buildCall(fn.group(1), fn.group(2), PARAM_NAME, known)));
        }
        Matcher fe = FN_EQ.matcher(text);
        while (fe.find()) {
            if (inside(claimed, fe.start())) continue;
            hits.add(new Hit(fe.start(), fe.end(),
                    buildCall(fe.group(1), fe.group(2), PARAM_EQ, known)));
        }

        // 3) ```tool / ```json 代码块
        Matcher f = FENCE.matcher(text);
        while (f.find()) {
            if (inside(claimed, f.start())) continue;
            String info = f.group(1) == null ? "" : f.group(1).trim().toLowerCase(Locale.ROOT);
            if (!info.contains("tool") && !info.contains("function") && !info.contains("json")) continue;
            hits.add(new Hit(f.start(), f.end(), fromJson(f.group(2), known)));
        }

        // 4) 单独的 TOOL_CALL: {...} 行
        Matcher l = LINE.matcher(text);
        while (l.find()) {
            if (inside(claimed, l.start())) continue;
            String json = balanced(text, l.end());
            if (json == null) continue;
            hits.add(new Hit(l.start(), l.end() + json.length(), fromJson(json, known)));
        }

        hits.sort((a, b) -> Integer.compare(a.start, b.start));
        return dedupe(hits);
    }

    /** position 是否落在已认领的区间里（避免同一段被解析两次）。 */
    private static boolean inside(List<int[]> ranges, int position) {
        for (int[] r : ranges) {
            if (position >= r[0] && position < r[1]) return true;
        }
        return false;
    }

    /** 去掉相互重叠的命中，保留先出现的那个。 */
    private static List<Hit> dedupe(List<Hit> hits) {
        List<Hit> out = new ArrayList<>();
        int at = -1;
        for (Hit h : hits) {
            if (h.start < at) continue;
            out.add(h);
            at = h.end;
        }
        return out;
    }

    /** 在 <tool_call> 内部尝试 XML 形式。 */
    private static ToolCall fromXml(String body, Set<String> known) {
        if (body == null) return null;
        Matcher fn = FN_NAME.matcher(body);
        if (fn.find()) return buildCall(fn.group(1), fn.group(2), PARAM_NAME, known);
        Matcher fe = FN_EQ.matcher(body);
        if (fe.find()) return buildCall(fe.group(1), fe.group(2), PARAM_EQ, known);
        return null;
    }

    /** 由 <function …> 的名字与内部片段拼出一次调用。 */
    private static ToolCall buildCall(String rawName, String inner, Pattern paramPattern,
                                      Set<String> known) {
        String name = normalize(rawName);
        if (name == null || name.isEmpty()) return null;
        if (known != null && !known.contains(name)) return null;

        JSONObject args = new JSONObject();
        try {
            Matcher p = paramPattern.matcher(inner == null ? "" : inner);
            while (p.find()) {
                String key = p.group(1) == null ? "" : p.group(1).trim();
                if (key.isEmpty()) continue;
                args.put(key, unwrapCdata(p.group(2)));
            }
        } catch (Exception ignored) {
        }
        return new ToolCall(name, args);
    }

    /** 参数值可能被 CDATA 包住（值里要是有 </param> 就得这么写）。 */
    private static String unwrapCdata(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.startsWith("<![CDATA[")) {
            s = s.substring("<![CDATA[".length());
            int end = s.indexOf("]]>");
            if (end >= 0) s = s.substring(0, end);
        }
        return s.trim();
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