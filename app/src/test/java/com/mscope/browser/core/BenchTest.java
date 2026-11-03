package com.mscope.browser.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** 端侧基准：固定用例、可比口径、人话结论。 */
public class BenchTest {

    @Test
    public void casesAreStableAndShort() {
        List<Bench.Case> c = Bench.cases(true);
        assertEquals(2, c.size());
        assertEquals(64, c.get(0).maxTokens);
        assertEquals(96, c.get(1).maxTokens);
        assertTrue(c.get(1).prompt.length() > c.get(0).prompt.length());
    }

    @Test
    public void scoreWeightsDecodeHigher() {
        List<Bench.Result> rs = new ArrayList<>();
        rs.add(new Bench.Result("a", 100, 1000, 10, 10));
        double a = Bench.score(rs);
        rs.add(new Bench.Result("b", 100, 1000, 10, 20));
        assertTrue(Bench.score(rs) > a);
    }

    @Test
    public void verdictMapsToAdvice() {
        List<Bench.Result> fast = new ArrayList<>();
        fast.add(new Bench.Result("a", 10, 100, 10, 40));
        assertTrue(Bench.verdict(fast, true).contains("流畅"));

        List<Bench.Result> slow = new ArrayList<>();
        slow.add(new Bench.Result("a", 10, 100, 10, 1));
        assertTrue(Bench.verdict(slow, true).contains("吃力"));

        assertTrue(Bench.verdict(new ArrayList<>(), true).length() > 0);
    }
}
