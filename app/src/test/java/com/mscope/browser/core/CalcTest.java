package com.mscope.browser.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * 计算器：本地小模型的算术错误是「看起来像答案的错误」，所以必须有确定性实现。
 */
public class CalcTest {

    private static String ev(String e) {
        return Calc.format(Calc.eval(e));
    }

    @Test
    public void basicOps() {
        assertEquals("7", ev("1+2*3"));
        assertEquals("9", ev("(1+2)*3"));
        assertEquals("0.5", ev("1/2"));
        assertEquals("1", ev("10%3"));
        assertEquals("0.15", ev("15%"));
        assertEquals("0.2", ev("10%+10%"));
    }

    @Test
    public void powerIsRightAssociative() {
        assertEquals("512", ev("2^3^2"));
        assertEquals("-8", ev("-2^3"));
    }

    @Test
    public void constantsAndFunctions() {
        assertTrue(ev("pi").startsWith("3.14159"));
        assertEquals("4", ev("sqrt(16)"));
        assertEquals("7.141592654", ev("sqrt(16)+pi"));
    }

    @Test
    public void noFloatingPointNoise() {
        assertEquals("0.3", ev("0.1+0.2"));
        assertEquals("3", ev("1.5*2"));
    }

    @Test
    public void rejectsGarbageInsteadOfGuessing() {
        for (String bad : new String[]{"", " ", "1+", "(1+2", "2*foo", "1..2", "1/0"}) {
            try {
                Calc.eval(bad);
                fail("应当拒绝：" + bad);
            } catch (RuntimeException expected) {
                // ok
            }
        }
    }
}
