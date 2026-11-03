package com.mscope.browser.agent;

import com.mscope.browser.core.Bench;
import com.mscope.browser.core.Calc;
import com.mscope.browser.core.DeviceFacts;
import com.mscope.browser.core.Journal;
import com.mscope.browser.core.Memory;
import com.mscope.browser.core.Patcher;
import com.mscope.browser.core.Planner;
import com.mscope.browser.core.Retriever;
import com.mscope.browser.core.TextKit;
import com.mscope.browser.core.ToolSchemas;
import com.mscope.browser.i18n.Lang;

import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 智能体可调用的工具集：本机终端（真 shell）、联网搜索、工作区文件、网页预览，
 * 以及一批「让模型能自己核对事实」的小工具（计算、时间、设备信息、检索、待办、日志）。
 *
 * <p>每个工具的执行结果都会作为 tool_result 回灌给模型，所以返回值统一是「给模型看的文本」，
 * 出错也返回文本（而不是抛异常），让模型能自行纠错 —— 但「改文件」这类工具必须先算成功再落盘，
 * 失败时原文件一个字都不能动。
 *
 * <p>工具的<b>定义</b>在 {@link ToolSchemas} 里（名字、参数、是否危险、中英说明），
 * 这里只负责执行。两处分开是为了让提示词、解析器、将来的接口 schema 共用同一份真相。
 */
public class AgentTools {

    /** 打开网页预览需要界面动作，由宿主回调（在主线程执行）。 */
    public interface Host {
        void openPreview(String relPath);
    }

    /**
     * 需要宿主提供「本机事实」与「跑基准」的地方。
     *
     * <p>和 {@link Host} 分开是因为两者用途不同：预览是纯 UI 回调，
     * 而这里要读硬件、占模型几秒 —— 不实现也能用，对应工具会明确告知「做不到」。
     */
    public interface Prober {
        /** 跑一次本机推理基准；拿不到引擎时返回 null。 */
        List<Bench.Result> runBench();

        /** 本机事实（型号、内存、上下文用量等）。 */
        List<DeviceFacts.Item> deviceFacts();
    }

    /** 单次工具结果最多回灌给模型的字符数，防止把上下文撑爆。 */
    private static final int MAX_RESULT = 6000;

    private final Workspace ws;
    private final Shell shell;
    private final Host host;
    private Prober prober;
    private final KbIndex kb;
    private final Journal journal = new Journal();
    private final Memory.Store memory;

    /** 本会话待办（由模型通过 todo_write 维护）。 */
    private final List<Planner.Item> todo = new ArrayList<>();

    public AgentTools(Workspace ws, Shell shell, Host host, Memory.Store memory) {
        this.ws = ws;
        this.shell = shell;
        this.host = host;
        this.memory = memory;
        this.kb = new KbIndex(ws);
    }

    /** 接上「本机事实/基准」的来源。 */
    public void setProber(Prober p) {
        this.prober = p;
    }

    /** 兼容旧构造：无长期记忆存储时退化为进程内存储。 */
    public AgentTools(Workspace ws, Shell shell, Host host) {
        this(ws, shell, host, new Memory.Store(null));
    }

    /** 全部工具名（用于校验模型输出，避免把普通 JSON 误判成调用）。 */
    public static Set<String> names() {
        Set<String> out = new LinkedHashSet<>();
        for (ToolSchemas.Spec s : ToolSchemas.all()) out.add(s.name);
        return Collections.unmodifiableSet(out);
    }

    /**
     * 当前这次对话实际启用的工具名。
     *
     * <p>解析器与提示词必须用同一份清单：以前提示词里写「可用工具」时按开关过滤，
     * 解析时却用全量 {@link #names()}，于是关掉联网后模型仍能调用 {@code web_search}
     * —— 结果是一个卡住的网络请求，用户看到的是「转很久然后失败」。
     */
    public static Set<String> enabled(boolean web, boolean files, boolean shellOn) {
        Set<String> out = new LinkedHashSet<>();
        if (shellOn) out.add("shell");
        if (web) {
            out.add("web_search");
            out.add("fetch_url");
        }
        if (files) {
            out.add("read_file");
            out.add("write_file");
            out.add("list_files");
            out.add("delete_file");
            out.add("open_preview");
            out.add("file_info");
            out.add("edit_file");
            out.add("apply_patch");
        }
        // 下面这些不碰本机外部状态，永远可用
        out.add("world_info");
        out.add("now");
        out.add("calc");
        out.add("todo_write");
        out.add("journal_read");
        out.add("remember");
        out.add("recall");
        out.add("kb_index");
        out.add("kb_search");
        out.add("bench");
        return out;
    }

