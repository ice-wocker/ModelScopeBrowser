package com.mscope.browser.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * 断点续传的区间解析。
 *
 * <p>这段一旦解析错，续传就会把两代文件拼成一个「长度对得上、加载就崩」的 GGUF，
 * 而最终只有原生层的魔数检查能拦下它 —— 所以单测必须钉死几种常见写法。</p>
 */
public class DownloadCenterTest {

    @Test
    public void parseContentRange_standard() {
        long[] r = DownloadCenter.parseContentRange("bytes 1024-2047/4096");
        assertEquals(1024L, r[0]);
        assertEquals(2047L, r[1]);
        assertEquals(4096L, r[2]);
    }

    @Test
    public void parseContentRange_unknownTotal() {
        long[] r = DownloadCenter.parseContentRange("bytes 0-99/*");
        assertEquals(0L, r[0]);
        assertEquals(99L, r[1]);
        assertEquals(-1L, r[2]);
    }

    @Test
    public void parseContentRange_toleratesCaseAndSpacing() {
        long[] r = DownloadCenter.parseContentRange("  Bytes  512-1023 / 2048 ");
        assertEquals(512L, r[0]);
        assertEquals(2048L, r[2]);
    }

    @Test
    public void parseContentRange_rejectsGarbage() {
        assertNull(DownloadCenter.parseContentRange(null));
        assertNull(DownloadCenter.parseContentRange(""));
        assertNull(DownloadCenter.parseContentRange("bytes */4096"));
        assertNull(DownloadCenter.parseContentRange("bytes abc-def/4096"));
    }
}
