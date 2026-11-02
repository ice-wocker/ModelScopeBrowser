package com.mscope.browser.agent;

import com.mscope.browser.core.JsonAccess;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.mscope.browser.core.FsKit;

/**
 * 技能库：把「常用的提示词套路」做成可复用、可搜索、可版本化的条目。
 *
 * <p>为什么不是「提示词模板」那种死列表：手机上真正难的是**选择**，不是存储。所以每条技能
 * 都带触发词（triggers），模型只需要给出任务描述，{@code skill_search} 用 BM25 在上面排序，
 * 只把最相关的一条正文塞进上下文。这样 3B 模型也能用上几十条技能，而不会被撑爆。
 *
 * <p>技能是纯文本文件（{@code skills/<id>.md}），第一段是 YAML 风格的元信息。刻意不用
 * JSON：用户要能手改，改完直接生效。
 */
public class SkillLibrary {

    private static final int MAX_BODY = 4000;

    /** 一条技能。 */
    public static final class Skill {
        public String id = "";
        public String name = "";
        public String desc = "";
        public String trigger = "";
        public String body = "";

        public String toMarkdown() {
            return "---\nid: " + id + "\nname: " + name + "\ndesc: " + desc + "\ntrigger: " + trigger
                    + "\n---\n\n" + body + "\n";
        }
    }

    private final Workspace ws;

    public SkillLibrary(Workspace ws) {
        this.ws = ws;
    }

