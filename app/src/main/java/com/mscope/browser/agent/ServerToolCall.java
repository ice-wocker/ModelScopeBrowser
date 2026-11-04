package com.mscope.browser.agent;

import com.mscope.browser.core.JsonAccess;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 解析「模型原生工具调用」——也就是模型自带 function-call 模板时吐出的那种格式。
 *
 * <p>和 {@link ToolCall} 的分工：那个认的是**提示词协议**（模型照着我们写的
 * {@code <function name="x">} 抄一遍），本类认的是**模型自己的协议**，它更可靠但各家不一：
 *
 * <ul>
 *   <li><b>DeepSeek-V3.1 / GLM-4.5（DSML）</b>：
 *       {@code <|DSML|tool_calls><|DSML|invoke name="shell"><|DSML|parameter name="command" string="true">ls}}
 *       —— 结尾故意是单个 {@code >} 而不是闭合标签，这一点按官方模板原样实现；</li>
 *   <li><b>Hermes / Qwen2.5 / Llama-3.x</b>：{@code <tool_call>{"name":…,"arguments":…}</tool_call>}，
 *       后续轮次用 {@code <tool_response>} 回灌（{@link #responseBlock}）；</li>
 *   <li><b>Mistral</b>：{@code [TOOL_CALLS][{"name":…,"arguments":…}]}；</li>
 *   <li><b>通用 JSON</b>：{@code {"name":…,"arguments":…}}（少数模型直接给裸 JSON）。</li>
 * </ul>
 *
 * <p>为什么值得单独写一遍：这些模型在 GGUF 里是**真的会调用工具**的，而且比提示词协议稳。
 * 之前只认提示词协议，等于把模型最强的能力关掉、让它去模仿一个人造格式 ——
 * 小模型模仿格式的失败率远高于直接用训练时的格式。
 *
 * <p>纯字符串处理，无 Android 依赖，可单测。
 */
public final class ServerToolCall {

    /** 原生协议族。决定回灌时用哪种结果包装。 */
    public enum Family {
        /** 没识别到原生调用 */
        NONE,
        /** DeepSeek-V3.1 / GLM-4.5 的 DSML */
        DSML,
        /** Hermes / Qwen / Llama 的 JSON 工具调用 */
        JSON,
        /** Mistral 的 [TOOL_CALLS] */
        MISTRAL,
        /** 裸 JSON */
        BARE
    }

    /** 一次解析结果。 */
    public static final class Parsed {
        public final Family family;
        public final List<ToolCall> calls;
        /** 去掉调用片段后的正文。 */
        public final String visible;
        /** 结尾被判为「还没写完」（流式输出被截断在调用中间）。 */
        public final boolean incomplete;

        public Parsed(Family family, List<ToolCall> calls, String visible, boolean incomplete) {
            this.family = family;
            this.calls = calls == null ? new ArrayList<>() : calls;
            this.visible = visible == null ? "" : visible;
            this.incomplete = incomplete;
        }

        /** 「没有原生调用」的结果，方便调用点少写一堆 null。 */
        public static Parsed none(String text) {
            return new Parsed(Family.NONE, null, text, false);
        }

        public boolean hasCalls() {
            return !calls.isEmpty();
        }
    }

    private static final String DSML = "<|DSML|";
    private static final String TOOL_CALLS = "[TOOL_CALLS]";

    private ServerToolCall() {
    }

    /**
     * 解析。
     *
     * @param text  模型输出
     * @param known 注册表里的工具名；为 null 表示不校验
     */
    public static Parsed parse(String text, java.util.Set<String> known) {
        String s = text == null ? "" : text;
        if (s.isEmpty()) return new Parsed(Family.NONE, null, "", false);

        // 顺序有讲究：DSML 与 [TOOL_CALLS] 都是强特征，先认；JSON 兜底。
        Parsed p = parseDsml(s, known);
        if (p != null) return p;
        p = parseMistral(s, known);
        if (p != null) return p;
        p = parseHermes(s, known);
        if (p != null) return p;
        p = parseBare(s, known);
        if (p != null) return p;
        return new Parsed(Family.NONE, null, s, false);
    }

    /* ------------------------------------------------------------------ DSML */

    /**
     * DSML（DeepSeek-V3.1 / GLM-4.5）。官方模板的样子：
     * <pre>
     * &lt;|DSML|tool_calls&gt;
     * &lt;|DSML|invoke name="shell"&gt;
     * &lt;|DSML|parameter name="command" string="true"&gt;ls -la
     * &lt;|DSML|/parameter&gt;
     * &lt;|DSML|/invoke&gt;
     * &lt;|DSML|/tool_calls&gt;
     * </pre>
     * 注意每行结尾只有一个 {@code >}：这不是漏写，模板里就这样。
     */
    private static Parsed parseDsml(String s, java.util.Set<String> known) {
        int at = s.indexOf(DSML);
        if (at < 0) return null;

        List<ToolCall> calls = new ArrayList<>();
        int cursor = s.indexOf(DSML + "tool_calls", at);
        int scanFrom = cursor >= 0 ? cursor + (DSML + "tool_calls").length() : at;

        while (true) {
            int invokeAt = s.indexOf(DSML + "invoke", scanFrom);
            if (invokeAt < 0) break;
            int nameEnd = tagEnd(s, invokeAt);
            if (nameEnd < 0) break;                       // 名字都没写完：本轮截断
            String header = s.substring(invokeAt, nameEnd);      // <|DSML|invoke name="shell" 或 > 之前
            String rawName = attr(header, "name");
            String name = normalize(rawName);

            int bodyStart = nameEnd + 1;
            int closeInvoke = s.indexOf(DSML + "/invoke", bodyStart);
            int nextInvoke = s.indexOf(DSML + "invoke", bodyStart);
            int bodyEnd = closeInvoke >= 0 ? closeInvoke : (nextInvoke >= 0 ? nextInvoke : s.length());
            if (closeInvoke < 0 && nextInvoke < 0) {
                // 既没有闭合也没有下一个：可能是流被截断
                return new Parsed(Family.DSML, calls, stripRange(s, invokeAt, s.length()), true);
            }
            String body = s.substring(bodyStart, bodyEnd);

            JSONObject args = new JSONObject();
            int p = 0;
            while (true) {
                int paramAt = body.indexOf(DSML + "parameter", p);
                if (paramAt < 0) break;
                int paramHeaderEnd = tagEnd(body, paramAt);
                if (paramHeaderEnd < 0) break;
                String ph = body.substring(paramAt, paramHeaderEnd);
                String pname = attr(ph, "name");
                String ptype = attr(ph, "string");
                int valStart = paramHeaderEnd + 1;
                int valEnd = body.indexOf(DSML + "/parameter", valStart);
                if (valEnd < 0) valEnd = body.length();
                String value = body.substring(valStart, valEnd);
                // 值后面常跟一个换行（模板按行排布），但那是排版不是内容
                if (value.endsWith("\n")) value = value.substring(0, value.length() - 1);
                if (!pname.isEmpty()) {
                    try {
                        args.put(pname, coerce(value.trim(), ptype));
                    } catch (Exception ignored) {
                    }
                }
                p = valEnd + (DSML + "/parameter").length();
            }

            if (name != null && !name.isEmpty() && (known == null || known.contains(name))) {
                calls.add(new ToolCall(name, args));
            }
            scanFrom = bodyEnd + (DSML + "/invoke").length();
        }

        if (calls.isEmpty()) return null;
        int endTag = s.indexOf(DSML + "/tool_calls", at);
        int cut = endTag >= 0 ? endTag + (DSML + "/tool_calls").length() : s.length();
        return new Parsed(Family.DSML, calls, stripRange(s, at, cut), false);
    }

    /** 找到 `<|DSML|invoke name="x"` 之后那个 `>` 的下标；找不到返回 -1。 */
    private static int tagEnd(String s, int from) {
        int i = s.indexOf('>', from);
        // 属性值里出现 '>' 的概率极低，但也要防止跨行误伤：限制在 200 字符内
        if (i < 0 || i - from > 200) return -1;
        return i;
    }

    /** 从标签头里取 `key="value"`；也接受 `key='value'` 与 `key` 单独出现（视为 true）。 */
    private static String attr(String header, String key) {
        String k = key.toLowerCase(Locale.ROOT);
        String lower = header.toLowerCase(Locale.ROOT);
        int i = lower.indexOf(k);
        while (i >= 0) {
            int after = i + k.length();
            while (after < lower.length() && lower.charAt(after) == ' ') after++;
            if (after < lower.length() && lower.charAt(after) == '=') {
                after++;
                while (after < lower.length() && lower.charAt(after) == ' ') after++;
                if (after < header.length()) {
                    char q = header.charAt(after);
                    if (q == '"' || q == '\'') {
                        int end = header.indexOf(q, after + 1);
                        if (end > after) return header.substring(after + 1, end);
                    } else {
                        int end = after;
                        while (end < header.length() && !Character.isWhitespace(header.charAt(end))
                                && header.charAt(end) != '>') end++;
                        return header.substring(after, end);
                    }
                }
            }
            i = lower.indexOf(k, after);
        }
        return "";
    }

    /** DSML 的 string="true|false" 决定值要不要当 JSON 还原。 */
    private static Object coerce(String value, String type) {
        if (type != null && !type.isEmpty() && "false".equalsIgnoreCase(type)) {
            String t = value.trim();
            if ("true".equalsIgnoreCase(t)) return Boolean.TRUE;
            if ("false".equalsIgnoreCase(t)) return Boolean.FALSE;
            try {
                if (t.contains(".")) return Double.parseDouble(t);
                return Long.parseLong(t);
            } catch (NumberFormatException ignored) {
                // 落回字符串
            }
        }
        return value;
    }

    /* --------------------------------------------------------------- Mistral */

    private static Parsed parseMistral(String s, java.util.Set<String> known) {
        int at = s.indexOf(TOOL_CALLS);
        if (at < 0) return null;
        // 起点必须越过 [TOOL_CALLS] 本身：它的 ']' 会把括号计数直接配平，
        // 于是 balancedArray 会返回 "[TOOL_CALLS]" 这个「数组」，真正的调用参数一个都读不到。
        int arrAt = s.indexOf('[', at + TOOL_CALLS.length());
        if (arrAt < 0) {
            return new Parsed(Family.MISTRAL, null, stripRange(s, at, s.length()), true);
        }
        String json = balancedArray(s, arrAt);
        if (json == null) {
            return new Parsed(Family.MISTRAL, null, stripRange(s, at, s.length()), true);
        }
        List<ToolCall> calls = fromJsonArray(json, known);
        int cut = arrAt + json.length();
        // 即使一个都没解析出来也要返回 MISTRAL：上层据此知道「模型确实想调用」，
        // 只是工具名不在白名单里 —— 这个信息要能显示出来，而不是当普通文本吞掉。
        return new Parsed(Family.MISTRAL, calls, stripRange(s, at, cut), calls.isEmpty());
    }

    /* ---------------------------------------------------------------- Hermes */

    private static Parsed parseHermes(String s, java.util.Set<String> known) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<tool_call>([\\s\\S]*?)</tool_call>", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(s);
        List<int[]> ranges = new ArrayList<>();
        List<ToolCall> calls = new ArrayList<>();
        boolean incomplete = false;
        while (m.find()) {
            ToolCall c = fromJsonObject(m.group(1), known);
            if (c != null) calls.add(c);
            ranges.add(new int[]{m.start(), m.end()});
        }
        int open = s.indexOf("<tool_call>");
        if (calls.isEmpty() && open >= 0 && s.indexOf("</tool_call>", open) < 0) {
            ranges.add(new int[]{open, s.length()});
            incomplete = true;
        }
        if (calls.isEmpty() && ranges.isEmpty()) return null;
        String visible = stripRanges(s, ranges);
        return new Parsed(Family.JSON, calls, visible, incomplete);
    }

    /* ------------------------------------------------------------------ 裸 JSON */

    /**
     * 极少数模型（以及我们自己提示词里的 ```json 块）直接给 {"name":…,"arguments":…}。
     *
     * <p>只认「整段回复就是一个调用对象」这一种形状，而且**不能**在别的协议标记之后触发：
     * 早先这里用「整段文本从 { 开始」判断，`[TOOL_CALLS][{...}]` 被剔除后剩下的 `[` 让判断落空，
     * 于是 Mistral 的调用被静默漏掉。现在显式排除「文本里还带着别的协议标记」的情况。
     */
    private static Parsed parseBare(String s, java.util.Set<String> known) {
        String t = s.trim();
        if (t.indexOf(TOOL_CALLS) >= 0 || t.indexOf(DSML) >= 0
                || t.toLowerCase(Locale.ROOT).contains("<tool_call>")) {
            return null;
        }
        if (!t.startsWith("{")) return null;
        ToolCall c = fromJsonObject(t, known);
        if (c == null) return null;
        return new Parsed(Family.BARE, java.util.Collections.singletonList(c), "", false);
    }

    /* ---------------------------------------------------------------- 公共 */

    private static List<ToolCall> fromJsonArray(String json, java.util.Set<String> known) {
        List<ToolCall> out = new ArrayList<>();
        Object root = JsonAccess.parse(json);
        if (root == null) return out;
        for (Object o : JsonAccess.list(root, null)) {
            ToolCall c = toCall(o, known);
            if (c != null) out.add(c);
        }
        return out;
    }

    private static ToolCall fromJsonObject(String body, java.util.Set<String> known) {
        if (body == null) return null;
        Object o = JsonAccess.parse(body.trim());
        return toCall(o, known);
    }

    public static ToolCall toCall(Object o, java.util.Set<String> known) {
        if (o == null) return null;
        String raw = JsonAccess.str(o, "name", null);
        if (raw == null) raw = JsonAccess.str(o, "tool", null);
        if (raw == null) raw = JsonAccess.str(o, "function.name", null);
        String name = normalize(raw);
        if (name == null || name.isEmpty()) return null;
        if (known != null && !known.contains(name)) return null;
        Object args = JsonAccess.get(o, "arguments");
        if (args == null) args = JsonAccess.get(o, "parameters");
        if (args == null) args = JsonAccess.get(o, "args");
        JSONObject obj;
        if (args instanceof JSONObject) {
            obj = (JSONObject) args;
        } else if (args instanceof String) {
            Object inner = JsonAccess.parse((String) args);
            obj = inner instanceof JSONObject ? (JSONObject) inner : new JSONObject();
        } else {
            obj = new JSONObject();
        }
        return new ToolCall(name, obj);
    }

    /** 工具名归一：小写、下划线，并把常见别名映射到注册表里的规范名。 */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String n = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        switch (n) {
            case "run": case "exec": case "bash": case "sh": case "cmd": case "command":
                return "shell";
            case "search": case "websearch": case "google":
                return "web_search";
            case "fetch": case "read_url": case "http_get":
                return "fetch_url";
            case "read": case "cat": case "open_file":
                return "read_file";
            case "write": case "save_file": case "create_file":
                return "write_file";
            case "ls": case "list": case "list_dir": case "dir":
                return "list_files";
            case "delete": case "rm": case "remove": case "del":
                return "delete_file";
            case "preview": case "show_preview":
                return "open_preview";
            default:
                return n;
        }
    }

    /**
     * 工具结果回灌用的包装。选对包装很重要：Hermes 系模型只认
     * {@code <tool_response>}，DSML 系模型按消息序列理解，不需要额外标签。
     */
    public static String responseBlock(Family family, String result, boolean zh) {
        String body = result == null ? "" : result;
        switch (family == null ? Family.NONE : family) {
            case JSON:
                return "<tool_response>\n" + body + "\n</tool_response>";
            case MISTRAL:
                return zh ? "[工具结果]\n" + body : "[tool_result]\n" + body;
            default:
                return body;
        }
    }

    /**
     * 模型是否「话没说完」：生成被 token 上限截断，或者原生调用写到一半。
     * 上层据此决定要不要自动续写一小段，而不是把一个半截调用当成结论。
     */
    public static boolean looksTruncated(String text) {
        if (text == null) return false;
        String t = text.trim();
        if (t.isEmpty()) return false;
        // 只有「结构没闭合」才算截断。一句没句号的短话不算 —— 模型本来就常省略句号，
        // 中文回复尤其如此，用句末标点判断会产生大量误报。
        if (t.endsWith("(") || t.endsWith("[") || t.endsWith("{") || t.endsWith("```")) return true;
        int dsml = t.lastIndexOf(DSML);
        if (dsml >= 0 && t.indexOf(DSML + "/tool_calls", dsml) < 0) return true;
        int tc = t.lastIndexOf("<tool_call>");
        return tc >= 0 && t.indexOf("</tool_call>", tc) < 0;
    }

    private static String stripRange(String s, int from, int to) {
        if (from < 0) return s;
        int end = Math.min(to, s.length());
        if (end <= from) return s;
        return s.substring(0, from) + s.substring(end);
    }

    private static String stripRanges(String s, List<int[]> ranges) {
        if (ranges.isEmpty()) return s;
        List<int[]> sorted = new ArrayList<>(ranges);
        sorted.sort((a, b) -> Integer.compare(a[0], b[0]));
        StringBuilder sb = new StringBuilder();
        int at = 0;
        for (int[] r : sorted) {
            if (r[0] < at) continue;
            sb.append(s, at, Math.min(r[0], s.length()));
            at = Math.min(r[1], s.length());
        }
        sb.append(s, at, s.length());
        return sb.toString();
    }

    /** 从 from 开始取一个括号平衡的 JSON 数组。 */
    private static String balancedArray(String s, int from) {
        int depth = 0;
        boolean inStr = false;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') i++;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return s.substring(from, i + 1);
            }
        }
        return null;
    }
}
