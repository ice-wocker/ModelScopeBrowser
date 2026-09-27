package com.mscope.browser;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 体积/计数格式化：边界与单位换算。 */
public class FormatTest {

    @Test
    public void size_nonPositiveIsZero() {
        assertEquals("0 B", Format.size(0));
        assertEquals("0 B", Format.size(-1024));
    }

    @Test
    public void size_units() {
        assertEquals("512 B", Format.size(512));
        assertEquals("1.0 KB", Format.size(1024));
        assertEquals("1.5 KB", Format.size(1536));
        assertEquals("1.0 MB", Format.size(1024L * 1024));
        assertEquals("1.0 GB", Format.size(1024L * 1024 * 1024));
    }

    @Test
    public void sizeOrDash_keepsDashForUnknown() {
        assertEquals("-", Format.sizeOrDash(0));
        assertEquals("-", Format.sizeOrDash(-1));
        assertEquals("2.0 KB", Format.sizeOrDash(2048));
    }

    @Test
    public void count_wanAndYi() {
        assertEquals("9999", Format.count(9999));
        assertEquals("1.0万", Format.count(10000));
        assertEquals("1.2万", Format.count(12345));
        assertEquals("1.0亿", Format.count(100000000L));
    }
}