    /** 需要用户确认后才能执行的工具（会改动本机状态）。 */
    public static boolean risky(String name) {
        ToolSchemas.Spec s = ToolSchemas.get(name);
        return s != null && s.risky;
    }

    /**
     * 生成给模型看的工具说明（只列出当前启用的）。
     *
     * <p>说明的语言跟随界面语言：用英文界面的人，模型也该用英文思考和汇报，
     * 否则「英文界面 + 中文工具说明」会让小模型两头不靠。
     */
    public static String describe(boolean web, boolean files, boolean shellOn) {
        return ToolSchemas.describe(enabled(web, files, shellOn), Lang.zh());
    }

    /* ---------------------------------------------------------------- 执行 */

    /** 执行一次工具调用，返回给模型看的结果文本。 */
    public String exec(ToolCall c) {
        final boolean zh = Lang.zh();
        String result;
        try {
            result = dispatch(c, zh);
        } catch (Patcher.Failure f) {
            result = (zh ? "改动失败，文件未变动：" : "patch rejected, file untouched: ") + f.getMessage();
        } catch (Exception e) {
            result = (zh ? "执行出错：" : "execution failed: ") + e.getMessage();
        }
        journal.add(c.name, summary(c), result, !isError(result, zh));
        return result;
    }

    private static boolean isError(String r, boolean zh) {
        if (r == null) return true;
        String head = r.length() > 12 ? r.substring(0, 12) : r;
        return head.startsWith("错误") || head.toLowerCase(Locale.ROOT).startsWith("error")
                || head.startsWith("未知工具") || head.startsWith("unknown tool")
                || head.startsWith("改动失败") || head.startsWith("patch rejected");
    }

