package com.mscope.browser.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 按实测速度调采样参数：慢机先保证说完，快机才放宽。
 *
 * <p>重点是兜底：top-p 为 0 会让采样器除零、top-k 为 0 会直接崩，
 * 这些值一旦漏到原生层，发作起来没人能定位。
 */
public class PerfAdvisorTest {

    @Test
    public void slowDeviceGetsShorterOutput() {
        PerfAdvisor.Sample s = PerfAdvisor.advise(2.0, 4096, -1, 0);
        PerfAdvisor.Sample f = PerfAdvisor.advise(30.0, 4096, -1, 0);
        assertTrue(s.maxTokens < f.maxTokens);
        assertTrue(s.temp <= f.temp);
    }

    @Test
    public void userOverrideWins() {
        PerfAdvisor.Sample s = PerfAdvisor.advise(2.0, 4096, 1.2f, 300);
        assertEquals(1.2f, s.temp, 1e-6);
        assertEquals(300, s.maxTokens);
    }

    @Test
    public void maxTokensNeverEatsWholeContext() {
        PerfAdvisor.Sample s = PerfAdvisor.advise(30.0, 2048, -1, 0);
        assertTrue(s.maxTokens <= 1024);
    }

    @Test
    public void clampsIllegalValues() {
        PerfAdvisor.Sample s = PerfAdvisor.sanitize(new PerfAdvisor.Sample(-1, 0, 0, 0, 0, 0), 4096, 0);
        assertTrue(s.temp >= 0);
        assertTrue(s.topP > 0 && s.topP <= 1);
        assertTrue(s.topK >= 1);
        assertTrue(s.maxTokens >= 16);
        assertTrue(s.repeatPenalty >= 1f);
    }

    @Test
    public void tiersAreStable() {
        assertEquals("slow", PerfAdvisor.tier(1));
        assertEquals("mid", PerfAdvisor.tier(6));
        assertEquals("fast", PerfAdvisor.tier(10));
        assertEquals("very-fast", PerfAdvisor.tier(30));
        assertEquals("unknown", PerfAdvisor.tier(0));
    }

    @Test
    public void explainMentionsSpeed() {
        String e = PerfAdvisor.explain(2.0, PerfAdvisor.advise(2.0, 4096, -1, 0), true);
        assertTrue(e, e.contains("2.0") || e.contains("慢"));
    }
}
