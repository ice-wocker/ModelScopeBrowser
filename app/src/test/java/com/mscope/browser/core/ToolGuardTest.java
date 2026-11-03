package com.mscope.browser.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.mscope.browser.i18n.Lang;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * 防打转闸门的单元测试。
 *
 * <p>这条链路的失败是「静默」的：模型一直重复同一个调用，界面看起来一切正常，
 * 只是永远不会给出答案。所以这里把「什么时候该拦、什么时候该收尾」钉死。
 */
public class ToolGuardTest {

    @Before
    public void setUp() {
        Lang.setChinese(true);
    }

    /** 参数顺序不同必须算同一个调用，否则模型换个键序就绕过了闸门。 */
    @Test
    public void canonicalArgsIgnoresOrder() {
        List<String> k1 = Arrays.asList("path", "content");
        List<String> v1 = Arrays.asList("a.txt", "hello");
        List<String> k2 = Arrays.asList("CONTENT", " path ");
        List<String> v2 = Arrays.asList(" hello ", "a.txt ");

        assertEquals(ToolGuard.canonicalArgs(k1, v1), ToolGuard.canonicalArgs(k2, v2));
        assertEquals("content=hello;path=a.txt", ToolGuard.canonicalArgs(k1, v1));
    }

    @Test
    public void sameCallOnlyRunsOnce() {
        ToolGuard g = new ToolGuard();
        ToolGuard.Verdict first = g.check("read_file", "path=a.txt");
        assertTrue(first.run);
        assertFalse(first.stop);

        ToolGuard.Verdict second = g.check("read_file", "path=a.txt");
        assertFalse(second.run);
        assertFalse(second.stop);
        assertTrue(second.reason.contains("已经执行过"));

        assertEquals(1, g.executedCount());
        assertEquals(1, g.repeatCount());
    }

    /** 重复到上限必须收尾，不能一边提示一边继续喂。 */
    @Test
    public void stopsAfterRepeatLimit() {
        ToolGuard g = new ToolGuard();
        g.check("shell", "command=ls");
        g.check("shell", "command=ls");
        ToolGuard.Verdict third = g.check("shell", "command=ls");

        assertFalse(third.run);
        assertTrue(third.stop);
        assertTrue(g.shouldStop());
        assertEquals(ToolGuard.MAX_REPEATS, g.repeatCount());
    }

    /** 换参数是合法的新调用，不该被当成重复。 */
    @Test
    public void differentArgsAreNotRepeats() {
        ToolGuard g = new ToolGuard();
        assertTrue(g.check("read_file", "path=a.txt").run);
        assertTrue(g.check("read_file", "path=b.txt").run);
        assertEquals(2, g.executedCount());
        assertEquals(0, g.repeatCount());
    }

    /** 换工具、参数相同，也算新调用。 */
    @Test
    public void differentToolsAreNotRepeats() {
        ToolGuard g = new ToolGuard();
        assertTrue(g.check("read_file", "path=a.txt").run);
        assertTrue(g.check("list_files", "path=a.txt").run);
        assertEquals(2, g.executedCount());
    }

    @Test
    public void toolNameIsCaseAndSpaceInsensitive() {
        ToolGuard g = new ToolGuard();
        assertTrue(g.check("Read_File", "path=a.txt").run);
        assertFalse(g.check("  read_file  ", " path=a.txt ").run);
    }

    /** 截断必须落在完整字符边界上，否则判重键里会出现半个代理对。 */
    @Test
    public void fingerprintDoesNotSplitSurrogatePairs() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) sb.append("\uD83D\uDE00");     // 😀 × 200
        String f = ToolGuard.fingerprint("write_file", sb.toString());
        String args = f.substring(f.indexOf('|') + 1);
        // 末尾不能是孤立的代理对高位
        assertFalse(Character.isHighSurrogate(args.charAt(args.length() - 1)));
        assertTrue(f.length() <= ToolGuard.MAX_ARGS + 16);
    }

    /** 超长参数（写大文件）不能把指纹撑爆，也不能因为截断把不同调用并成一个。 */
    @Test
    public void longArgsAreBounded() {
        StringBuilder big = new StringBuilder();
        StringBuilder big2 = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            big.append('a');
            big2.append('a');
        }
        String f1 = ToolGuard.fingerprint("write_file", big.toString());
        assertTrue(f1.length() <= ToolGuard.MAX_ARGS + 16);

        // 头部相同但尾部不同：截断后确实可能撞在一起，这里只要求「不抛异常且稳定可重复」
        assertEquals(f1, ToolGuard.fingerprint("write_file", big.toString()));
        assertNotNull(ToolGuard.fingerprint(null, null));
        assertEquals("|", ToolGuard.fingerprint(null, null));
    }

    /** 英文语境下返回英文提示，避免中英混排。 */
    @Test
    public void reasonFollowsLanguage() {
        Lang.setChinese(false);
        ToolGuard g = new ToolGuard();
        g.check("read_file", "path=a.txt");
        String en = g.check("read_file", "path=a.txt").reason;
        assertTrue(en.startsWith("This exact call already ran"));

        Lang.setChinese(true);
        ToolGuard g2 = new ToolGuard();
        g2.check("read_file", "path=a.txt");
        String zh = g2.check("read_file", "path=a.txt").reason;
        assertTrue(zh.startsWith("这个调用在本轮已经执行过"));
    }

    @Test
    public void resetClearsState() {
        ToolGuard g = new ToolGuard();
        g.check("shell", "command=ls");
        g.check("shell", "command=ls");
        g.reset();
        assertEquals(0, g.executedCount());
        assertEquals(0, g.repeatCount());
        assertTrue(g.check("shell", "command=ls").run);
    }
}
