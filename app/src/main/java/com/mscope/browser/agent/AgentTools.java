package com.mscope.browser.agent;

import android.content.Context;

import com.mscope.browser.core.DigestKit;
import com.mscope.browser.core.DocConverter;
import com.mscope.browser.core.TextKit;
import com.mscope.browser.i18n.Lang;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 智能体可调用的工具集。
 *
 * <p>结构：<b>一张表</b>（{@link ToolRegistry}）描述全部工具，<b>一个 switch</b> 分发执行。
 * 之前工具说明、白名单、风险判定是三份手写清单，加一个工具要改三处，漏一处就会出现
 * 「能调用但模型不知道」或者「危险动作没确认」。现在三者都从注册表派生。
 *
 * <p>每个工具的执行结果都会作为 tool_result 回灌给模型，所以返回值统一是「给模型看的文本」，
 * 出错也返回文本（而不是抛异常），让模型能自行纠错 —— 小模型看到明确的错误会重试，
 * 看到 Java 堆栈只会放弃。
 *
 * <p>需要界面权限的动作（通知、剪贴板、分享、预览）走 {@link ToolHost}，因此本类主体
 * 仍然可以在没有 Activity 的情况下工作（后台导出、单测）。
 */
public class AgentTools {

    /**
     * 宿主的旧名字。保留它是为了让「只关心预览」的调用点（{@code rel -> …}）不必写六个方法；
     * 新的调用点直接用 {@link ToolHost}。
     */
    public interface Host extends ToolHost {
    }

    /** 单次工具结果最多回灌给模型的字符数，防止把上下文撑爆。 */
    private static final int MAX_RESULT = 6000;

    private final Workspace ws;
    private final Shell shell;
    private final ToolHost host;
    private final WorkspaceTools wt;
    private final TodoBoard todo;
    private final MemoryStore memory;
    private final SkillLibrary skills;
    private final ExternalModel external;
    private final Context appCtx;
    /** 本会话开了哪些工具组。 */
    private final Set<ToolSpec.Group> enabled;

    /** 只给预览回调的便捷构造（后台导出、测试常用）。 */
    public AgentTools(Workspace ws, Shell shell, ToolHost host) {
        this(ws, shell, host, null, null, EnumSet.of(
                ToolSpec.Group.FILE, ToolSpec.Group.TEXT, ToolSpec.Group.CALC,
                ToolSpec.Group.AGENT, ToolSpec.Group.DEVICE, ToolSpec.Group.WEB));
    }

    public AgentTools(Workspace ws, Shell shell, ToolHost host, Context ctx, ExternalModel external,
                      Set<ToolSpec.Group> enabled) {
        this.ws = ws;
        this.shell = shell;
        this.host = host != null ? host : ToolHost.NOOP;
        this.wt = new WorkspaceTools(ws);
        this.todo = new TodoBoard(ws);
        this.memory = new MemoryStore(ws);
        this.skills = new SkillLibrary(ws);
        this.external = external;
        this.appCtx = ctx;
        this.enabled = enabled == null ? EnumSet.noneOf(ToolSpec.Group.class) : enabled;
    }

    /** 全部工具名（用于校验模型输出，避免把普通 JSON 误判成调用）。 */
    public static Set<String> names() {
        return ToolRegistry.names();
    }

    /** 需要用户确认后才能执行的工具（会改动本机状态）。 */
    public static boolean risky(String name) {
        return ToolRegistry.risky(name);
    }

    /** 兼容旧签名：全部工具组、完整说明。 */
    public static String describe(boolean web, boolean files, boolean shellOn) {
        Set<ToolSpec.Group> g = EnumSet.of(ToolSpec.Group.FILE, ToolSpec.Group.TEXT,
                ToolSpec.Group.CALC, ToolSpec.Group.AGENT);
        if (web) g.add(ToolSpec.Group.WEB);
        if (shellOn) g.add(ToolSpec.Group.SHELL);
        if (files) g.add(ToolSpec.Group.DEVICE);
        return ToolRegistry.describe(g, false, Lang.zh());
    }

