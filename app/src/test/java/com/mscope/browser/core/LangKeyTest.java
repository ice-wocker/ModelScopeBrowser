package com.mscope.browser.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/** 双语资源对账：少一条不会崩，只会让那一句在中/英界面下变成另一种语言。 */
public class LangKeyTest {

    @Test
    public void parsesStringsWithInlineMarkup() {
        String xml = "<resources>\n"
                + "  <string name=\"a\">纯文本</string>\n"
                + "  <string name=\"b\">带 <b>粗体</b> 与 %1$s</string>\n"
                + "  <string name=\"c\" translatable=\"false\">x</string>\n"
                + "</resources>";
        List<LangKey.Entry> es = LangKey.parse(xml);
        assertEquals(3, es.size());
        assertEquals("a", es.get(0).name);
        assertTrue(es.get(1).value.contains("粗体"));
        assertEquals("x", es.get(2).value);
    }

    @Test
    public void placeholdersCollected() {
        assertEquals(3, LangKey.placeholders("值 %1$s 与 %2$d 以及 %.2f").size());
        assertEquals(0, LangKey.placeholders("100%% 完成").size());
    }

    @Test
    public void diffDetectsAllThreeProblems() {
        String base = "<resources><string name=\"a\">%1$s</string>"
                + "<string name=\"b\">x</string></resources>";
        String zh = "<resources><string name=\"a\">文本</string>"
                + "<string name=\"c\">y</string></resources>";
        LangKey.Diff d = LangKey.diff(base, zh);
        assertTrue(d.missingInZh.contains("b"));
        assertTrue(d.extraInZh.contains("c"));
        assertTrue(d.placeholderMismatch.toString().contains("a"));
        assertTrue(d.report().contains("missing"));
    }

    @Test
    public void identicalFilesAreClean() {
        String xml = "<resources><string name=\"a\">文 %1$s</string></resources>";
        assertTrue(LangKey.diff(xml, xml).clean());
    }
}
