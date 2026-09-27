package com.mscope.browser.llama;

/** 一条对话消息。 */
public class ChatMessage {

    public static final String SYSTEM = "system";
    public static final String USER = "user";
    public static final String ASSISTANT = "assistant";

    public final String role;
    public String content;

    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public boolean isUser() {
        return USER.equals(role);
    }
}