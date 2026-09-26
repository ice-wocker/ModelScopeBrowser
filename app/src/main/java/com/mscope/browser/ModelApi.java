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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 魔搭 ModelScope 数据访问层。
 *
 * 列表：POST /api/v1/dolphin/models（支持 SortBy/Order 排序、SingleCriterion 任务筛选），
 *      失败时按「去掉筛选 → 去掉排序 → GET → 首页聚合」逐级降级，并通过 Page 上的
 *      sortApplied / filterApplied / fallback 标记告知调用方实际生效情况。
 * 详情：GET /api/v1/models/{ns}/{name}
 * 文件：GET /api/v1/models/{ns}/{name}/repo/files?Revision=master&Recursive=true
 * 下载：GET /api/v1/models/{ns}/{name}/repo?Revision=master&FilePath={path}
 * 任务树：GET /api/v1/tasks
 */
public class ModelApi {

    public static final String BASE = "https://www.modelscope.cn";

    public static final String SORT_DEFAULT = "Default";
    public static final String SORT_DOWNLOADS = "DownloadsCount";
    public static final String SORT_STARS = "StarsCount";
    public static final String SORT_UPDATED = "GmtModified";

    private static final String UA =
            "Mozilla/5.0 (Linux; Android " + Build.VERSION.RELEASE + ") AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    public static class Page {
        public final List<ModelItem> items = new ArrayList<>();
        public int total = -1;
        public String error;
        /** 走的是首页聚合兜底：只有第一页数据，不可继续分页 */
        public boolean fallback;
        public boolean sortApplied = true;
        public boolean filterApplied = true;
    }

    /** 魔搭任务类型（用于筛选）。 */
    public static class Task {
        public final String name;
        public final String label;
        public final String domain;

        public Task(String name, String label, String domain) {
            this.name = name;
            this.label = label;
            this.domain = domain;
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

    private static class Attempt {
        final String body;
        final boolean filter;
        final boolean sort;

        Attempt(String body, boolean filter, boolean sort) {
            this.body = body;
            this.filter = filter;
            this.sort = sort;
        }
    }

    public static Page listModels(int page, int pageSize, String keyword,
                                  String sortBy, String order, String taskFilter) {
        Page p = new Page();
        final String kw = keyword == null ? "" : keyword.trim();
        final String sort = (sortBy == null || sortBy.isEmpty()) ? SORT_DEFAULT : sortBy;
        final String ord = (order == null || order.isEmpty()) ? "desc" : order;
        final boolean hasFilter = taskFilter != null && !taskFilter.isEmpty();
        final boolean hasSort = !SORT_DEFAULT.equals(sort);

        List<Attempt> attempts = new ArrayList<>();
        addAttempt(attempts, new Attempt(buildPayload(page, pageSize, kw, sort, ord, taskFilter), hasFilter, hasSort));
        if (hasFilter) {
            addAttempt(attempts, new Attempt(buildPayload(page, pageSize, kw, sort, ord, null), false, hasSort));
        }
        if (hasFilter || hasSort) {
            addAttempt(attempts, new Attempt(buildPayload(page, pageSize, kw, SORT_DEFAULT, ord, null), false, false));
        }

        for (Attempt a : attempts) {
            try {
                String resp = http("POST", BASE + "/api/v1/dolphin/models", a.body);
                if (parsePage(resp, p)) {
                    p.filterApplied = a.filter;
                    p.sortApplied = a.sort;
                    return p;
                }
            } catch (Exception e) {
                p.error = e.getMessage();
            }
        }

        try {
            String url = BASE + "/api/v1/dolphin/models?PageSize=" + pageSize + "&PageNumber=" + page
                    + "&SortBy=" + URLEncoder.encode(SORT_DEFAULT, "UTF-8") + "&Order=" + ord
                    + "&Name=" + URLEncoder.encode(kw, "UTF-8")
                    + "&Public=true&SingleCriterion=&Criterion=";
            String resp = http("GET", url, null);
            if (parsePage(resp, p)) {
                p.filterApplied = !hasFilter;
                p.sortApplied = !hasSort;
                return p;
            }
        } catch (Exception e) {
            p.error = e.getMessage();
        }

        if (page <= 1 && !hasFilter && !hasSort) {
            try {
                String resp = http("GET", BASE + "/api/v1/dolphin/agg/homepage", null);
                if (parsePage(resp, p)) {
                    p.fallback = true;
                    p.error = null;
                    return p;
                }
            } catch (Exception e) {
                p.error = e.getMessage();
            }
        }
        return p;
    }

    private static void addAttempt(List<Attempt> list, Attempt a) {
        for (Attempt x : list) {
            if (x.body.equals(a.body)) return;
        }
        list.add(a);
    }

    private static String buildPayload(int page, int pageSize, String kw,
                                       String sortBy, String order, String taskFilter) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"PageSize\":").append(pageSize)
                .append(",\"PageNumber\":").append(page)
                .append(",\"SortBy\":").append(JSONObject.quote(sortBy))
                .append(",\"Order\":").append(JSONObject.quote(order))
                .append(",\"Name\":").append(JSONObject.quote(kw));
        if (taskFilter != null && !taskFilter.isEmpty()) {
            sb.append(",\"SingleCriterion\":[{\"category\":\"tasks\",\"predicate\":\"contains\",\"values\":[")
                    .append(JSONObject.quote(taskFilter)).append("]}]");
        } else {
            sb.append(",\"SingleCriterion\":[]");
        }
        sb.append(",\"Criterion\":[]}");
        return sb.toString();
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
                String type = o.optString("Type", "blob");
                if (!"blob".equalsIgnoreCase(type)) continue;
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

    /* ------------------------------------------------------------- 任务接口 */

    public static List<Task> listTasks() throws Exception {
        String resp = http("GET", BASE + "/api/v1/tasks", null);
        JSONObject root = new JSONObject(resp);
        JSONObject data = root.optJSONObject("Data");
        JSONArray domains = data != null ? data.optJSONArray("Domains") : null;

        Map<String, Task> map = new LinkedHashMap<>();
        if (domains != null) {
            for (int i = 0; i < domains.length(); i++) {
                JSONObject d = domains.optJSONObject(i);
                if (d == null) continue;
                String domain = d.optString("ChineseName", d.optString("DomainName", ""));
                collectTasks(d.optJSONArray("Tasks"), domain, map, 0);
            }
        }
        return new ArrayList<>(map.values());
    }

    private static void collectTasks(JSONArray arr, String domain, Map<String, Task> out, int depth) {
        if (arr == null || depth > 3) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject t = arr.optJSONObject(i);
            if (t == null) continue;
            String name = t.optString("Name", "");
            if (!name.isEmpty() && !out.containsKey(name)) {
                String label = t.optString("ChineseName", name);
                if (label.isEmpty()) label = name;
                out.put(name, new Task(name, label, domain));
            }
            collectTasks(t.optJSONArray("Tasks"), domain, out, depth + 1);
        }
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