    private File dir() {
        File d = new File(ws.root(), "skills");
        if (!d.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    public static String dirName() {
        return "skills";
    }

    public List<Skill> load() {
        List<Skill> out = new ArrayList<>();
        File[] fs = dir().listFiles();
        if (fs == null) return out;
        java.util.Arrays.sort(fs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File f : fs) {
            if (!f.isFile()) continue;
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (!n.endsWith(".md") && !n.endsWith(".txt")) continue;
            Skill s = parse(FsKit.readText(f, 128 * 1024));
            if (s.id.isEmpty()) s.id = f.getName().replaceAll("\\.[^.]+$", "");
            if (s.name.isEmpty()) s.name = s.id;
            if (s.body.isEmpty()) continue;
            out.add(s);
        }
        return out;
    }

    /** 解析带元信息头的技能文件；没有头也能用（全文当正文，首行当名字）。 */
    public static Skill parse(String text) {
        Skill s = new Skill();
        String t = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
        if (t.trim().isEmpty()) return s;
        String body = t;
        if (t.startsWith("---")) {
            int end = t.indexOf("\n---", 3);
            if (end > 0) {
                String head = t.substring(3, end);
                body = t.substring(t.indexOf('\n', end + 1) + 1);
                for (String line : head.split("\n")) {
                    int c = line.indexOf(':');
                    if (c <= 0) continue;
                    String k = line.substring(0, c).trim().toLowerCase(Locale.ROOT);
                    String v = stripQuotes(line.substring(c + 1).trim());
                    switch (k) {
                        case "id":
                            s.id = v;
                            break;
                        case "name":
                        case "title":
                            s.name = v;
                            break;
                        case "desc":
                        case "description":
                            s.desc = v;
                            break;
                        case "trigger":
                        case "triggers":
                        case "when":
                            s.trigger = v;
                            break;
                        default:
                            break;
                    }
                }
            } else if (t.indexOf("\n---", 3) < 0) {
                // 只有开头那个 --- 没有收尾，当普通正文
                body = t;
            }
        }
        s.body = body.trim();
        if (s.name.isEmpty() && !s.body.isEmpty()) {
            String first = s.body.split("\n", 2)[0].trim();
            if (first.startsWith("#")) first = first.replaceAll("^#+\\s*", "");
            s.name = first.length() > 60 ? first.substring(0, 60) : first;
        }
        if (s.desc.isEmpty()) {
            String firstLine = s.body.isEmpty() ? "" : s.body.split("\n", 2)[0].trim();
            s.desc = firstLine.length() > 120 ? firstLine.substring(0, 120) + "…" : firstLine;
        }
        return s;
    }

    private static String stripQuotes(String v) {
        if (v == null) return "";
        String t = v.trim();
        if (t.length() >= 2) {
            char c = t.charAt(0);
            if ((c == '"' || c == '\'') && t.charAt(t.length() - 1) == c) return t.substring(1, t.length() - 1);
        }
        return t;
    }

    /** 按 id 覆盖写。id 会做安全文件名处理。 */
    public String save(String id, String name, String desc, String trigger, String body, boolean zh) {
        String sid = com.mscope.browser.core.TextKit.safeFileName(id, 60);
        if (sid.isEmpty()) return zh ? "错误：id 不合法" : "error: invalid id";
        Skill s = new Skill();
        s.id = sid;
        s.name = name == null || name.trim().isEmpty() ? sid : name.trim();
        s.desc = desc == null ? "" : desc.trim();
        s.trigger = trigger == null ? "" : trigger.trim();
        s.body = body == null ? "" : body.trim();
        File f = new File(dir(), sid + ".md");
        boolean ok = FsKit.writeAtomic(f, s.toMarkdown());
        return ok
                ? (zh ? "已保存技能 " : "saved skill ") + sid + " → /skills/" + sid + ".md"
                : (zh ? "错误：保存失败" : "error: save failed");
    }

    public boolean remove(String id) {
        String sid = com.mscope.browser.core.TextKit.safeFileName(id, 60);
        if (sid.isEmpty()) return false;
        File f = new File(dir(), sid + ".md");
        return f.isFile() && f.delete();
    }

    /**
     * 关键词检索：triggers 权重最高，其次名字、描述、正文。
     *
     * @return 命中的技能（含正文），最多 limit 条
     */
    public List<Skill> search(String query, int limit) {
        List<Skill> all = load();
        List<Skill> out = new ArrayList<>();
        if (all.isEmpty()) return out;
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            out.addAll(all);
            if (limit > 0 && out.size() > limit) return new ArrayList<>(out.subList(0, limit));
            return out;
        }
        com.mscope.browser.core.Bm25 index = new com.mscope.browser.core.Bm25();
        for (Skill s : all) {
            // 触发词重复三遍 ≈ 权重 3 倍，比在检索器里加权重字段简单且够用
            index.add(s.id, s.trigger + " " + s.trigger + " " + s.trigger + " "
                    + s.name + " " + s.desc + " " + s.body);
        }
        index.index();
        Map<String, Skill> byId = new LinkedHashMap<>();
        for (Skill s : all) byId.put(s.id, s);
        for (com.mscope.browser.core.Bm25.Hit h : index.search(q, limit <= 0 ? 5 : limit)) {
            Skill s = byId.get(h.id);
            if (s != null) out.add(s);
        }
        return out;
    }

    /** 给模型看的检索结果：只给清单，正文等它明确要再用 skill_use 取。 */
    public String renderList(List<Skill> hits, boolean zh) {
        if (hits.isEmpty()) return zh ? "没有匹配的技能。" : "No matching skills.";
        StringBuilder sb = new StringBuilder();
        for (Skill s : hits) {
            sb.append("- ").append(s.id).append(" · ").append(s.name).append(" —— ").append(s.desc).append('\n');
        }
        sb.append(zh ? "用 skill_use 取某条的完整步骤。" : "Use skill_use to load the full steps.");
        return sb.toString();
    }

    /** 取出一条技能的正文（截断到上限，防止一条技能吃掉半个上下文）。 */
    public String renderBody(Skill s, boolean zh) {
        if (s == null) return zh ? "没有这条技能。" : "No such skill.";
        String body = s.body;
        if (body.length() > MAX_BODY) body = body.substring(0, MAX_BODY) + "\n…";
        return "# " + s.name + "\n\n" + body;
    }

    /** 内置技能：首次使用时铺到 skills/ 下，用户可以改、可以删。 */
    public int seedBuiltins(boolean zh) {
        int created = 0;
        for (Skill s : builtins(zh)) {
            File f = new File(dir(), s.id + ".md");
            if (f.isFile()) continue;
            if (FsKit.writeAtomic(f, s.toMarkdown())) created++;
        }
        return created;
    }

    /** 内置技能清单（中英各一套，内容是实用套路，不是占位）。 */
    public static List<Skill> builtins(boolean zh) {
        List<Skill> out = new ArrayList<>();
        out.add(skill("summarize", zh ? "长文摘要" : "Long-text summary",
                zh ? "把长文压缩成要点，保留数字与结论" : "Compress long text into points, keep numbers and conclusions",
                zh ? "总结 摘要 提炼 太长 summary tl;dr" : "summarize summary tl;dr condense",
                zh ? "步骤：\n1. 先通读，划出结论句与数字。\n2. 输出 3–7 条要点，每条不超过 30 字。\n"
                        + "3. 数字、日期、专有名词原样保留，不要改写。\n4. 最后一行写「一句话结论」。"
                        : "Steps:\n1. Read through, mark conclusion sentences and numbers.\n"
                        + "2. Output 3-7 bullets, each under 20 words.\n3. Keep numbers, dates and names verbatim.\n"
                        + "4. End with a one-line takeaway."));
        out.add(skill("code-review", zh ? "代码评审" : "Code review",
                zh ? "按事实性错误优先的顺序评审一段代码" : "Review code, facts before style",
                zh ? "评审 代码 review 检查 bug" : "review code audit bug review",
                zh ? "只报真问题，顺序：\n1. 会崩/会丢数据（越界、空指针、未关闭资源）。\n"
                        + "2. 并发下会错（竞态、可见性、重复释放）。\n3. 逻辑与需求不符。\n"
                        + "4. 其余一律不提，不做风格建议。每条给出文件行号与修法。"
                        : "Report real problems only, in this order:\n1. Crashes / data loss (bounds, null, leaked resources).\n"
                        + "2. Breaks under concurrency (races, visibility, double free).\n3. Logic that contradicts the goal.\n"
                        + "4. Nothing else - no style notes. Give line numbers and a fix for each."));
        out.add(skill("debug", zh ? "故障定位" : "Debugging",
                zh ? "从报错出发，逐层缩小范围" : "Shrink the search space from the error outward",
                zh ? "报错 崩溃 排查 bug 不工作 error crash" : "error crash broken fails debug",
                zh ? "1. 先复述报错原文，不要转述。\n2. 列 3 个最可能的成因，按可能性排序。\n"
                        + "3. 对每个成因给一个「一次就能证伪」的检查（命令或日志）。\n4. 只改已验证的那一处。"
                        : "1. Quote the error verbatim.\n2. List 3 likely causes, most likely first.\n"
                        + "3. For each, give one check (command or log) that can rule it out.\n"
                        + "4. Change only the verified one."));
        out.add(skill("webpage", zh ? "单文件网页" : "Single-file page",
                zh ? "生成不依赖外部资源、可离线打开的 HTML" : "Produce an offline-openable HTML with no external assets",
                zh ? "网页 html 页面 做个小工具" : "webpage html page build ui",
                zh ? "要求：\n1. 单文件，CSS/JS 内联，不引用 CDN（离线环境会白屏）。\n"
                        + "2. 移动端优先，viewport 完整，触控区域 ≥ 44px。\n3. 深色模式用 prefers-color-scheme。\n"
                        + "4. 用 ```html filename=xxx.html 输出，方便直接保存。"
                        : "Rules:\n1. Single file, inline CSS/JS, no CDN (offline = blank page).\n"
                        + "2. Mobile first, full viewport, tap targets >= 44px.\n3. Dark mode via prefers-color-scheme.\n"
                        + "4. Emit ```html filename=xxx.html so it can be saved directly."));
        out.add(skill("translate", zh ? "翻译校对" : "Translation",
                zh ? "技术文案翻译，术语一致、不改数字" : "Translate technical text, consistent terms, numbers untouched",
                zh ? "翻译 中译英 translate" : "translate english chinese localization",
                zh ? "1. 先列术语表（原文→译文），全篇统一。\n2. 数字、单位、代码、命令、路径一律不动。\n"
                        + "3. 不增不删，不加解释。\n4. 中文避免长定语从句，英文避免中式表达。"
                        : "1. Build a term list first, use it consistently.\n2. Never touch numbers, units, code, commands or paths.\n"
                        + "3. Add nothing, drop nothing, no commentary.\n4. Keep sentences short in both directions."));
        out.add(skill("refactor", zh ? "小步重构" : "Small-step refactor",
                zh ? "可回滚的分步重构，每步都能跑" : "Refactor in revertible steps that always compile",
                zh ? "重构 拆分 重构代码 refactor" : "refactor split cleanup restructure",
                zh ? "1. 先补一个能证明行为不变的检查（测试或对比输出）。\n2. 一次只搬一块，搬完就编译。\n"
                        + "3. 不顺手改逻辑、不改格式（diff 里只应有搬动）。\n4. 最后删旧代码，跑一遍检查。"
                        : "1. Add a check that proves behaviour is unchanged.\n2. Move one piece at a time and compile after each.\n"
                        + "3. Do not also change logic or formatting (the diff shows moves only).\n"
                        + "4. Delete the old code last, then run the check."));
        out.add(skill("shell-safe", zh ? "安全命令" : "Safe commands",
                zh ? "在本机执行命令前先自查破坏面" : "Check blast radius before running a command",
                zh ? "命令 终端 删除 shell rm" : "shell terminal command delete rm",
                zh ? "执行前自查：\n1. 目标是相对路径吗？（绝对路径会被沙箱拒绝）\n"
                        + "2. 这条命令最坏会删掉什么？删的是工作区内的东西吗？\n3. 有 -rf、通配符、重定向吗？先用 ls 看一眼。\n"
                        + "4. 输出可能很长时加 | head -50。"
                        : "Before running:\n1. Is the path relative? (absolute paths are rejected by the sandbox)\n"
                        + "2. What is the worst case of this command? Is it inside the workspace?\n"
                        + "3. Recursive delete, glob or redirect? List the target with ls first.\n"
                        + "4. Cap long output with | head -50."));
        return out;
    }

    private static Skill skill(String id, String name, String desc, String trigger, String body) {
        Skill s = new Skill();
        s.id = id;
        s.name = name;
        s.desc = desc;
        s.trigger = trigger;
        s.body = body;
        return s;
    }

    /** 导出成 JSON，供界面列表。 */
    public String toJson() {
        JSONArray arr = new JSONArray();
        for (Skill s : load()) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", s.id);
                o.put("name", s.name);
                o.put("desc", s.desc);
            } catch (Exception ignored) {
                continue;
            }
            arr.put(o);
        }
        return arr.toString();
    }

    /** 供测试使用的路径不存在时也能解析（不需要真写文件）。 */
    public static Skill parseOrNull(String text) {
        Skill s = parse(text);
        return s.body.isEmpty() ? null : s;
    }

    /** 从 JSON 反序列化（界面用）。 */
    public static List<Skill> fromJson(String json) {
        List<Skill> out = new ArrayList<>();
        Object root = JsonAccess.parse(json);
        if (!(root instanceof JSONArray)) return out;
        JSONArray arr = (JSONArray) root;
        for (int i = 0; i < arr.length(); i++) {
            Object e = arr.opt(i);
            if (!(e instanceof JSONObject)) continue;
            Skill s = new Skill();
            s.id = JsonAccess.str(e, "id", "");
            s.name = JsonAccess.str(e, "name", s.id);
            s.desc = JsonAccess.str(e, "desc", "");
            out.add(s);
        }
        return out;
    }
}