    /** 按当前开关生成说明。 */
    public String describeEnabled(boolean compact) {
        return ToolRegistry.describe(enabled, compact, Lang.zh());
    }

    /** 当前开启的工具组。 */
    public Set<ToolSpec.Group> enabledGroups() {
        return enabled;
    }

    public TodoBoard todo() {
        return todo;
    }

    public MemoryStore memory() {
        return memory;
    }

    public SkillLibrary skills() {
        return skills;
    }

    /** 当前语言对应的工具个数（状态条用）。 */
    public String summary() {
        return ToolRegistry.summary(enabled, Lang.zh());
    }

    /* ---------------------------------------------------------------- 执行 */

    /** 执行一次工具调用，返回给模型看的结果文本。 */
    public String exec(ToolCall c) {
        boolean zh = Lang.zh();
        if (c == null || c.name == null || c.name.isEmpty()) {
            return zh ? "错误：这不是一次有效的工具调用" : "error: not a valid tool call";
        }
        ToolSpec spec = ToolRegistry.get(c.name);
        if (spec == null) {
            return (zh ? "未知工具：" : "unknown tool: ") + c.name;
        }
        if (!enabled.contains(spec.group)) {
            return (zh ? "该工具未启用：" : "tool not enabled: ") + c.name
                    + (zh ? "（可在智能体能力里打开）" : " (enable it in Agent capabilities)");
        }
        try {
            return dispatch(c, spec, zh);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return (zh ? "执行出错：" : "execution failed: ") + msg;
        }
    }

