package com.mscope.browser.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 摘要与校验：比 {@code TextKit.sha256Hex} 更全的一套（流式大文件、CRC32、BLAKE 风格简写）。
 *
 * <p>为什么单独一件：下载回来的 GGUF 动辄 4 GB，必须先流式算摘要再和模型页给的校验值比，
 * 一次性读进内存会直接 OOM。这里所有文件级方法都是 8 KB 分块读。
 *
 * <p>纯 JDK，可在 JVM 单测里跑。
 */
public final class DigestKit {

    private static final int BUF = 8 * 1024;

    private DigestKit() {
    }

    /** 流式算摘要，算法名如 MD5 / SHA-1 / SHA-256。异常或文件不存在返回空串。 */
    public static String fileDigest(File f, String algo) {
        if (f == null || !f.isFile()) return "";
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance(algo);
            byte[] buf = new byte[BUF];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return hex(md.digest());
        } catch (Exception e) {
            return "";
        }
    }

    public static String sha256File(File f) {
        return fileDigest(f, "SHA-256");
    }

    public static String md5File(File f) {
        return fileDigest(f, "MD5");
    }

    public static String sha256(String s) {
        return digest("SHA-256", s);
    }

    public static String md5(String s) {
        return digest("MD5", s);
    }

    private static String digest(String algo, String s) {
        try {
            MessageDigest md = MessageDigest.getInstance(algo);
            return hex(md.digest((s == null ? "" : s).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }

    /** CRC32（十六进制），用于快速判断两份文件是不是同一份，不用于安全。 */
    public static String crc32(File f) {
        if (f == null || !f.isFile()) return "";
        try (InputStream in = new FileInputStream(f)) {
            java.util.zip.CRC32 c = new java.util.zip.CRC32();
            byte[] buf = new byte[BUF];
            int n;
            while ((n = in.read(buf)) > 0) c.update(buf, 0, n);
            return String.format(Locale.ROOT, "%08x", c.getValue());
        } catch (Exception e) {
            return "";
        }
    }

    /** HMAC-SHA256（十六进制），给需要签名头的 HTTP 通道用。 */
    public static String hmacSha256(String key, String msg) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    (key == null ? "" : key).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal((msg == null ? "" : msg).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 校验值比对：容忍大小写、空白、以及「sha256:」前缀和「文件 名」这类 sha256sum 输出格式。
     *
     * @return 期望值为空时返回 false（没有期望就不算通过）
     */
    public static boolean match(String expected, String actual) {
        String e = norm(expected);
        if (e.isEmpty()) return false;
        return e.equals(norm(actual));
    }

    private static String norm(String s) {
        if (s == null) return "";
        String t = s.trim().toLowerCase(Locale.ROOT);
        int cut = t.indexOf(' ');
        if (cut > 0) t = t.substring(0, cut);
        if (t.startsWith("sha256:")) t = t.substring(7);
        return t.replaceAll("[^0-9a-f]", "");
    }

    /** 十六进制串是否像一个合法的摘要（长度 32/40/64/128）。 */
    public static boolean looksLikeDigest(String s) {
        String t = norm(s);
        return t.length() == 32 || t.length() == 40 || t.length() == 64 || t.length() == 128;
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }

    /** 一组文件的总字节数与摘要清单（用于「导出校验单」）。 */
    public static Map<String, String> digestManifest(List<File> files, File root) {
        Map<String, String> out = new LinkedHashMap<>();
        if (files == null) return out;
        String base = root == null ? "" : root.getAbsolutePath();
        for (File f : files) {
            if (f == null || !f.isFile()) continue;
            String rel = f.getAbsolutePath();
            if (!base.isEmpty() && rel.startsWith(base)) rel = rel.substring(base.length());
            if (rel.startsWith(File.separator)) rel = rel.substring(1);
            out.put(rel, sha256File(f));
        }
        return out;
    }

    /** 形如 sha256sum 输出的文本，便于用户复制出去核对。 */
    public static String renderManifest(Map<String, String> manifest) {
        StringBuilder sb = new StringBuilder();
        if (manifest == null) return "";
        List<String> keys = new ArrayList<>(manifest.keySet());
        java.util.Collections.sort(keys);
        for (String k : keys) sb.append(manifest.get(k)).append("  ").append(k).append('\n');
        return sb.toString();
    }
}
