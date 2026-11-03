package com.mscope.browser.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 工具定义表：提示词渲染与接口 JSON 必须来自同一份 schema，不能各写一套。
 */
public class ToolSchemasTest {

    @Test
    public void lookupIsCaseInsensitive() {
        assertNotNull(ToolSchemas.get("read_file"));
        assertNotNull(ToolSchemas.get("READ_FILE"));
        assertNull(ToolSchemas.get("nope"));
    }

    @Test
    public void namesAreUnique() {
        Set<String> seen = new HashSet<>();
        for (ToolSchemas.Spec s : ToolSchemas.all()) {
            assertTrue("重复的工具名：" + s.name, seen.add(s.name));
        }
    }

    @Test
    public void describeOnlyListsEnabled() {
        String zh = ToolSchemas.describe(Arrays.asList("calc", "now"), true);
        assertTrue(zh, zh.contains("calc"));
        assertTrue(zh, zh.contains("now"));
        assertFalse(zh, zh.contains("web_search"));
    }

    @Test
    public void describeMarksOptionalParams() {
        String d = ToolSchemas.describe(Arrays.asList("list_files"), false);
        assertTrue(d, d.contains("path:string?"));
        String r = ToolSchemas.describe(Arrays.asList("read_file"), false);
        assertTrue(r, r.contains("path:string "));
    }

    @Test
    public void jsonIsWellFormedShape() {
        String json = ToolSchemas.toJson(Arrays.asList("read_file"), true);
        assertTrue(json.startsWith("[{\"type\":\"function\""));
        assertTrue(json.endsWith("}]"));
        assertTrue(json, json.contains("\"required\":[\"path\"]"));
        // 引号必须被转义，否则发出去的 JSON 直接非法
        assertFalse(json.contains("\"description\":\"read a"));
    }

    @Test
    public void jsonEscapesSpecialChars() {
        String json = ToolSchemas.toJson(Arrays.asList("read_file"), false);
        assertTrue(json, json.contains("512KB"));
    }

    @Test
    public void riskyToolsAreMarked() {
        assertTrue(ToolSchemas.get("shell").risky);
        assertTrue(ToolSchemas.get("edit_file").risky);
        assertFalse(ToolSchemas.get("read_file").risky);
    }
}
