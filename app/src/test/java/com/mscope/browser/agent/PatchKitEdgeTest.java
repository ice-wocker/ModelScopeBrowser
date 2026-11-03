package com.mscope.browser.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

/**
 * {@link PatchKit} 的边界：这些用例专门盯「改错地方」和「宁可不改」两类行为。
 *
 * <p>片段替换的价值在于破坏面小，所以**误改比不改严重得多** —— 用户看得出「没改成」，
 * 但看不出「改到别处去了」。下面每一条都对应一个真实的错法。
 */
public class PatchKitEdgeTest {

    @Test
    public void appliesUniqueHunk() {
        PatchKit.Result r = PatchKit.apply("a\nb\nc\n",
                Arrays.asList(new PatchKit.Hunk("b", "B")), true);
        assertTrue(r.ok);
        assertEquals("a\nB\nc\n", r.text);
        assertEquals(1, r.applied);
    }

    @Test
    public void missingFindLeavesFileUntouched() {
        String original = "alpha\nbeta\n";
        PatchKit.Result r = PatchKit.apply(original,
                Arrays.asList(new PatchKit.Hunk("gamma", "G")), true);
        assertFalse(r.ok);
        assertEquals("找不到时文件不能被改动", original, r.text);
        assertEquals(0, r.applied);
    }

    @Test
    public void anyFailingHunkRollsBackWholePatch() {
        String original = "a\nb\nc\n";
        PatchKit.Result r = PatchKit.apply(original, Arrays.asList(
                new PatchKit.Hunk("a", "A"),
                new PatchKit.Hunk("nope", "X")), true);
        assertFalse("多片段里有一个失败就整体不生效", r.ok);
        assertEquals(original, r.text);
    }

    @Test
    public void crlfFindMatchesLfFileWithCorrectSubstring() {
        // 模型按 LF 给片段，文件本身是 CRLF：这里最容易用错下标把文件写坏
        String file = "line1\r\nTARGET\r\nline3\r\n";
        PatchKit.Result r = PatchKit.apply(file,
                Arrays.asList(new PatchKit.Hunk("TARGET\nline3", "REPLACED")), true);
        assertTrue("应当命中", r.ok);
        assertTrue("替换后不留下半截 CRLF 残片：" + r.text, r.text.startsWith("line1\r\n"));
        assertTrue(r.text.contains("REPLACED"));
        assertFalse(r.text.contains("TARGET"));
    }

    @Test
    public void lfFindOnLfFileUsesExactRange() {
        String file = "x\ny\nz\n";
        PatchKit.Result r = PatchKit.apply(file,
                Arrays.asList(new PatchKit.Hunk("y\nz", "YZ")), true);
        assertTrue(r.ok);
        assertEquals("x\nYZ\n", r.text);
    }

    @Test
    public void ambiguousTrimmedMatchIsRejected() {
        // 去掉首尾空白后同一段出现两次：不能猜，宁可报失败
        String file = "  dup  \nmid\n  dup  \n";
        PatchKit.Result r = PatchKit.apply(file,
                Arrays.asList(new PatchKit.Hunk("dup", "DONE")), true);
        assertFalse("多处命中时不能默默改第一处", r.ok);
        assertEquals(file, r.text);
    }

    @Test
    public void curlyQuotesFallbackKeepsLengthAndPosition() {
        String file = "print(\u201chi\u201d)";
        PatchKit.Result r = PatchKit.apply(file,
                Arrays.asList(new PatchKit.Hunk("print(\"hi\")", "print(\"yo\")")), true);
        assertTrue(r.ok);
        assertEquals("print(\"yo\")", r.text);
    }

    @Test
    public void emptyFindIsRejected() {
        PatchKit.Result r = PatchKit.apply("abc",
                Arrays.asList(new PatchKit.Hunk("", "x")), true);
        assertFalse(r.ok);
        assertEquals("abc", r.text);
    }

    @Test
    public void emptyHunkListIsRejected() {
        PatchKit.Result r = PatchKit.apply("abc", null, true);
        assertFalse(r.ok);
    }

    @Test
    public void locateReturnsNullForEmptyInputs() {
        assertNull(PatchKit.locate("", "x"));
        assertNull(PatchKit.locate("abc", ""));
        assertNull(PatchKit.locate(null, "x"));
    }

    @Test
    public void parsePatchTextRoundTrips() {
        String patch = "<<<<<<< FIND\nold\n=======\nnew\n>>>>>>> REPLACE\n";
        java.util.List<PatchKit.Hunk> hs = PatchKit.parsePatchText(patch);
        assertEquals(1, hs.size());
        PatchKit.Result r = PatchKit.apply("a\nold\nb\n", hs, true);
        assertTrue(r.ok);
        assertEquals("a\nnew\nb\n", r.text);
    }

    @Test
    public void looksLikePatchDetectsMarker() {
        assertTrue(PatchKit.looksLikePatch("<<<<<<< FIND\nx\n=======\ny\n>>>>>>> REPLACE"));
        assertFalse(PatchKit.looksLikePatch("just some text"));
    }
}