    private String dispatch(ToolCall c, ToolSpec spec, boolean zh) throws Exception {
        switch (c.name) {
            // -------------------------------------------------------- 文件
            case "list_files":
                return wt.listFiles(arg(c, "path", "dir", "directory"));
            case "read_file":
                return wt.readFile(arg(c, "path", "file", "filename"));
            case "write_file": {
                String path = arg(c, "path", "file", "filename");
                String content = arg(c, "content", "text", "body", "data");
                return wt.writeFile(path, content);
            }
            case "append_file":
                return wt.appendFile(arg(c, "path", "file", "filename"), arg(c, "content", "text", "body"));
            case "apply_patch":
                return wt.applyPatch(arg(c, "path", "file", "filename"), patchHunks(c));
            case "delete_file":
                return wt.deleteFile(arg(c, "path", "file", "filename"));
            case "move_file":
                return wt.moveFile(arg(c, "from", "src", "source", "path"),
                        arg(c, "to", "dst", "target", "dest"));
            case "copy_file":
                return wt.copyFile(arg(c, "from", "src", "source", "path"),
                        arg(c, "to", "dst", "target", "dest"));
            case "mkdir":
                return wt.mkdir(arg(c, "path", "dir"));
            case "file_stat":
                return wt.fileStat(arg(c, "path", "file", "filename"));
            case "file_tree":
                return wt.fileTree(arg(c, "path", "dir"), intArg(c, 3, "depth", "level", "max"));
            case "grep":
                return wt.grep(arg(c, "pattern", "regex", "query", "q"), arg(c, "path", "dir", "file"));
            case "find_files":
                return wt.findFiles(arg(c, "pattern", "name", "glob", "query"));
            case "checksum":
                return wt.checksum(arg(c, "path", "file", "filename"), arg(c, "algo", "algorithm", "hash"));
            case "zip":
                return wt.zip(arg(c, "path", "dir"), arg(c, "output", "to", "out"));
            case "unzip":
                return wt.unzip(arg(c, "path", "file", "zip"), arg(c, "output", "to", "out"));

            // -------------------------------------------------------- 文本
            case "replace_text":
                return wt.replaceText(arg(c, "text", "input"),
                        arg(c, "pattern", "regex", "find"), arg(c, "replacement", "replace", "with"));
            case "extract_links":
                return wt.extract(arg(c, "text", "input"), "link", zh);
            case "extract_emails":
                return wt.extract(arg(c, "text", "input"), "email", zh);
            case "extract_code":
                return wt.extract(arg(c, "text", "input"), "code", zh);
            case "convert_table":
                return wt.convertTable(arg(c, "text", "input", "csv"), arg(c, "to", "target", "format"));
            case "text_stats":
                return wt.textStats(arg(c, "text", "input"));
            case "diff":
                return wt.diffText(arg(c, "before", "a", "old"), arg(c, "after", "b", "new"));
            case "redact": {
                String text = arg(c, "text", "input");
                SecureRedactor.Result r = SecureRedactor.redact(text);
                return r.changed() ? r.text + "\n\n" + SecureRedactor.describe(r, zh) : text;
            }
            case "outline":
                return wt.outline(arg(c, "text", "input"));
            case "summarize_text":
                return wt.summarize(arg(c, "text", "input"), intArg(c, 5, "sentences", "count", "n"));
            case "json_query":
                return wt.jsonQuery(arg(c, "json", "text", "input"), arg(c, "path", "key", "pointer"));
            case "json_format":
                return wt.jsonFormat(arg(c, "json", "text", "input"));

            // -------------------------------------------------------- 计算与时间
            case "calc":
                return CalcTools.calc(arg(c, "expr", "expression", "e", "query"));
            case "now":
                return CalcTools.now(arg(c, "tz", "timezone"));
            case "date_math":
                return CalcTools.dateMath(arg(c, "base", "date", "from"), arg(c, "delta", "add", "duration"));
            case "random":
                return CalcTools.random(arg(c, "kind", "type"), arg(c, "a", "min", "list"),
                        arg(c, "b", "max"));
            case "hash_text":
                return CalcTools.hashText(arg(c, "text", "input"), arg(c, "algo", "algorithm"));
            case "base64":
                return CalcTools.base64(arg(c, "text", "input"), arg(c, "mode", "action"));
            case "url_codec":
                return CalcTools.urlCodec(arg(c, "text", "input"), arg(c, "mode", "action"));
            case "unit_convert":
                return CalcTools.unitConvert(arg(c, "value", "v", "a"), arg(c, "from", "src", "unit"),
                        arg(c, "to", "dst", "target"));

            // -------------------------------------------------------- 设备
            case "device_info":
                return appCtx == null
                        ? (zh ? "当前没有可用的设备上下文" : "no device context available")
                        : DeviceInfo.describe(appCtx, zh) + "\n" + DeviceInfo.advice(zh);
            case "storage_report":
                return appCtx == null
                        ? (zh ? "当前没有可用的设备上下文" : "no device context available")
                        : DeviceInfo.storageReport(appCtx, ws, zh);
            case "notify": {
                boolean ok = host.notify(arg(c, "title", "head"), arg(c, "text", "body", "message"));
                return ok ? (zh ? "已发送通知" : "notification posted")
                        : (zh ? "通知未发出（可能未授权通知权限）" : "notification not posted (permission?)");
            }
            case "clipboard": {
                String action = arg(c, "action", "mode").toLowerCase(java.util.Locale.ROOT);
                if (action.startsWith("read") || action.startsWith("get") || action.isEmpty()) {
                    String t = host.getClipboard();
                    return t.isEmpty() ? (zh ? "剪贴板是空的" : "clipboard is empty") : truncate(t, MAX_RESULT);
                }
                return host.setClipboard(arg(c, "text", "content", "value"))
                        ? (zh ? "已写入剪贴板" : "clipboard updated")
                        : (zh ? "写入剪贴板失败" : "clipboard write failed");
            }
            case "share": {
                String rel = Workspace.normalize(arg(c, "path", "file", "filename"));
                File f = ws.resolve(rel);
                if (f == null || !f.isFile()) return (zh ? "文件不存在：" : "file not found: ") + rel;
                boolean ok = host.shareFile(f, mimeOf(f));
                return ok ? (zh ? "已打开分享面板" : "share sheet opened")
                        : (zh ? "无法分享（没有可用的分享目标）" : "cannot share (no target)");
            }
            case "open_url": {
                String url = arg(c, "url", "link");
                if (!WebSearch.allowedForFetch(url)) {
                    return zh ? "错误：只允许打开公网 http/https 链接" : "error: only public http/https links";
                }
                return host.openUrl(url) ? (zh ? "已用浏览器打开" : "opened in the browser")
                        : (zh ? "没有可用的浏览器" : "no browser available");
            }
            case "open_preview": {
                String rel = Workspace.normalize(arg(c, "path", "file", "filename", "url"));
                if (!ws.exists(rel)) return (zh ? "文件不存在：" : "file not found: ") + rel;
                host.openPreview(rel);
                return zh ? "已在预览页打开 /" + rel : "opened /" + rel + " in the preview";
            }

            // -------------------------------------------------------- 终端
            case "shell": {
                String cmd = arg(c, "command", "cmd", "script");
                if (cmd.isEmpty()) return zh ? "错误：缺少参数 command" : "error: missing argument command";
                PathGuard.Verdict v = PathGuard.check(cmd);
                if (!v.allowed) {
                    return (zh ? "已阻止：" : "blocked: ") + v.reason;
                }
                String outside = PathGuard.offendingPath(cmd, ws.root());
                if (outside != null) {
                    return (zh ? "错误：命令里出现了工作区外的绝对路径 " : "error: absolute path outside the workspace: ")
                            + outside;
                }
                Shell.Out o = shell.run(cmd);
                String body = o.text.isEmpty() ? (zh ? "（无输出）" : "(no output)") : o.text;
                return "$ " + cmd + "\n" + body
                        + (o.code != 0 ? (zh ? "\n[退出码 " + o.code + "]" : "\n[exit code " + o.code + "]") : "");
            }

            // -------------------------------------------------------- 联网
            case "web_search": {
                String q = arg(c, "query", "q", "keywords", "keyword");
                if (q.isEmpty()) return zh ? "错误：缺少参数 query" : "error: missing argument query";
                List<WebSearch.Result> rs = WebSearch.search(q, 6);
                if (rs.isEmpty()) {
                    return zh ? "没有检索到「" + q + "」的结果。" : "No results found for \"" + q + "\".";
                }
                return (zh ? "搜索「" + q + "」的结果：\n" : "Results for \"" + q + "\":\n")
                        + WebSearch.format(rs);
            }
            case "fetch_url": {
                String url = arg(c, "url", "link", "href");
                if (!WebSearch.allowedForFetch(url)) {
                    return zh
                            ? "错误：只允许抓取公网 http/https 地址（本机与内网地址已拒绝）"
                            : "error: only public http/https URLs are allowed "
                            + "(loopback and private-network addresses are rejected)";
                }
                return WebSearch.fetchPage(url, MAX_RESULT);
            }
            case "download_file": {
                String url = arg(c, "url", "link", "href");
                if (!WebSearch.allowedForFetch(url)) {
                    return zh ? "错误：只允许下载公网地址" : "error: only public URLs are allowed";
                }
                String name = arg(c, "path", "file", "filename");
                if (name.isEmpty()) {
                    name = fileNameOf(url);
                }
                String rel = Workspace.normalize(name);
                File dest = ws.resolve(rel);
                if (dest == null) return zh ? "错误：路径越界" : "error: path escapes the workspace";
                long n = Http.download(url, dest, 512L * 1024 * 1024);
                if (n < 0) return zh ? "下载失败（网络或体积超限）" : "download failed (network or size limit)";
                return (zh ? "已下载 " : "downloaded ") + DocConverter.compactSize(n)
                        + (zh ? " → /" : " → /") + rel;
            }
            case "http_request": {
                String url = arg(c, "url", "link");
                String method = arg(c, "method", "verb");
                String body = arg(c, "body", "data", "json");
                Map<String, String> headers = new LinkedHashMap<>();
                String token = arg(c, "token", "bearer");
                if (!token.isEmpty()) headers.put("Authorization", "Bearer " + token);
                String ct = arg(c, "content_type", "type");
                Http.Resp r = Http.request(method.isEmpty() ? "GET" : method, url,
                        ct.isEmpty() ? "application/json" : ct, body, headers, 1024 * 1024);
                return Http.describe(r, MAX_RESULT, zh);
            }

            // -------------------------------------------------------- 智能体自身
            case "memory_add": {
                String t = arg(c, "text", "content", "value", "note");
                if (t.isEmpty()) return zh ? "错误：缺少参数 text" : "error: missing argument text";
                return memory.add(t)
                        ? (zh ? "已记住：" : "remembered: ") + t
                        : (zh ? "已存在或为空，未重复记录：" : "already remembered or empty: ") + t;
            }
            case "memory_list": {
                String s = memory.render(zh);
                return s.isEmpty() ? (zh ? "还没有记住任何偏好。" : "nothing remembered yet.") : s;
            }
            case "memory_forget": {
                int idx = intArg(c, 0, "index", "i", "n");
                return memory.remove(idx)
                        ? (idx <= 0 ? (zh ? "已清空全部偏好" : "cleared all preferences")
                                    : (zh ? "已忘掉第 " : "forgot item #") + idx)
                        : (zh ? "没有这一条" : "no such item");
            }
            case "todo_add": {
                String t = arg(c, "title", "text", "item");
                return todo.add(t) ? todo.render(zh)
                        : (zh ? "错误：待办为空或已达上限" : "error: empty title or board full");
            }
            case "todo_done":
                return todo.setStatus(intArg(c, 0, "index", "i", "n"),
                        arg(c, "status", "state"), zh);
            case "todo_list": {
                String s = todo.render(zh);
                return s.isEmpty() ? (zh ? "待办板为空。" : "the todo board is empty.") : s;
            }
            case "todo_clear":
                return todo.clear() ? (zh ? "待办板已清空" : "todo board cleared")
                        : (zh ? "清空失败" : "clear failed");
            case "skill_search": {
                String q = arg(c, "query", "q", "keywords");
                skills.seedBuiltins(zh);
                List<SkillLibrary.Skill> hits = skills.search(q, 5);
                return skills.renderList(hits, zh);
            }
            case "skill_use": {
                String id = arg(c, "id", "name", "skill");
                List<SkillLibrary.Skill> all = skills.load();
                for (SkillLibrary.Skill s : all) {
                    if (s.id.equalsIgnoreCase(id) || s.name.equalsIgnoreCase(id)) return skills.renderBody(s, zh);
                }
                return (zh ? "没有这条技能：" : "no such skill: ") + id;
            }
            case "skill_save": {
                String id = arg(c, "id", "name");
                if (id.isEmpty()) id = "skill-" + TextKit.shortHash(arg(c, "body", "text"), 6);
                return skills.save(id, arg(c, "name", "title"), arg(c, "desc", "description"),
                        arg(c, "trigger", "triggers", "when"), arg(c, "body", "text", "content"), zh);
            }

            // -------------------------------------------------------- 外部模型
            case "llm_ask": {
                if (external == null || !external.usable()) {
                    return zh ? "外部模型未配置。设置 → 外部模型里填基地址与模型名后可用。"
                            : "external model not configured. Set it in Settings → External model.";
                }
                String prompt = arg(c, "prompt", "query", "question", "text");
                if (prompt.isEmpty()) return zh ? "错误：缺少参数 prompt" : "error: missing argument prompt";
                ExternalModel.Answer a = external.ask(prompt, arg(c, "system", "role"),
                        intArg(c, 0, "max_tokens", "tokens"));
                if (!a.ok) return (zh ? "外部模型调用失败：" : "external model failed: ") + a.error;
                String usage = a.completionTokens > 0
                        ? (zh ? "\n\n[外部模型 token：" : "\n\n[external tokens: ") + a.promptTokens + "+"
                          + a.completionTokens + "]"
                        : "";
                return a.text + usage;
            }

            default:
                return (zh ? "未知工具：" : "unknown tool: ") + c.name;
        }
    }

