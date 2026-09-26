package com.mscope.browser;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 一条魔搭模型记录。字段与 ModelScope /api/v1/models/{ns}/{name} 返回的 Data 结构一致。 */
public class ModelItem {

    public String name = "";
    public String owner = "";
    public String chineseName = "";
    public long downloads = 0;
    public long stars = 0;
    public String license = "";
    public String task = "";
    public String tags = "";
    public String description = "";
    public long createdTime = 0;
    public long updatedTime = 0;

    public String displayName() {
        return chineseName == null || chineseName.isEmpty() ? name : chineseName;
    }

    public String fullName() {
        return (owner == null || owner.isEmpty()) ? name : owner + "/" + name;
    }

    public String webUrl() {
        return ModelApi.BASE + "/models/" + fullName();
    }

    public String createdText() {
        return fmt(createdTime);
    }

    public String updatedText() {
        return fmt(updatedTime);
    }

    private static String fmt(long seconds) {
        if (seconds <= 0) return "-";
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
        return sdf.format(new Date(seconds * 1000L));
    }

    public static ModelItem from(JSONObject o) {
        ModelItem m = new ModelItem();
        if (o == null) return m;
        m.name = o.optString("Name", o.optString("name", ""));
        m.owner = o.optString("Path", o.optString("Namespace", o.optString("namespace", "")));
        m.chineseName = o.optString("ChineseName", "");
        m.downloads = toLong(o.opt("Downloads"));
        m.stars = toLong(o.opt("Stars"));
        m.license = o.optString("License", "");
        m.task = firstTask(o.optJSONArray("Tasks"));
        m.tags = joinArray(o.optJSONArray("Tags"));
        m.description = o.optString("Description", "");
        m.createdTime = toLong(o.opt("CreatedTime"));
        m.updatedTime = toLong(o.opt("LastUpdatedTime"));
        if (m.owner.isEmpty() && m.name.contains("/")) {
            int i = m.name.indexOf('/');
            m.owner = m.name.substring(0, i);
            m.name = m.name.substring(i + 1);
        }
        return m;
    }

    private static String firstTask(JSONArray arr) {
        if (arr == null || arr.length() == 0) return "";
        JSONObject t = arr.optJSONObject(0);
        if (t == null) return "";
        String cn = t.optString("ChineseName", "");
        return cn.isEmpty() ? t.optString("Name", "") : cn;
    }

    private static String joinArray(JSONArray arr) {
        if (arr == null || arr.length() == 0) return "";
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "");
            if (!s.isEmpty()) parts.add(s);
        }
        return android.text.TextUtils.join(" · ", parts);
    }

    public static long toLong(Object v) {
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (Exception e) {
            return 0;
        }
    }
}