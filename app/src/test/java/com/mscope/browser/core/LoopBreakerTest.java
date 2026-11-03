package com.mscope.browser.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 内容级复读熔断：字节没停（有 token 产出）但内容在原地打转。
 */
public class LoopBreakerTest {

    @Test
    public void detectsRepeatedParagraph() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) sb.append("这是一段重复的话用来测试熔断是否生效。");
        assertTrue(LoopBreaker.check(sb).looping);
    }

    @Test
    public void detectsRepeatedEnglishSentence() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) sb.append("The quick brown fox jumps over the lazy dog. ");
        assertTrue(LoopBreaker.check(sb).looping);
    }

    @Test
    public void normalAnswerIsNotFlagged() {
        String s = "手机端本地推理的关键是内存与带宽的权衡。"
                + "先把上下文压到设备能承受的范围，再看量化格式与线程数，"
                + "最后才谈采样参数。每一步都能被单独测出来。";
        assertFalse(LoopBreaker.check(s).looping);
    }

    @Test
    public void codeBlockWithStructureIsNotFlagged() {
        String s = "```java\n"
                + "int a = 1;\nint b = 2;\nint c = 3;\n"
                + "if (a < b) { return c; }\nreturn a + b + c;\n```\n"
                + "上面这段只做加法。";
        assertFalse(LoopBreaker.check(s).looping);
    }

    @Test
    public void repeatedLineDetector() {
        assertTrue(LoopBreaker.repeatsLine("a\nsame line\nsame line\nsame line\n", 3));
        assertFalse(LoopBreaker.repeatsLine("a\nb\nc\nd\n", 3));
    }
}