    private List<PatchKit.Hunk> patchHunks(ToolCall c) {
        // 支持三种写法：hunks 数组、patch 文本、find/replace 顶层键
        Object raw = c.args == null ? null : c.args.opt("hunks");
        List<PatchKit.Hunk> hs = PatchKit.parseHunks(raw);
        if (!hs.isEmpty()) return hs;
        String patch = arg(c, "patch", "diff");
        if (PatchKit.looksLikePatch(patch)) return PatchKit.parsePatchText(patch);
        String find = arg(c, "find", "old", "search");
        if (!find.isEmpty()) {
            hs = new ArrayList<>();
            hs.add(new PatchKit.Hunk(find, arg(c, "replace", "new", "with")));
        }
        return hs;
    }

    private static String fileNameOf(String url) {
        try {
            String path = new java.net.URL(url).getPath();
            String name = path.substring(path.lastIndexOf('/') + 1);
            String safe = TextKit.safeFileName(name, 100);
            return safe.isEmpty() ? "download-" + System.currentTimeMillis() + ".bin" : safe;
        } catch (Exception e) {
            return "download-" + System.currentTimeMillis() + ".bin";
        }
    }

    private static String mimeOf(File f) {
        String n = f.getName().toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html";
        if (n.endsWith(".txt") || n.endsWith(".md")) return "text/plain";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".zip")) return "application/zip";
        return "*/*";
    }

    /**
     * 截断给模型看的结果。
     *
     * <p>必须按码点截断：直接 {@code substring(max)} 会把 emoji / 生僻字切成半个
     * surrogate pair，拼进 prompt 后就是乱码甚至让部分模型的分词器直接报错。</p>
     */
    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return TextKit.trimUtf8(s, max)
                + (Lang.zh() ? "\n…（内容过长，已截断）" : "\n… (truncated: too long)");
    }

    private static String arg(ToolCall c, String... names) {
        if (c == null) return "";
        String v = c.arg(names, "");
        return v == null ? "" : v;
    }

    private static int intArg(ToolCall c, int def, String... names) {
        String v = arg(c, names);
        if (v.isEmpty()) return def;
        try {
            return (int) Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 供界面展示的参数摘要（用于确认弹窗）。 */
    public static String summary(ToolCall c) {
        boolean zh = Lang.zh();
        try {
            JSONObject a = c.args;
            if (a == null) return c.name;
            if ("shell".equals(c.name)) {
                return "$ " + c.arg(new String[]{"command", "cmd", "script"}, "");
            }
            if ("write_file".equals(c.name) || "append_file".equals(c.name)) {
                String path = c.arg(new String[]{"path", "file", "filename"}, "");
                String content = c.arg(new String[]{"content", "text", "body", "data"}, "");
                return (zh ? "写入 " : "write ") + path + "\n\n" + truncate(content, 800);
            }
            if ("apply_patch".equals(c.name)) {
                String path = c.arg(new String[]{"path", "file", "filename"}, "");
                Object raw = a.opt("hunks");
                int n = PatchKit.parseHunks(raw).size();
                String patch = c.arg(new String[]{"patch", "diff"}, "");
                if (n == 0 && PatchKit.looksLikePatch(patch)) n = PatchKit.parsePatchText(patch).size();
                return (zh ? "修改 " : "patch ") + path + " · " + n + (zh ? " 处片段" : " hunk(s)");
            }
            if ("delete_file".equals(c.name)) {
                return (zh ? "删除 " : "delete ") + c.arg(new String[]{"path", "file", "filename"}, "");
            }
            if ("move_file".equals(c.name) || "copy_file".equals(c.name)) {
                return c.name + ": " + c.arg(new String[]{"from", "src", "source", "path"}, "")
                        + " → " + c.arg(new String[]{"to", "dst", "target", "dest"}, "");
            }
            if ("http_request".equals(c.name) || "download_file".equals(c.name)) {
                String url = c.arg(new String[]{"url", "link", "href"}, "");
                String method = c.arg(new String[]{"method", "verb"}, "GET");
                return method + " " + url;
            }
            if ("notify".equals(c.name)) {
                return c.arg(new String[]{"title", "head"}, "") + " · " + c.arg(new String[]{"text", "body"}, "");
            }
            if ("llm_ask".equals(c.name)) {
                return truncate(c.arg(new String[]{"prompt", "query", "question", "text"}, ""), 500);
            }
            String digest = DigestKit.md5(a.toString());
            return c.name + " " + truncate(a.toString(), 400) + (zh ? "\n[" : "\n[")
                    + (zh ? "参数摘要 " : "args digest ") + digest.substring(0, 8) + "]";
        } catch (Exception e) {
            return c.name;
        }
    }

    /** 工具调用卡片的分类（决定配色与图标）。 */
    public static String kindOf(String tool) {
        ToolSpec s = ToolRegistry.get(tool);
        if (s == null) return "info";
        switch (s.group) {
            case SHELL:
                return "shell";
            case WEB:
                return "search";
            case FILE:
            case TEXT:
                return "file";
            case DEVICE:
                return "device";
            case MODEL:
                return "model";
            default:
                return "info";
        }
    }

    /** 从工具结果的 JSON 里提取可点击的路径（预览/分享入口）。 */
    public static List<String> pathsIn(String result) {
        List<String> out = new ArrayList<>();
        if (result == null) return out;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("/([A-Za-z0-9_\\-./\\u4e00-\\u9fff]+\\.(?:html|htm|md|txt|json|csv|zip))")
                .matcher(result);
        while (m.find() && out.size() < 8) {
            String p = m.group(1);
            if (!out.contains(p)) out.add(p);
        }
        return out;
    }

    /** 供设置页读取的 JSON 形式的能力开关。 */
    public static Map<String, Boolean> groupSwitches(boolean web, boolean files, boolean shellOn) {
        Map<String, Boolean> m = new LinkedHashMap<>();
        m.put(ToolSpec.Group.FILE.name(), files);
        m.put(ToolSpec.Group.TEXT.name(), files);
        m.put(ToolSpec.Group.CALC.name(), true);
        m.put(ToolSpec.Group.AGENT.name(), true);
        m.put(ToolSpec.Group.DEVICE.name(), files);
        m.put(ToolSpec.Group.WEB.name(), web);
        m.put(ToolSpec.Group.SHELL.name(), shellOn);
        return m;
    }

    /** 把开关表还原成工具组集合。 */
    public static Set<ToolSpec.Group> groupsFrom(Map<String, Boolean> switches) {
        Set<ToolSpec.Group> out = EnumSet.noneOf(ToolSpec.Group.class);
        if (switches == null) return out;
        for (Map.Entry<String, Boolean> e : switches.entrySet()) {
            if (e.getValue() == null || !e.getValue()) continue;
            try {
                out.add(ToolSpec.Group.valueOf(e.getKey()));
            } catch (IllegalArgumentException ignored) {
                // 旧版本留下的键名，忽略
            }
        }
        return out;
    }

}
