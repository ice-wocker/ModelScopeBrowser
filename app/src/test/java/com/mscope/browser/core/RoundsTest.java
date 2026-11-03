package com.mscope.browser.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.mscope.browser.i18n.Lang;

import org.junit.Before;
import org.junit.Test;

/**
 * 单轮预算：步数、时间、上下文三条线各自独立生效。
 *
 * <p>这里钉的是「以前那个写死的 8 步」被换成了什么：
 * 步数按难度给，时间与上下文任何一条到顶都要能单独叫停，
 * 且给出的收尾提示必须说明原因 —— 用户最烦的是不知道为什么停了。
 */
public class RoundsTest {

    @Before
    public void setUp() {
        Lang.setChinese(true);
    }

    @Test
    public void effortRoundsIncreaseMonotonically() {
        int prev = 0;
        for (Rounds.Effort e : Rounds.Effort.values()) {
            assertTrue(e.name() + " 的步数应大于上一档", e.rounds > prev);
            assertTrue(e.name() + " 的时间预算应为正", e.budgetMs > 0);
            prev = e.rounds;
        }
        assertEquals(32, Rounds.Effort.QUICK.rounds);
        assertEquals(200, Rounds.Effort.EXTREME.rounds);
    }

    @Test
    public void stopReasonRounds() {
        Rounds.Budget b = new Rounds.Budget(Rounds.Effort.QUICK, 1000);
        assertEquals(Rounds.Stop.NONE, Rounds.stopReason(0, b, 1000, 0));
        assertEquals(Rounds.Stop.ROUNDS, Rounds.stopReason(b.rounds - 1, b, 1000, 0));
    }

    @Test
    public void stopReasonTime() {
        Rounds.Budget b = new Rounds.Budget(Rounds.Effort.QUICK, 0);
        assertEquals(Rounds.Stop.TIME, Rounds.stopReason(2, b, Rounds.Effort.QUICK.budgetMs + 1, 0));
    }

    @Test
    public void stopReasonContext() {
        Rounds.Budget b = new Rounds.Budget(Rounds.Effort.DEEP, 0);
        assertEquals(Rounds.Stop.CONTEXT, Rounds.stopReason(1, b, 10, 0.96));
        assertEquals(Rounds.Stop.NONE, Rounds.stopReason(1, b, 10, 0.5));
    }

    @Test
    public void stopMessageMentionsReason() {
        Rounds.Budget b = new Rounds.Budget(Rounds.Effort.NORMAL, 0);
        String zh = Rounds.stopMessage(Rounds.Stop.ROUNDS, b, 3, 1);
        assertTrue(zh, zh.contains("步"));
        String en = Rounds.stopMessage(Rounds.Stop.TIME, b, 3, 9_000_000);
        assertTrue(en, en.contains("time budget") || en.contains("时间"));
    }

    @Test
    public void unknownEffortFallsBackToNormal() {
        assertEquals(Rounds.Effort.NORMAL, Rounds.parse("nope"));
        assertEquals(Rounds.Effort.NORMAL, Rounds.parse(null));
        assertEquals(Rounds.Effort.DEEP, Rounds.parse("deep"));
    }

    @Test
    public void budgetNeverReportsNegativeRemaining() {
        Rounds.Budget b = new Rounds.Budget(Rounds.Effort.QUICK, 0);
        assertEquals(0, b.remainingMs(Long.MAX_VALUE / 2));
        assertFalse(b.elapsed(500) < 0);
    }
}
