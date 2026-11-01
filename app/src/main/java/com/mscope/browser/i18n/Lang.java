package com.mscope.browser.i18n;

import java.util.Locale;

/**
 * 界面/文案语言。
 *
 * <p>两件事要分清：
 * <ul>
 *   <li><b>界面语言</b>：资源目录（{@code values} 英文、{@code values-zh} 中文）由系统按 locale 选，</li>
 *   <li><b>模型语言</b>：提示词、工具说明、给模型看的工具结果 —— 这些是代码里的字符串，
 *       必须跟着「用户看得见的语言」走，否则会说中文的人拿到英文提示词。</li>
 * </ul>
 *
 * <p>这里统一收口：{@link #setChinese(boolean)} 由 Activity 在系统选完资源后、首次取文案前调用；
 * 之后的纯逻辑代码（core / agent）只问 {@link #zh()}。默认跟随系统 locale。
 */
public final class Lang {

    private static volatile Boolean chinese;

    private Lang() {
    }

    /** 跟随系统 locale（例：切回「跟随系统」时）。 */
    public static void followSystem() {
        chinese = null;
    }

    /** 显式指定，覆盖系统 locale。 */
    public static void setChinese(boolean zh) {
        chinese = zh;
    }

    /** 当前是否是中文语境。未显式指定时按系统 locale 判断。 */
    public static boolean zh() {
        Boolean v = chinese;
        if (v != null) return v;
        Locale l = Locale.getDefault();
        return "zh".equals(l.getLanguage());
    }

    /** 从两份文案里选一份。 */
    public static String pick(String zh, String en) {
        return zh() ? zh : en;
    }
}
