package com.mscope.browser.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 回放许可只能「用一次、只属于下一轮」。
 *
 * <p>这几个用例钉住的是几次真实翻车：判定算了但参数没传（永远不生效）、
 * 许可没有清零（用过之后每轮都在沿用）、以及没产出的轮次也放行（白跑校验）。
 */
public class KvReplayTest {

    @Test
    public void takesOnceThenForgets() {
        KvReplay kv = new KvReplay();
        kv.finished(true);
        kv.markReplayable();
        assertTrue("确认过可丢弃，下一轮应当允许回放", kv.take());
        assertFalse("许可只能用一次", kv.take());
        assertFalse(kv.take());
    }

    @Test
    public void noContentMeansNothingToDrop() {
        KvReplay kv = new KvReplay();
        kv.finished(false);
        kv.markReplayable();
        assertFalse("这一轮没有产出，KV 里没多出东西，不该放行", kv.take());
    }

    @Test
    public void withoutPermissionStaysStrict() {
        KvReplay kv = new KvReplay();
        kv.finished(true);
        assertFalse("上层没确认可丢弃时，保持严格前缀", kv.take());
    }

    @Test
    public void invalidateClearsBothFlags() {
        KvReplay kv = new KvReplay();
        kv.finished(true);
        kv.markReplayable();
        kv.invalidate();
        assertFalse(kv.take());
        assertFalse(kv.dirty());
        assertFalse(kv.armed());
    }

    @Test
    public void permissionDoesNotLeakIntoLaterRounds() {
        KvReplay kv = new KvReplay();
        kv.finished(true);
        kv.markReplayable();
        assertTrue(kv.take());
        // 第二轮正常结束但没人确认可丢弃
        kv.finished(true);
        assertFalse("上一轮的许可不能漏到这一轮", kv.take());
    }
}
