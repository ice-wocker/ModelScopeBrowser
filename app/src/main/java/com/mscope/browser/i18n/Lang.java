package com.mscope.browser.i18n;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /** true = 显式指定了语言，此时以 {@link #chineseValue} 为准。 */
    private static final AtomicBoolean EXPLICIT = new AtomicBoolean(false);
    private static volatile boolean chineseValue;

    private Lang() {
    }

    /** 跟随系统 locale（例：切回「跟随系统」时）。 */
    public static void followSystem() {
        EXPLICIT.set(false);
    }

    /** 显式指定，覆盖系统 locale。 */
    public static void setChinese(boolean zh) {
        chineseValue = zh;
        EXPLICIT.set(true);
    }

    /**
     * 当前是否是中文语境。未显式指定时按系统 locale 判断。
     *
     * <p>刻意用「标志位 + boolean」而不是可空的 Boolean：两个字段之间没有原子性要求
     * （最坏情况是切换的一瞬间读到旧语言），但能省掉装箱与拆箱 —— 这个方法在
     * 界面滚动、工具执行、提示词拼接里每秒被调用成千上万次。</p>
     */
    public static boolean zh() {
        if (EXPLICIT.get()) return chineseValue;
        return "zh".equals(Locale.getDefault().getLanguage());
    }

    /** 从两份文案里选一份。 */
    public static String pick(String zh, String en) {
        return zh() ? zh : en;
    }
}
