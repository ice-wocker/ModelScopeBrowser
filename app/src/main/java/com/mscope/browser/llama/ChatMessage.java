package com.mscope.browser.llama;

/** 一条对话消息。 */
public class ChatMessage {

    public static final String SYSTEM = "system";
    public static final String USER = "user";
    public static final String ASSISTANT = "assistant";
    /** 工具结果（联网检索 / 文件写入 / 终端命令），以卡片形式展示，不进入模型上下文。 */
    public static final String TOOL = "tool";

    public final String role;
    public String content;

    /** 工具消息的标题（如「联网搜索」），普通消息为空。 */
    public String title = "";
    /** 工具消息的类型：search / file / shell / info，决定卡片图标与配色。 */
    public String kind = "";

    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    /** 构造一条工具消息。 */
    public static ChatMessage tool(String title, String kind, String content) {
        ChatMessage m = new ChatMessage(TOOL, content);
        m.title = title == null ? "" : title;
        m.kind = kind == null ? "" : kind;
        return m;
    }

    public boolean isUser() {
        return USER.equals(role);
    }

    public boolean isTool() {
        return TOOL.equals(role);
    }

    public boolean isAssistant() {
        return ASSISTANT.equals(role);
    }
}