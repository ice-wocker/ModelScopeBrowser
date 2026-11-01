package com.mscope.browser.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 系统提示词的模板与拼装。
 *
 * <p>以前提示词散落在 ChatActivity 的字符串常量里，模型一换就得改代码；这里把
 * 「角色 + 工具说明 + 输出约定」拆成可组合的片段，并给出一个针对小模型的压缩版：
 * 参数量越小，越容易被长提示词带跑偏，也越容易被工具格式绕晕。
 *
 * <p>纯字符串处理，无 Android 依赖，可单测。
 */
public final class PromptTemplate {

    /** 预设模板。 */
    public enum Preset {
        /** 通用中文助手，偏简洁 */
        GENERAL,
        /** 智能体：鼓励动手调工具 */
        AGENT,
        /** 代码：要求带文件名的代码块，便于自动落盘 */
        CODER,
        /** 极简：给 0.5B~2B 的小模型用，尽量短 */
        MINIMAL
    }

    private PromptTemplate() {
    }

    /** 模板正文（不含工具清单）。 */
    public static String body(Preset preset) {
        switch (preset == null ? Preset.GENERAL : preset) {
            case AGENT:
                return "你是一个运行在手机本地的中文 AI 助手，可以调用工具在本机上完成实际任务。"
                        + "请用简体中文准确、简洁地回答；需要动手时先调用工具，再根据真实结果作答。";
            case CODER:
                return "你是一个运行在手机本地的编程助手，请用简体中文简要点评，代码放在带文件名的 "
                        + "Markdown 代码块里输出，例如 ```html filename=index.html，这样会自动保存到工作区。";
            case MINIMAL:
                return "你是手机本地助手。中文回答，简短直接。要动手就调用工具，拿到结果再答。";
            case GENERAL:
            default:
                return "你是一个运行在手机本地的中文 AI 助手，请用简体中文准确、简洁地回答。"
                        + "涉及代码或网页时，请用带文件名的 Markdown 代码块输出，例如 ```html filename=index.html，"
                        + "这样文件会自动保存到用户的工作区。";
        }
    }

    /**
     * 智能体模式的工具说明段。
     *
     * @param toolList 形如 {@code shell(命令) —— 执行命令} 的清单，每行一条
     * @param minimal  是否用压缩版（小模型）
     */
    public static String toolSection(String toolList, boolean minimal) {
        if (minimal) {
            return "\n\n要动手就输出：<function name=\"工具\"><param name=\"参数\">值</param></function>\n"
                    + "可用：" + oneLine(toolList) + "\n"
                    + "之后会收到 <tool_response> 结果，据此继续作答；不需要工具就直接回答。";
        }
        return "\n\n你可以调用工具在手机本机上完成任务。需要实时信息或本机操作时，直接输出下面这种调用"
                + "（不要用代码块包裹，一次可以输出多个）：\n\n"
                + "<function name=\"工具名\"><param name=\"参数名\">参数值</param></function>\n\n"
                + "可用工具：\n" + (toolList == null ? "" : toolList.trim()) + "\n"
                + "调用后会收到一条 <tool_response> 结果（里面是真实执行结果），然后据此继续作答；"
                + "不需要工具时直接回答，不要输出调用。";
    }

    /** 把多行工具清单压成一行，省 token。 */
    private static String oneLine(String toolList) {
        if (toolList == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : toolList.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            int cut = t.indexOf("——");
            if (cut > 0) t = t.substring(0, cut).trim();
            if (sb.length() > 0) sb.append("；");
            sb.append(t);
        }
        return sb.toString();
    }

    /**
     * 拼出最终系统提示词。
     *
     * @param userPrompt 用户自定义内容，可空
     * @param preset     预设
     * @param tools      工具说明段，可空（非智能体模式）
     * @param memory     记忆条目（用户长期偏好），可空
     */
    public static String compose(String userPrompt, Preset preset, String tools, List<String> memory) {
        StringBuilder sb = new StringBuilder();
        String user = userPrompt == null ? "" : userPrompt.trim();
        sb.append(user.isEmpty() ? body(preset) : user);
        if (tools != null && !tools.trim().isEmpty()) sb.append(tools.trim());
        List<String> mem = sanitizeMemory(memory);
        if (!mem.isEmpty()) {
            sb.append("\n\n用户长期偏好（请始终遵守）：\n");
            for (String m : mem) sb.append("- ").append(m).append('\n');
        }
        return sb.toString().trim();
    }

    /**
     * 记忆条目清洗：去空白、去重、限制条数与单条长度。
     * 记忆是会被拼进每次请求的，条数一多就白烧上下文。
     */
    public static List<String> sanitizeMemory(List<String> memory) {
        List<String> out = new ArrayList<>();
        if (memory == null) return out;
        for (String raw : memory) {
            if (raw == null) continue;
            String s = raw.trim().replaceAll("\\s+", " ");
            if (s.isEmpty()) continue;
            if (s.length() > MAX_MEMORY_ITEM) s = s.substring(0, MAX_MEMORY_ITEM) + "…";
            if (!out.contains(s)) out.add(s);
            if (out.size() >= MAX_MEMORY_ITEMS) break;
        }
        return out;
    }

    public static final int MAX_MEMORY_ITEMS = 8;
    public static final int MAX_MEMORY_ITEM = 120;

    /**
     * 根据模型名猜一个合适的预设。
     *
     * <p>依据是参数量：带 "0.5b" / "1.5b" / "2b" 这类小模型给 MINIMAL，
     * 带 "coder" / "code" 的给 CODER，其余 AGENT。
     */
    public static Preset guessPreset(String modelName) {
        if (modelName == null) return Preset.AGENT;
        String n = modelName.toLowerCase(Locale.ROOT);
        if (n.contains("coder") || n.contains("code")) return Preset.CODER;
        double size = guessBillionParams(n);
        if (size > 0 && size <= 2.5) return Preset.MINIMAL;
        return Preset.AGENT;
    }

    /** 从模型名里猜参数量（单位 B），猜不到返回 0。 */
    public static double guessBillionParams(String name) {
        if (name == null) return 0;
        String n = name.toLowerCase(Locale.ROOT);
        // 形如 0.5b / 1.5b / 7b / 32b
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+(?:\\.\\d+)?)\\s*b(?![a-z0-9])").matcher(n);
        double found = 0;
        while (m.find()) {
            try {
                double v = Double.parseDouble(m.group(1));
                if (v > 0 && v <= 1000) found = Math.max(found, v);
            } catch (NumberFormatException ignored) {
                // 名字里出现怪数字，忽略即可
            }
        }
        return found;
    }

    /** 是否应该用压缩版工具说明。 */
    public static boolean wantsCompactTools(String modelName) {
        double size = guessBillionParams(modelName);
        return size > 0 && size <= 2.5;
    }
}
