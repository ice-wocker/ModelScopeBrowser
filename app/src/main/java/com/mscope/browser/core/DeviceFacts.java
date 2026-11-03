package com.mscope.browser.core;

import java.util.List;

/**
 * 「关于这台手机」的一句话事实集合，供 {@code world_info} 工具与界面复用。
 *
 * <p>为什么模型需要它：本地小模型被问到「我手机能跑多大模型」时会一本正经地编，
 * 因为它看不见硬件。这里把这些事实读出来交给它，它才有话说。
 *
 * <p>纯数据 + 纯排版，读取动作由宿主注入（避免这里依赖 Android）。
 */
public final class DeviceFacts {

    private DeviceFacts() {
    }

    /** 一条事实。 */
    public static final class Item {
        public final String key;
        public final String zh;
        public final String en;
        public final String value;

        public Item(String key, String zh, String en, String value) {
            this.key = key;
            this.zh = zh;
            this.en = en;
            this.value = value;
        }

        public String line(boolean chinese) {
            return (chinese ? zh : en) + (chinese ? "：" : ": ") + value;
        }
    }

    public static Item of(String key, String value, String zh, String en) {
        return new Item(key, zh, en, value == null ? "-" : value);
    }

    /** 渲染成给模型看的文本。 */
    public static String render(List<Item> items, boolean zh) {
        if (items == null || items.isEmpty()) {
            return zh ? "没有读到本机信息。" : "No device information available.";
        }
        StringBuilder sb = new StringBuilder();
        for (Item i : items) {
            if (i.value == null || i.value.isEmpty() || "-".equals(i.value)) continue;
            sb.append(i.line(zh)).append('\n');
        }
        return sb.toString().trim();
    }

    /** 人类可读的字节数（与 Android Formatter 口径一致：1024 进制）。 */
    public static String bytes(long b) {
        if (b < 0) return "?";
        if (b < 1024) return b + " B";
        double v = b;
        String[] units = {"KB", "MB", "GB", "TB"};
        int i = -1;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(java.util.Locale.ROOT, "%.1f %s", v, units[i]);
    }
}