    private String dispatch(ToolCall c, boolean zh) throws Exception {
        switch (c.name) {
            case "shell": {
                String cmd = c.arg(new String[]{"command", "cmd", "script"}, "");
                if (cmd.isEmpty()) return err(zh, "missing argument command", "缺少参数 command");
                Shell.Out o = shell.run(cmd);
                String body = o.text.isEmpty() ? (zh ? "（无输出）" : "(no output)") : o.text;
                return "$ " + cmd + "\n" + body
                        + (o.code != 0 ? (zh ? "\n[退出码 " + o.code + "]" : "\n[exit code " + o.code + "]") : "");
            }
            case "web_search": {
                String q = c.arg(new String[]{"query", "q", "keywords", "keyword"}, "");
                if (q.isEmpty()) return err(zh, "missing argument query", "缺少参数 query");
                List<WebSearch.Result> rs = WebSearch.search(q, 6);
                if (rs.isEmpty()) {
                    return zh ? "没有检索到「" + q + "」的结果。" : "No results found for \"" + q + "\".";
                }
                return (zh ? "搜索「" + q + "」的结果：\n" : "Results for \"" + q + "\":\n")
                        + WebSearch.format(rs);
            }
            case "fetch_url": {
                String url = c.arg(new String[]{"url", "link", "href"}, "");
                if (!WebSearch.allowedForFetch(url)) {
                    return zh
                            ? "错误：只允许抓取公网 http/https 地址（本机与内网地址已拒绝）"
                            : "error: only public http/https URLs are allowed "
                            + "(loopback and private-network addresses are rejected)";
                }
                return WebSearch.fetchPage(url, MAX_RESULT);
            }
            case "list_files": {
                String path = c.arg(new String[]{"path", "dir", "directory"}, ".");
                String rel = Workspace.normalize(path);
                if (!ws.exists(rel)) {
                    return (zh ? "目录不存在：" : "directory not found: ") + (path.isEmpty() ? "." : path);
                }
                if (!ws.isDir(rel)) return (zh ? "这不是目录：" : "not a directory: ") + path;
                List<Workspace.Entry> es = ws.list(rel);
                if (es.isEmpty()) return zh ? "（空目录）" : "(empty directory)";
                StringBuilder sb = new StringBuilder();
                for (Workspace.Entry e : es) {
                    sb.append(e.dir ? (zh ? "[目录] " : "[dir] ") : (zh ? "[文件] " : "[file] "))
                            .append(e.relPath)
                            .append(e.dir ? "/" : (zh ? "  (" + e.size + " 字节)" : "  (" + e.size + " bytes)"))
                            .append('\n');
                }
                return sb.toString().trim();
            }
            case "read_file": {
                String path = c.arg(new String[]{"path", "file", "filename"}, "");
                if (path.isEmpty()) return err(zh, "missing argument path", "缺少参数 path");
                File f = ws.resolve(path);
                if (!f.exists()) return (zh ? "文件不存在：" : "file not found: ") + path;
                if (f.isDirectory()) return (zh ? "这是一个目录：" : "this is a directory: ") + path;
                return truncate(ws.readText(path), MAX_RESULT);
            }
            case "write_file": {
                String path = c.arg(new String[]{"path", "file", "filename"}, "");
                if (path.isEmpty()) return err(zh, "missing argument path", "缺少参数 path");
                String content = c.arg(new String[]{"content", "text", "body", "data"}, "");
                ws.writeText(path, content);
                int bytes = content.getBytes().length;
                return zh
                        ? "已写入 /" + Workspace.normalize(path) + "（" + bytes + " 字节）"
                        : "wrote /" + Workspace.normalize(path) + " (" + bytes + " bytes)";
            }
            case "delete_file": {
                String path = c.arg(new String[]{"path", "file", "filename"}, "");
                if (path.isEmpty()) return err(zh, "missing argument path", "缺少参数 path");
                return ws.delete(path)
                        ? (zh ? "已删除 /" : "deleted /") + Workspace.normalize(path)
                        : (zh ? "不存在：" : "not found: ") + path;
            }
            case "open_preview": {
                String path = c.arg(new String[]{"path", "file", "filename", "url"}, "");
                if (path.isEmpty()) return err(zh, "missing argument path", "缺少参数 path");
                String rel = Workspace.normalize(path);
                if (!ws.exists(rel)) return (zh ? "文件不存在：" : "file not found: ") + path;
                if (host != null) host.openPreview(rel);
                return zh ? "已在预览页打开 /" + rel : "opened /" + rel + " in the preview";
            }
            case "file_info": {
                String path = c.arg(new String[]{"path", "file", "filename"}, "");
                if (path.isEmpty()) return err(zh, "missing argument path", "缺少参数 path");
                File f = ws.resolve(path);
                if (!f.exists()) return (zh ? "不存在：" : "not found: ") + path;
                String kind = f.isDirectory() ? (zh ? "目录" : "directory") : (zh ? "文件" : "file");
                String time = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
                        .format(new Date(f.lastModified()));
                return (zh ? "路径：" : "path: ") + Workspace.normalize(path) + "\n"
                        + (zh ? "类型：" : "kind: ") + kind + "\n"
                        + (zh ? "大小：" : "size: ") + DeviceFacts.bytes(f.length()) + " (" + f.length() + ")\n"
                        + (zh ? "修改时间：" : "modified: ") + time;
            }
            case "edit_file": {
                String path = c.arg(new String[]{"path", "file", "filename"}, "");
                if (path.isEmpty()) return err(zh, "missing argument path", "缺少参数 path");
                File f = ws.resolve(path);
                if (!f.exists()) return (zh ? "文件不存在：" : "file not found: ") + path;
                if (f.isDirectory()) return (zh ? "这是一个目录：" : "this is a directory: ") + path;
                String old = c.arg(new String[]{"old", "find", "search", "from"}, "");
                String neu = c.arg(new String[]{"new", "replace", "to"}, "");
                String src = ws.readText(path);
                String out = Patcher.replaceOnce(src, old, neu, zh);
                ws.writeText(path, out);
                return zh
                        ? "已改 /" + Workspace.normalize(path) + "（" + old.length() + " 字符 → " + neu.length() + " 字符）"
                        : "edited /" + Workspace.normalize(path) + " (" + old.length() + " → " + neu.length() + " chars)";
            }
            case "apply_patch": {
                String diff = c.arg(new String[]{"patch", "diff", "content"}, "");
                if (diff.isEmpty()) return err(zh, "missing argument patch", "缺少参数 patch");
                List<Patcher.FilePatch> patches = Patcher.parse(diff);
                // 先在内存里把所有文件算一遍，全部成功才落盘：不允许改一半。
                List<String[]> results = new ArrayList<>();
                for (Patcher.FilePatch fp : patches) {
                    String rel = Workspace.normalize(fp.path);
                    File f = ws.resolve(rel);
                    if (f.isDirectory()) {
                        return (zh ? "这是一个目录：" : "this is a directory: ") + rel;
                    }
                    String before = f.exists() ? ws.readText(rel) : "";
                    results.add(new String[]{rel, Patcher.apply(before, fp)});
                }
                for (String[] r : results) ws.writeText(r[0], r[1]);
                return (zh ? "已应用补丁：" : "applied patch: ") + Patcher.summary(patches);
            }
            case "world_info": {
                List<DeviceFacts.Item> items = prober == null ? new ArrayList<>() : prober.deviceFacts();
                items.add(DeviceFacts.of("kb", kb.stats(zh), "工作区索引", "workspace index"));
                return DeviceFacts.render(items, zh);
            }
            case "now": {
                Date d = new Date();
                return new SimpleDateFormat(zh ? "yyyy-MM-dd HH:mm:ss (z)" : "yyyy-MM-dd HH:mm:ss (z)", Locale.ROOT)
                        .format(d) + "\n" + (zh ? "时区：" : "timezone: ") + java.util.TimeZone.getDefault().getID();
            }
            case "calc": {
                String e = c.arg(new String[]{"expr", "expression", "e"}, "");
                if (e.isEmpty()) return err(zh, "missing argument expr", "缺少参数 expr");
                try {
                    return e + " = " + Calc.solve(e);
                } catch (RuntimeException ex) {
                    return (zh ? "错误：无法计算「" : "error: cannot evaluate \"") + e + "\" — " + ex.getMessage();
                }
            }
            case "todo_write": {
                List<Planner.Item> items = Planner.parse(c.arg(new String[]{"items", "list", "content"}, ""));
                todo.clear();
                todo.addAll(items);
                if (todo.isEmpty()) return zh ? "已清空待办。" : "todo cleared.";
                return (zh ? "已更新待办（" + Planner.doneCount(todo) + "/" + todo.size() + " 完成）：\n"
                           : "todo updated (" + Planner.doneCount(todo) + "/" + todo.size() + " done):\n")
                        + Planner.render(todo);
            }
            case "journal_read": {
                String n = c.arg(new String[]{"limit", "n", "count"}, "");
                int limit;
                try {
                    limit = n.isEmpty() ? 30 : Integer.parseInt(n.trim());
                } catch (NumberFormatException e) {
                    limit = 30;
                }
                return (zh ? "工作日志（最近 " : "work journal (last ") + (limit <= 0 ? "全部" : limit) + "）：\n"
                        + journal.render(limit, zh);
            }
            case "remember": {
                String text = c.arg(new String[]{"text", "fact", "content", "memory"}, "");
                if (text.trim().isEmpty()) return err(zh, "missing argument text", "缺少参数 text");
                boolean hadSecret = com.mscope.browser.core.SecretVault.containsSecret(text);
                memory.add(text);
                return (zh
                        ? "已记住：" + com.mscope.browser.core.SecretVault.redact(text)
                        + (hadSecret ? "\n（检测到疑似密钥，已脱敏后再存储）" : "")
                        : "remembered: " + com.mscope.browser.core.SecretVault.redact(text)
                        + (hadSecret ? "\n(suspected secret detected and redacted before saving)" : ""));
            }
            case "recall": {
                String q = c.arg(new String[]{"query", "q", "keywords"}, "");
                List<Memory.Fact> hits = memory.search(q, 8);
                if (hits.isEmpty()) return zh ? "没有相关记忆。" : "nothing relevant in memory.";
                StringBuilder sb = new StringBuilder(zh ? "相关记忆：\n" : "relevant memories:\n");
                for (Memory.Fact f : hits) sb.append("· ").append(f.text).append('\n');
                return sb.toString().trim();
            }
            case "kb_index": {
                int n = kb.build(c.arg(new String[]{"rebuild", "force"}, "false").startsWith("t"));
                return (zh ? "索引完成，处理了 " + n + " 个文件。" : "indexed, " + n + " file(s) processed.")
                        + "\n" + kb.stats(zh);
            }
            case "kb_search": {
                String q = c.arg(new String[]{"query", "q", "keywords"}, "");
                if (q.trim().isEmpty()) return err(zh, "missing argument query", "缺少参数 query");
                int k = 5;
                try {
                    String ks = c.arg(new String[]{"top_k", "k", "limit"}, "");
                    if (!ks.isEmpty()) k = Math.max(1, Math.min(12, Integer.parseInt(ks.trim())));
                } catch (NumberFormatException ignored) {
                }
                List<Retriever.Result> rs = kb.search(q, k);
                if (rs.isEmpty()) {
                    return zh ? "索引里没有匹配「" + q + "」的片段（可能文件尚未索引，先调 kb_index）。"
                              : "no chunk matched \"" + q + "\" (index may be empty; call kb_index first).";
                }
                StringBuilder sb = new StringBuilder();
                for (Retriever.Result r : rs) {
                    sb.append("[").append(r.source).append("]\n")
                      .append(Retriever.highlight(r.snippet, q, 260)).append("\n\n");
                }
                return truncate(sb.toString().trim(), MAX_RESULT);
            }
            case "bench": {
                List<Bench.Result> rs = prober == null ? null : prober.runBench();
                if (rs == null || rs.isEmpty()) {
                    return zh ? "错误：当前没有加载模型，无法跑基准。" : "error: no model loaded; cannot benchmark.";
                }
                StringBuilder sb = new StringBuilder();
                for (Bench.Result r : rs) sb.append(r.line(zh)).append('\n');
                sb.append('\n').append(Bench.verdict(rs, zh));
                return sb.toString().trim();
            }
            default:
                return (zh ? "未知工具：" : "unknown tool: ") + c.name;
        }
    }

