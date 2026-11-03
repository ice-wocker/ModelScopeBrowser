package com.mscope.browser.agent;

import com.mscope.browser.core.TextKit;
import com.mscope.browser.i18n.Lang;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 极简 HTTP 客户端（只用 {@link HttpURLConnection}，不加依赖）。
 *
 * <p>三条硬约束，都是为了「模型不能拿它当跳板」：
 * <ul>
 *   <li>只允许 http/https，且拒绝内网与回环地址（{@link WebSearch#allowedForFetch}）；</li>
 *   <li>重定向**手动跟随并复查每一跳**：默认的自动跟随会绕过第一跳的地址检查；</li>
 *   <li>响应有字节上限，避免一个 4 GB 的地址把手机撑爆。</li>
 * </ul>
 *
 * <p>没有 OkHttp 的原因很实际：release 包 8 MB，为一个「读网页 / 发一条 JSON」的需求
 * 引入 1 MB 的库不划算，而这里的场景不需要连接池与 HTTP/2。
 */
public final class Http {

    /** 一次响应。 */
    public static final class Resp {
        public final int code;
        public final String body;
        public final String contentType;
        public final String finalUrl;
        public final Map<String, String> headers;

        Resp(int code, String body, String contentType, String finalUrl, Map<String, String> headers) {
            this.code = code;
            this.body = body == null ? "" : body;
            this.contentType = contentType == null ? "" : contentType;
            this.finalUrl = finalUrl == null ? "" : finalUrl;
            this.headers = headers == null ? new LinkedHashMap<>() : headers;
        }

        public boolean ok() {
            return code >= 200 && code < 300;
        }
    }

    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;
    private static final int TIMEOUT = 20000;

    private Http() {
    }

    public static Resp get(String url, Map<String, String> headers) {
        return request("GET", url, null, null, headers, MAX_BYTES);
    }

    public static Resp postJson(String url, String json, Map<String, String> headers) {
        return request("POST", url, "application/json", json, headers, MAX_BYTES);
    }

    /**
     * 发请求。
     *
     * @param body 仅 POST/PUT/PATCH 有意义
     */
    public static Resp request(String method, String url, String contentType, String body,
                               Map<String, String> headers, int maxBytes) {
        String target = url == null ? "" : url.trim();
        String m = (method == null ? "GET" : method.toUpperCase(Locale.ROOT));
        int limit = maxBytes <= 0 ? MAX_BYTES : Math.min(maxBytes, MAX_BYTES);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (!WebSearch.allowedForFetch(target)) {
                return new Resp(-1,
                        Lang.pick("错误：只允许访问公网 http/https 地址（本机与内网已拒绝）",
                                "error: only public http/https URLs are allowed"),
                        "", target, null);
            }
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(target).openConnection();
                c.setRequestMethod(m);
                c.setConnectTimeout(TIMEOUT);
                c.setReadTimeout(TIMEOUT);
                c.setInstanceFollowRedirects(false);
                c.setRequestProperty("User-Agent", "ModelScopeBrowser/agent");
                c.setRequestProperty("Accept", "*/*");
                if (headers != null) {
                    for (Map.Entry<String, String> e : headers.entrySet()) {
                        if (e.getKey() != null && e.getValue() != null
                                && !"host".equalsIgnoreCase(e.getKey())) {
                            c.setRequestProperty(e.getKey(), e.getValue());
                        }
                    }
                }
                boolean hasBody = body != null && !"GET".equals(m) && !"HEAD".equals(m);
                if (hasBody) {
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type",
                            contentType == null ? "application/octet-stream" : contentType);
                    byte[] data = body.getBytes(StandardCharsets.UTF_8);
                    c.setFixedLengthStreamingMode(data.length);
                    try (OutputStream out = c.getOutputStream()) {
                        out.write(data);
                    }
                }
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    if (loc != null && !loc.isEmpty()) {
                        target = absolutize(target, loc);
                        continue;
                    }
                }
                Map<String, String> hs = new LinkedHashMap<>();
                for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
                    if (e.getKey() == null || e.getValue() == null || e.getValue().isEmpty()) continue;
                    hs.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().get(0));
                }
                String ct = c.getContentType() == null ? "" : c.getContentType();
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                String text = readLimited(in, limit);
                return new Resp(code, text, ct, target, hs);
            } catch (Exception e) {
                return new Resp(-1, (Lang.zh() ? "请求失败：" : "request failed: ") + e.getMessage(),
                        "", target, null);
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return new Resp(-1, Lang.pick("错误：重定向次数过多", "error: too many redirects"), "", target, null);
    }

    private static String absolutize(String base, String loc) {
        try {
            return new URL(new URL(base), loc).toString();
        } catch (Exception e) {
            return loc;
        }
    }

    private static String readLimited(InputStream in, int limit) {
        if (in == null) return "";
        try (InputStream is = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                if (bos.size() + n > limit) {
                    bos.write(buf, 0, Math.max(0, limit - bos.size()));
                    break;
                }
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 流式下载到文件。
     *
     * @return 实际写入的字节数；-1 表示失败
     */
    public static long download(String url, java.io.File dest, long maxBytes) {
        if (!WebSearch.allowedForFetch(url)) return -1;
        HttpURLConnection c = null;
        try {
            // 重定向手动跟随并逐跳复查：自动跟随时 HttpClient 不会重新做地址校验，
            // 一个公网地址 302 到 http://192.168.1.1/ 就能把本机内网当跳板。
            String target = url.trim();
            int code = -1;
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                if (!WebSearch.allowedForFetch(target)) return -1;
                c = (HttpURLConnection) new URL(target).openConnection();
                c.setConnectTimeout(TIMEOUT);
                c.setReadTimeout(TIMEOUT);
                c.setInstanceFollowRedirects(false);
                c.setRequestProperty("User-Agent", "ModelScopeBrowser/agent");
                code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    c.disconnect();
                    c = null;
                    if (loc == null || loc.isEmpty()) return -1;
                    target = absolutize(target, loc);
                    continue;
                }
                break;
            }
            if (c == null) return -1;
            if (code < 200 || code >= 300) return -1;
            long cap = maxBytes <= 0 ? 512L * 1024 * 1024 : maxBytes;
            long total = 0;
            // 目录必须先建：写到 <工作区>/下载/a.bin 时 parent 还不存在，
            // FileOutputStream 会直接抛 FileNotFoundException，用户看到的是「下载失败」而不知道为什么。
            java.io.File parent = dest.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
                return -1;
            }
            java.io.File tmp = new java.io.File(parent, dest.getName() + ".part");
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > cap) {
                        //noinspection ResultOfMethodCallIgnored
                        tmp.delete();
                        return -1;
                    }
                    out.write(buf, 0, n);
                }
                out.getFD().sync();
            }
            if (dest.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dest.delete();
            }
            return tmp.renameTo(dest) ? total : -1;
        } catch (Exception e) {
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 给模型看的简短响应描述。 */
    public static String describe(Resp r, int maxChars, boolean zh) {
        if (r == null) return "";
        if (r.code < 0) return r.body;
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP ").append(r.code);
        if (!r.contentType.isEmpty()) sb.append(" · ").append(r.contentType);
        sb.append('\n');
        String body = r.body;
        if (body.length() > maxChars) body = TextKit.trimUtf8(body, maxChars) + (zh ? "\n…（已截断）" : "\n… (truncated)");
        return sb.append(body).toString();
    }
}
