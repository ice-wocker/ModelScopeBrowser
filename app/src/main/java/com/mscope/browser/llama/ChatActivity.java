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
import com.mscope.browser.agent.AgentTools;
import com.mscope.browser.agent.HtmlPreviewActivity;
import com.mscope.browser.agent.Shell;
import com.mscope.browser.agent.TerminalActivity;
import com.mscope.browser.agent.ToolCall;
import com.mscope.browser.agent.WebSearch;
import com.mscope.browser.agent.Workspace;
import com.mscope.browser.agent.WorkspaceActivity;
import com.mscope.browser.local.LocalModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
 * <p>「智能体模式」下这里是一条真正的工具调用闭环：模型输出工具调用 → App 执行
 * （本机终端 / 联网 / 工作区文件 / 网页预览）→ 结果回灌 → 模型继续推理，直到给出最终答案。
 * 工具协议见 {@link ToolCall}，执行见 {@link AgentTools}。写操作与本机命令会先征求用户确认。
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
    private static final String KEY_AGENT = "agent_mode";
    private static final String KEY_TOOL_WEB = "tool_web";
    private static final String DEFAULT_SYS =
            "你是一个运行在手机本地的中文 AI 助手，请用简体中文准确、简洁地回答。"
                    + "涉及代码或网页时，请用带文件名的 Markdown 代码块输出，例如 ```html filename=index.html，"
                    + "这样文件会自动保存到用户的工作区。";
    /** 智能体模式下的默认提示词：不再要求「输出代码块」，而是鼓励直接用工具干活。 */
    private static final String DEFAULT_SYS_AGENT =
            "你是一个运行在手机本地的中文 AI 助手，可以调用工具在本机上完成实际任务。"
                    + "请用简体中文准确、简洁地回答；需要动手时先调用工具，再根据真实结果作答。";

    /** 单轮对话里最多允许的工具调用轮数，防止模型反复空转。 */
    private static final int MAX_ROUNDS = 8;

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
    private Shell shell;
    private AgentTools agentTools;
    private Markwon markwon;
    private final ExecutorService tools = Executors.newSingleThreadExecutor();
    /** 智能体循环专用线程：生成、解析、执行工具都在这里串行推进。 */
    private final ExecutorService agentExec = Executors.newSingleThreadExecutor();

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
    private TextView chipAgent;
    private ProgressBar loadProgress;

    private SharedPreferences prefs;
    private String systemPrompt = DEFAULT_SYS;
    private int nCtx = DEFAULT_CTX;
    /** 智能体模式：模型可自主调用工具，并在结果回灌后继续推理。 */
    private boolean agentMode;
    /** 是否允许模型联网（web_search / fetch_url 工具）。 */
    private boolean toolWeb;
    /** 本轮联网检索得到的资料，只在本次请求里注入 system，不写入历史。 */
    private String pendingWebContext;

    /** 当前轮次已推进到第几步（第 1 步就是普通生成），仅用于状态提示。 */
    private volatile int agentStep;
    /** 用户点了停止：让循环在下一个安全点退出。 */
    private volatile boolean turnCancelled;
    /** 用户选择了「本会话都允许」：写操作与命令不再逐次确认。 */
    private volatile boolean allowAllWrites;

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
        shell = new Shell(workspace);
        agentTools = new AgentTools(workspace, shell,
                rel -> runOnUiThread(() -> {
                    try {
                        HtmlPreviewActivity.start(this, rel);
                    } catch (Exception e) {
                        Toast.makeText(this, String.valueOf(e.getMessage()), Toast.LENGTH_SHORT).show();
                    }
                }));
        prefs = getSharedPreferences(PREF, MODE_PRIVATE);
        nCtx = prefs.getInt(KEY_CTX, DEFAULT_CTX);
        systemPrompt = prefs.getString(KEY_SYS, DEFAULT_SYS);
        agentMode = prefs.getBoolean(KEY_AGENT, true);
        toolWeb = prefs.getBoolean(KEY_TOOL_WEB, true);

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
        chipAgent = findViewById(R.id.chipAgent);
        loadProgress = findViewById(R.id.loadProgress);

        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        adapter = new Adapter();
        chatList.setLayoutManager(lm);
        chatList.setAdapter(adapter);

        btnSend.setOnClickListener(v -> onSendClicked());
        findViewById(R.id.btnMore).setOnClickListener(v -> showToolsMenu());
        chipWeb.setOnClickListener(v -> toggleWeb());
        chipAgent.setOnClickListener(v -> toggleAgent());
        findViewById(R.id.chipWorkspace).setOnClickListener(v -> openWorkspace());
        findViewById(R.id.chipTerminal).setOnClickListener(v -> openTerminal());
        bindQuick(R.id.quick1);
        bindQuick(R.id.quick2);
        bindQuick(R.id.quick3);

        updateChipWeb();
        updateChipAgent();
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
        } else if (generating && agentStep >= 2) {
            tvCtx.setText(getString(R.string.agent_step, agentStep));
        } else {
            tvCtx.setText(getString(R.string.chat_ctx_usage, engine.cachedTokens(), engine.contextSize()));
        }
    }

    /* -------------------------------------------------------------- 能力开关 */

    private void toggleWeb() {
        toolWeb = !toolWeb;
        prefs.edit().putBoolean(KEY_TOOL_WEB, toolWeb).apply();
        updateChipWeb();
        Toast.makeText(this, toolWeb ? R.string.chat_web_on : R.string.chat_web_off,
                Toast.LENGTH_SHORT).show();
    }

    private void updateChipWeb() {
        chipWeb.setSelected(toolWeb);
        chipWeb.setText(toolWeb ? getString(R.string.chat_web) + " · 开" : getString(R.string.chat_web));
    }

    private void toggleAgent() {
        agentMode = !agentMode;
        prefs.edit().putBoolean(KEY_AGENT, agentMode).apply();
        updateChipAgent();
        Toast.makeText(this, agentMode ? R.string.chat_agent_on : R.string.chat_agent_off,
                Toast.LENGTH_SHORT).show();
    }

    private void updateChipAgent() {
        chipAgent.setSelected(agentMode);
        chipAgent.setText(agentMode ? getString(R.string.chat_agent) + " · 开" : getString(R.string.chat_agent));
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
                turnCancelled = true;  // 让智能体循环在下一个安全点退出
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
        startTurn(-1);
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
                startTurn(-1);
            });
        });
    }

    private void setSearching(boolean b) {
        searching = b;
        btnSend.setEnabled(!b);
        etInput.setEnabled(!b);
        updateCtx();
    }

    /* ------------------------------------------------------------ 智能体循环 */

    /** 开始一轮对话。continueFrom >= 0 时把新内容追加到该条助手气泡（用于「继续生成」）。 */
    private void startTurn(final int continueFrom) {
        turnCancelled = false;
        agentStep = 0;
        stopping = false;
        setGenerating(true);
        final int cf = continueFrom;
        agentExec.execute(() -> runAgent(cf));
    }

    /**
     * 工具调用闭环：生成 → 解析 → 执行 → 结果回灌 → 再生成。
     * 只有智能体模式才会真的解析并执行工具；否则退化成单次生成。
     */
    private void runAgent(final int continueFrom) {
        final List<ChatMessage> ctx = buildBaseContext();
        int reuse = continueFrom;

        for (int round = 0; round < MAX_ROUNDS; round++) {
            if (turnCancelled || !alive) break;
            agentStep = round + 1;
            uiSync(this::updateCtx);

            Gen gen = generateBlocking(ctx, reuse);
            reuse = -1;
            if (gen == null) break;                       // 出错或被取消

            final List<ToolCall> calls = agentMode
                    ? ToolCall.parse(gen.text, AgentTools.names())
                    : new ArrayList<>();
            final String visible = (agentMode
                    ? ToolCall.strip(gen.text, AgentTools.names())
                    : gen.text).trim();
            final boolean last = calls.isEmpty();
            // 整轮只有工具调用时删掉空气泡；但「继续生成」已有内容的气泡要保留
            applyAssistantText(gen.index, gen.prefix + visible, last || !gen.prefix.isEmpty());

            if (last) {                                    // 没有工具调用：这就是最终答案
                if (!TextUtils.isEmpty(visible)) {
                    final String v = visible;
                    uiSync(() -> autoSaveFiles(v));
                }
                break;
            }

            // 模型这轮的原始输出（含调用）留在上下文里，它才知道自己刚才要干什么
            ctx.add(new ChatMessage(ChatMessage.ASSISTANT, gen.text));
            for (ToolCall c : calls) {
                if (turnCancelled || !alive) break;
                final int card = addToolCard(c);
                boolean ok = true;
                if (AgentTools.risky(c.name) && !allowAllWrites) ok = confirmTool(c);
                final String result = ok ? agentTools.exec(c) : getString(R.string.agent_denied);
                updateToolCard(card, result);
                ctx.add(new ChatMessage(ChatMessage.USER, toolResultText(c.name, result)));
            }
        }

        if (agentStep >= MAX_ROUNDS && !turnCancelled) {
            uiSync(() -> addTool(getString(R.string.chat_tool_info), "info",
                    getString(R.string.agent_max_rounds, MAX_ROUNDS)));
        }
        finishTurn();
    }

    /** 请求 = 系统提示（含工具说明与本轮联网资料）+ 历史（跳过工具卡片）。 */
    private List<ChatMessage> buildBaseContext() {
        List<ChatMessage> req = new ArrayList<>();
        String sys = buildSystemPrompt();
        if (!TextUtils.isEmpty(sys)) req.add(new ChatMessage(ChatMessage.SYSTEM, sys));
        for (ChatMessage m : messages) {
            if (m.isTool()) continue;
            if (TextUtils.isEmpty(m.content)) continue;
            req.add(m);
        }
        return req;
    }

    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        String base = systemPrompt == null ? "" : systemPrompt.trim();
        if (TextUtils.isEmpty(base) || DEFAULT_SYS.equals(base)) {
            base = agentMode ? DEFAULT_SYS_AGENT : DEFAULT_SYS;
        }
        sb.append(base);
        if (agentMode) {
            sb.append("\n\n")
              .append(getString(R.string.agent_sys_hint, AgentTools.describe(toolWeb, true, true)));
        }
        if (!TextUtils.isEmpty(pendingWebContext)) {
            sb.append("\n\n").append(getString(R.string.chat_web_context))
              .append('\n').append(pendingWebContext);
        }
        return sb.toString();
    }

    /** 回灌给模型的工具结果：用 user 角色 + 明确包裹，兼容各家聊天模板。 */
    private static String toolResultText(String name, String result) {
        return "<tool_result name=\"" + name + "\">\n" + result + "\n</tool_result>";
    }

    private static class Gen {
        final String text;
        final int index;
        final String prefix;

        Gen(String text, int index, String prefix) {
            this.text = text;
            this.index = index;
            this.prefix = prefix;
        }
    }

    /** 生成一轮并阻塞等待完成；返回 null 表示失败或已取消。 */
    private Gen generateBlocking(final List<ChatMessage> ctx, final int reuseIndex) {
        final int[] idx = {-1};
        final String[] pre = {""};
        uiSync(() -> {
            if (reuseIndex >= 0 && reuseIndex < messages.size()
                    && messages.get(reuseIndex).isAssistant()) {
                idx[0] = reuseIndex;                       // 继续生成：复用同一条气泡
                ChatMessage m = messages.get(reuseIndex);
                pre[0] = m.content == null ? "" : m.content;
            } else {
                idx[0] = messages.size();
                messages.add(new ChatMessage(ChatMessage.ASSISTANT, ""));
                stats.add("");
                adapter.notifyDataSetChanged();
                scrollToBottom();
            }
        });
        final int aiIndex = idx[0];
        final String prefix = pre[0];

        final CountDownLatch latch = new CountDownLatch(1);
        final String[] out = {null};
        final boolean[] failed = {false};

        engine.generate(new ArrayList<>(ctx), params, new LlamaEngine.StreamListener() {
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
                out[0] = fullText == null ? "" : fullText;
                if (aiIndex < stats.size()) {
                    stats.set(aiIndex, getString(R.string.chat_stat,
                            prefillMs / 1000.0, tokensPerSec, tokens));
                    adapter.notifyItemChanged(aiIndex);
                }
                latch.countDown();
            }

            @Override
            public void onError(String message) {
                failed[0] = true;
                if (aiIndex < messages.size()) {
                    messages.get(aiIndex).content = getString(R.string.chat_load_failed, message);
                    adapter.notifyItemChanged(aiIndex);
                }
                latch.countDown();
            }
        });

        try {
            if (!latch.await(10, TimeUnit.MINUTES)) return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        if (failed[0] || !alive) return null;
        return new Gen(out[0], aiIndex, prefix);
    }

    /**
     * 落定这一轮助手气泡的正文。keepEmpty=false 且没有可见正文时（整轮只有工具调用）
     * 就把空气泡删掉，界面上只留下工具卡片。
     */
    private void applyAssistantText(final int index, final String text, final boolean keepEmpty) {
        uiSync(() -> {
            if (index < 0 || index >= messages.size()) return;
            if (TextUtils.isEmpty(text) && !keepEmpty) {
                messages.remove(index);
                if (index < stats.size()) stats.remove(index);
            } else {
                ChatMessage m = messages.get(index);
                m.content = TextUtils.isEmpty(text) ? getString(R.string.chat_stopped) : text;
            }
            adapter.notifyDataSetChanged();
        });
    }

    private void finishTurn() {
        uiSync(() -> {
            setGenerating(false);
            stopping = false;
            pendingWebContext = null;
            updateCtx();
            scrollToBottom();
            persist();
        });
    }

    private int addToolCard(final ToolCall c) {
        final int[] index = {-1};
        uiSync(() -> {
            messages.add(ChatMessage.tool(toolTitle(c.name), toolKind(c.name),
                    getString(R.string.agent_running)));
            stats.add("");
            index[0] = messages.size() - 1;
            adapter.notifyDataSetChanged();
            scrollToBottom();
        });
        return index[0];
    }

    private void updateToolCard(final int index, final String body) {
        uiSync(() -> {
            if (index < 0 || index >= messages.size()) return;
            messages.get(index).content = body;
            adapter.notifyItemChanged(index);
            scrollToBottom();
            persist();
        });
    }

    private static String toolTitle(String name) {
        switch (name) {
            case "shell": return "终端命令";
            case "web_search": return "联网搜索";
            case "fetch_url": return "读取网页";
            case "read_file": return "读取文件";
            case "write_file": return "写入文件";
            case "list_files": return "文件列表";
            case "delete_file": return "删除文件";
            case "open_preview": return "网页预览";
            default: return name;
        }
    }

    private static String toolKind(String name) {
        switch (name) {
            case "shell": return "shell";
            case "web_search": case "fetch_url": return "search";
            case "read_file": case "write_file": case "list_files":
            case "delete_file": return "file";
            default: return "info";
        }
    }

    /** 在界面线程执行一段修改；不在界面线程时阻塞到执行完，保证消息下标一致。 */
    private void uiSync(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
            return;
        }
        if (!alive) return;
        final CountDownLatch latch = new CountDownLatch(1);
        ui.post(() -> {
            try {
                r.run();
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 会改动本机状态的操作（命令 / 写文件 / 删除）先征求用户同意。 */
    private boolean confirmTool(final ToolCall c) {
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = {false};
        ui.post(() -> {
            if (!alive) {
                latch.countDown();
                return;
            }
            try {
                new MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.agent_confirm_title, toolTitle(c.name)))
                        .setMessage(AgentTools.summary(c))
                        .setCancelable(false)
                        .setPositiveButton(R.string.agent_allow, (d, w) -> {
                            ok[0] = true;
                            latch.countDown();
                        })
                        .setNegativeButton(R.string.agent_deny, (d, w) -> latch.countDown())
                        .setNeutralButton(R.string.agent_allow_all, (d, w) -> {
                            allowAllWrites = true;
                            ok[0] = true;
                            latch.countDown();
                        })
                        .show();
            } catch (Exception e) {
                latch.countDown();
            }
        });
        try {
            if (!latch.await(10, TimeUnit.MINUTES)) return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return ok[0];
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
                    toolWeb = "on".equalsIgnoreCase(rest);
                } else {
                    toolWeb = !toolWeb;
                }
                prefs.edit().putBoolean(KEY_TOOL_WEB, toolWeb).apply();
                updateChipWeb();
                addTool(getString(R.string.chat_tool_info), "info",
                        getString(toolWeb ? R.string.chat_web_on : R.string.chat_web_off));
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
                runShell(body);   // 直接 /ls、/cat x、/grep foo *.txt 等，走真 shell
        }
    }

    /** 斜杠命令 /run、/ls 等：在本机终端执行，结果落到一张工具卡片上（后台跑，不卡界面）。 */
    private void runShell(final String cmd) {
        if (cmd.isEmpty()) {
            addTool(getString(R.string.chat_tool_error), "error", getString(R.string.chat_slash_tip));
            return;
        }
        final int card = messages.size();
        messages.add(ChatMessage.tool(getString(R.string.chat_tool_shell), "shell",
                getString(R.string.agent_running)));
        stats.add("");
        adapter.notifyDataSetChanged();
        scrollToBottom();

        tools.execute(() -> {
            final Shell.Out out = shell.run(cmd);
            final String text = out.text == null || out.text.isEmpty() ? "（无输出）" : out.text;
            ui.post(() -> {
                if (!alive || card >= messages.size()) return;
                ChatMessage m = messages.get(card);
                if (out.code != 0) {
                    m.title = getString(R.string.chat_tool_error);
                    m.kind = "error";
                }
                m.content = "$ " + cmd + "\n" + text;
                adapter.notifyItemChanged(card);
                scrollToBottom();
                persist();
            });
        });
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
        startTurn(pos);        // 追加到同一条气泡，接着往下写
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
                    startTurn(-1);
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
        startTurn(-1);
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
        if (generating) {
            turnCancelled = true;
            engine.cancel();
        }
        allowAllWrites = false;                      // 新会话重新逐次确认
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
        turnCancelled = true;
        if (generating) engine.cancel();
        persist();
        ui.removeCallbacksAndMessages(null);
        tools.shutdownNow();
        agentExec.shutdownNow();
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