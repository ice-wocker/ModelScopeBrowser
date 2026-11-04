package com.mscope.browser.agent;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 一个工具的声明：名字、分组、参数名、是否会改动本机状态。
 *
 * <p>用一张表描述所有工具，而不是在 {@code AgentTools} 里堆 switch + 手写说明字符串。
 * 这样「新增一个工具」只需要在 {@link AgentTools} 里加一行声明和一个 case，
 * 而工具说明、白名单、风险判定会**自动**跟上 —— 之前这三处是三份各自维护的，
 * 一不留神就出现「工具能调用，但模型不知道它存在」或者「风险工具没被确认」。
 */
public final class ToolSpec {

    /** 分组：决定开关粒度与说明顺序。 */
    public enum Group {
        /** 文件读写 */
        FILE,
        /** 文本处理（不碰网络） */
        TEXT,
        /** 计算与时间 */
        CALC,
        /** 本机信息 */
        DEVICE,
        /** 终端 */
        SHELL,
        /** 联网 */
        WEB,
        /** 智能体自身（记忆、待办、技能） */
        AGENT,
        /** 外部模型（OpenAI 兼容接口） */
        MODEL,
        /** MCP 外部服务 */
        MCP
    }

    public final String name;
    public final Group group;
    /** 参数清单（只用于生成说明，实际解析仍走 ToolCall 的别名容错）。 */
    public final String[] args;
    /** 是否改动本机状态（需要用户确认）。 */
    public final boolean risky;
    public final String zh;
    public final String en;

    private ToolSpec(String name, Group group, boolean risky, String zh, String en, String... args) {
        this.name = name;
        this.group = group;
        this.risky = risky;
        this.zh = zh;
        this.en = en;
        this.args = args == null ? new String[0] : args;
    }

    public static ToolSpec of(String name, Group group, boolean risky, String zh, String en, String... args) {
        return new ToolSpec(name, group, risky, zh, en, args);
    }

    /** 生成一行说明，如 {@code - shell: 执行命令。参数 {"command":"..."}}。 */
    public String describe(boolean zhText) {
        StringBuilder sb = new StringBuilder("- ").append(name).append(": ")
                .append(zhText ? zh : en);
        if (args.length > 0) {
            sb.append(zhText ? " 参数 {" : " args {");
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append('"').append(args[i]).append("\": \"...\"");
            }
            sb.append('}');
        }
        return sb.append('\n').toString();
    }

    /** 参数名集合，供 {@link ToolCall} 做白名单校验。 */
    public Set<String> argSet() {
        Set<String> s = new LinkedHashSet<>();
        for (String a : args) s.add(a);
        return s;
    }
}
