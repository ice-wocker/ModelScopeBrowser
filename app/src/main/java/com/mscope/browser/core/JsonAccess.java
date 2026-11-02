package com.mscope.browser.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * {@code org.json} 的容错读取层。
 *
 * <p>为什么需要它：工具参数来自模型输出，类型几乎不可信 —— 该给数字的给了字符串，
 * 该给数组的给了单个对象，该给字符串的给了 {@code null}。直接用 {@code optString}
 * 拿到的是 {@code "null"} 或者空，最后报出来的错和真正原因毫无关系。
 *
 * <p>这里的约定是：**任何一次读取都不抛异常，读不到就给默认值**，并且提供
 * {@code path} 形式的深取（{@code "a.b[0].c"}），省得每处都写四层判空。
 */
public final class JsonAccess {

    private JsonAccess() {
    }

    /** 深取一个字段：支持 {@code a.b[0].c} 这种路径。取不到返回 null。 */
    public static Object get(Object root, String path) {
        if (root == null || path == null || path.isEmpty()) return root;
        Object cur = root;
        for (String seg : split(path)) {
            if (cur == null) return null;
            String name = seg;
            int idx = -1;
            int br = seg.indexOf('[');
            if (br >= 0 && seg.endsWith("]")) {
                name = seg.substring(0, br);
                try {
                    idx = Integer.parseInt(seg.substring(br + 1, seg.length() - 1));
                } catch (NumberFormatException e) {
                    idx = -1;
                }
            }
            if (!name.isEmpty()) {
                if (cur instanceof JSONObject) {
                    JSONObject o = (JSONObject) cur;
                    if (!o.has(name) || o.isNull(name)) return null;
                    cur = o.opt(name);
                } else {
                    return null;
                }
            }
            if (idx >= 0) {
                if (!(cur instanceof JSONArray)) return null;
                JSONArray a = (JSONArray) cur;
                if (idx >= a.length()) return null;
                cur = a.opt(idx);
            }
        }
        return cur;
    }

    /** 取字符串。数字与布尔会转成字符串，其他类型给默认值。 */
    public static String str(Object root, String path, String def) {
        Object v = get(root, path);
        if (v == null || v == JSONObject.NULL) return def;
        if (v instanceof String) return (String) v;
        if (v instanceof JSONObject || v instanceof JSONArray) return def;
        return String.valueOf(v);
    }

    public static int i(Object root, String path, int def) {
        Object v = get(root, path);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try {
                return (int) Double.parseDouble(((String) v).trim());
            } catch (NumberFormatException e) {
                return def;
            }
        }
        return def;
    }

    public static long l(Object root, String path, long def) {
        Object v = get(root, path);
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try {
                return Long.parseLong(((String) v).trim());
            } catch (NumberFormatException e) {
                return def;
            }
        }
        return def;
    }

    public static double d(Object root, String path, double def) {
        Object v = get(root, path);
        if (v instanceof Number) return ((Number) v).doubleValue();
        if (v instanceof String) {
            try {
                return Double.parseDouble(((String) v).trim());
            } catch (NumberFormatException e) {
                return def;
            }
        }
        return def;
    }

    public static boolean bool(Object root, String path, boolean def) {
        Object v = get(root, path);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        if (v instanceof String) {
            String s = ((String) v).trim().toLowerCase(java.util.Locale.ROOT);
            if ("true".equals(s) || "1".equals(s) || "yes".equals(s) || "on".equals(s)) return true;
            if ("false".equals(s) || "0".equals(s) || "no".equals(s) || "off".equals(s)) return false;
        }
        return def;
    }

    /** 取数组；标的（标量）会被包成单元素数组，缺失给空数组。 */
    public static List<Object> list(Object root, String path) {
        Object v = get(root, path);
        List<Object> out = new ArrayList<>();
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            for (int i = 0; i < a.length(); i++) {
                Object e = a.opt(i);
                if (e != null && e != JSONObject.NULL) out.add(e);
            }
        } else if (v != null && v != JSONObject.NULL) {
            out.add(v);
        }
        return out;
    }

    /** 取字符串数组。 */
    public static List<String> strList(Object root, String path) {
        List<Object> raw = list(root, path);
        List<String> out = new ArrayList<>(raw.size());
        for (Object o : raw) {
            if (o instanceof JSONObject || o instanceof JSONArray) continue;
            String s = String.valueOf(o);
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    /** 安全解析一段 JSON 文本（对象或数组），失败返回 null。模型常在外面裹一层 ```json。 */
    public static Object parse(String text) {
        String t = stripFence(text);
        if (t.isEmpty()) return null;
        char first = t.charAt(0);
        try {
            if (first == '{') return new JSONObject(t);
            if (first == '[') return new JSONArray(t);
        } catch (Exception ignored) {
            // 落到下面按「截取最外层括号」再试一次
        }
        int o = t.indexOf('{');
        int oa = t.indexOf('[');
        int start = o < 0 ? oa : (oa < 0 ? o : Math.min(o, oa));
        if (start < 0) return null;
        char open = t.charAt(start);
        char close = open == '{' ? '}' : ']';
        int end = t.lastIndexOf(close);
        if (end <= start) return null;
        String sub = t.substring(start, end + 1);
        try {
            return open == '{' ? new JSONObject(sub) : new JSONArray(sub);
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripFence(String text) {
        if (text == null) return "";
        String t = text.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) t = t.substring(nl + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        return t.trim();
    }

    /** 键名排序后的稳定序列化（用于比对两个 JSON 是否等价，不受键顺序影响）。 */
    public static String canonical(JSONObject o) {
        if (o == null) return "{}";
        List<String> keys = new ArrayList<>();
        for (Iterator<String> it = o.keys(); it.hasNext(); ) keys.add(it.next());
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder("{");
        for (String k : keys) {
            if (sb.length() > 1) sb.append(',');
            sb.append(JSONObject.quote(k)).append(':').append(String.valueOf(o.opt(k)));
        }
        return sb.append('}').toString();
    }

    private static List<String> split(String path) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '.') {
                if (cur.length() > 0) out.add(cur.toString());
                cur.setLength(0);
            } else if (c == '[') {
                int close = path.indexOf(']', i);
                if (close < 0) {
                    cur.append(path.substring(i));
                    break;
                }
                if (cur.length() > 0) out.add(cur.toString());
                cur.setLength(0);
                out.add(path.substring(i, close + 1));
                i = close;
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }
}
