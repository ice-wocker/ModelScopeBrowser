package com.mscope.browser.agent;

import org.json.JSONObject;

import java.io.File;
import java.util.Arrays;
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

    /** 全部工具名（用于校验模型输出，避免把普通 JSON 误判成调用）。 */
    public static Set<String> names() {
        return new HashSet<>(Arrays.asList(
                "shell", "web_search", "fetch_url",
                "read_file", "write_file", "list_files", "delete_file", "open_preview"));
    }

    /** 需要用户确认后才能执行的工具（会改动本机状态）。 */
    public static boolean risky(String name) {
        return "shell".equals(name) || "write_file".equals(name) || "delete_file".equals(name);
    }

    /** 生成给模型看的工具说明（只列出当前启用的）。 */
    public static String describe(boolean web, boolean files, boolean shellOn) {
        StringBuilder sb = new StringBuilder();
        if (shellOn) {
            sb.append("- shell：在手机本机终端执行命令（工作目录 = 工作区）。")
              .append("参数 {\"command\": \"ls -la\"}\n");
        }
        if (web) {
            sb.append("- web_search：联网搜索。参数 {\"query\": \"关键词\"}\n")
              .append("- fetch_url：抓取网页正文。参数 {\"url\": \"https://...\"}\n");
        }
        if (files) {
            sb.append("- list_files：列出工作区目录。参数 {\"path\": \".\"}\n")
              .append("- read_file：读取工作区文件。参数 {\"path\": \"a.txt\"}\n")
              .append("- write_file：写入工作区文件。参数 {\"path\": \"a.txt\", \"content\": \"...\"}\n")
              .append("- delete_file：删除工作区文件。参数 {\"path\": \"a.txt\"}\n")
              .append("- open_preview：在预览页打开工作区 HTML。参数 {\"path\": \"page.html\"}\n");
        }
        return sb.toString();
    }

    /** 执行一次工具调用，返回给模型看的结果文本。 */
    public String exec(ToolCall c) {
        try {
            switch (c.name) {
                case "shell": {
                    String cmd = c.arg(new String[]{"command", "cmd", "script"}, "");
                    if (cmd.isEmpty()) return "错误：缺少参数 command";
                    Shell.Out o = shell.run(cmd);
                    String body = o.text.isEmpty() ? "（无输出）" : o.text;
                    return "$ " + cmd + "\n" + body + (o.code != 0 ? "\n[退出码 " + o.code + "]" : "");
                }
                case "web_search": {
                    String q = c.arg(new String[]{"query", "q", "keywords", "keyword"}, "");
                    if (q.isEmpty()) return "错误：缺少参数 query";
                    List<WebSearch.Result> rs = WebSearch.search(q, 6);
                    if (rs.isEmpty()) return "没有检索到「" + q + "」的结果。";
                    return "搜索「" + q + "」的结果：\n" + WebSearch.format(rs);
                }
                case "fetch_url": {
                    String url = c.arg(new String[]{"url", "link", "href"}, "");
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        return "错误：url 必须是 http/https 开头的完整地址";
                    }
                    return WebSearch.fetchPage(url, MAX_RESULT);
                }
                case "list_files": {
                    String path = c.arg(new String[]{"path", "dir", "directory"}, ".");
                    String rel = Workspace.normalize(path);
                    if (!ws.exists(rel)) return "目录不存在：" + (path.isEmpty() ? "." : path);
                    if (!ws.isDir(rel)) return "这不是目录：" + path;
                    List<Workspace.Entry> es = ws.list(rel);
                    if (es.isEmpty()) return "（空目录）";
                    StringBuilder sb = new StringBuilder();
                    for (Workspace.Entry e : es) {
                        sb.append(e.dir ? "[目录] " : "[文件] ").append(e.relPath)
                          .append(e.dir ? "/" : "  (" + e.size + " 字节)").append('\n');
                    }
                    return sb.toString().trim();
                }
                case "read_file": {
                    String path = c.arg(new String[]{"path", "file", "filename"}, "");
                    if (path.isEmpty()) return "错误：缺少参数 path";
                    File f = ws.resolve(path);
                    if (!f.exists()) return "文件不存在：" + path;
                    if (f.isDirectory()) return "这是一个目录：" + path;
                    return truncate(ws.readText(path), MAX_RESULT);
                }
                case "write_file": {
                    String path = c.arg(new String[]{"path", "file", "filename"}, "");
                    if (path.isEmpty()) return "错误：缺少参数 path";
                    String content = c.arg(new String[]{"content", "text", "body", "data"}, "");
                    ws.writeText(path, content);
                    return "已写入 /" + Workspace.normalize(path)
                            + "（" + content.getBytes().length + " 字节）";
                }
                case "delete_file": {
                    String path = c.arg(new String[]{"path", "file", "filename"}, "");
                    if (path.isEmpty()) return "错误：缺少参数 path";
                    return ws.delete(path) ? "已删除 /" + Workspace.normalize(path) : "不存在：" + path;
                }
                case "open_preview": {
                    String path = c.arg(new String[]{"path", "file", "filename", "url"}, "");
                    if (path.isEmpty()) return "错误：缺少参数 path";
                    String rel = Workspace.normalize(path);
                    if (!ws.exists(rel)) return "文件不存在：" + path;
                    if (host != null) host.openPreview(rel);
                    return "已在预览页打开 /" + rel;
                }
                default:
                    return "未知工具：" + c.name;
            }
        } catch (Exception e) {
            return "执行出错：" + e.getMessage();
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "\n…（内容过长，已截断）";
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
                return "写入 " + path + "\n\n" + truncate(content, 800);
            }
            if ("delete_file".equals(c.name)) {
                return "删除 " + c.arg(new String[]{"path", "file", "filename"}, "");
            }
            return c.name + " " + a.toString();
        } catch (Exception e) {
            return c.name;
        }
    }
}