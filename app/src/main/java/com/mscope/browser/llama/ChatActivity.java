package com.mscope.browser.llama;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.mscope.browser.R;
import com.mscope.browser.Ui;
import com.mscope.browser.agent.HtmlPreviewActivity;
import com.mscope.browser.agent.Terminal;
import com.mscope.browser.agent.TerminalActivity;
import com.mscope.browser.agent.WebSearch;
import com.mscope.browser.agent.Workspace;
import com.mscope.browser.agent.WorkspaceActivity;
import com.mscope.browser.local.LocalModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.Markwon;
import io.noties.markwon.core.MarkwonTheme;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.ext.tables.TablePlugin;

/**
 * 与本地 GGUF 模型对话：llama.cpp 流式生成、Markdown 渲染、可中断，历史会话自动持久化。
 *
 * <p>在此基础上还提供三项「智能体」能力：
 * <ul>
 *   <li><b>联网搜索</b>：打开开关后，每次提问先检索网页并把结果注入上下文；</li>
 *   <li><b>工作区文件</b>：模型输出的带文件名代码块会自动保存到应用私有工作区，可预览；</li>
 *   <li><b>本机终端</b>：受限命令在手机上直接读写工作区（见 {@link Terminal}）。</li>
 * </ul>
 */
public class ChatActivity extends AppCompatActivity {

    private static final String EXTRA_PATH = "m_path";
    private static final String EXTRA_NAME = "m_name";
    private static final String EXTRA_REPO = "m_repo";
    private static final String EXTRA_FILE = "m_file";
    private static final String EXTRA_SIZE = "m_size";

    private static final String PREF = "chat_prefs";
    private static final String KEY_CTX = "n_ctx";
    private static final String KEY_SYS = "system";
    private static final String KEY_WEB = "web_search";
    private static final String DEFAULT_SYS =
            "你是一个运行在手机本地的中文 AI 助手，请用简体中文准确、简洁地回答。"
                    + "涉及代码或网页时，请用带文件名的 Markdown 代码块输出，例如 ```html filename=index.html，"
                    + "这样文件会自动保存到用户的工作区。";

    /** 默认上下文窗口。输出上限必须挤在上下文里（上下文 = 提示词/历史 + 本次输出），
     *  所以想放开输出，上下文也要一起放大；KV 已量化为 q8_0，8192 的占用约为原先一半。 */
    private static final int DEFAULT_CTX = 8192;

    /** 自动保存时识别 ```lang filename 之类的标注。 */
    private static final Pattern FENCE = Pattern.compile("```([^\\n`]*)\\n([\\s\\S]*?)```");

    public static void start(Context ctx, LocalModel m) {
        Intent it = new Intent(ctx, ChatActivity.class);
        it.putExtra(EXTRA_PATH, m.localPath);
        it.putExtra(EXTRA_NAME, m.displayName);
        it.putExtra(EXTRA_REPO, m.repoFullName());
        it.putExtra(EXTRA_FILE, m.filePath);
        it.putExtra(EXTRA_SIZE, m.size);
        ctx.startActivity(it);
    }

    /* ------------------------------------------------------------------ */

    private LocalModel model;
    private LlamaEngine engine;
    private ChatStore store;
    private ChatStore.Session session;
    private Workspace workspace;
    private Terminal terminal;
    private Markwon markwon;
    private final ExecutorService tools = Executors.newSingleThreadExecutor();

    private final LlamaEngine.Params params = new LlamaEngine.Params();
    /** 当前会话的消息与统计（直接引用 session 内的列表，切会话时整体替换）。 */
    private List<ChatMessage> messages;
    private List<String> stats;

    private RecyclerView chatList;
    private EditText etInput;
    private ImageButton btnSend;
    private View loadBox;
    private View chatHint;
    private TextView tvLoadStatus;
    private TextView tvCtx;
    private TextView tvHintTitle;
    private TextView tvHintDesc;
    private TextView chipWeb;
    private ProgressBar loadProgress;

    private SharedPreferences prefs;
    private String systemPrompt = DEFAULT_SYS;
    private int nCtx = DEFAULT_CTX;
    private boolean webSearchOn;
    /** 本轮联网检索得到的资料，只在本次请求里注入 system，不写入历史。 */
    private String pendingWebContext;

