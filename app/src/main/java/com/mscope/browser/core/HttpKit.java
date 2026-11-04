package com.mscope.browser.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 极小的 HTTP 取文本工具：固定超时、限长读取、有意义的错误信息。
 *
 * <p>为什么不直接用 {@link HttpURLConnection}：原来的三处联网代码各写一遍超时，
 * 而且 {@code new URL(x).openConnection()} 没有连接/读超时的统一上限 —— 本地模型给出
 * 一个黑洞地址，智能体线程就会一直挂在那里，用户按「停止」也不会返回（停止只在下一次
 * 回调检查）。这里把超时、限长、关闭连接收在一处。</p>
 */
public final class HttpKit {

    private HttpKit() {
    }

    /**
     * GET 一个地址并读取文本。
     *
     * @param body 发送体；null 表示 GET，否则用 POST 发送（UTF-8）
     */
    public static String get(String url, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setInstanceFollowRedirects(true);
            if (body != null) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) throw new IOException("HTTP " + code);
            String text = readLimited(in, 4 * 1024 * 1024);
            if (code >= 400) throw new IOException("HTTP " + code);
            return text;
        } finally {
            c.disconnect();
        }
    }

    /** 读流并限制最大字节数，防止一个超大响应把内存吃穿。 */
    public static String readLimited(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            int room = maxBytes - bos.size();
            if (room <= 0) break;
            bos.write(buf, 0, Math.min(n, room));
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
