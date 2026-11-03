package com.mscope.browser.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.List;

/**
 * diff 应用器：宁可失败，也不猜。
 *
 * <p>这里重点钉三类「静默改坏文件」的路径：
 * 片段对不上却照行号硬改、片段在两处都匹配却改了第一处、失败时留下半截结果。
 */
public class PatcherTest {

    private static final String SRC = "line1\nline2\nline3\nline4\nline5\n";

    @Test
    public void appliesSimpleReplacement() throws Exception {
        String diff = "--- a/t.txt\n+++ b/t.txt\n@@ -2,3 +2,3 @@\n line2\n-line3\n+LINE3\n line4\n";
        List<Patcher.FilePatch> ps = Patcher.parse(diff);
        assertEquals(1, ps.size());
        String out = Patcher.apply(SRC, ps.get(0));
        assertEquals("line1\nline2\nLINE3\nline4\nline5\n", out);
        assertEquals(1, ps.get(0).added());
        assertEquals(1, ps.get(0).removed());
    }

    @Test
    public void missingContextFailsLoudly() throws Exception {
        String diff = "--- a/t.txt\n+++ b/t.txt\n@@ -2,3 +2,3 @@\n nothere\n-line3\n+LINE3\n";
        List<Patcher.FilePatch> ps = Patcher.parse(diff);
        try {
            Patcher.apply(SRC, ps.get(0));
            fail("片段对不上时必须失败");
        } catch (Patcher.Failure e) {
            assertTrue(e.getMessage(), e.getMessage().contains("does not match"));
        }
    }

    @Test
    public void ambiguousSnippetIsRejected() throws Exception {
        String src = "a\nDUP\nb\nDUP\nc\n";
        String diff = "--- a/t.txt\n+++ b/t.txt\n@@ -2,1 +2,1 @@\n-DUP\n+NEW\n";
        List<Patcher.FilePatch> ps = Patcher.parse(diff);
        try {
            Patcher.apply(src, ps.get(0));
            fail("两处匹配必须拒绝，否则会改错行");
        } catch (Patcher.Failure e) {
            assertTrue(e.getMessage(), e.getMessage().contains("matches 2 places"));
        }
    }

    @Test
    public void parseRejectsHeaderWithoutBody() {
        try {
            Patcher.parse("--- a/x\n+++ b/x\n");
            fail("没有 hunk 应当报错");
        } catch (Patcher.Failure e) {
            assertTrue(e.getMessage(), e.getMessage().contains("no hunk"));
        }
    }

    @Test
    public void replaceOnceRequiresUniqueness() throws Exception {
        assertEquals("a\nB\nc\n", Patcher.replaceOnce("a\nb\nc\n", "b", "B", true));
        try {
            Patcher.replaceOnce("x x x", "x", "y", true);
            fail("多处命中必须失败");
        } catch (Patcher.Failure e) {
            assertTrue(e.getMessage(), e.getMessage().contains("多次"));
        }
        try {
            Patcher.replaceOnce("abc", "zzz", "y", true);
            fail("未命中必须失败");
        } catch (Patcher.Failure e) {
            assertTrue(e.getMessage(), e.getMessage().contains("没找到"));
        }
    }

    @Test
    public void multipleFilesInOnePatch() throws Exception {
        String diff = "--- a/one.txt\n+++ b/one.txt\n@@ -1,1 +1,1 @@\n-a\n+A\n"
                + "--- a/two.txt\n+++ b/two.txt\n@@ -1,1 +1,1 @@\n-x\n+X\n";
        assertEquals(2, Patcher.parse(diff).size());
        assertEquals("one.txt +1 -1; two.txt +1 -1", Patcher.summary(Patcher.parse(diff)));
    }
}
