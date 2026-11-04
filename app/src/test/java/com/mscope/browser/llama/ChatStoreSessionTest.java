package com.mscope.browser.llama;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 会话的消息/统计对齐与标题推导。 */
public class ChatStoreSessionTest {

    @Test
    public void align_padsStatsToMessages() {
        ChatStore.Session s = new ChatStore.Session();
        s.messages.add(new ChatMessage(ChatMessage.USER, "你好"));
        s.messages.add(new ChatMessage(ChatMessage.ASSISTANT, "你好！"));
        s.align();
        assertEquals(s.messages.size(), s.stats.size());
    }

    @Test
    public void align_trimsExtraStats() {
        ChatStore.Session s = new ChatStore.Session();
        s.messages.add(new ChatMessage(ChatMessage.USER, "你好"));
        s.stats.add("a");
        s.stats.add("b");
        s.align();
        assertEquals(1, s.stats.size());
    }

    @Test
    public void titleText_usesFirstUserMessage() {
        ChatStore.Session s = new ChatStore.Session();
        s.messages.add(new ChatMessage(ChatMessage.ASSISTANT, "欢迎"));
        s.messages.add(new ChatMessage(ChatMessage.USER, "  用一句话介绍北京  "));
        assertEquals("用一句话介绍北京", s.titleText());
    }

    @Test
    public void titleText_truncatesLongText() {
        ChatStore.Session s = new ChatStore.Session();
        s.messages.add(new ChatMessage(ChatMessage.USER, "0123456789abcdefghijklmn"));
        assertEquals("0123456789abcdefgh…", s.titleText());
    }

    @Test
    public void titleText_emptyWhenNoUserMessage() {
        assertEquals("", new ChatStore.Session().titleText());
    }
}