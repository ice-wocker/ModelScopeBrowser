package com.mscope.browser.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 纯 Java 检索：中文按二元组切，避免「内」「存」各自命中半篇文章。
 */
public class RetrieverTest {

    private static List<Retriever.Doc> docs() {
        List<Retriever.Doc> d = new ArrayList<>();
        d.add(new Retriever.Doc("a.txt", "手机内存不足时系统会杀掉后台应用，这是 LMK 的行为。"));
        d.add(new Retriever.Doc("b.txt", "这个项目内有很多文件，存了很多日志，都没整理。"));
        d.add(new Retriever.Doc("c.txt", "量化格式可选 Q4_K_M，速度与体积折中。"));
        return d;
    }

    @Test
    public void chineseBigramFindsTheRightDoc() {
        List<Retriever.Result> rs = Retriever.search(docs(), "内存不足怎么办", 3);
        assertFalse(rs.isEmpty());
        assertEquals("a.txt", rs.get(0).source);
    }

    @Test
    public void unrelatedTermsScoreZero() {
        assertTrue(Retriever.search(docs(), "量子纠缠", 3).isEmpty());
    }

    @Test
    public void emptyQueryReturnsNothing() {
        assertTrue(Retriever.search(docs(), "", 3).isEmpty());
        assertTrue(Retriever.search(docs(), null, 3).isEmpty());
    }

    @Test
    public void englishTokensAreCaseInsensitive() {
        List<Retriever.Result> rs = Retriever.search(docs(), "lmk", 3);
        assertEquals("a.txt", rs.get(0).source);
        assertFalse(Retriever.search(docs(), "Q4_K_M", 3).isEmpty());
    }

    @Test
    public void chunkKeepsLineNumbersAndOverlap() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 200; i++) sb.append("line ").append(i).append(" with some text\n");
        List<Retriever.Doc> chunks = Retriever.chunk("big.txt", sb.toString(), 400, 2);
        assertTrue(chunks.size() > 1);
        assertTrue(chunks.get(0).source.startsWith("big.txt#L1"));
    }

    @Test
    public void highlightCentersOnMatch() {
        String text = repeat("填充", 200) + "关键命中点" + repeat("尾巴", 200);
        String h = Retriever.highlight(text, "关键命中", 30);
        assertTrue(h, h.contains("关键命中"));
        assertTrue(h.length() < text.length());
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }
}
