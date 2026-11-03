package com.mscope.browser.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * 待办清单：模型写在外部、系统每轮回灌 —— 对抗「多步任务忘了做到哪」。
 */
public class PlannerTest {

    @Test
    public void parsesCheckboxSyntax() {
        List<Planner.Item> items = Planner.parse("[x] 读文件\n[ ] 改文件\n- [X] 再读回来");
        assertEquals(3, items.size());
        assertTrue(items.get(0).done);
        assertFalse(items.get(1).done);
        assertTrue(items.get(2).done);
        assertEquals("再读回来", items.get(2).text);
    }

    @Test
    public void ignoresBlankAndCapsLength() {
        assertEquals(0, Planner.parse("\n \n").size());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 80; i++) sb.append("[ ] 第").append(i).append("步\n");
        assertEquals(50, Planner.parse(sb.toString()).size());
    }

    @Test
    public void progressCounting() {
        List<Planner.Item> items = Planner.parse("[x] a\n[ ] b\n[x] c");
        assertEquals(2, Planner.doneCount(items));
        assertFalse(Planner.allDone(items));
        assertTrue(Planner.allDone(Planner.parse("[x] a\n[x] b")));
        assertFalse(Planner.allDone(Planner.parse("")));
    }

    @Test
    public void briefOnlyShowsPending() {
        List<Planner.Item> items = Planner.parse("[x] 已完成的事\n[ ] 待办一\n[ ] 待办二");
        String brief = Planner.brief(items, true, 400);
        assertTrue(brief, brief.contains("1/3"));
        assertFalse(brief, brief.contains("已完成的事"));
        assertTrue(brief, brief.contains("待办一"));
    }

    @Test
    public void renderRoundTrips() {
        String body = "[x] a\n[ ] b";
        assertEquals(Planner.render(Planner.parse(body)), body);
    }
}