    private static String err(boolean zh, String en, String cn) {
        return zh ? "错误：" + cn : "error: " + en;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + (Lang.zh() ? "\n…（内容过长，已截断）" : "\n… (truncated: too long)");
    }

    /* ---------------------------------------------------------------- 会话状态 */

    /** 本会话待办（进提示词用）。 */
    public List<Planner.Item> todo() {
        return new ArrayList<>(todo);
    }

    /** 工作日志。 */
    public Journal journal() {
        return journal;
    }

    /** 工作区检索索引。 */
    public KbIndex index() {
        return kb;
    }

    /** 长期记忆。 */
    public Memory.Store memoryStore() {
        return memory;
    }

    /** 新会话时清空会话级状态（日志与待办），长期记忆保留。 */
    public void resetSession() {
        journal.clear();
        todo.clear();
    }

    /** 供界面展示的参数摘要（用于确认弹窗）。 */
    public static String summary(ToolCall c) {
        try {
            JSONObject a = c.args;
            if (a == null) return c.name;
            if ("shell".equals(c.name)) return "$ " + c.arg(new String[]{"command", "cmd", "script"}, "");
            if ("write_file".equals(c.name)) {
                String path = c.arg(new String[]{"path", "file", "filename"}, "");
                String content = c.arg(new String[]{"content", "text", "body", "data"}, "");
                return (Lang.zh() ? "写入 " : "write ") + path + "\n\n" + truncate(content, 800);
            }
            if ("delete_file".equals(c.name)) {
                return (Lang.zh() ? "删除 " : "delete ")
                        + c.arg(new String[]{"path", "file", "filename"}, "");
            }
            if ("edit_file".equals(c.name)) {
                return (Lang.zh() ? "改 " : "edit ") + c.arg(new String[]{"path", "file", "filename"}, "")
                        + "\n\n- " + truncate(c.arg(new String[]{"old", "find", "search", "from"}, ""), 300)
                        + "\n+ " + truncate(c.arg(new String[]{"new", "replace", "to"}, ""), 300);
            }
            if ("apply_patch".equals(c.name)) {
                return truncate(c.arg(new String[]{"patch", "diff", "content"}, ""), 800);
            }
            return c.name + " " + a.toString();
        } catch (Exception e) {
            return c.name;
        }
    }

    /** 工具分组标签（界面用）。 */
    public static String groupLabel(String group, boolean zh) {
        switch (group == null ? "" : group) {
            case "net":
                return zh ? "联网" : "Web";
            case "file":
                return zh ? "文件" : "Files";
            case "ext":
                return zh ? "增强" : "Extras";
            default:
                return zh ? "本机" : "Device";
        }
    }

    /** 全部工具的 (名字, 一句话说明) 列表，供设置页展示。 */
    public static List<String[]> catalog(boolean zh) {
        List<String[]> out = new ArrayList<>();
        for (ToolSchemas.Spec s : ToolSchemas.all()) {
            out.add(new String[]{s.name, s.desc(zh), groupLabel(s.group, zh), s.risky ? "1" : "0"});
        }
        return out;
    }

    /** 供宿主统计：当前启用集合的大小。 */
    public static int enabledCount(boolean web, boolean files, boolean shellOn) {
        return enabled(web, files, shellOn).size();
    }
}
