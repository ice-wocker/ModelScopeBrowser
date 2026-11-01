package com.mscope.browser.agent;

import com.mscope.browser.core.JsonAccess;
import com.mscope.browser.core.SseParser;
import com.mscope.browser.i18n.Lang;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 可选的外部模型通道（OpenAI 兼容：{@code POST {base}/chat/completions}）。
 *
 * <p>本地 3B 模型算得出「帮我改个按钮」，算不出「这两份文档哪里矛盾」。所以在允许联网时，
 * 给模型一个 {@code llm_ask} 工具，把真正难的子问题转给更大的模型 —— 不是取代端侧推理，
 * 而是让端侧智能体能**做决定**：哪些自己能干，哪些该外包。
 *
 * <p>默认关闭。Key 只存本机 SharedPreferences，不会进对话历史（工具结果里也不回显）。
 * 全程走 {@link Http}，所以同样受「公网才允许」的约束 —— 用户自建的
 * {@code http://192.168.1.5:11434/v1} 会被拒绝，这是刻意的：内网地址是 SSRF 的入口。
 */
public final class ExternalModel {

    /** 配置。 */
    public static final class Config {
        public String base = "";
        public String key = "";
        public String model = "";
        public int maxTokens = 1024;
        public double temperature = 0.3;

        public boolean usable() {
            return !base.trim().isEmpty() && !model.trim().isEmpty();
        }
    }

    /** 结果。 */
    public static final class Answer {
        public final boolean ok;
        public final String text;
        public final String error;
        public final int promptTokens;
        public final int completionTokens;

        Answer(boolean ok, String text, String error, int pt, int ct) {
            this.ok = ok;
            this.text = text == null ? "" : text;
            this.error = error == null ? "" : error;
            this.promptTokens = pt;
            this.completionTokens = ct;
        }
    }

    private final Config cfg;

    public ExternalModel(Config cfg) {
        this.cfg = cfg == null ? new Config() : cfg;
    }

    public boolean usable() {
        return cfg.usable();
    }

    /** 组装请求体（拆出来是为了单测能盯住这个形状）。 */
    public static String buildBody(String model, String prompt, String system, int maxTokens, double temp) {
        JSONObject root = new JSONObject();
        try {
            root.put("model", model);
            root.put("max_tokens", Math.max(16, Math.min(maxTokens, 8192)));
            root.put("temperature", Math.max(0, Math.min(temp, 2)));
            root.put("stream", false);
            JSONArray msgs = new JSONArray();
            if (system != null && !system.trim().isEmpty()) {
                msgs.put(new JSONObject().put("role", "system").put("content", system.trim()));
            }
            msgs.put(new JSONObject().put("role", "user").put("content", prompt == null ? "" : prompt));
            root.put("messages", msgs);
        } catch (Exception e) {
            return "{}";
        }
        return root.toString();
    }

    /** 解析响应；兼容 OpenAI 与少数只返回 {@code content} 的服务。 */
    public static Answer parse(String body, boolean zh) {
        if (body == null || body.trim().isEmpty()) {
            return new Answer(false, "", zh ? "空响应" : "empty response", 0, 0);
        }
        if (body.trim().startsWith("data:")) {
            // 服务端无视 stream=false 时，从 SSE 里把增量拼起来
            final StringBuilder sb = new StringBuilder();
            SseParser p = new SseParser();
            p.feed(body, e -> sb.append(SseParser.deltaText(e.data)));
            p.finish(e -> sb.append(SseParser.deltaText(e.data)));
            String t = sb.toString().trim();
            return new Answer(!t.isEmpty(), t, t.isEmpty() ? (zh ? "流里没有正文" : "no content in stream") : "", 0, 0);
        }
        Object root = JsonAccess.parse(body);
        if (root == null) {
            return new Answer(false, "", (zh ? "不是合法 JSON：" : "not JSON: ") + head(body), 0, 0);
        }
        String text = JsonAccess.str(root, "choices[0].message.content", null);
        if (text == null) text = JsonAccess.str(root, "choices[0].text", null);
        if (text == null) text = JsonAccess.str(root, "content", null);
        String err = JsonAccess.str(root, "error.message", null);
        if (err == null) err = JsonAccess.str(root, "message", null);
        int pt = JsonAccess.i(root, "usage.prompt_tokens", 0);
        int ct = JsonAccess.i(root, "usage.completion_tokens", 0);
        if (text == null || text.trim().isEmpty()) {
            return new Answer(false, "", err == null ? (zh ? "响应里没有正文" : "no content in response") : err, pt, ct);
        }
        return new Answer(true, text.trim(), "", pt, ct);
    }

    private static String head(String s) {
        String t = s.trim();
        return t.length() > 160 ? t.substring(0, 160) + "…" : t;
    }

    /** 真正发请求。 */
    public Answer ask(String prompt, String system, int maxTokens) {
        if (!cfg.usable()) {
            return new Answer(false, "", Lang.pick(
                    "外部模型未配置（设置 → 外部模型里填基地址与模型名）",
                    "external model is not configured (Settings → External model)"), 0, 0);
        }
        if (prompt == null || prompt.trim().isEmpty()) {
            return new Answer(false, "", Lang.pick("问题为空", "empty prompt"), 0, 0);
        }
        String url = joinUrl(cfg.base, "/chat/completions");
        Map<String, String> headers = new LinkedHashMap<>();
        if (cfg.key != null && !cfg.key.trim().isEmpty()) {
            headers.put("Authorization", "Bearer " + cfg.key.trim());
        }
        String body = buildBody(cfg.model.trim(), prompt,
                system == null ? "" : system,
                maxTokens > 0 ? maxTokens : cfg.maxTokens, cfg.temperature);
        Http.Resp r = Http.request("POST", url, "application/json", body, headers, 4 * 1024 * 1024);
        if (r.code < 0) return new Answer(false, "", r.body, 0, 0);
        if (!r.ok()) return new Answer(false, "", "HTTP " + r.code + " " + head(r.body), 0, 0);
        return parse(r.body, Lang.zh());
    }

    /** 连通性自检：只发一条最短的请求，能区分「地址错」「Key 错」「模型名错」。 */
    public Answer probe() {
        return ask("ping", "", 8);
    }

    static String joinUrl(String base, String path) {
        String b = base == null ? "" : base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (!b.toLowerCase(java.util.Locale.ROOT).startsWith("http")) b = "https://" + b;
        if (b.endsWith("/chat/completions")) return b;
        if (path == null || path.isEmpty()) return b;
        return b + path;
    }

    /** 供界面显示，Key 不出现。 */
    public String describe() {
        if (!cfg.usable()) return Lang.pick("未配置", "not configured");
        String host = "";
        try {
            host = new java.net.URL(joinUrl(cfg.base, "")).getHost();
        } catch (Exception ignored) {
            host = cfg.base;
        }
        return cfg.model + " @ " + host;
    }
}
