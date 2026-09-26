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
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * 魔搭 ModelScope 数据访问层（接口均经实测确认）。
 *
 * 列表：PUT /api/v1/dolphin/models
 *   body: {"PageSize":N,"PageNumber":P,"Name":"关键字","SortBy":"Default|DownloadsCount|StarsCount|GmtModified",
 *          "Order":"desc","Criterion":[{"category":"license","predicate":"contains","values":["mit"]}]}
 *   返回: Data.Model.Models[] + Data.Model.TotalCount + Data.FiledAgg（可选筛选维度的聚合计数）
 *   注意：分页与筛选都走 PUT；用 POST/GET 访问该路径会返回 404。
 *   注意：筛选参数名是 Criterion；SingleCriterion 会被服务端忽略。
 *
 * 详情：GET /api/v1/models/{ns}/{name}
 * 文件：GET /api/v1/models/{ns}/{name}/repo/files?Revision=master&Recursive=true
 * 下载：GET /api/v1/models/{ns}/{name}/repo?Revision=master&FilePath={path}
 */
public class ModelApi {

    public static final String BASE = "https://www.modelscope.cn";

    public static final String SORT_DEFAULT = "Default";
    public static final String SORT_DOWNLOADS = "DownloadsCount";
    public static final String SORT_STARS = "StarsCount";
    public static final String SORT_UPDATED = "GmtModified";

    /** 服务端真正支持的筛选维度（来自 Data.FiledAgg 的字段名）。 */
    private static final String[] FACET_ORDER =
            {"license", "libraries", "tags", "language", "model_type", "nexa_catalog"};

