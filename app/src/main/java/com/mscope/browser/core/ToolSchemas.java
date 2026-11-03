package com.mscope.browser.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 工具的机器可读定义（JSON Schema 风格），JSON 与提示词两处共用。
 *
 * <p>以前工具说明是一段手写的中文/英文散文，提示词能看懂，程序看不懂。
 * 想给外部模型（OpenAI 兼容接口）发 {@code tools} 数组时，只能再手写一份，
 * 两边一旦漂移就是「提示词说能调、实际调不通」。
 *
 * <p>这里把定义收成一份表，{@link #describe} 用它渲染提示词，
 * {@link #toJson} 用它渲染接口要求的 {@code tools} 数组。
 *
 * <p>字段声明格式：{@code name:type:required:说明}，type 支持
 * {@code string / integer / boolean / array<string>}。
 */
public final class ToolSchemas {

    private ToolSchemas() {
    }

    /** 一个参数。 */
    public static final class Param {
        public final String name;
        public final String type;
        public final boolean required;
        public final String zh;
        public final String en;

        Param(String name, String type, boolean required, String zh, String en) {
            this.name = name;
            this.type = type;
            this.required = required;
            this.zh = zh;
            this.en = en;
        }

        public String desc(boolean zh) {
            return zh ? this.zh : this.en;
        }
    }

    /** 一个工具。 */
    public static final class Spec {
        public final String name;
        public final String zh;
        public final String en;
        public final List<Param> params;
        /** 会改动本机状态，执行前需要用户确认。 */
        public final boolean risky;
        /** 归属分组：builtin / net / file / ext。 */
        public final String group;

        Spec(String name, String group, boolean risky, String zh, String en, Param... ps) {
            this.name = name;
            this.group = group;
            this.risky = risky;
            this.zh = zh;
            this.en = en;
            this.params = Collections.unmodifiableList(Arrays.asList(ps));
        }

        public String desc(boolean zh) {
            return zh ? this.zh : this.en;
        }

        public List<Param> requiredParams() {
            List<Param> out = new ArrayList<>();
            for (Param p : params) if (p.required) out.add(p);
            return out;
        }
    }

    private static Param p(String name, String type, boolean required, String zh, String en) {
        return new Param(name, type, required, zh, en);
    }

    private static final List<Spec> ALL = Collections.unmodifiableList(Arrays.asList(
            new Spec("shell", "builtin", true,
                    "在手机本机终端执行命令（工作目录 = 工作区）",
                    "run a command in the on-device terminal (cwd = workspace)",
                    p("command", "string", true, "要执行的命令", "shell command")),
            new Spec("web_search", "net", false,
                    "联网搜索，返回标题与摘要",
                    "search the web; returns titles and snippets",
                    p("query", "string", true, "关键词", "keywords")),
            new Spec("fetch_url", "net", false,
                    "抓取公网网页正文（内网地址会被拒绝）",
                    "fetch the text of a public web page (private hosts are rejected)",
                    p("url", "string", true, "http/https 地址", "http/https URL")),
            new Spec("read_file", "file", false,
                    "读取工作区文件（文本，512KB 以内）",
                    "read a workspace file (text, up to 512KB)",
                    p("path", "string", true, "相对路径", "relative path")),
            new Spec("write_file", "file", true,
                    "整体写入工作区文件（覆盖）",
                    "write a workspace file (overwrites)",
                    p("path", "string", true, "相对路径", "relative path"),
                    p("content", "string", true, "文件内容", "file content")),
            new Spec("list_files", "file", false,
                    "列出目录内容",
                    "list a directory",
                    p("path", "string", false, "相对路径，默认 \".\"", "relative path, default \".\"")),
            new Spec("delete_file", "file", true,
                    "删除工作区文件或目录",
                    "delete a workspace file or directory",
                    p("path", "string", true, "相对路径", "relative path")),
            new Spec("open_preview", "file", false,
                    "在预览页打开工作区 HTML",
                    "open a workspace HTML file in the preview",
                    p("path", "string", true, "相对路径", "relative path")),
            new Spec("file_info", "file", false,
                    "查看文件/目录的大小、修改时间与类型（读之前先用它判断）",
                    "stat a path: size, mtime, kind (check before reading)",
                    p("path", "string", true, "相对路径", "relative path")),
            new Spec("edit_file", "file", true,
                    "按片段替换文件内容：old 必须唯一命中，找不到或多处命中都会报错（不会误改）",
                    "replace an exact snippet; fails loudly when old is missing or ambiguous",
                    p("path", "string", true, "相对路径", "relative path"),
                    p("old", "string", true, "要替换的原文（需唯一）", "exact snippet (must be unique)"),
                    p("new", "string", true, "替换成什么", "replacement")),
            new Spec("apply_patch", "file", true,
                    "按多个统一 diff 片段批量改文件（---/+++/@@ 格式，带行号校验）",
                    "apply unified-diff hunks with line-number verification",
                    p("patch", "string", true, "diff 文本", "diff text")),
            new Spec("world_info", "builtin", false,
                    "查本机事实：型号、ABI、内存、存储、电量、模型、上下文用量",
                    "device facts: model, ABI, memory, storage, battery, model, context usage"),
            new Spec("now", "builtin", false,
                    "当前时间与时区",
                    "current time and timezone"),
            new Spec("calc", "builtin", false,
                    "四则与幂运算求值（不联网、不执行代码）",
                    "evaluate arithmetic (no network, no code execution)",
                    p("expr", "string", true, "表达式，如 (1+2)*3", "expression, e.g. (1+2)*3")),
            new Spec("todo_write", "ext", false,
                    "维护本会话的待办清单（每项一行，前缀 [ ] / [x]）",
                    "maintain this chat's TODO list (one per line, prefix [ ] / [x])",
                    p("items", "string", true, "清单正文", "list body")),
            new Spec("journal_read", "ext", false,
                    "读工作日志：本会话之前的工具调用与写入记录",
                    "read the work journal: earlier tool calls and writes in this chat"),
            new Spec("remember", "ext", false,
                    "记下一条跨会话使用的偏好/事实（会做密钥脱敏）",
                    "remember a cross-session fact (secrets are redacted)",
                    p("text", "string", true, "要记住的内容", "the fact")),
            new Spec("recall", "ext", false,
                    "从长期记忆里检索相关条目（BM25）",
                    "search long-term memory (BM25)",
                    p("query", "string", false, "关键词，留空列出最近几条", "keywords; empty lists recent")),
            new Spec("kb_index", "ext", false,
                    "给工作区文本文件建检索索引（大文件不必整段塞进上下文）",
                    "index workspace text files so big files need not be pasted in"),
            new Spec("kb_search", "ext", false,
                    "在工作区索引里检索相关片段（BM25 + 中文二元组）",
                    "search the workspace index (BM25 + CJK bigrams)",
                    p("query", "string", true, "关键词", "keywords")),
            new Spec("bench", "ext", true,
                    "跑一次本机推理基准，得到预填充/解码 tok/s（会占用模型几秒）",
                    "run an on-device inference benchmark (occupies the model for a moment)")
    ));

    private static final Map<String, Spec> BY_NAME;

    static {
        Map<String, Spec> m = new java.util.LinkedHashMap<>();
        for (Spec s : ALL) m.put(s.name, s);
        BY_NAME = Collections.unmodifiableMap(m);
    }

    public static List<Spec> all() {
        return ALL;
    }

    public static Spec get(String name) {
        return name == null ? null : BY_NAME.get(name.toLowerCase(Locale.ROOT));
    }

    /** 工具说明（提示词用）：只列启用的，参数写清类型与是否必填。 */
    public static String describe(java.util.Collection<String> enabled, boolean zh) {
        StringBuilder sb = new StringBuilder();
        for (Spec s : ALL) {
            if (enabled != null && !enabled.contains(s.name)) continue;
            sb.append("- ").append(s.name).append(zh ? "：" : ": ").append(s.desc(zh)).append('\n');
            if (s.params.isEmpty()) continue;
            sb.append(zh ? "  参数 " : "  args ");
            for (int i = 0; i < s.params.size(); i++) {
                Param pm = s.params.get(i);
                if (i > 0) sb.append(", ");
                sb.append(pm.name).append(':').append(pm.type);
                if (!pm.required) sb.append('?');
            }
            sb.append(zh ? "（示例：{\"" : " (e.g. {\"")
              .append(s.params.get(0).name).append("\": \"")
              .append(exampleFor(s.params.get(0).name)).append("\"}")
              .append(zh ? "）\n" : ")\n");
        }
        return sb.toString();
    }

    /** 参数示例值：只为了让人/模型看出形状，不求语义正确。 */
    public static String exampleFor(String param) {
        switch (param) {
            case "command":
                return "ls -la";
            case "query":
                return "关键词";
            case "url":
                return "https://example.com";
            case "path":
                return "a.txt";
            case "content":
                return "...";
            case "old":
                return "原文";
            case "new":
                return "新文";
            case "patch":
                return "--- a.txt\n+++ a.txt\n@@ -1,1 +1,1 @@\n-a\n+b";
            case "expr":
                return "(1+2)*3";
            case "items":
                return "[ ] 第一步";
            case "text":
                return "用户偏好简洁回答";
            default:
                return "...";
        }
    }

    /**
     * 渲染成接口需要的 JSON（OpenAI 兼容 {@code tools} 数组）。
     *
     * <p>手写而不用 JSONObject：这个类要能在纯 JVM 单测里跑，
     * 而 Android 的 org.json 在单测环境是空实现（调用即抛异常）。
     */
    public static String toJson(java.util.Collection<String> enabled, boolean zh) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Spec s : ALL) {
            if (enabled != null && !enabled.contains(s.name)) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"type\":\"function\",\"function\":{\"name\":").append(q(s.name))
              .append(",\"description\":").append(q(s.desc(zh)))
              .append(",\"parameters\":{\"type\":\"object\",\"properties\":{");
            for (int i = 0; i < s.params.size(); i++) {
                Param pm = s.params.get(i);
                if (i > 0) sb.append(',');
                sb.append(q(pm.name)).append(":{\"type\":");
                String t = pm.type;
                if (t.startsWith("array<")) {
                    sb.append("\"array\",\"items\":{\"type\":")
                      .append(q(t.substring(6, t.length() - 1))).append('}');
                } else {
                    sb.append(q(t));
                }
                sb.append(",\"description\":").append(q(pm.desc(zh))).append('}');
            }
            sb.append("},\"required\":[");
            List<Param> req = s.requiredParams();
            for (int i = 0; i < req.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(q(req.get(i).name));
            }
            sb.append("]}}}");
        }
        return sb.append(']').toString();
    }

    private static String q(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
