package com.mscope.browser.local;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * 断点续传的区间校验。
 *
 * <p>这条逻辑的意义不是「解析一个响应头」，而是挡住一类**会让模型加载崩掉**的文件：
 * 远端换了同名文件后，206 返回的区间与我们本地的断点并不衔接，把两代内容拼在一起，
 * 长度校验照样能过、GGUF 魔数也还在，但加载时就会炸。所以这里只做一件事 ——
 * 认不出、或者起点对不上，都必须能让上层判定「重下」。
 */
public class ContentRangeTest {

    @Test
    public void parsesStandardForm() {
        assertArrayEquals(new long[]{1024, 2047, 4096},
                DownloadCenter.parseContentRange("bytes 1024-2047/4096"));
    }

    @Test
    public void parsesUnknownTotal() {
        assertArrayEquals(new long[]{0, 99, -1},
                DownloadCenter.parseContentRange("bytes 0-99/*"));
    }

    @Test
    public void toleratesExtraSpaces() {
        assertArrayEquals(new long[]{10, 20, 100},
                DownloadCenter.parseContentRange("  bytes   10-20/100 "));
    }

    @Test
    public void returnsNullForGarbage() {
        assertNull(DownloadCenter.parseContentRange(null));
        assertNull(DownloadCenter.parseContentRange(""));
        assertNull(DownloadCenter.parseContentRange("bytes"));
        assertNull(DownloadCenter.parseContentRange("bytes abc-def/ghi"));
        assertNull(DownloadCenter.parseContentRange("items 1-2/3"));
    }
}
