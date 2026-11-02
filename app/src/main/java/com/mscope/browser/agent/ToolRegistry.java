package com.mscope.browser.agent;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 全部工具的唯一真源。
 *
 * <p>模型能看到的说明、{@link ToolCall} 的工具名白名单、{@link AgentTools#risky} 的确认判定，
 * 三处都从这里派生。新增工具只改这一个文件 + 一个 case。
 */
public final class ToolRegistry {

    private static final Map<String, ToolSpec> ALL = new LinkedHashMap<>();

    private ToolRegistry() {
    }

    static {
        // ---------------------------------------------------------------- 文件
        add(ToolSpec.of("list_files", ToolSpec.Group.FILE, false,
                "列出工作区目录", "list a workspace directory", "path"));
        add(ToolSpec.of("read_file", ToolSpec.Group.FILE, false,
                "读取工作区文件", "read a workspace file", "path"));
        add(ToolSpec.of("write_file", ToolSpec.Group.FILE, true,
                "写入工作区文件（覆盖）", "write a workspace file (overwrite)", "path", "content"));
        add(ToolSpec.of("append_file", ToolSpec.Group.FILE, true,
                "在文件末尾追加内容", "append text to a workspace file", "path", "content"));
        add(ToolSpec.of("apply_patch", ToolSpec.Group.FILE, true,
                "按片段修改文件：只给要找的原文与替换内容，不必重写全文",
                "edit a file by hunks: give the text to find and its replacement",
                "path", "hunks"));
        add(ToolSpec.of("delete_file", ToolSpec.Group.FILE, true,
                "删除工作区文件", "delete a workspace file", "path"));
        add(ToolSpec.of("move_file", ToolSpec.Group.FILE, true,
                "移动或重命名文件", "move or rename a file", "from", "to"));
        add(ToolSpec.of("copy_file", ToolSpec.Group.FILE, true,
                "复制文件", "copy a file", "from", "to"));
        add(ToolSpec.of("mkdir", ToolSpec.Group.FILE, true,
                "创建目录", "create a directory", "path"));
        add(ToolSpec.of("file_stat", ToolSpec.Group.FILE, false,
                "查看文件大小、行数、摘要（不必读入上下文）",
                "file size, line count and digest without reading it", "path"));
        add(ToolSpec.of("file_tree", ToolSpec.Group.FILE, false,
                "以树形列出工作区（限深度）", "tree view of the workspace", "path", "depth"));
        add(ToolSpec.of("grep", ToolSpec.Group.FILE, false,
                "在工作区内按正则搜索内容，返回行号",
                "regex search across workspace files, with line numbers", "pattern", "path"));
        add(ToolSpec.of("find_files", ToolSpec.Group.FILE, false,
                "按文件名通配符查找文件", "find files by name glob", "pattern"));
        add(ToolSpec.of("checksum", ToolSpec.Group.FILE, false,
                "算文件摘要（md5/sha1/sha256/crc32）", "file digest (md5/sha1/sha256/crc32)", "path", "algo"));
        add(ToolSpec.of("zip", ToolSpec.Group.FILE, true,
                "把工作区目录打包成 zip", "pack a workspace directory into a zip", "path", "output"));
        add(ToolSpec.of("unzip", ToolSpec.Group.FILE, true,
                "解压工作区内的 zip（拒绝越界条目）", "unpack a zip inside the workspace", "path", "output"));

        // ---------------------------------------------------------------- 文本
        add(ToolSpec.of("replace_text", ToolSpec.Group.TEXT, false,
                "在文本里做正则替换并返回结果（不改文件）",
                "regex replace inside text, returns the result (no file change)", "text", "pattern", "replacement"));
        add(ToolSpec.of("extract_links", ToolSpec.Group.TEXT, false,
                "从文本中抽出链接", "extract links from text", "text"));
        add(ToolSpec.of("extract_emails", ToolSpec.Group.TEXT, false,
                "从文本中抽出邮箱", "extract email addresses from text", "text"));
        add(ToolSpec.of("extract_code", ToolSpec.Group.TEXT, false,
                "抽出文本里的 Markdown 代码块", "extract fenced code blocks from text", "text"));
        add(ToolSpec.of("convert_table", ToolSpec.Group.TEXT, false,
                "CSV 与 Markdown 表格互转", "convert between CSV and Markdown tables", "text", "to"));
        add(ToolSpec.of("text_stats", ToolSpec.Group.TEXT, false,
                "字符数、词数、行数、token 估算", "characters, words, lines and estimated tokens", "text"));
        add(ToolSpec.of("diff", ToolSpec.Group.TEXT, false,
                "对比两段文本，给出 unified diff", "unified diff of two texts", "before", "after"));
        add(ToolSpec.of("redact", ToolSpec.Group.TEXT, false,
                "脱敏：抹掉密钥、Token、密码、私钥",
                "redact keys, tokens, passwords and private keys", "text"));
        add(ToolSpec.of("outline", ToolSpec.Group.TEXT, false,
                "给长文生成 Markdown 大纲", "build a Markdown outline of long text", "text"));
        add(ToolSpec.of("summarize_text", ToolSpec.Group.TEXT, false,
                "抽取式摘要（不调用模型，纯排序）", "extractive summary without calling a model", "text", "sentences"));
        add(ToolSpec.of("json_query", ToolSpec.Group.TEXT, false,
                "按路径取 JSON 里的值（a.b[0].c）", "read a JSON value by path (a.b[0].c)", "json", "path"));
        add(ToolSpec.of("json_format", ToolSpec.Group.TEXT, false,
                "格式化 JSON（缩进）", "pretty-print JSON", "json"));

        // ---------------------------------------------------------------- 计算与时间
        add(ToolSpec.of("calc", ToolSpec.Group.CALC, false,
                "算式求值（含单位换算、百分比、幂）",
                "evaluate an arithmetic expression (units, percent, power)", "expr"));
        add(ToolSpec.of("now", ToolSpec.Group.CALC, false,
                "现在的时间、星期与周数", "current time, weekday and week number", "tz"));
        add(ToolSpec.of("date_math", ToolSpec.Group.CALC, false,
                "日期加减（如 2026-01-01 +3d）", "date arithmetic (e.g. 2026-01-01 +3d)", "base", "delta"));
        add(ToolSpec.of("random", ToolSpec.Group.CALC, false,
                "随机数 / 随机挑选 / UUID", "random number, choice or UUID", "kind", "a", "b"));
        add(ToolSpec.of("hash_text", ToolSpec.Group.CALC, false,
                "文本摘要（md5/sha1/sha256）", "text digest (md5/sha1/sha256)", "text", "algo"));
        add(ToolSpec.of("base64", ToolSpec.Group.CALC, false,
                "base64 编码或解码", "base64 encode or decode", "text", "mode"));
        add(ToolSpec.of("url_codec", ToolSpec.Group.CALC, false,
                "URL 编码或解码", "URL encode or decode", "text", "mode"));
        add(ToolSpec.of("unit_convert", ToolSpec.Group.CALC, false,
                "长度、重量、温度换算", "length, weight and temperature conversion", "value", "from", "to"));

        // ---------------------------------------------------------------- 本机
        add(ToolSpec.of("device_info", ToolSpec.Group.DEVICE, false,
                "机型、系统、CPU、内存、可用空间、电量",
                "model, OS, CPU, memory, free space and battery"));
        add(ToolSpec.of("storage_report", ToolSpec.Group.DEVICE, false,
                "应用各目录占用与剩余空间", "space used by app directories and what is left", ""));
        add(ToolSpec.of("notify", ToolSpec.Group.DEVICE, true,
                "发一条系统通知（后台任务完成时提醒）",
                "post a system notification", "title", "text"));
        add(ToolSpec.of("clipboard", ToolSpec.Group.DEVICE, true,
                "读写剪贴板", "read or write the clipboard", "action", "text"));
        add(ToolSpec.of("share", ToolSpec.Group.DEVICE, true,
                "把工作区文件共享给其他应用", "share a workspace file with other apps", "path"));
        add(ToolSpec.of("open_url", ToolSpec.Group.DEVICE, true,
                "用系统浏览器打开链接", "open a link in the system browser", "url"));
        add(ToolSpec.of("open_preview", ToolSpec.Group.DEVICE, false,
                "在应用内预览工作区 HTML", "preview a workspace HTML in the app", "path"));

        // ---------------------------------------------------------------- 终端
        add(ToolSpec.of("shell", ToolSpec.Group.SHELL, true,
                "在手机本机终端执行命令（工作目录 = 工作区）",
                "run a command in the on-device terminal (cwd = workspace)", "command"));

        // ---------------------------------------------------------------- 联网
        add(ToolSpec.of("web_search", ToolSpec.Group.WEB, false,
                "联网搜索", "search the web", "query"));
        add(ToolSpec.of("fetch_url", ToolSpec.Group.WEB, false,
                "抓取网页正文", "fetch the text of a web page", "url"));
        add(ToolSpec.of("download_file", ToolSpec.Group.WEB, true,
                "把公网文件下载到工作区", "download a public file into the workspace", "url", "path"));
        add(ToolSpec.of("http_request", ToolSpec.Group.WEB, true,
                "发一个 HTTP 请求（可带方法与 JSON 体）",
                "send an HTTP request (method and optional JSON body)", "url", "method", "body"));

        // ---------------------------------------------------------------- 智能体自身
        add(ToolSpec.of("memory_add", ToolSpec.Group.AGENT, false,
                "记住一条长期偏好，之后每轮都会带上",
                "remember a long-term preference, injected into every turn", "text"));
        add(ToolSpec.of("memory_list", ToolSpec.Group.AGENT, false,
                "列出已记住的偏好", "list remembered preferences", ""));
        add(ToolSpec.of("memory_forget", ToolSpec.Group.AGENT, true,
                "忘掉一条或全部偏好", "forget one or all preferences", "index"));
        add(ToolSpec.of("todo_add", ToolSpec.Group.AGENT, false,
                "把一步计划写进待办板", "add a step to the todo board", "title"));
        add(ToolSpec.of("todo_done", ToolSpec.Group.AGENT, false,
                "更新待办状态（pending/doing/done/blocked）",
                "update a todo status (pending/doing/done/blocked)", "index", "status"));
        add(ToolSpec.of("todo_list", ToolSpec.Group.AGENT, false,
                "查看待办板与进度", "show the todo board and progress", ""));
        add(ToolSpec.of("todo_clear", ToolSpec.Group.AGENT, true,
                "清空待办板", "clear the todo board", ""));
        add(ToolSpec.of("skill_search", ToolSpec.Group.AGENT, false,
                "在技能库里按关键词找现成套路",
                "search the skill library for a ready-made procedure", "query"));
        add(ToolSpec.of("skill_use", ToolSpec.Group.AGENT, false,
                "取出某条技能的完整步骤", "load the full steps of a skill", "id"));
        add(ToolSpec.of("skill_save", ToolSpec.Group.AGENT, true,
                "把一套流程存成技能，下次可复用",
                "save a procedure as a reusable skill", "id", "name", "body", "trigger"));

        // ---------------------------------------------------------------- 外部模型
        add(ToolSpec.of("llm_ask", ToolSpec.Group.MODEL, false,
                "把你的问题交给配置好的外部大模型（OpenAI 兼容接口）",
                "hand a question to the configured external model (OpenAI-compatible)", "prompt", "system"));
    }

    private static void add(ToolSpec s) {
        if (s == null || s.name.isEmpty()) return;
        ALL.put(s.name, s);
    }

    /** 全部工具（按声明顺序）。 */
    public static List<ToolSpec> all() {
        return new ArrayList<>(ALL.values());
    }

    public static ToolSpec get(String name) {
        return name == null ? null : ALL.get(name);
    }

    public static boolean exists(String name) {
        return name != null && ALL.containsKey(name);
    }

    /** 工具名集合，供 ToolCall 白名单。 */
    public static Set<String> names() {
        return new java.util.LinkedHashSet<>(ALL.keySet());
    }

    public static boolean risky(String name) {
        ToolSpec s = get(name);
        return s != null && s.risky;
    }

    public static List<ToolSpec> group(ToolSpec.Group g) {
        List<ToolSpec> out = new ArrayList<>();
        for (ToolSpec s : ALL.values()) if (s.group == g) out.add(s);
        return out;
    }

    /**
     * 生成给模型看的工具清单。
     *
     * @param allowed 允许出现的分组；为 null 表示全部
     * @param compact 小模型压缩版（只给名字与一句话，不给参数格式）
     */
    public static String describe(Set<ToolSpec.Group> allowed, boolean compact, boolean zh) {
        Set<ToolSpec.Group> allow = allowed == null ? EnumSet.allOf(ToolSpec.Group.class) : allowed;
        StringBuilder sb = new StringBuilder();
        for (ToolSpec s : ALL.values()) {
            if (!allow.contains(s.group)) continue;
            if (compact) {
                sb.append("- ").append(s.name).append(zh ? "：" : ": ").append(zh ? s.zh : s.en).append('\n');
            } else {
                sb.append(s.describe(zh));
            }
        }
        return sb.toString();
    }

    /** 「有哪些工具」的一句话摘要，用于状态条。 */
    public static String summary(Set<ToolSpec.Group> allowed, boolean zh) {
        int n = 0;
        if (allowed == null) {
            n = ALL.size();
        } else {
            for (ToolSpec s : ALL.values()) if (allowed.contains(s.group)) n++;
        }
        return zh ? n + " 个工具可用" : n + " tools available";
    }
}
