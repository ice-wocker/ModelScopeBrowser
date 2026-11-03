package com.mscope.browser.agent;

import com.mscope.browser.i18n.Lang;

import org.json.JSONObject;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 智能体可调用的工具集：本机终端（真 shell）、联网搜索、工作区文件、网页预览。
 *
 * <p>每个工具的执行结果都会作为 tool_result 回灌给模型，所以返回值统一是「给模型看的文本」，
 * 出错也返回文本（而不是抛异常），让模型能自行纠错。
 */
public class AgentTools {

    /** 打开网页预览需要界面动作，由宿主回调（在主线程执行）。 */
    public interface Host {
        void openPreview(String relPath);
    }

    /** 单次工具结果最多回灌给模型的字符数，防止把上下文撑爆。 */
    private static final int MAX_RESULT = 6000;

    private final Workspace ws;
    private final Shell shell;
    private final Host host;

    public AgentTools(Workspace ws, Shell shell, Host host) {
        this.ws = ws;
        this.shell = shell;
        this.host = host;
    }

    private static final Set<String> ALL = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "shell", "web_search", "fetch_url",
                    "read_file", "write_file", "list_files", "delete_file", "open_preview")));

    /** 全部工具名（用于校验模型输出，避免把普通 JSON 误判成调用）。 */
    public static Set<String> names() {
        return ALL;
    }

    /**
     * 当前这次对话实际启用的工具名。
     *
     * <p>解析器与提示词必须用同一份清单：以前提示词里写「可用工具」时按开关过滤，
     * 解析时却用全量 {@link #names()}，于是关掉联网后模型仍能调用 {@code web_search}
     * —— 结果是一个卡住的网络请求，用户看到的是「转很久然后失败」。
     */
    public static Set<String> enabled(boolean web, boolean files, boolean shellOn) {
        Set<String> out = new HashSet<>();
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
        }
        return out;
    }

    /** 需要用户确认后才能执行的工具（会改动本机状态）。 */
    public static boolean risky(String name) {
        return "shell".equals(name) || "write_file".equals(name) || "delete_file".equals(name);
    }

    /**
     * 生成给模型看的工具说明（只列出当前启用的）。
     *
     * <p>说明的语言跟随界面语言：用英文界面的人，模型也该用英文思考和回报，
     * 否则「英文界面 + 中文工具说明」会让小模型两头不靠。
     */
    public static String describe(boolean web, boolean files, boolean shellOn) {
        boolean zh = Lang.zh();
        StringBuilder sb = new StringBuilder();
        if (shellOn) {
            sb.append(zh
                    ? "- shell：在手机本机终端执行命令（工作目录 = 工作区）。参数 {\"command\": \"ls -la\"}\n"
                    : "- shell: run a command in the on-device terminal (cwd = workspace). "
                    + "args {\"command\": \"ls -la\"}\n");
        }
        if (web) {
            sb.append(zh
                    ? "- web_search：联网搜索。参数 {\"query\": \"关键词\"}\n"
                    + "- fetch_url：抓取网页正文。参数 {\"url\": \"https://...\"}\n"
                    : "- web_search: search the web. args {\"query\": \"keywords\"}\n"
                    + "- fetch_url: fetch the text of a web page. args {\"url\": \"https://...\"}\n");
        }
        if (files) {
            sb.append(zh
                    ? "- list_files：列出工作区目录。参数 {\"path\": \".\"}\n"
                    + "- read_file：读取工作区文件。参数 {\"path\": \"a.txt\"}\n"
                    + "- write_file：写入工作区文件。参数 {\"path\": \"a.txt\", \"content\": \"...\"}\n"
                    + "- delete_file：删除工作区文件。参数 {\"path\": \"a.txt\"}\n"
                    + "- open_preview：在预览页打开工作区 HTML。参数 {\"path\": \"page.html\"}\n"
                    : "- list_files: list a workspace directory. args {\"path\": \".\"}\n"
                    + "- read_file: read a workspace file. args {\"path\": \"a.txt\"}\n"
                    + "- write_file: write a workspace file. args {\"path\": \"a.txt\", \"content\": \"...\"}\n"
                    + "- delete_file: delete a workspace file. args {\"path\": \"a.txt\"}\n"
                    + "- open_preview: open a workspace HTML file in the preview. args {\"path\": \"page.html\"}\n");
        }
        return sb.toString();
    }

    /** 执行一次工具调用，返回给模型看的结果文本。 */
    public String exec(ToolCall c) {
        boolean zh = Lang.zh();
        try {
            switch (c.name) {
                case "shell": {
                    String cmd = c.arg(new String[]{"command", "cmd", "script"}, "");
                    if (cmd.isEmpty()) return zh ? "错误：缺少参数 command" : "error: missing argument command";
                    Shell.Out o = shell.run(cmd);
                    String body = o.text.isEmpty() ? (zh ? "（无输出）" : "(no output)") : o.text;
                    return "$ " + cmd + "\n" + body
                            + (o.code != 0 ? (zh ? "\n[退出码 " + o.code + "]" : "\n[exit code " + o.code + "]") : "");
                }
                case "web_search": {
                    String q = c.arg(new String[]{"query", "q", "keywords", "keyword"}, "");
                    if (q.isEmpty()) return zh ? "错误：缺少参数 query" : "error: missing argument query";
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
                    if (path.isEmpty()) return zh ? "错误：缺少参数 path" : "error: missing argument path";
                    File f = ws.resolve(path);
                    if (!f.exists()) return (zh ? "文件不存在：" : "file not found: ") + path;
                    if (f.isDirectory()) return (zh ? "这是一个目录：" : "this is a directory: ") + path;
                    return truncate(ws.readText(path), MAX_RESULT);
                }
                case "write_file": {
                    String path = c.arg(new String[]{"path", "file", "filename"}, "");
                    if (path.isEmpty()) return zh ? "错误：缺少参数 path" : "error: missing argument path";
                    String content = c.arg(new String[]{"content", "text", "body", "data"}, "");
                    ws.writeText(path, content);
                    int bytes = content.getBytes().length;
                    return zh
                            ? "已写入 /" + Workspace.normalize(path) + "（" + bytes + " 字节）"
                            : "wrote /" + Workspace.normalize(path) + " (" + bytes + " bytes)";
                }
                case "delete_file": {
                    String path = c.arg(new String[]{"path", "file", "filename"}, "");
                    if (path.isEmpty()) return zh ? "错误：缺少参数 path" : "error: missing argument path";
                    return ws.delete(path)
                            ? (zh ? "已删除 /" : "deleted /") + Workspace.normalize(path)
                            : (zh ? "不存在：" : "not found: ") + path;
                }
                case "open_preview": {
                    String path = c.arg(new String[]{"path", "file", "filename", "url"}, "");
                    if (path.isEmpty()) return zh ? "错误：缺少参数 path" : "error: missing argument path";
                    String rel = Workspace.normalize(path);
                    if (!ws.exists(rel)) return (zh ? "文件不存在：" : "file not found: ") + path;
                    if (host != null) host.openPreview(rel);
                    return zh ? "已在预览页打开 /" + rel : "opened /" + rel + " in the preview";
                }
                default:
                    return (zh ? "未知工具：" : "unknown tool: ") + c.name;
            }
        } catch (Exception e) {
            return (zh ? "执行出错：" : "execution failed: ") + e.getMessage();
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + (Lang.zh() ? "\n…（内容过长，已截断）" : "\n… (truncated: too long)");
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
            return c.name + " " + a.toString();
        } catch (Exception e) {
            return c.name;
        }
    }
}