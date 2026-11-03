package com.mscope.browser.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.mscope.browser.i18n.Lang;

import org.junit.Before;
import org.junit.Test;

import java.util.Set;

/**
 * 工具「能不能被调到」的一致性。
 *
 * <p>这里钉的是一条容易静默失效的约定：提示词里列出的工具、解析器认的工具，
 * 必须是同一份集合。两边不一致时表现为「关了联网，模型却一直去调 web_search」
 * 或者「提示词写了 xxx，但它永远调不通」—— 都不报错，只是不好用。
 */
public class AgentToolsTest {

    @Before
    public void setUp() {
        Lang.setChinese(true);
    }

    @Test
    public void enabled_respectsWebSwitch() {
        Set<String> on = AgentTools.enabled(true, true, true);
        assertTrue(on.contains("web_search"));
        assertTrue(on.contains("fetch_url"));

        Set<String> off = AgentTools.enabled(false, true, true);
        assertFalse(off.contains("web_search"));
        assertFalse(off.contains("fetch_url"));
        assertTrue(off.contains("shell"));
        assertTrue(off.contains("read_file"));
    }

    @Test
    public void enabled_respectsFilesAndShellSwitches() {
        Set<String> noFiles = AgentTools.enabled(true, false, false);
        assertFalse(noFiles.contains("read_file"));
        assertFalse(noFiles.contains("write_file"));
        assertFalse(noFiles.contains("shell"));
        assertTrue(noFiles.contains("web_search"));
    }

    /** 启用的集合必须是全量的子集，否则解析器会认出一堆执行不了的工具。 */
    @Test
    public void enabled_isSubsetOfAll() {
        Set<String> all = AgentTools.names();
        for (boolean web : new boolean[]{true, false}) {
            for (boolean files : new boolean[]{true, false}) {
                for (boolean shell : new boolean[]{true, false}) {
                    Set<String> on = AgentTools.enabled(web, files, shell);
                    assertTrue(all.containsAll(on));
                }
            }
        }
    }

    /** 全量清单不可被外部修改：它同时被解析器与提示词共用。 */
    @Test
    public void names_isImmutable() {
        try {
            AgentTools.names().add("hack");
            throw new AssertionError("names() 必须只读");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }

    @Test
    public void describe_onlyListsEnabledTools() {
        String withWeb = AgentTools.describe(true, true, true);
        assertTrue(withWeb.contains("web_search"));
        assertTrue(withWeb.contains("shell"));
        assertTrue(withWeb.contains("read_file"));

        String noWeb = AgentTools.describe(false, true, true);
        assertFalse(noWeb.contains("web_search"));
        assertFalse(noWeb.contains("fetch_url"));

        String bare = AgentTools.describe(false, false, false);
        assertEquals("", bare.trim());
    }

    @Test
    public void risky_coversMutationOnly() {
        assertTrue(AgentTools.risky("shell"));
        assertTrue(AgentTools.risky("write_file"));
        assertTrue(AgentTools.risky("delete_file"));
        assertFalse(AgentTools.risky("read_file"));
        assertFalse(AgentTools.risky("list_files"));
        assertFalse(AgentTools.risky("web_search"));
        assertFalse(AgentTools.risky("open_preview"));
    }
}