    private static final String UA =
            "Mozilla/5.0 (Linux; Android " + Build.VERSION.RELEASE + ") AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    public static class Page {
        public final List<ModelItem> items = new ArrayList<>();
        public final List<Facet> facets = new ArrayList<>();
        public int total = -1;
        public String error;
        /** 全部接口都失败时退化为首页聚合数据：只有一页，不可继续分页 */
        public boolean fallback;
        public boolean sortApplied = true;
        public boolean filterApplied = true;
    }

    /** 一个可用的筛选维度取值，如 「许可证: mit (9840)」。 */
    public static class Facet {
        public final String group;
        public final String groupLabel;
        public final String value;
        public final long count;

        public Facet(String group, String groupLabel, String value, long count) {
            this.group = group;
            this.groupLabel = groupLabel;
            this.value = value;
            this.count = count;
        }
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

    public static Page listModels(int page, int pageSize, String keyword,
                                  String sortBy, String order,
                                  String filterCategory, String filterValue) {
        Page p = new Page();
        final String kw = keyword == null ? "" : keyword.trim();
        final String sort = (sortBy == null || sortBy.isEmpty()) ? SORT_DEFAULT : sortBy;
        final String ord = (order == null || order.isEmpty()) ? "desc" : order;
        final boolean hasFilter = filterCategory != null && !filterCategory.isEmpty()
                && filterValue != null && !filterValue.isEmpty();
        final boolean hasSort = !SORT_DEFAULT.equals(sort);

        // 逐级降级：完整参数 → 去掉筛选 → 再去掉排序。服务端目前都支持，这里只是兜底。
        List<String> payloads = new ArrayList<>();
        payloads.add(buildPayload(page, pageSize, kw, sort, ord,
                hasFilter ? filterCategory : null, hasFilter ? filterValue : null));
        if (hasFilter) {
            payloads.add(buildPayload(page, pageSize, kw, sort, ord, null, null));
        }
        if (hasFilter || hasSort) {
            payloads.add(buildPayload(page, pageSize, kw, SORT_DEFAULT, ord, null, null));
        }

        for (int i = 0; i < payloads.size(); i++) {
            try {
                String resp = http("PUT", BASE + "/api/v1/dolphin/models", payloads.get(i));
                if (parsePage(resp, p)) {
                    boolean usedFilter = i == 0 && hasFilter;
                    boolean usedSort = i <= 1;
                    p.filterApplied = !hasFilter || usedFilter;
                    p.sortApplied = !hasSort || (usedSort && i <= 1);
                    return p;
                }
            } catch (Exception e) {
                p.error = e.getMessage();
            }
        }

        // 最后兜底：首页聚合数据（只有第一页，且不带筛选/排序）
        if (page <= 1) {
            try {
                String resp = http("GET", BASE + "/api/v1/dolphin/agg/homepage", null);
                if (parsePage(resp, p)) {
                    p.fallback = true;
                    p.filterApplied = !hasFilter;
                    p.sortApplied = !hasSort;
                    p.error = null;
                    return p;
                }
            } catch (Exception e) {
                p.error = e.getMessage();
            }
        }
        return p;
    }

    private static String buildPayload(int page, int pageSize, String kw,
                                       String sortBy, String order,
                                       String filterCategory, String filterValue) {
        String criterion = "[]";
        if (filterCategory != null && !filterCategory.isEmpty()
                && filterValue != null && !filterValue.isEmpty()) {
            criterion = "[{\"category\":" + JSONObject.quote(filterCategory)
                    + ",\"predicate\":\"contains\",\"values\":[" + JSONObject.quote(filterValue) + "]}]";
        }
        return "{\"PageSize\":" + pageSize
                + ",\"PageNumber\":" + page
                + ",\"Name\":" + JSONObject.quote(kw)
                + ",\"SortBy\":" + JSONObject.quote(sortBy)
                + ",\"Order\":" + JSONObject.quote(order)
                + ",\"Target\":\"\""
                + ",\"SingleCriterion\":[]"
                + ",\"Criterion\":" + criterion + "}";
    }

    /* ------------------------------------------------------------- 详情接口 */

    public static ModelItem getModelDetail(String namespace, String name) throws Exception {
        String resp = http("GET", BASE + "/api/v1/models/" + namespace + "/" + name, null);
        JSONObject root = new JSONObject(resp);
        JSONObject data = root.optJSONObject("Data");
        if (data == null) data = root;
        ModelItem m = ModelItem.from(data);
        if (m.owner.isEmpty()) m.owner = namespace;
        if (m.name.isEmpty()) m.name = name;
        return m;
    }

    /* ------------------------------------------------------------- 文件接口 */

    public static List<ModelFile> listFiles(String namespace, String name) throws Exception {
        String url = BASE + "/api/v1/models/" + namespace + "/" + name
                + "/repo/files?Revision=master&Recursive=true";
        String resp = http("GET", url, null);
        JSONObject root = new JSONObject(resp);
        JSONObject data = root.optJSONObject("Data");
        JSONArray arr = data != null ? data.optJSONArray("Files") : root.optJSONArray("Files");

        List<ModelFile> out = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                if (!"blob".equalsIgnoreCase(o.optString("Type", "blob"))) continue;
                ModelFile f = new ModelFile();
                f.path = o.optString("Path", o.optString("Name", ""));
                f.size = ModelItem.toLong(o.opt("Size"));
                f.lfs = o.optBoolean("IsLFS", false);
                if (!f.path.isEmpty()) out.add(f);
            }
        }
        Collections.sort(out, (a, b) -> a.path.compareToIgnoreCase(b.path));
        return out;
    }

    public static String downloadUrl(String namespace, String name, String filePath) throws Exception {
        return BASE + "/api/v1/models/" + namespace + "/" + name
                + "/repo?Revision=master&FilePath=" + URLEncoder.encode(filePath, "UTF-8");
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
            p.facets.clear();
            p.facets.addAll(parseFacets(root));

            int total = -1;
            if (c.parent != null) total = (int) ModelItem.toLong(c.parent.opt("TotalCount"));
            if (total <= 0) total = findInt(root, "TotalCount");
            p.total = total > 0 ? total : -1;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 解析 Data.FiledAgg，得到服务端真正支持的筛选维度与计数。 */
    private static List<Facet> parseFacets(JSONObject root) {
        List<Facet> out = new ArrayList<>();
        JSONObject data = root.optJSONObject("Data");
        JSONObject agg = data != null ? data.optJSONObject("FiledAgg") : null;
        if (agg == null) agg = root.optJSONObject("FiledAgg");
        if (agg == null) return out;

        for (String cat : FACET_ORDER) {
            JSONArray arr = agg.optJSONArray(cat);
            if (arr == null) continue;
            int n = Math.min(arr.length(), 40);
            for (int i = 0; i < n; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String value = o.optString("Value", "");
                if (value.isEmpty()) continue;
                out.add(new Facet(cat, facetLabel(cat), value, ModelItem.toLong(o.opt("Count"))));
            }
        }
        return out;
    }

    public static String facetLabel(String group) {
        if (group == null) return "";
        switch (group) {
            case "license":
                return "许可证";
            case "libraries":
                return "框架库";
            case "tags":
                return "标签";
            case "language":
                return "语言";
            case "model_type":
                return "模型结构";
            case "nexa_catalog":
                return "领域";
            default:
                return group;
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