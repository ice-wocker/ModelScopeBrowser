package com.mscope.browser.agent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 开关式联网检索：抓取公开搜索结果页并解析出标题 / 链接 / 摘要。
 *
 * <p>不依赖任何 API Key，开箱即用。主用 DuckDuckGo 的轻量 HTML 版（结构简单、无反爬跳转），
 * 失败时退回 Bing 网页结果。两个来源都失败会抛异常，由调用方提示用户。
 */
public class WebSearch {

    public static class Result {
        public final String title;
        public final String url;
        public final String snippet;

        public Result(String title, String url, String snippet) {
            this.title = title;
            this.url = url;
            this.snippet = snippet;
        }
    }

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private static final Pattern DDG_ITEM = Pattern.compile(
            "class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern DDG_SNIPPET = Pattern.compile(
            "class=\"result__snippet\"[^>]*>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern BING_ITEM = Pattern.compile(
            "<h2><a[^>]*href=\"(https?://[^\"]+)\"[^>]*>(.*?)</a></h2>", Pattern.DOTALL);
    private static final Pattern BING_SNIPPET = Pattern.compile(
            "<p[^>]*class=\"b_lineclamp[^\"]*\"[^>]*>(.*?)</p>", Pattern.DOTALL);

    private WebSearch() {
    }

    /** 检索关键词，返回最多 limit 条结果（可能为空列表）。 */
    public static List<Result> search(String query, int limit) throws IOException {
        IOException last = null;
        try {
            List<Result> r = parseDdg(fetch("https://html.duckduckgo.com/html/?q="
                    + URLEncoder.encode(query, "UTF-8")), limit);
            if (!r.isEmpty()) return r;
        } catch (IOException e) {
            last = e;
        }
        try {
            List<Result> r = parseBing(fetch("https://www.bing.com/search?q="
                    + URLEncoder.encode(query, "UTF-8") + "&setlang=zh-CN"), limit);
            if (!r.isEmpty()) return r;
        } catch (IOException e) {
            last = e;
        }
        if (last != null) throw last;
        return new ArrayList<>();
    }

    /** 把结果整理成 markdown，既用于注入模型上下文，也用于界面上的工具卡片。 */
    public static String format(List<Result> results) {
        if (results == null || results.isEmpty()) return "（没有检索到结果）";
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (Result r : results) {
            sb.append(i++).append(". **").append(r.title).append("**\n")
              .append("   ").append(r.snippet).append("\n")
              .append("   ").append(r.url).append("\n");
        }
        return sb.toString().trim();
    }

    /* ------------------------------------------------------------------ 实现 */

    private static String fetch(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml");
        c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (is == null) throw new IOException("HTTP " + code);
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
            String line;
            int guard = 0;
            while ((line = br.readLine()) != null && guard++ < 4000) sb.append(line).append('\n');
        }
        if (code >= 400) throw new IOException("HTTP " + code);
        return sb.toString();
    }

    private static List<Result> parseDdg(String html, int limit) {
        List<Result> out = new ArrayList<>();
        Matcher m = DDG_ITEM.matcher(html);
        List<String> snippets = all(DDG_SNIPPET, html);
        int i = 0;
        while (m.find() && out.size() < limit) {
            String url = cleanUrl(m.group(1));
            String title = text(m.group(2));
            String snip = i < snippets.size() ? text(snippets.get(i)) : "";
            i++;
            if (url.isEmpty() || title.isEmpty()) continue;
            out.add(new Result(title, url, snip));
        }
        return out;
    }

    private static List<Result> parseBing(String html, int limit) {
        List<Result> out = new ArrayList<>();
        Matcher m = BING_ITEM.matcher(html);
        List<String> snippets = all(BING_SNIPPET, html);
        int i = 0;
        while (m.find() && out.size() < limit) {
            String url = m.group(1);
            String title = text(m.group(2));
            String snip = i < snippets.size() ? text(snippets.get(i)) : "";
            i++;
            if (title.isEmpty()) continue;
            out.add(new Result(title, url, snip));
        }
        return out;
    }

    private static List<String> all(Pattern p, String s) {
        List<String> out = new ArrayList<>();
        Matcher m = p.matcher(s);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** DDG 的结果链接常带 uddg 跳转参数，取出真实地址。 */
    private static String cleanUrl(String href) {
        String h = href;
        if (h.startsWith("//")) h = "https:" + h;
        int i = h.indexOf("uddg=");
        if (i >= 0) {
            String enc = h.substring(i + 5);
            int amp = enc.indexOf('&');
            if (amp >= 0) enc = enc.substring(0, amp);
            try {
                return URLDecoder.decode(enc, "UTF-8");
            } catch (Exception ignored) {
            }
        }
        return h;
    }

    /** 去标签 + 解码常见实体。 */
    private static String text(String raw) {
        if (raw == null) return "";
        String s = raw.replaceAll("<[^>]+>", " ");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
             .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
             .replace("&#x27;", "'").replace("&hellip;", "…");
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }
}