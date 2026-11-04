package com.mscope.browser.core;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 复读熔断的「语言级」补充：只看字节有没有增长，抓不到「换着花样重复」。
 *
 * <p>原有的 {@code LlamaEngine.isStuck} 判的是「几秒内一个 token 都没新增」，
 * 对「把同一句话改几个字再说一遍」无能为力。这里看的是内容本身：
 * 取最后 {@code WINDOW} 个字符里最长的重复子串，若一段 ≥16 字的片段
 * 在窗口里出现 3 次以上，判为原地打转。
 *
 * <p>为什么是 16 且要 3 次：代码块、表格、列表天然高频重复，
 * 阈值低一点就会把正常回答切断 —— 宁可漏判，不要误杀。
 */
public final class LoopBreaker {

    private LoopBreaker() {
    }

    public static final int MIN_SEGMENT = 16;
    public static final int MIN_REPEAT = 3;
    public static final int WINDOW = 1200;

    /** 判定结果。 */
    public static final class Report {
        public final boolean looping;
        public final String segment;

        Report(boolean looping, String segment) {
            this.looping = looping;
            this.segment = segment;
        }

        public static Report ok() {
            return new Report(false, "");
        }
    }

    /**
     * 检查一段已生成的文本是否在自我重复。
     *
     * <p>复杂度 O(n²)（只在每 64 个 token 调一次、窗口固定 1200 字），
     * 用滚动哈希把常数压到很低。
     */
    public static Report check(CharSequence text) {
        if (text == null) return Report.ok();
        String s = text.toString();
        int n = s.length();
        if (n < MIN_SEGMENT * MIN_REPEAT) return Report.ok();
        int from = Math.max(0, n - WINDOW);
        s = s.substring(from);
        n = s.length();

        Map<Long, int[]> seen = new HashMap<>();
        // 从较长的片段开始找：先命中更具说服力
        for (int len = 48; len >= MIN_SEGMENT; len -= 8) {
            seen.clear();
            for (int i = 0; i + len <= n; i++) {
                long h = hash(s, i, len);
                int[] slot = seen.get(h);
                if (slot == null) {
                    seen.put(h, new int[]{i, 1});
                    continue;
                }
                // 哈希相等还要真的比一遍，避免碰撞误判
                if (s.regionMatches(slot[0], s, i, len)) {
                    slot[1]++;
                    if (slot[1] >= MIN_REPEAT) {
                        return new Report(true, s.substring(slot[0], slot[0] + len));
                    }
                } else {
                    seen.put(h, new int[]{i, 1});
                }
            }
        }
        return Report.ok();
    }

    private static long hash(String s, int from, int len) {
        long h = 1125899906842597L;
        for (int i = 0; i < len; i++) h = 31 * h + s.charAt(from + i);
        return h;
    }

    /**
     * 判断输出是不是在「念经」：同一行（去空白后）连续出现多次。
     * 模型在小上下文里最常见的一种坏输出就是无限重复同一行。
     */
    public static boolean repeatsLine(CharSequence text, int times) {
        if (text == null) return false;
        String[] lines = text.toString().split("\n");
        int run = 1;
        String prev = null;
        for (int i = lines.length - 1; i >= 0; i--) {
            String cur = lines[i].trim().toLowerCase(Locale.ROOT);
            if (cur.isEmpty()) continue;
            if (cur.equals(prev)) {
                run++;
                if (run >= times) return true;
            } else {
                run = 1;
                prev = cur;
            }
        }
        return false;
    }
}
