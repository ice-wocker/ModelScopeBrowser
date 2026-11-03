package com.mscope.browser.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 长期记忆：一行一条、靠相似度更新而不是叠加、写入即脱敏。
 */
public class MemoryTest {

    @Test
    public void parseSkipsBrokenLines() {
        String body = "# 注释\n"
                + "abc|100|用户偏好中文\n"
                + "坏行没有分隔符\n"
                + "def|notanumber|时间戳坏了也要保留内容\n"
                + "\n";
        List<Memory.Fact> fs = Memory.parse(body);
        assertEquals(2, fs.size());
        assertEquals("用户偏好中文", fs.get(0).text);
        assertEquals(0, fs.get(1).ts);
    }

    @Test
    public void addDedupesSimilarFacts() {
        List<Memory.Fact> fs = Memory.add(new ArrayList<>(), "用户偏好简洁回答", 1);
        fs = Memory.add(fs, "用户偏好简洁回答", 2);
        assertEquals(1, fs.size());
        assertEquals(2, fs.get(0).ts);
    }

    @Test
    public void addKeepsDifferentFacts() {
        List<Memory.Fact> fs = Memory.add(new ArrayList<>(), "用户偏好简洁回答", 1);
        fs = Memory.add(fs, "设备是 arm64 的安卓机", 2);
        assertEquals(2, fs.size());
    }

    @Test
    public void addRedactsSecrets() {
        List<Memory.Fact> fs = Memory.add(new ArrayList<>(),
                "我的 github token 是 gho_FAKE0000000000000000000000000000000000", 1);
        assertEquals(1, fs.size());
        assertFalse(fs.get(0).text.contains("gho_FAKE"));
        assertTrue(fs.get(0).text.contains("redacted"));
    }

    @Test
    public void emptyFactIsIgnored() {
        assertEquals(0, Memory.add(new ArrayList<>(), "   ", 1).size());
    }

    @Test
    public void renderRoundTrips() {
        List<Memory.Fact> fs = Memory.add(new ArrayList<>(), "用户偏好简洁回答", 7);
        List<Memory.Fact> again = Memory.parse(Memory.render(fs));
        assertEquals(fs.size(), again.size());
        assertEquals(fs.get(0).text, again.get(0).text);
        assertEquals(7, again.get(0).ts);
    }

    @Test
    public void searchPrefersRelevant() {
        List<Memory.Fact> fs = new ArrayList<>();
        fs = Memory.add(fs, "用户偏好简洁回答", 1);
        fs = Memory.add(fs, "用户在做一个安卓本地推理项目", 2);
        List<Memory.Fact> hit = Memory.search(fs, "安卓项目", 3);
        assertFalse(hit.isEmpty());
        assertTrue(hit.get(0).text.contains("安卓"));
    }

    @Test
    public void capDropsOldest() {
        List<Memory.Fact> fs = new ArrayList<>();
        for (int i = 0; i < Memory.MAX_FACTS + 20; i++) {
            fs = Memory.add(fs, "事实编号 unique-" + i + "-topic" + i, i);
        }
        assertEquals(Memory.MAX_FACTS, fs.size());
    }

    @Test
    public void removeById() {
        List<Memory.Fact> fs = Memory.add(new ArrayList<>(), "用户偏好简洁回答", 1);
        assertEquals(0, Memory.remove(fs, fs.get(0).id).size());
    }
}