    private boolean generating;
    private boolean ready;
    private boolean stopping;
    private boolean searching;
    private boolean alive = true;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private Adapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);
        Ui.edgeToEdge(this, findViewById(R.id.root));

        engine = LlamaEngine.get();
        store = new ChatStore(this);
        workspace = new Workspace(this);
        terminal = new Terminal(workspace);
        prefs = getSharedPreferences(PREF, MODE_PRIVATE);
        nCtx = prefs.getInt(KEY_CTX, DEFAULT_CTX);
        systemPrompt = prefs.getString(KEY_SYS, DEFAULT_SYS);
        webSearchOn = prefs.getBoolean(KEY_WEB, false);

        markwon = Markwon.builder(this)
                .usePlugin(StrikethroughPlugin.create())
                .usePlugin(TablePlugin.create(this))
                .usePlugin(new AbstractMarkwonPlugin() {
                    @Override
                    public void configureTheme(@NonNull MarkwonTheme.Builder builder) {
                        builder.codeTypeface(Typeface.MONOSPACE);
                        builder.codeBackgroundColor(0x14000000);
                        builder.linkColor(0xFFFF5A2D);
                    }
                })
                .build();

        model = new LocalModel();
        model.localPath = nz(getIntent().getStringExtra(EXTRA_PATH));
        model.displayName = nz(getIntent().getStringExtra(EXTRA_NAME));
        model.repoOwner = "";
        model.repoName = nz(getIntent().getStringExtra(EXTRA_REPO));
        model.filePath = nz(getIntent().getStringExtra(EXTRA_FILE));
        model.size = getIntent().getLongExtra(EXTRA_SIZE, 0);
        if (model.displayName.isEmpty()) model.displayName = "本地模型";

        MaterialToolbar toolbar = findViewById(R.id.cToolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setOnMenuItemClickListener(this::onMenu);
        toolbar.setTitle(model.displayName);
        toolbar.setSubtitle(model.repoName);

        chatList = findViewById(R.id.chatList);
        etInput = findViewById(R.id.etInput);
        btnSend = findViewById(R.id.btnSend);
        loadBox = findViewById(R.id.loadBox);
        chatHint = findViewById(R.id.chatHint);
        tvLoadStatus = findViewById(R.id.tvLoadStatus);
        tvCtx = findViewById(R.id.tvCtx);
        tvHintTitle = findViewById(R.id.tvHintTitle);
        tvHintDesc = findViewById(R.id.tvHintDesc);
        chipWeb = findViewById(R.id.chipWeb);
        loadProgress = findViewById(R.id.loadProgress);

        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        adapter = new Adapter();
        chatList.setLayoutManager(lm);
        chatList.setAdapter(adapter);

        btnSend.setOnClickListener(v -> onSendClicked());
        findViewById(R.id.btnMore).setOnClickListener(v -> showToolsMenu());
        chipWeb.setOnClickListener(v -> toggleWeb());
        findViewById(R.id.chipWorkspace).setOnClickListener(v -> openWorkspace());
        findViewById(R.id.chipTerminal).setOnClickListener(v -> openTerminal());
        bindQuick(R.id.quick1);
        bindQuick(R.id.quick2);
        bindQuick(R.id.quick3);

        updateChipWeb();
        tvHintTitle.setText(model.displayName);
        tvHintDesc.setText(getString(R.string.chat_greeting, model.displayName));

        // 打开该模型最近一次会话；没有就新建
        openSession(store.latest(model.localPath));
        loadModel();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private void bindQuick(int id) {
        TextView v = findViewById(id);
        v.setOnClickListener(x -> {
            etInput.setText(v.getText());
            etInput.setSelection(etInput.getText().length());
            etInput.requestFocus();
        });
    }

    /* -------------------------------------------------------------- 会话切换 */

    private void openSession(ChatStore.Session s) {
        session = (s != null) ? s : store.create(model.localPath);
        session.align();
        messages = session.messages;
        stats = session.stats;
    }

    private void persist() {
        if (session != null) store.save(session);
    }

    /* -------------------------------------------------------------- 模型加载 */

    private void loadModel() {
        LocalModel cur = engine.currentModel();
        boolean same = engine.isLoaded() && cur != null && cur.localPath.equals(model.localPath);
        if (same) {
            onReady();
            return;
        }
        if (engine.isLoaded() || engine.isLoading()) {
            String curName = cur == null ? "-" : cur.displayName();
            new MaterialAlertDialogBuilder(this)
                    .setMessage(getString(R.string.chat_unload, curName, model.displayName()))
                    .setNegativeButton(android.R.string.cancel, (d, w) -> finish())
                    .setPositiveButton(R.string.p_apply, (d, w) -> doLoad())
                    .show();
            return;
        }
        doLoad();
    }

    private void doLoad() {
        ready = false;
        loadBox.setVisibility(View.VISIBLE);
        loadProgress.setVisibility(View.VISIBLE);
        tvLoadStatus.setText(R.string.chat_loading);
        chatHint.setVisibility(View.GONE);
        etInput.setEnabled(false);
        btnSend.setEnabled(false);

        engine.load(model, nCtx, getApplicationInfo().nativeLibraryDir, (ok, msg) -> {
            if (!alive) return;
            if (ok) {
                onReady();
            } else {
                loadProgress.setVisibility(View.GONE);
                tvLoadStatus.setText(getString(R.string.chat_load_failed, msg));
                showHint(getString(R.string.err_title), msg);
            }
        });
    }

    private void onReady() {
        ready = true;
        loadProgress.setVisibility(View.GONE);
        String be = engine.backendInfo();
        tvLoadStatus.setText(be.isEmpty()
                ? getString(R.string.chat_loaded, engine.contextSize())
                : getString(R.string.chat_loaded_backend, engine.contextSize(), be));
        loadBox.setVisibility(View.VISIBLE);
        ui.postDelayed(() -> {
            if (alive && ready) loadBox.setVisibility(View.GONE);
        }, 2500);

        etInput.setEnabled(true);
        btnSend.setEnabled(true);
        updateHint();
        adapter.notifyDataSetChanged();
        updateCtx();
        scrollToBottom();
    }

    /** 新会话（还没有任何消息）时展示欢迎 + 快捷提示，有内容则收起。 */
    private void updateHint() {
        if (!ready || !messages.isEmpty()) {
            chatHint.setVisibility(View.GONE);
            return;
        }
        chatHint.setVisibility(View.VISIBLE);
    }

    private void showHint(String title, String desc) {
        chatHint.setVisibility(View.VISIBLE);
        tvHintTitle.setText(title);
        tvHintDesc.setText(desc);
    }

    /** 刷新上下文用量；生成中/停止中/检索中显示对应状态。 */
    private void updateCtx() {
        if (!ready) {
            tvCtx.setVisibility(View.GONE);
            return;
        }
        tvCtx.setVisibility(View.VISIBLE);
        if (searching) {
            tvCtx.setText(R.string.chat_searching);
        } else if (stopping) {
            tvCtx.setText(R.string.chat_stopping);
        } else {
            tvCtx.setText(getString(R.string.chat_ctx_usage, engine.cachedTokens(), engine.contextSize()));
        }
    }

    /* -------------------------------------------------------------- 能力开关 */

    private void toggleWeb() {
        webSearchOn = !webSearchOn;
        prefs.edit().putBoolean(KEY_WEB, webSearchOn).apply();
        updateChipWeb();
        Toast.makeText(this, webSearchOn ? R.string.chat_web_on : R.string.chat_web_off,
                Toast.LENGTH_SHORT).show();
    }

    private void updateChipWeb() {
        chipWeb.setSelected(webSearchOn);
        chipWeb.setText(webSearchOn ? getString(R.string.chat_web) + " · 开" : getString(R.string.chat_web));
    }

    private void openWorkspace() {
        startActivity(new Intent(this, WorkspaceActivity.class));
    }

    private void openTerminal() {
        startActivity(new Intent(this, TerminalActivity.class));
    }

    /* -------------------------------------------------------------- 发送流程 */

    private void onSendClicked() {
        if (searching) return;
        if (generating) {
            if (!stopping) {          // 立即给出反馈：原生层要到下个 token 边界才真正停下
                stopping = true;
                engine.cancel();
                btnSend.setEnabled(false);
                updateCtx();
            }
            return;
        }
        if (!ready) {
            Toast.makeText(this, R.string.chat_need_model, Toast.LENGTH_SHORT).show();
            return;
        }
        final String text = etInput.getText().toString().trim();
        if (text.isEmpty()) return;

        etInput.setText("");
        hideIme();

        if (text.startsWith("/")) {
            handleSlash(text);
            return;
        }

        chatHint.setVisibility(View.GONE);
        messages.add(new ChatMessage(ChatMessage.USER, text));
        stats.add("");
        adapter.notifyDataSetChanged();
        scrollToBottom();
        persist();

        pendingWebContext = null;
        if (webSearchOn) {
            searchThenGenerate(text);
        } else {
            startGeneration();
        }
    }

    /** 联网模式：先检索、把结果作为工具卡片展示并存为待注入上下文，再让模型作答。 */
    private void searchThenGenerate(final String query) {
        final int toolIndex = messages.size();
        messages.add(ChatMessage.tool(getString(R.string.chat_tool_search), "search",
                getString(R.string.chat_searching)));
        stats.add("");
        adapter.notifyDataSetChanged();
        scrollToBottom();
        setSearching(true);

        tools.execute(() -> {
            String body;
            String context = null;
            try {
                List<WebSearch.Result> results = WebSearch.search(query, 6);
                if (results.isEmpty()) {
                    body = getString(R.string.chat_search_empty);
                } else {
                    body = getString(R.string.chat_search_query, query, results.size())
                            + "\n\n" + WebSearch.format(results);
                    context = WebSearch.format(results);
                }
            } catch (Exception e) {
                body = getString(R.string.chat_search_failed, String.valueOf(e.getMessage()));
            }
            final String fBody = body;
            final String fContext = context;
            ui.post(() -> {
                if (!alive) return;
                if (toolIndex < messages.size()) {
                    messages.get(toolIndex).content = fBody;
                    adapter.notifyItemChanged(toolIndex);
                }
                setSearching(false);
                pendingWebContext = fContext;
                startGeneration();
            });
        });
    }

    private void setSearching(boolean b) {
        searching = b;
        btnSend.setEnabled(!b);
        etInput.setEnabled(!b);
        updateCtx();
    }

    /** 为「最后一条消息」生成回复：先补一个空的 AI 占位，再跑推理。 */
    private void startGeneration() {
        messages.add(new ChatMessage(ChatMessage.ASSISTANT, ""));
        stats.add("");
        final int aiIndex = messages.size() - 1;

        adapter.notifyDataSetChanged();
        scrollToBottom();
        setGenerating(true);

        engine.generate(buildRequest(), params, listener(aiIndex));
    }

    /** 请求 = 系统提示（含本轮联网资料）+ 历史（跳过工具卡片与末尾空占位）。 */
    private List<ChatMessage> buildRequest() {
        List<ChatMessage> req = new ArrayList<>();
        StringBuilder sys = new StringBuilder();
        if (!TextUtils.isEmpty(systemPrompt.trim())) sys.append(systemPrompt.trim());
        if (!TextUtils.isEmpty(pendingWebContext)) {
            if (sys.length() > 0) sys.append("\n\n");
            sys.append(getString(R.string.chat_web_context)).append('\n').append(pendingWebContext);
        }
        if (sys.length() > 0) req.add(new ChatMessage(ChatMessage.SYSTEM, sys.toString()));
        for (int i = 0; i < messages.size() - 1; i++) {
            ChatMessage m = messages.get(i);
            if (m.isTool()) continue;
            if (!TextUtils.isEmpty(m.content)) req.add(m);
        }
        return req;
    }

    private LlamaEngine.StreamListener listener(final int aiIndex) {
        return new LlamaEngine.StreamListener() {
            @Override
            public void onToken(String piece) {
                if (!alive || aiIndex >= messages.size()) return;
                ChatMessage m = messages.get(aiIndex);
                m.content = m.content + piece;
                adapter.notifyItemChanged(aiIndex);
                scrollToBottom();
            }

            @Override
            public void onDone(String fullText, int tokens, long elapsedMs, long prefillMs,
                               double tokensPerSec) {
                if (!alive) return;
                stopping = false;
                if (aiIndex >= messages.size()) {
                    setGenerating(false);
                    return;
                }
                ChatMessage m = messages.get(aiIndex);
                if (!TextUtils.isEmpty(fullText)) m.content = fullText;
                if (TextUtils.isEmpty(m.content)) m.content = getString(R.string.chat_stopped);
                stats.set(aiIndex, getString(R.string.chat_stat,
                        prefillMs / 1000.0, tokensPerSec, tokens));
                adapter.notifyItemChanged(aiIndex);
                setGenerating(false);
                updateCtx();
                pendingWebContext = null;
                persist();
                scrollToBottom();
                autoSaveFiles(m.content);
            }

            @Override
            public void onError(String message) {
                if (!alive) return;
                stopping = false;
                if (aiIndex < messages.size()) {
                    ChatMessage m = messages.get(aiIndex);
                    m.content = getString(R.string.chat_load_failed, message);
                    adapter.notifyItemChanged(aiIndex);
                }
                setGenerating(false);
                updateCtx();
            }
        };
    }

    private void setGenerating(boolean b) {
        generating = b;
        btnSend.setEnabled(true);
        btnSend.setImageResource(b ? R.drawable.ic_stop : R.drawable.ic_send);
        btnSend.setBackgroundResource(b ? R.drawable.bg_circle_accent : R.drawable.bg_circle_brand);
        btnSend.setContentDescription(getString(b ? R.string.chat_stop : R.string.chat_send));
        etInput.setEnabled(!b);
        if (!b) etInput.requestFocus();
    }

    private void scrollToBottom() {
        if (messages.isEmpty()) return;
        chatList.post(() -> chatList.scrollToPosition(messages.size() - 1));
    }

    private void hideIme() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etInput.getWindowToken(), 0);
    }

    /* ------------------------------------------------------------ 斜杠命令 */

    private void handleSlash(String raw) {
        String body = raw.substring(1).trim();
        if (body.isEmpty()) return;
        int sp = body.indexOf(' ');
        String cmd = (sp < 0 ? body : body.substring(0, sp)).toLowerCase(Locale.ROOT);
        String rest = sp < 0 ? "" : body.substring(sp + 1).trim();

        switch (cmd) {
            case "help":
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.chat_slash_tip)
                        .setMessage(R.string.chat_slash_help)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                return;
            case "files":
                openWorkspace();
                return;
            case "web": {
                if ("on".equalsIgnoreCase(rest) || "off".equalsIgnoreCase(rest)) {
                    webSearchOn = "on".equalsIgnoreCase(rest);
                } else {
                    webSearchOn = !webSearchOn;
                }
                prefs.edit().putBoolean(KEY_WEB, webSearchOn).apply();
                updateChipWeb();
                addTool(getString(R.string.chat_tool_info), "info",
                        getString(webSearchOn ? R.string.chat_web_on : R.string.chat_web_off));
                return;
            }
            case "search": {
                if (rest.isEmpty()) {
                    addTool(getString(R.string.chat_tool_error), "error",
                            getString(R.string.chat_slash_help));
                    return;
                }
                chatHint.setVisibility(View.GONE);
                messages.add(new ChatMessage(ChatMessage.USER, rest));
                stats.add("");
                adapter.notifyDataSetChanged();
                persist();
                pendingWebContext = null;
                searchThenGenerate(rest);
                return;
            }
            case "preview": {
                if (rest.isEmpty()) {
                    addTool(getString(R.string.chat_tool_error), "error", "/preview <文件>");
                    return;
                }
                try {
                    HtmlPreviewActivity.start(this, workspace.normalize(rest));
                } catch (Exception e) {
                    addTool(getString(R.string.chat_tool_error), "error", String.valueOf(e.getMessage()));
                }
                return;
            }
            case "run": {
                runShell(rest);
                return;
            }
            default:
                runShell(body);   // 直接 /ls、/cat x、/write f text 等
        }
    }

    private void runShell(String cmd) {
        if (cmd.isEmpty()) {
            addTool(getString(R.string.chat_tool_error), "error", getString(R.string.chat_slash_tip));
            return;
        }
        Terminal.Out out = terminal.run(cmd);
        if (out.text != null && out.text.startsWith("\f")) return;
        String text = out.text == null || out.text.isEmpty() ? "（无输出）" : out.text;
        if (out.code != 0) {
            addTool(getString(R.string.chat_tool_error), "error", "$ " + cmd + "\n" + text);
        } else {
            addTool(getString(R.string.chat_tool_shell), "shell", "$ " + cmd + "\n" + text);
        }
    }

    private void addTool(String title, String kind, String body) {
        messages.add(ChatMessage.tool(title, kind, body));
        stats.add("");
        adapter.notifyDataSetChanged();
        scrollToBottom();
        persist();
    }

    /* ---------------------------------------------------- 自动保存生成的文件 */

    /**
     * 扫描回复里的代码块：带文件名标注（```html filename=index.html）或 html 代码块会自动
     * 落盘到工作区，并补一张工具卡片；其余普通代码块不动，避免把随手示例也写进工作区。
     */
    private void autoSaveFiles(String text) {
        if (TextUtils.isEmpty(text)) return;
        Matcher m = FENCE.matcher(text);
        List<String> saved = new ArrayList<>();
        int htmlSeq = 0;
        while (m.find()) {
            String info = m.group(1) == null ? "" : m.group(1).trim();
            String body = m.group(2);
            if (body == null || body.trim().isEmpty()) continue;
            FenceInfo p = parseFenceInfo(info);
            String name = p.filename;
            if (TextUtils.isEmpty(name)) {
                if ("html".equals(p.lang) || "htm".equals(p.lang)) {
                    htmlSeq++;
                    name = htmlSeq == 1 ? "page.html" : "page-" + htmlSeq + ".html";
                } else {
                    continue;
                }
            }
            try {
                String rel = uniqueName(Workspace.normalize(name));
                workspace.writeText(rel, body);
                saved.add(rel);
            } catch (Exception ignored) {
            }
        }
        if (saved.isEmpty()) return;
        StringBuilder sb = new StringBuilder(getString(R.string.chat_saved_many, saved.size()));
        for (String s : saved) sb.append("\n• /").append(s);
        addTool(getString(R.string.chat_tool_file), "file", sb.toString());
    }

    private static class FenceInfo {
        String lang = "";
        String filename = "";
    }

    private static FenceInfo parseFenceInfo(String info) {
        FenceInfo out = new FenceInfo();
        if (info.isEmpty()) return out;
        String[] toks = info.split("\\s+");
        for (int i = 0; i < toks.length; i++) {
            String t = toks[i];
            if (t.isEmpty()) continue;
            int eq = t.indexOf('=');
            if (eq > 0) {
                String k = t.substring(0, eq).toLowerCase(Locale.ROOT);
                String v = strip(t.substring(eq + 1));
                if (k.equals("filename") || k.equals("file") || k.equals("name") || k.equals("title")) {
                    if (out.filename.isEmpty()) out.filename = v;
                    continue;
                }
            }
            if (i == 0) {
                int colon = t.indexOf(':');
                if (colon > 0) {
                    out.lang = t.substring(0, colon).toLowerCase(Locale.ROOT);
                    out.filename = strip(t.substring(colon + 1));
                } else if (t.contains(".")) {
                    out.filename = strip(t);
                } else {
                    out.lang = t.toLowerCase(Locale.ROOT);
                }
                continue;
            }
            if (out.filename.isEmpty() && t.contains(".")) out.filename = strip(t);
        }
        if (out.lang.isEmpty() && !out.filename.isEmpty()) {
            int dot = out.filename.lastIndexOf('.');
            if (dot > 0) out.lang = out.filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        }
        return out;
    }

    private static String strip(String s) {
        String t = s.trim();
        if (t.length() >= 2 && (t.startsWith("\"") && t.endsWith("\"")
                || t.startsWith("'") && t.endsWith("'")
                || t.startsWith("`") && t.endsWith("`"))) {
            t = t.substring(1, t.length() - 1);
        }
        return t;
    }

    /** 同名文件自动加序号，避免覆盖用户已有内容。 */
    private String uniqueName(String rel) {
        if (TextUtils.isEmpty(rel) || !workspace.exists(rel)) return rel;
        int dot = rel.lastIndexOf('.');
        String base = dot > 0 ? rel.substring(0, dot) : rel;
        String ext = dot > 0 ? rel.substring(dot) : "";
        for (int i = 2; i < 100; i++) {
            String cand = base + "-" + i + ext;
            if (!workspace.exists(cand)) return cand;
        }
        return base + "-" + System.currentTimeMillis() + ext;
    }

    /* ---------------------------------------------------------- 消息操作 */

    /** 长按气泡：复制 / 重新生成或编辑重发 / 删除本条。 */
    private void showMessageMenu(final int pos) {
        if (pos < 0 || pos >= messages.size()) return;
        if (generating || searching) {
            Toast.makeText(this, R.string.chat_thinking, Toast.LENGTH_SHORT).show();
            return;
        }
        final ChatMessage m = messages.get(pos);
        if (m.isTool()) {
            showToolMenu(pos);
            return;
        }
        final boolean user = m.isUser();
        final String[] actions = user
                ? new String[]{getString(R.string.msg_copy), getString(R.string.msg_edit_resend),
                               getString(R.string.msg_delete)}
                : new String[]{getString(R.string.msg_copy), getString(R.string.msg_continue),
                               getString(R.string.msg_regenerate), getString(R.string.msg_delete)};

        new MaterialAlertDialogBuilder(this)
                .setItems(actions, (d, which) -> {
                    if (which == 0) {
                        copy(m.content);
                    } else if (user) {
                        if (which == 1) editResend(pos);
                        else deleteMessage(pos);
                    } else {
                        if (which == 1) continueGeneration(pos);
                        else if (which == 2) regenerate(pos);
                        else deleteMessage(pos);
                    }
                })
                .show();
    }

    /** 续写：保留已生成的回复，让它接着往下写（输出被 maxTokens 截断时最有用）。 */
    private void continueGeneration(final int pos) {
        if (generating || searching) return;
        if (pos != messages.size() - 1) {
            Toast.makeText(this, R.string.msg_continue_last, Toast.LENGTH_SHORT).show();
            return;
        }
        if (TextUtils.isEmpty(messages.get(pos).content)) return;
        startGeneration();
    }

    private void showToolMenu(final int pos) {
        final ChatMessage m = messages.get(pos);
        final String[] actions = {getString(R.string.msg_copy), getString(R.string.workspace_title),
                                  getString(R.string.msg_delete)};
        new MaterialAlertDialogBuilder(this)
                .setItems(actions, (d, which) -> {
                    if (which == 0) copy(m.content);
                    else if (which == 1) openWorkspace();
                    else deleteMessage(pos);
                })
                .show();
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("chat", text == null ? "" : text));
        Toast.makeText(this, R.string.msg_copied, Toast.LENGTH_SHORT).show();
    }

    /** 丢弃 keepThrough（含）之后的所有消息，用于重发/重新生成。 */
    private void trimAfter(int keepThrough) {
        for (int i = messages.size() - 1; i > keepThrough; i--) {
            messages.remove(i);
            if (i < stats.size()) stats.remove(i);
        }
        session.align();
        adapter.notifyDataSetChanged();
    }

    private void editResend(final int pos) {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_text, null);
        EditText et = v.findViewById(R.id.etText);
        et.setText(messages.get(pos).content);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.msg_edit_title)
                .setView(v)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.p_apply, (d, w) -> {
                    final String text = et.getText().toString().trim();
                    if (text.isEmpty()) return;
                    messages.get(pos).content = text;
                    trimAfter(pos);       // 该消息之后的内容作废，重新生成
                    startGeneration();
                })
                .show();
    }

    private void regenerate(final int pos) {
        int userIdx = -1;
        for (int i = pos - 1; i >= 0; i--) {
            if (messages.get(i).isUser()) {
                userIdx = i;
                break;
            }
        }
        if (userIdx < 0) return;           // 没有对应的用户消息，不处理
        trimAfter(userIdx);
        startGeneration();
    }

    private void deleteMessage(int pos) {
        messages.remove(pos);
        if (pos < stats.size()) stats.remove(pos);
        session.align();
        adapter.notifyDataSetChanged();
        persist();
    }

    /* ------------------------------------------------------------------ 菜单 */

    private boolean onMenu(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_new_chat) {
            newChat();
            return true;
        }
        if (id == R.id.action_history) {
            showHistory();
            return true;
        }
        if (id == R.id.action_workspace) {
            openWorkspace();
            return true;
        }
        if (id == R.id.action_terminal) {
            openTerminal();
            return true;
        }
        if (id == R.id.action_export) {
            exportChat();
            return true;
        }
        if (id == R.id.action_params) {
            showParamsDialog();
            return true;
        }
        if (id == R.id.action_system) {
            showSystemDialog();
            return true;
        }
        return false;
    }

    /** 输入框左侧「+」：把高频操作收进一个菜单。 */
    private void showToolsMenu() {
        final String[] actions = {
                getString(R.string.chat_new),
                getString(R.string.chat_history),
                getString(R.string.workspace_title),
                getString(R.string.term_title),
                getString(R.string.chat_params),
                getString(R.string.chat_system),
                getString(R.string.chat_export)};
        new MaterialAlertDialogBuilder(this)
                .setItems(actions, (d, which) -> {
                    switch (which) {
                        case 0: newChat(); break;
                        case 1: showHistory(); break;
                        case 2: openWorkspace(); break;
                        case 3: openTerminal(); break;
                        case 4: showParamsDialog(); break;
                        case 5: showSystemDialog(); break;
                        default: exportChat(); break;
                    }
                })
                .show();
    }

    private void newChat() {
        if (generating) engine.cancel();
        persist();                                   // 先存当前会话
        openSession(store.create(model.localPath));  // 再开新会话
        adapter.notifyDataSetChanged();
        chatHint.setVisibility(View.GONE);
        if (ready) {
            engine.reset();
            updateHint();
            updateCtx();
        }
    }

    private void showHistory() {
        final List<ChatStore.Session> all = store.list(model.localPath);
        if (all.isEmpty()) {
            Toast.makeText(this, R.string.chat_history_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] labels = new String[all.size()];
        for (int i = 0; i < all.size(); i++) {
            ChatStore.Session s = all.get(i);
            String t = s.titleText();
            if (t.isEmpty()) t = getString(R.string.chat_session_none);
            labels[i] = t + "   (" + s.messages.size() + ")";
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.chat_history_title)
                .setItems(labels, (d, which) -> {
                    ChatStore.Session target = all.get(which);
                    if (target.id.equals(session.id)) return;
                    if (generating) engine.cancel();
                    persist();
                    openSession(target);
                    adapter.notifyDataSetChanged();
                    if (ready) {
                        engine.reset();     // 换了会话，KV 前缀不再适用
                        updateHint();
                        updateCtx();
                    }
                    scrollToBottom();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void exportChat() {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : messages) {
            if (TextUtils.isEmpty(m.content)) continue;
            sb.append(m.isUser() ? "我" : (m.isTool() ? "工具" : model.displayName))
              .append("：\n")
              .append(m.content.trim())
              .append("\n\n");
        }
        if (sb.length() == 0) {
            Toast.makeText(this, R.string.chat_nothing_export, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent it = new Intent(Intent.ACTION_SEND);
        it.setType("text/plain");
        it.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.chat_export));
        it.putExtra(Intent.EXTRA_TEXT, sb.toString().trim());
        startActivity(Intent.createChooser(it, getString(R.string.chat_export)));
    }

    private void showParamsDialog() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_params, null);
        EditText etMax = v.findViewById(R.id.etMaxTokens);
        EditText etTemp = v.findViewById(R.id.etTemp);
        EditText etTopP = v.findViewById(R.id.etTopP);
        EditText etTopK = v.findViewById(R.id.etTopK);
        EditText etCtx = v.findViewById(R.id.etCtx);

        etMax.setText(String.valueOf(params.maxTokens));
        etTemp.setText(String.valueOf(params.temp));
        etTopP.setText(String.valueOf(params.topP));
        etTopK.setText(String.valueOf(params.topK));
        etCtx.setText(String.valueOf(nCtx));

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.chat_params)
                .setView(v)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.p_reset, (d, w) -> {
                    LlamaEngine.Params def = new LlamaEngine.Params();
                    params.maxTokens = def.maxTokens;
                    params.temp = def.temp;
                    params.topP = def.topP;
                    params.topK = def.topK;
                    if (nCtx != DEFAULT_CTX) {
                        nCtx = DEFAULT_CTX;
                        prefs.edit().putInt(KEY_CTX, nCtx).apply();
                        doLoad();
                    }
                    Toast.makeText(this, R.string.p_reset, Toast.LENGTH_SHORT).show();
                })
                .setPositiveButton(R.string.p_apply, (d, w) -> {
                    params.maxTokens = clampInt(etMax.getText().toString(), params.maxTokens, 1, 8192);
                    params.temp = clampFloat(etTemp.getText().toString(), params.temp, 0f, 2f);
                    params.topP = clampFloat(etTopP.getText().toString(), params.topP, 0f, 1f);
                    params.topK = clampInt(etTopK.getText().toString(), params.topK, 0, 200);
                    int ctx = clampInt(etCtx.getText().toString(), nCtx, 512, 32768);
                    if (ctx != nCtx) {
                        nCtx = ctx;
                        prefs.edit().putInt(KEY_CTX, nCtx).apply();
                        if (generating) engine.cancel();
                        doLoad();     // 上下文变化需要重建 llama context
                    }
                    // 输出必须给提示词/历史留出空间：最多占上下文的 3/4
                    params.maxTokens = Math.min(params.maxTokens, Math.max(256, nCtx * 3 / 4));
                })
                .show();
    }

    private void showSystemDialog() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_text, null);
        EditText et = v.findViewById(R.id.etText);
        et.setText(systemPrompt);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.chat_system)
                .setView(v)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.p_apply, (d, w) -> {
                    systemPrompt = et.getText().toString().trim();
                    prefs.edit().putString(KEY_SYS, systemPrompt).apply();
                })
                .show();
    }

    private static int clampInt(String s, int def, int min, int max) {
        try {
            int v = Integer.parseInt(s.trim());
            return Math.max(min, Math.min(max, v));
        } catch (Exception e) {
            return def;
        }
    }

    private static float clampFloat(String s, float def, float min, float max) {
        try {
            float v = Float.parseFloat(s.trim());
            return Math.max(min, Math.min(max, v));
        } catch (Exception e) {
            return def;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        persist();     // 退到后台也把当前会话落下
    }

    @Override
    protected void onDestroy() {
        alive = false;
        if (generating) engine.cancel();
        persist();
        ui.removeCallbacksAndMessages(null);
        tools.shutdownNow();
        super.onDestroy();
    }

    /* ---------------------------------------------------------------- 适配器 */

    private class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int T_USER = 0;
        private static final int T_AI = 1;
        private static final int T_TOOL = 2;

        @Override
        public int getItemViewType(int position) {
            ChatMessage m = messages.get(position);
            if (m.isTool()) return T_TOOL;
            return m.isUser() ? T_USER : T_AI;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (viewType == T_USER) {
                return new UserVH(inf.inflate(R.layout.item_chat_user, parent, false));
            }
            if (viewType == T_TOOL) {
                return new ToolVH(inf.inflate(R.layout.item_chat_tool, parent, false));
            }
            return new AiVH(inf.inflate(R.layout.item_chat_ai, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
            ChatMessage m = messages.get(position);
            if (h instanceof UserVH) {
                ((UserVH) h).bubble.setText(m.content);
            } else if (h instanceof ToolVH) {
                bindTool((ToolVH) h, m);
            } else {
                bindAi((AiVH) h, m, position);
            }
            h.itemView.setOnLongClickListener(v -> {
                showMessageMenu(position);
                return true;
            });
        }

        private void bindAi(AiVH a, ChatMessage m, int position) {
            String st = position < stats.size() ? stats.get(position) : "";
            if (TextUtils.isEmpty(st)) {
                a.stat.setVisibility(View.GONE);
            } else {
                a.stat.setVisibility(View.VISIBLE);
                a.stat.setText(st);
            }
            // 流式过程中用纯文本，避免每个 token 都重新解析 Markdown
            boolean streaming = generating && position == messages.size() - 1;
            if (streaming) {
                a.bubble.setText(TextUtils.isEmpty(m.content)
                        ? getString(R.string.chat_thinking) : m.content);
            } else if (TextUtils.isEmpty(m.content)) {
                a.bubble.setText("");
            } else {
                markwon.setMarkdown(a.bubble, m.content);
            }
        }

        private void bindTool(ToolVH t, ChatMessage m) {
            t.title.setText(m.title);
            markwon.setMarkdown(t.body, m.content == null ? "" : m.content);
            t.icon.setImageResource(toolIcon(m.kind));
            boolean files = "file".equals(m.kind);
            t.hint.setVisibility(files ? View.VISIBLE : View.GONE);
            t.itemView.setOnClickListener(files ? v -> openWorkspace() : null);
        }

        private int toolIcon(String kind) {
            if ("search".equals(kind)) return R.drawable.ic_globe;
            if ("file".equals(kind)) return R.drawable.ic_file;
            if ("shell".equals(kind)) return R.drawable.ic_terminal;
            return R.drawable.ic_smart_toy;
        }

        @Override
        public int getItemCount() {
            return messages.size();
        }

        class UserVH extends RecyclerView.ViewHolder {
            final TextView bubble;

            UserVH(@NonNull View v) {
                super(v);
                bubble = v.findViewById(R.id.tvBubble);
            }
        }

        class AiVH extends RecyclerView.ViewHolder {
            final TextView bubble;
            final TextView stat;

            AiVH(@NonNull View v) {
                super(v);
                bubble = v.findViewById(R.id.tvBubble);
                stat = v.findViewById(R.id.tvStat);
            }
        }

        class ToolVH extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView title;
            final TextView body;
            final TextView hint;

            ToolVH(@NonNull View v) {
                super(v);
                icon = v.findViewById(R.id.ivTool);
                title = v.findViewById(R.id.tvToolTitle);
                body = v.findViewById(R.id.tvToolBody);
                hint = v.findViewById(R.id.tvToolHint);
            }
        }
    }
}