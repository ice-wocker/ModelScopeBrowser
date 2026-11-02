package com.mscope.browser.core;

/**
 * Server-Sent Events（text/event-stream）增量解析器。
 *
 * <p>OpenAI 兼容接口、MCP 的 streamable HTTP、以及各类本地推理服务都走 SSE。
 * 手写读取有两个常见错误：一是按行 split 后丢掉跨块的事件，二是把 {@code data:} 后面的
 * 那个空格也算进 JSON 里。这里按规范处理：以空行分隔事件、多行 data 用换行拼接、
 * {@code [DONE]} 单独识别。
 *
 * <p>刻意不依赖 Android / 网络类，方便单测用假分块验证「一次 read 只到半个事件」的情况。
 */
public final class SseParser {

    /** 一条完整事件。 */
    public static final class Event {
        public final String event;
        public final String data;
        public final String id;

        public Event(String event, String data, String id) {
            this.event = event == null ? "" : event;
            this.data = data == null ? "" : data;
            this.id = id == null ? "" : id;
        }

        @Override
        public String toString() {
            return "Event{" + event + ", " + data.length() + " chars}";
        }
    }

    /** 事件回调。 */
    @FunctionalInterface
    public interface Sink {
        void onEvent(Event e);
    }

    private final StringBuilder data = new StringBuilder();
    private String event = "";
    private String id = "";
    private final StringBuilder line = new StringBuilder();
    /** 上一个字符是不是 \r，用来把 \r\n 里的 \n 吃掉。 */
    private boolean afterCr;

    /**
     * 喂一段字节（已按 UTF-8 解码的字符串），解析完成的事件通过 sink 回调。
     *
     * @return 是否遇到 {@code [DONE]}（调用方据此提前收尾）
     */
    public boolean feed(String chunk, Sink sink) {
        if (chunk == null || chunk.isEmpty()) return false;
        boolean done = false;
        for (int i = 0; i < chunk.length(); i++) {
            char c = chunk.charAt(i);
            if (c == '\r') {
                done |= flushLine(sink);
                afterCr = true;
            } else if (c == '\n') {
                if (!afterCr) done |= flushLine(sink);
                afterCr = false;
            } else {
                afterCr = false;
                line.append(c);
            }
        }
        return done;
    }

    /** 流结束时把残留的一行冲出去（有些服务最后一条事件没有空行收尾）。 */
    public void finish(Sink sink) {
        flushLine(sink);
        dispatch(sink);
    }

    private boolean flushLine(Sink sink) {
        String l = line.toString();
        line.setLength(0);
        if (l.isEmpty()) return dispatch(sink);
        if (l.charAt(0) == ':') return false;             // 注释行（心跳）
        String field;
        String value;
        int colon = l.indexOf(':');
        if (colon < 0) {
            field = l;
            value = "";
        } else {
            field = l.substring(0, colon);
            value = l.substring(colon + 1);
            if (value.startsWith(" ")) value = value.substring(1);
        }
        switch (field) {
            case "data":
                if (data.length() > 0) data.append('\n');
                data.append(value);
                return false;
            case "event":
                event = value;
                return false;
            case "id":
                id = value;
                return false;
            default:
                return false;
        }
    }

    private boolean dispatch(Sink sink) {
        if (data.length() == 0 && event.isEmpty()) return false;
        String d = data.toString();
        boolean done = "[DONE]".equals(d.trim());
        if (sink != null && !done) sink.onEvent(new Event(event, d, id));
        data.setLength(0);
        event = "";
        return done;
    }

    /**
     * 从一条 SSE 的 data 里取正文增量。
     *
     * <p>同时认三种形状：OpenAI {@code choices[0].delta.content}、
     * Anthropic 风格 {@code delta.text}、以及纯文本 data。取不到返回空串。
     */
    public static String deltaText(String data) {
        if (data == null) return "";
        String t = data.trim();
        if (t.isEmpty() || "[DONE]".equals(t)) return "";
        if (t.charAt(0) == '{') {
            Object o = JsonAccess.parse(t);
            String a = JsonAccess.str(o, "choices[0].delta.content", null);
            if (a != null) return a;
            String b = JsonAccess.str(o, "choices[0].text", null);
            if (b != null) return b;
            String c = JsonAccess.str(o, "delta.text", null);
            if (c != null) return c;
            String e = JsonAccess.str(o, "content", null);
            if (e != null) return e;
            String f = JsonAccess.str(o, "message.content", null);
            if (f != null) return f;
            // 出错帧要能被看见，否则用户只会看到「一动不动」
            return ""; // 错误帧由 errorOf() 取，正文增量里不掺
        }
        return data;
    }

    /** 是不是一个错误帧（用于把服务端 4xx 包在 200 里的情况说清楚）。 */
    public static String errorOf(String data) {
        if (data == null) return "";
        String t = data.trim();
        if (t.isEmpty() || t.charAt(0) != '{') return "";
        Object o = JsonAccess.parse(t);
        String err = JsonAccess.str(o, "error.message", null);
        if (err == null) err = JsonAccess.str(o, "message", null);
        if (err == null) err = JsonAccess.str(o, "detail", null);
        return err == null ? "" : err;
    }
}
