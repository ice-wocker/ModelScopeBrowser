package com.mscope.browser;

import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * 魔搭 ModelScope 数据访问层。
 * 列表接口（官方为 POST /api/v1/dolphin/models）带多种降级方案，
 * 详情接口 GET /api/v1/models/{namespace}/{name} 已实测可用。
 */
public class ModelApi {

    public static final String BASE = "https://www.modelscope.cn";

    private static final String UA =
            "Mozilla/5.0 (Linux; Android " + Build.VERSION.RELEASE + ") AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    public static class Page {
        public final List<ModelItem> items = new ArrayList<>();
        public int total = -1;
        public String error;
    }

    /* ------------------------------------------------------------------ 网络 */

    public static String http(String method, String url, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(25000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "application/json, text/plain, */*");
        c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        c.setRequestProperty("Referer", BASE + "/models");
        c.setRequestProperty("Origin", BASE);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes("UTF-8"));
            }
        }
        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        StringBuilder sb = new StringBuilder();
        if (is != null) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
            }
        }
        if (code >= 400) {
            String snippet = sb.length() > 140 ? sb.substring(0, 140) : sb.toString();
            throw new IOException("HTTP " + code + " " + snippet.replace('\n', ' '));
        }
        return sb.toString();
    }

    /* ------------------------------------------------------------- 列表接口 */

    public static Page listModels(int page, int pageSize, String keyword) {
        Page p = new Page();
        final String kw = keyword == null ? "" : keyword.trim();

        String[] payloads = new String[]{
                "{\"PageSize\":" + pageSize + ",\"PageNumber\":" + page + ",\"SortBy\":\"Default\","
                        + "\"Target\":\"\",\"SingleCriterion\":[],\"Name\":" + JSONObject.quote(kw) + ",\"Criterion\":[]}",
                "{\"PageSize\":" + pageSize + ",\"PageNumber\":" + page + ",\"Name\":" + JSONObject.quote(kw)
                        + ",\"SingleCriterion\":[],\"Public\":true}",
                "{\"PageSize\":" + pageSize + ",\"PageNumber\":" + page + ",\"Query\":" + JSONObject.quote(kw)
                        + ",\"Criterion\":[],\"Sort\":\"default\"}"
        };

        for (String body : payloads) {
            try {
                String resp = http("POST", BASE + "/api/v1/dolphin/models", body);
                if (parsePage(resp, p)) return p;
            } catch (Exception e) {
                p.error = e.getMessage();
            }
        }

        try {
            String url = BASE + "/api/v1/dolphin/models?PageSize=" + pageSize + "&PageNumber=" + page
                    + "&SortBy=Default&Name=" + URLEncoder.encode(kw, "UTF-8")
                    + "&Public=true&Target=&SingleCriterion=";
            String resp = http("GET", url, null);
            if (parsePage(resp, p)) return p;
        } catch (Exception e) {
            p.error = e.getMessage();
        }

        if (page <= 1) {
            try {
                String resp = http("GET", BASE + "/api/v1/dolphin/agg/homepage", null);
                if (parsePage(resp, p)) {
                    p.error = null;
                    return p;
                }
            } catch (Exception e) {
                p.error = e.getMessage();
            }
        }
        return p;
    }

    /* ------------------------------------------------------------- 详情接口 */

    public static ModelItem getModelDetail(String namespace, String name) throws Exception {
        String url = BASE + "/api/v1/models/" + namespace + "/" + name;
        String resp = http("GET", url, null);
        JSONObject root = new JSONObject(resp);
        JSONObject data = root.optJSONObject("Data");
        if (data == null) data = root;
        ModelItem m = ModelItem.from(data);
        if (m.owner.isEmpty()) m.owner = namespace;
        if (m.name.isEmpty()) m.name = name;
        return m;
    }

    /* ----------------------------------------------------------- 宽松解析器 */

    private static boolean parsePage(String resp, Page p) {
        try {
            JSONObject root = new JSONObject(resp);
            Candidate c = findModelArray(root);
            if (c == null || c.array.length() == 0) return false;
            List<ModelItem> parsed = new ArrayList<>();
            for (int i = 0; i < c.array.length(); i++) {
                JSONObject o = c.array.optJSONObject(i);
                if (o != null && !o.optString("Name", "").isEmpty()) parsed.add(ModelItem.from(o));
            }
            if (parsed.isEmpty()) return false;
            p.items.clear();
            p.items.addAll(parsed);
            int total = -1;
            if (c.parent != null) total = (int) ModelItem.toLong(c.parent.opt("TotalCount"));
            if (total <= 0) total = findInt(root, "TotalCount");
            p.total = total > 0 ? total : -1;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static class Candidate {
        String key;
        JSONArray array;
        JSONObject parent;
    }

    /** 在任意层级的 JSON 中找到最像"模型数组"的那个数组（并记录其父对象以便取 TotalCount）。 */
    private static Candidate findModelArray(JSONObject root) {
        List<Candidate> candidates = new ArrayList<>();
        collect(root, "", null, candidates, 0);
        Candidate best = null;
        int bestScore = -1;
        for (Candidate c : candidates) {
            if (c.array.length() == 0) continue;
            JSONObject first = c.array.optJSONObject(0);
            if (first == null) continue;
            if (first.optString("Name", "").isEmpty()) continue;
            int score = 1;
            if (c.key.equalsIgnoreCase("Models")) score = 5;
            else if (c.key.equalsIgnoreCase("ModelList")) score = 4;
            else if (c.key.equalsIgnoreCase("List")) score = 3;
            else if (c.key.equalsIgnoreCase("Data")) score = 2;
            if (first.has("Path")) score += 3;
            if (first.has("Downloads")) score += 1;
            if (first.has("Tasks")) score += 1;
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return best;
    }

    private static void collect(Object o, String key, JSONObject parent, List<Candidate> out, int depth) {
        if (depth > 7 || o == null) return;
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            Iterator<String> it = j.keys();
            while (it.hasNext()) {
                String k = it.next();
                Object v = j.opt(k);
                if (v instanceof JSONArray) {
                    Candidate c = new Candidate();
                    c.key = k;
                    c.array = (JSONArray) v;
                    c.parent = j;
                    out.add(c);
                }
                collect(v, k, j, out, depth + 1);
            }
        } else if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            int n = Math.min(a.length(), 3);
            for (int i = 0; i < n; i++) collect(a.opt(i), key, parent, out, depth + 1);
        }
    }

    private static int findInt(JSONObject root, String targetKey) {
        Deque<Object> queue = new ArrayDeque<>();
        queue.add(root);
        int guard = 0;
        while (!queue.isEmpty() && guard++ < 5000) {
            Object o = queue.poll();
            if (o instanceof JSONObject) {
                JSONObject j = (JSONObject) o;
                Iterator<String> it = j.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    if (k.equalsIgnoreCase(targetKey)) {
                        long v = ModelItem.toLong(j.opt(k));
                        if (v > 0) return (int) v;
                    }
                    Object v = j.opt(k);
                    if (v instanceof JSONObject || v instanceof JSONArray) queue.add(v);
                }
            } else if (o instanceof JSONArray) {
                JSONArray a = (JSONArray) o;
                for (int i = 0; i < a.length(); i++) {
                    Object v = a.opt(i);
                    if (v instanceof JSONObject || v instanceof JSONArray) queue.add(v);
                }
            }
        }
        return -1;
    }
}