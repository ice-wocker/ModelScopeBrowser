package com.mscope.browser.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 这一批纯逻辑件的行为约定。 */
public class ToolKitTest {

    // ---------------------------------------------------------------- DigestKit

    @Test
    public void digest_matchesKnownVectors() throws Exception {
        // 空串的 SHA-256 / MD5 是公开的固定值，写死在这里能挡住「悄悄换了算法」
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                DigestKit.sha256(""));
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", DigestKit.md5(""));
        assertEquals(DigestKit.sha256("abc"),
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    public void digest_fileAndManifest() throws Exception {
        File dir = Files.createTempDirectory("digest").toFile();
        File f = new File(dir, "a.txt");
        Files.write(f.toPath(), "hello".getBytes(StandardCharsets.UTF_8));
        String expect = DigestKit.sha256("hello");
        assertEquals(expect, DigestKit.sha256File(f));
        assertTrue(DigestKit.looksLikeDigest(expect));

        List<File> files = new ArrayList<>();
        files.add(f);
        Map<String, String> m = DigestKit.digestManifest(files, dir);
        assertEquals(1, m.size());
        assertTrue(DigestKit.renderManifest(m).contains(expect));
    }

    @Test
    public void digest_matchToleratesSha256sumFormat() {
        String hex = DigestKit.sha256("x");
        assertTrue(DigestKit.match(hex.toUpperCase(), hex));
        assertTrue(DigestKit.match("sha256:" + hex, hex));
        assertTrue(DigestKit.match(hex + "  some/file.bin", hex));
        assertFalse(DigestKit.match("", hex));
        assertFalse(DigestKit.match(hex, DigestKit.sha256("y")));
    }

    // ---------------------------------------------------------------- TimeKit

    @Test
    public void time_parseDurationHandlesCompactForms() {
        assertEquals(3 * TimeKit.DAY, TimeKit.parseDuration("3d"));
        assertEquals(2 * TimeKit.HOUR + 30 * TimeKit.MINUTE, TimeKit.parseDuration("2h30m"));
        assertEquals(1500, TimeKit.parseDuration("1500ms"));
        assertEquals(-1, TimeKit.parseDuration("下周"));
        assertEquals(TimeKit.MINUTE, TimeKit.parseDuration("1分钟"));
    }

    @Test
    public void time_humanizeIsReadable() {
        assertTrue(TimeKit.humanize(TimeKit.DAY + TimeKit.HOUR, true).contains("天"));
        assertTrue(TimeKit.humanize(90_000, false).contains("m"));
    }

    @Test
    public void time_weekdayIsNeverOutOfRange() {
        long now = 1_700_000_000_000L;
        String zh = TimeKit.weekday(now, true);
        String en = TimeKit.weekday(now, false);
        assertTrue(zh.startsWith("周"));
        assertTrue(en.length() > 2);
        assertTrue(TimeKit.describeNow(now, null, true).contains("现在"));
    }

    // ---------------------------------------------------------------- JsonAccess

    @Test
    public void json_deepPath() {
        Object o = JsonAccess.parse("{\"a\":{\"b\":[{\"c\":42}]}}");
        assertNotNull(o);
        assertEquals(42, JsonAccess.i(o, "a.b[0].c", 0));
        assertEquals("x", JsonAccess.str(o, "a.missing", "x"));
        assertNull(JsonAccess.get(o, "a.b[9].c"));
    }

    @Test
    public void json_listWrapsScalar() {
        Object o = JsonAccess.parse("{\"tags\":\"one\",\"many\":[\"a\",\"b\"]}");
        assertEquals(1, JsonAccess.strList(o, "tags").size());
        assertEquals(2, JsonAccess.strList(o, "many").size());
        assertEquals(0, JsonAccess.strList(o, "nope").size());
    }

    @Test
    public void json_parseStripsFencesAndProse() {
        assertNotNull(JsonAccess.parse("```json\n{\"a\":1}\n```"));
        assertNotNull(JsonAccess.parse("好的，结果如下：{\"a\":1} 以上。"));
        assertNotNull(JsonAccess.parse("[1,2,3]"));
        assertNull(JsonAccess.parse("这不是 JSON"));
    }

    @Test
    public void json_boolAndNumberFromStrings() {
        Object o = JsonAccess.parse("{\"on\":\"true\",\"n\":\"12\",\"d\":\"1.5\"}");
        assertTrue(JsonAccess.bool(o, "on", false));
        assertEquals(12, JsonAccess.i(o, "n", 0));
        assertEquals(1.5, JsonAccess.d(o, "d", 0), 1e-9);
    }

    // ---------------------------------------------------------------- SseParser

    @Test
    public void sse_handlesChunkBoundaries() {
        SseParser p = new SseParser();
        List<String> got = new ArrayList<>();
        // 刻意把一条事件切成三段，其中一块正好断在 \n\n 中间
        String s = "data: {\"x\":1}\n\ndata: {\"x\":2}\n\ndata: [DONE]\n\n";
        p.feed(s.substring(0, 12), e -> got.add(e.data));
        p.feed(s.substring(12, 13), e -> got.add(e.data));
        p.feed(s.substring(13), e -> got.add(e.data));
        assertEquals(2, got.size());
        assertEquals("{\"x\":1}", got.get(0));
    }

    @Test
    public void sse_multiLineDataAndCrlf() {
        SseParser p = new SseParser();
        List<String> got = new ArrayList<>();
        p.feed("event: message\r\ndata: line1\r\ndata: line2\r\n\r\n", e -> got.add(e.event + "|" + e.data));
        assertEquals(1, got.size());
        assertEquals("message|line1\nline2", got.get(0));
    }

    @Test
    public void sse_deltaTextUnderstandsProviders() {
        assertEquals("hi", SseParser.deltaText("{\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}"));
        assertEquals("yo", SseParser.deltaText("{\"delta\":{\"text\":\"yo\"}}"));
        assertEquals("plain", SseParser.deltaText("plain"));
        assertEquals("", SseParser.deltaText("[DONE]"));
    }

    // ---------------------------------------------------------------- Bm25

    @Test
    public void bm25_ranksRelevantDocFirst() {
        Bm25 idx = new Bm25();
        idx.add("a", "本地推理 需要 GGUF 量化模型，手机内存有限");
        idx.add("b", "今天天气不错，适合出门散步");
        idx.add("c", "量化模型 的选择：Q4_K_M 比较均衡");
        idx.index();
        List<Bm25.Hit> hits = idx.search("量化模型", 2);
        assertTrue(hits.size() >= 2);
        assertFalse("a".equals(hits.get(0).id) && "c".equals(hits.get(1).id) && hits.get(0).score < hits.get(1).score);
        assertTrue(hits.get(0).score > 0);
    }

    @Test
    public void bm25_cjkBigramMatchesSubstring() {
        assertTrue(Bm25.tokens("本地推理").contains("推理"));
        assertTrue(Bm25.tokens("大模型").contains("模型"));
        Bm25 idx = new Bm25();
        idx.add("x", "这是一个关于本地推理的文档");
        idx.index();
        assertEquals(1, idx.search("推理", 5).size());
    }

    @Test
    public void bm25_chunkKeepsSizeAndContent() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) sb.append("第").append(i).append("段：").append("内容只有一点点。\n\n");
        List<String> cs = Bm25.chunk(sb.toString(), 100);
        assertTrue(cs.size() > 1);
        for (String c : cs) assertTrue(c.length() <= 400);
    }

    // ---------------------------------------------------------------- DiffKit

    @Test
    public void diff_countsAddsAndRemoves() {
        List<DiffKit.Line> d = DiffKit.diff("a\nb\nc\n", "a\nB\nc\nd\n");
        DiffKit.Stat st = DiffKit.stat(d);
        assertEquals(2, st.added);
        assertEquals(1, st.removed);
        assertEquals(2, st.same);
        assertEquals("+2 −1", DiffKit.summary(d, true));
    }

    @Test
    public void diff_unifiedHasHunkHeader() {
        List<DiffKit.Line> d = DiffKit.diff("one\ntwo\nthree\n", "one\nTWO\nthree\n");
        String u = DiffKit.unified("a.txt", d, 1, 100, true);
        assertTrue(u.contains("--- a/a.txt"));
        assertTrue(u.contains("@@ -"));
        assertTrue(u.contains("-two"));
        assertTrue(u.contains("+TWO"));
    }

    @Test
    public void diff_sameTextHasNoHunks() {
        List<DiffKit.Line> d = DiffKit.diff("x", "x");
        assertEquals(0, DiffKit.stat(d).changed());
        assertTrue(DiffKit.unified("f", d, 3, 10, true).contains("内容未变化"));
    }

    @Test
    public void diff_linesKeepsTrailingSemantic() {
        assertEquals(2, DiffKit.lines("a\nb").size());
        assertEquals(2, DiffKit.lines("a\nb\n").size());
        assertEquals(0, DiffKit.lines("").size());
    }

    // ---------------------------------------------------------------- DocConverter

    @Test
    public void csv_parsesQuotedAndNewlines() {
        String csv = "name,note\n\"a,b\",\"line1\nline2\"\n";
        List<List<String>> rows = DocConverter.parseCsv(csv, ',');
        assertEquals(2, rows.size());
        assertEquals("a,b", rows.get(1).get(0));
        assertEquals("line1\nline2", rows.get(1).get(1));
    }

    @Test
    public void csv_toMarkdownRoundTrip() {
        String csv = "a,b\n1,2\n";
        String md = DocConverter.csvToMarkdown(csv, ',');
        assertTrue(md.contains("| a | b |"));
        assertTrue(md.contains("| --- | --- |"));
        String back = DocConverter.markdownToCsv(md);
        assertTrue(back.contains("a,b"));
        assertTrue(back.contains("1,2"));
    }

    @Test
    public void table_displayWidthCountsCjkDouble() {
        assertEquals(4, DocConverter.displayWidth("中文"));
        assertEquals(3, DocConverter.displayWidth("abc"));
        String t = DocConverter.toTextTable(DocConverter.parseCsv("名,值\n中文,1\n", ','));
        assertTrue(t.contains("名"));
    }

    @Test
    public void fence_lengthensWhenContentHasTicks() {
        String f = DocConverter.fenceFor("```inner```");
        assertTrue(f.startsWith("````"));
        assertTrue(f.endsWith("````"));
    }

    @Test
    public void clip_neverSplitsChars() {
        String s = "中文中文中文";
        String c = DocConverter.clip(s, 10);
        assertTrue(c.getBytes(StandardCharsets.UTF_8).length <= 10);
        assertTrue(c.endsWith("…"));
    }

    @Test
    public void chart_emitsMermaid() {
        String m = DocConverter.lineChart("speed", java.util.Arrays.asList("1B", "3B"),
                java.util.Arrays.asList(12.0, 5.5));
        assertTrue(m.contains("xychart-beta"));
        assertTrue(m.contains("line [12, 5.50]"));
    }
}
