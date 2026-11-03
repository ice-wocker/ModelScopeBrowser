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
import com.mscope.browser.core.Bench;
import com.mscope.browser.core.DeviceFacts;
import com.mscope.browser.core.MarkdownOutline;
import com.mscope.browser.core.MemoryBudget;
import com.mscope.browser.core.PerfAdvisor;
import com.mscope.browser.core.PerfStats;
import com.mscope.browser.core.Planner;
import com.mscope.browser.core.PromptTemplate;
import com.mscope.browser.core.Rounds;
import com.mscope.browser.core.TextKit;
import com.mscope.browser.core.ToolGuard;
import com.mscope.browser.core.Validators;
import com.mscope.browser.i18n.Lang;
import com.mscope.browser.local.LocalModel;

import org.json.JSONObject;

import java.io.File;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
import com.mscope.browser.i18n.BaseActivity;

/**
 * 与本地 GGUF 模型对话：llama.cpp 流式生成、Markdown 渲染、可中断，历史会话自动持久化。
 *
 * <p>「智能体模式」下这里是一条真正的工具调用闭环：模型输出工具调用 → App 执行
 * （本机终端 / 联网 / 工作区文件 / 网页预览）→ 结果回灌 → 模型继续推理，直到给出最终答案。
 * 工具协议见 {@link ToolCall}，执行见 {@link AgentTools}。写操作与本机命令会先征求用户确认。
 */
public class ChatActivity extends BaseActivity {

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
    /**
     * 默认系统提示词直接走 {@link PromptTemplate}，不再各写一份。
     *
     * <p>以前这类文案在 ChatActivity 里又抄了一遍，结果就是「模板改了、这里没改」，
     * 而且语言切换后两份会不一致。现在只有一处真源。
     */
    private static String defaultSystem(boolean agent) {
        return PromptTemplate.body(agent ? PromptTemplate.Preset.AGENT : PromptTemplate.Preset.GENERAL);
    }

    /**
     * 单轮预算：以前这里是写死的 8 步。
     *
     * <p>8 不是安全边界，只是当初随手写的数字。「读文件 → 改一处 → 再读回来核对」
     * 就要 3 步，稍复杂一点的任务必然撞上限，用户看到的是「已达到上限」而事情只做了一半。
     *
     * <p>现在换成一个三线预算（步数 / 墙钟 / 上下文），由 {@link Rounds} 统一裁决：
     * 步数按难度给到 32~200，任何一条线到顶都体面收尾并说明原因。
     */
    private static final String KEY_EFFORT = "agent_effort";
    private Rounds.Effort effort = Rounds.Effort.NORMAL;
    /** 本轮预算快照（生成时用，线程安全由 volatile 保证读写可见）。 */
    private volatile Rounds.Budget budget = new Rounds.Budget(Rounds.Effort.NORMAL, 0);

    /**
     * 工具结果之后补的一句提醒。
     *
     * <p>小模型拿到一长串结果后容易「忘记自己刚才要干什么」，进而再调一次同样的工具。
     * 这句固定追加在结果后面，成本只有一个短句，但显著降低原地打转的概率。
     */
    private static String toolResultReminder() {
        return Lang.zh()
                ? "\n\n(工具已执行完毕。请根据上面的真实结果继续下一步；"
                + "不要重复执行刚刚已经完成的操作，已经做完就直接给出最终回答。)"
                : "\n\n(The tool has finished. Continue from the real result above; do not repeat an "
                + "operation that already succeeded — if the task is done, give the final answer now.)";
    }

    /** 默认上下文窗口。输出上限必须挤在上下文里（上下文 = 提示词/历史 + 本次输出），
     *  所以想放开输出，上下文也要一起放大；KV 已量化为 q8_0，8192 的占用约为原先一半。 */
    private static final int DEFAULT_CTX = 8192;

    /** 最近若干轮的推理性能，用于状态条展示均值/P90 与劣化提示。 */
    private final PerfStats perf = new PerfStats(12);
    /** 启动时按设备内存算出的「安全上下文」，用于参数弹窗里的说明。 */
    private int autoCtx = DEFAULT_CTX;

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
    /** 用户自定义的系统提示词；空串表示「用当前语言的默认模板」。 */
    private String systemPrompt = "";
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
        agentTools.setProber(new AgentTools.Prober() {
            @Override
            public List<Bench.Result> runBench() {
                if (!engine.isLoaded() || engine.isGenerating()) return null;
                return engine.runBench(Lang.zh());
            }

            @Override
            public List<DeviceFacts.Item> deviceFacts() {
                return collectFacts();
            }
        });
        prefs = getSharedPreferences(PREF, MODE_PRIVATE);
        nCtx = prefs.getInt(KEY_CTX, DEFAULT_CTX);
        // 设备内存先算一遍：这是「默认值是否放得下」的唯一依据。
        // 读不到 meminfo 时 suggestCtx 会原样返回 fallback，行为与以前一致。
        MemoryBudget.Snapshot mem = MemoryBudget.read();
        autoCtx = MemoryBudget.suggestCtx(mem, getIntent().getLongExtra(EXTRA_SIZE, 0), true, DEFAULT_CTX);
        if (!prefs.contains(KEY_CTX)) {
            nCtx = autoCtx;
        }
        systemPrompt = nz(prefs.getString(KEY_SYS, ""));
        agentMode = prefs.getBoolean(KEY_AGENT, true);
        effort = Rounds.parse(prefs.getString(KEY_EFFORT, Rounds.Effort.NORMAL.name()));
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
        if (model.displayName.isEmpty()) model.displayName = getString(R.string.local_model_fallback);

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
        } else if (generating && agentStep >= 1) {
            // 同时显示「第几步 / 总共几步」：用户能判断是该等还是该停
            tvCtx.setText(getString(R.string.agent_step_budget, agentStep, budget.rounds));
        } else {
            String usage = getString(R.string.chat_ctx_usage, engine.cachedTokens(), engine.contextSize());
            // 有足够样本时把均值 / P90 一并显示：单次 tok/s 抖动太大，只看最后一个数会误判设备
            String perfText = perf.size() >= 2 ? perf.summary() : "";
            tvCtx.setText(perfText.isEmpty() ? usage : usage + " · " + perfText);
            String advice = perf.advice();
            if (!advice.isEmpty()) {
                tvCtx.setText(tvCtx.getText() + "\n" + advice);
            }
        }
    }

    /* -------------------------------------------------------------- 能力开关 */

    private void toggleWeb() {
        toolWeb = !toolWeb;
        prefs.edit().putBoolean(KEY_TOOL_WEB, toolWeb).apply();
        updateChipWeb();
        invalidatePrefix();
        Toast.makeText(this, toolWeb ? R.string.chat_web_on : R.string.chat_web_off,
                Toast.LENGTH_SHORT).show();
    }

    /**
     * 系统提示词/参数变了，KV 里的公共前缀就不再成立。
     *
     * <p>不清也能算对（原生层会从第一个不同的 token 开始重算），但那是「白算」：
     * 前缀越靠前，重算越长。这里显式清掉，语义也更直白 —— 新的设定，新的上下文。
     * 生成中不动：正在跑的那一轮由它自己收尾，结束后自然会按新设定重算。
     */
    private void invalidatePrefix() {
        if (ready && !generating) engine.reset();
    }

    private void updateChipWeb() {
        chipWeb.setSelected(toolWeb);
        chipWeb.setText(toolWeb
                ? getString(R.string.chip_on, getString(R.string.chat_web))
                : getString(R.string.chat_web));
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
        chipAgent.setText(agentMode
                ? getString(R.string.chip_on, getString(R.string.chat_agent))
                : getString(R.string.chat_agent));
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
     *
     * <p>停下来有三种理由，都会告诉用户具体是哪一条（见 {@link Rounds}）：
     * 步数用完、时间用完、上下文将满。以前只有一个写死的轮数上限，
     * 而且到顶时的提示是「已达上限」，用户不知道是该继续还是该改目标。
     */
    private void runAgent(final int continueFrom) {
        final List<ChatMessage> ctx = buildBaseContext();
        // 本轮已经执行过的调用（名字 + 参数），用于识别「原地打转」：
        // 小模型拿到工具结果后仍可能重复同一个调用，不拦就会把整个轮次配额烧光。
        final ToolGuard guard = new ToolGuard();
        final Rounds.Budget bud = new Rounds.Budget(effort, System.currentTimeMillis());
        budget = bud;
        int reuse = continueFrom;
        boolean cancelledMidRound = false;
        Rounds.Stop why = Rounds.Stop.NONE;
        int round = 0;

        for (; round < bud.rounds; round++) {
            if (turnCancelled || !alive) break;
            agentStep = round + 1;
            uiSync(this::updateCtx);

            Gen gen = generateBlocking(ctx, reuse);
            reuse = -1;
            if (gen == null) break;                       // 出错或被取消
            if (gen.cancelled) cancelledMidRound = true;   // 用户按了停止：做完收尾就退出，别再继续生成

            final Set<String> toolNames = enabledTools();
            final List<ToolCall> calls = agentMode
                    ? ToolCall.parse(gen.text, toolNames)
                    : new ArrayList<>();
            // 去掉工具调用片段与思考块，只留能给人看的正文
            final String visible = ToolCall.stripThinking(agentMode
                    ? ToolCall.strip(gen.text, toolNames)
                    : gen.text).trim();
            final boolean last = calls.isEmpty();
            final boolean hasPrefix = !TextUtils.isEmpty(gen.prefix);
            // 整轮只有工具调用时删掉空气泡；但「继续生成」已有内容的气泡要保留
            final String shown;
            if (last && visible.isEmpty() && !hasPrefix) shown = getString(R.string.chat_empty_reply);
            else if (last && visible.isEmpty()) shown = gen.prefix;         // 续写没产出：别把原文覆盖成空
            else shown = gen.prefix + visible;
            applyAssistantText(gen.index, shown, last || hasPrefix);

            if (last) {                                    // 没有工具调用：这就是最终答案
                if (!TextUtils.isEmpty(visible)) {
                    final String v = visible;
                    uiSync(() -> autoSaveFiles(v));
                }
                break;
            }

            // 模型这一轮的输出（含调用）要留在上下文里，它才知道自己刚才要干什么。
            // 但**必须与下一条工具结果合并成同一条消息**：一旦出现两条连续的同类角色消息，
            // 带 function-call 模板的模型（Qwen / DeepSeek / MiniCPM 的默认模板）会把后一条
            // 当成「新一轮对话的开始」，前面那句「我刚才调了工具」就被截断在上一轮里，
            // 表现就是看不见结果、重复调用同一个工具。
            final StringBuilder turn = new StringBuilder(gen.text == null ? "" : gen.text);
            boolean executedThisRound = false;
            boolean allIdle = true;                        // 本轮是否全是只读/查询类调用
            for (ToolCall c : calls) {
                if (stoppedBy(gen)) break;
                final int card = addToolCard(c);
                if (card < 0) break;                       // 界面已销毁

                final ToolGuard.Verdict verdict = guard.check(c.name, callKey(c));
                String result;
                if (!verdict.run) {
                    result = verdict.reason;
                } else if (AgentTools.risky(c.name) && !allowAllWrites && !confirmTool(c)) {
                    // 用户拒绝，或确认期间按了停止：都不执行
                    result = stoppedBy(gen) ? getString(R.string.agent_cancelled)
                                            : getString(R.string.agent_denied);
                } else if (stoppedBy(gen)) {
                    // 轮到它时本轮已被叫停，不再动本机状态
                    result = getString(R.string.agent_cancelled);
                } else {
                    result = agentTools.exec(c);
                }
                if (AgentTools.risky(c.name)) allIdle = false;
                if (!verdict.run) allIdle = false;
                executedThisRound = true;
                updateToolCard(card, result);
                turn.append('\n').append(toolResultText(fitForContext(result)));
            }
            ctx.add(new ChatMessage(ChatMessage.ASSISTANT,
                    turn.toString() + "\n" + toolResultReminder()));

            // 上下文将满：再喂一轮只会把最早的内容挤掉，模型看到的是一段残缺历史
            double ctxUse = ctxRatio();
            why = Rounds.stopReason(round, bud, System.currentTimeMillis(), ctxUse);
            if (why != Rounds.Stop.NONE) break;

            if (guard.shouldStop()) {
                // 已经开始原地打转：再喂一轮只会更糟，直接收尾并说明原因
                uiSync(() -> addTool(getString(R.string.chat_tool_info), "info",
                        getString(R.string.agent_repeat_stop, guard.repeatCount())));
                break;
            }
            if (!executedThisRound) break;      // 本轮被叫停，没有任何工具真的执行：不必再喂一轮
            // 这一步全是查询、且模型已经有了工具结果：再多跑通常只是把同样的东西再查一遍
            if (allIdle && onlyReadOnly(calls) && guard.markIdleRound() >= ToolGuard.IDLE_BUDGET) {
                uiSync(() -> addTool(getString(R.string.chat_tool_info), "info",
                        getString(R.string.agent_idle_stop)));
                break;
            }
            if (!allIdle) guard.markProgress();
        }

        if (why != Rounds.Stop.NONE && !turnCancelled && !cancelledMidRound) {
            final String msg = Rounds.stopMessage(why, bud, round, System.currentTimeMillis());
            uiSync(() -> addTool(getString(R.string.chat_tool_info), "info", msg));
        }
        finishTurn();
    }

    /** 本轮调用是否全是只读（不动本机状态）。 */
    private static boolean onlyReadOnly(List<ToolCall> calls) {
        for (ToolCall c : calls) if (AgentTools.risky(c.name)) return false;
        return true;
    }

    /** 当前上下文占用比例（0~1）；引擎未知时给 0，不影响判断。 */
    private double ctxRatio() {
        try {
            int n = engine.cachedTokens();
            int max = engine.contextSize();
            if (n <= 0 || max <= 0) return 0;
            return Math.min(1.0, (double) n / max);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 请求 = 系统提示（含工具说明与本轮联网资料）+ 历史（跳过工具卡片）。 */
    private List<ChatMessage> buildBaseContext() {
        List<ChatMessage> req = new ArrayList<>();
        String sys = buildSystemPrompt();
        if (!TextUtils.isEmpty(sys)) req.add(new ChatMessage(ChatMessage.SYSTEM, sys));
        for (ChatMessage m : messages) {
            if (m.isTool()) continue;
            if (TextUtils.isEmpty(m.content)) continue;
            if (m.isAssistant()) {
                // 历史里的助手消息 = 上一轮已经「用过的」输出。工具调用片段与思考内容
                // 再喂回去只会诱导模型重复调用同一个工具；思考块更是纯噪声。
                String clean = agentMode
                        ? ToolCall.strip(ToolCall.stripThinking(m.content), enabledTools())
                        : ToolCall.stripThinking(m.content);
                clean = clean.trim();
                if (clean.isEmpty()) continue;
                appendMerged(req, ChatMessage.ASSISTANT, clean);
                continue;
            }
            appendMerged(req, m.role, m.content);
        }
        return req;
    }

    /**
     * 追加一条消息；若与上一条同角色，则合并成一条。
     *
     * <p>为什么必须合并：带 function-call 模板的模型（Qwen / DeepSeek / MiniCPM 的 chat template）
     * 在渲染时按「轮」生成控制 token。同一个 role 连续出现两次时，模板会在中间插入一次
     * 「上一轮结束 + 新一轮开始」，于是前一条的内容被划到上一轮里 —— 模型看到的是
     * 「我调完工具，然后对话重新开始了」，工具结果等于没给。
     *
     * <p>而界面上本来就有现成的同角色相邻场景：工具卡片不参与请求，但它前后的两条文本消息
     * 在请求里就是相邻的；自动落盘、错误提示、用户连发两条也一样。
     */
    private static void appendMerged(List<ChatMessage> req, String role, String content) {
        if (TextUtils.isEmpty(content)) return;
        ChatMessage last = req.isEmpty() ? null : req.get(req.size() - 1);
        // 与最后一条同角色就合并。注意只看最后一条：中间的空消息已在上面被跳过，
        // 不会出现「连着几条空消息把同角色消息隔开」的情况。
        if (last != null && role.equals(last.role)) {
            last.content = last.content + "\n\n" + content;
            return;
        }
        req.add(new ChatMessage(role, content));
    }

    /**
     * 本次对话实际启用的工具集合。
     *
     * <p>解析、剥离、提示词三处必须共用它：只要有一处用了全量清单，就会出现
     * 「提示词没列这个工具，模型却调得通」或者反过来的静默失效。
     */
    private Set<String> enabledTools() {
        return AgentTools.enabled(toolWeb, true, true);
    }

    /** 最后一条用户输入（用于把最相关的记忆带进提示词）。 */
    private String lastUserText() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if (m.isUser() && !TextUtils.isEmpty(m.content)) return m.content;
        }
        return "";
    }

    private String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        String base = systemPrompt == null ? "" : systemPrompt.trim();
        // 空串 = 没自定义过：用当前语言的默认模板（切换语言后自动跟上）
        if (TextUtils.isEmpty(base)) base = defaultSystem(agentMode);
        PromptTemplate.Preset preset = agentMode ? PromptTemplate.Preset.AGENT : PromptTemplate.Preset.GENERAL;
        // 小模型（≤2.5B）容易被长提示词带跑偏，工具说明换成压缩版；工具清单照旧来自 AgentTools，
        // 免得「提示词说的工具」和「真的能执行的工具」两套东西对不上。
        boolean compact = PromptTemplate.wantsCompactTools(model == null ? "" : model.displayName);
        // 工具清单只列「真的能执行」的那些：关掉联网后还写着 web_search，
        // 模型就会去调一个注定失败的接口，用户看到的是一次长时间的空转。
        String tools = null;
        if (agentMode) {
            Set<String> on = enabledTools();
            String list = AgentTools.describe(on.contains("web_search"), true, on.contains("shell"));
            tools = PromptTemplate.toolSection(list, compact);
            // 提示词里明说「别重复调用」，与宿主侧闸门配合，减少一次无用的往返
            tools = tools + PromptTemplate.noRepeatRule();
        }
        sb.append(PromptTemplate.compose(base, preset, tools, null));
        // 把模型「自己的工作状态」还给模型：待办进度、改过哪些文件、记住了什么。
        // 这些都很短，但正是多步任务最容易丢的三样东西（上下文一裁就没了）。
        if (agentMode) {
            String todo = Planner.brief(agentTools.todo(), Lang.zh(), 300);
            if (!TextUtils.isEmpty(todo)) {
                sb.append("\n\n").append(getString(R.string.chat_prompt_todo)).append('\n').append(todo);
            }
            String journal = agentTools.journal().summary(280, Lang.zh());
            if (!TextUtils.isEmpty(journal)) {
                sb.append("\n\n").append(getString(R.string.chat_prompt_journal)).append('\n').append(journal);
            }
        }
        String mem = agentTools.memoryStore().brief(lastUserText(), 360);
        if (!TextUtils.isEmpty(mem)) {
            sb.append("\n\n").append(getString(R.string.chat_prompt_memory)).append('\n').append(mem);
        }
        if (!TextUtils.isEmpty(pendingWebContext)) {
            sb.append("\n\n").append(getString(R.string.chat_web_context))
              .append('\n').append(pendingWebContext);
        }
        return sb.toString();
    }

    /**
     * 回灌给模型的工具结果。用 {@code <tool_response>} 包裹：
     * 这正是 MiniCPM5 / Qwen 等模板里工具返回的标准写法，兼容性最好。
     */
    private static String toolResultText(String result) {
        return "<tool_response>\n" + result + "\n</tool_response>";
    }

    /** 单条工具结果回灌给模型时的字符上限（界面卡片仍显示完整内容）。 */
    private static final int MAX_TOOL_RESULT_CHARS = 6000;

    /**
     * 工具结果回灌前收口：超长的截断，并明确告诉模型「被截断了」。
     *
     * <p>不截断时，一次 {@code cat} 或抓网页就能把整段历史挤出上下文，
     * 随后引擎的裁剪会把最早几条消息丢掉 —— 模型看到的历史突然缺了一块，
     * 更容易重复调用。截断点按「完整字符」取，不会把 emoji 切成半个。
     */
    private static String fitForContext(String result) {
        String r = result == null ? "" : result;
        if (r.length() <= MAX_TOOL_RESULT_CHARS) return r;
        String head = TextKit.trimUtf8(r, MAX_TOOL_RESULT_CHARS / 2);
        String tail = r.substring(r.length() - MAX_TOOL_RESULT_CHARS / 4);
        return head + "\n…" + (Lang.zh() ? "（中略）" : " (omitted) ") + "…\n" + tail;
    }

    /**
     * 判重用的调用指纹：工具名 + 归一化后的参数。
     *
     * <p>参数先按 JSON 的对象顺序无关地排序再拼，避免「同一个调用、参数顺序不同」被判成两次。
     * 数值/布尔统一成字符串，免得 {@code 1} 与 {@code "1"} 看起来不一样。
     */
    private static String callKey(ToolCall c) {
        if (c == null || c.args == null) return "";
        final JSONObject a = c.args;
        final List<String> keys = new ArrayList<>();
        final List<String> values = new ArrayList<>();
        for (java.util.Iterator<String> it = a.keys(); it.hasNext(); ) {
            String k = it.next();
            keys.add(k);
            values.add(String.valueOf(a.opt(k)));
        }
        return ToolGuard.canonicalArgs(keys, values);
    }

    private static class Gen {
        final String text;
        final int index;
        final String prefix;
        /** 这一轮是被用户「停止」打断的（而不是正常结束）。 */
        final boolean cancelled;

        Gen(String text, int index, String prefix, boolean cancelled) {
            this.text = text;
            this.index = index;
            this.prefix = prefix;
            this.cancelled = cancelled;
        }
    }

    /** 这一轮是否已经被用户打断：循环里每个安全点都用它检查，避免「按了停止还在跑工具」。 */
    private boolean stoppedBy(Gen gen) {
        return gen.cancelled || turnCancelled;
    }

    /** 生成一轮并阻塞等待完成；返回 null 表示失败。 */
    private Gen generateBlocking(final List<ChatMessage> ctx, final int reuseIndex) {
        final int[] idx = {-1};
        final String[] pre = {""};
        final boolean[] reused = {false};
        uiSync(() -> {
            if (reuseIndex >= 0 && reuseIndex < messages.size()
                    && messages.get(reuseIndex).isAssistant()) {
                idx[0] = reuseIndex;                       // 继续生成：复用同一条气泡
                reused[0] = true;
                ChatMessage m = messages.get(reuseIndex);
                pre[0] = m.content == null ? "" : m.content;
                // 续写时上下文里必须带上「已经写出来的部分」，否则模型不知道从哪接：
                // 它会重新写一遍开头，而界面上的气泡已经有一段内容，两段叠在一起就是重复。
                ctx.add(new ChatMessage(ChatMessage.ASSISTANT, pre[0]));
            } else {
                idx[0] = messages.size();
                messages.add(new ChatMessage(ChatMessage.ASSISTANT, ""));
                stats.add("");
                adapter.notifyDataSetChanged();
                scrollToBottom();
            }
        });
        final int aiIndex = idx[0];
        final String prefix = reused[0] ? pre[0] : "";

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
                perf.add(new PerfStats.Sample(tokens, prefillMs, tokensPerSec,
                        System.currentTimeMillis()));
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
        if (failed[0] || !alive || aiIndex < 0) return null;
        // 续写时气泡里已经有 prefix，不能再拼一次；新气泡则是从空开始。
        return new Gen(out[0], aiIndex, prefix, turnCancelled);
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
        if (!alive) return -1;
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

    /** 工具卡片标题（本地化）；默认值就是工具名本身，保持可读但不误导。 */
    private String toolTitle(String name) {
        switch (name) {
            case "shell": return getString(R.string.tool_title_shell);
            case "web_search": return getString(R.string.tool_title_web_search);
            case "fetch_url": return getString(R.string.tool_title_fetch_url);
            case "read_file": return getString(R.string.tool_title_read_file);
            case "write_file": return getString(R.string.tool_title_write_file);
            case "list_files": return getString(R.string.tool_title_list_files);
            case "delete_file": return getString(R.string.tool_title_delete_file);
            case "open_preview": return getString(R.string.tool_title_open_preview);
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
                invalidatePrefix();
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
                    addTool(getString(R.string.chat_tool_error), "error", getString(R.string.cmd_preview_usage));
                    return;
                }
                try {
                    HtmlPreviewActivity.start(this, Workspace.normalize(rest));
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
            final String text = out.text == null || out.text.isEmpty()
                    ? getString(R.string.term_no_output) : out.text;
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
        // 「本篇大纲」只在最后一条回复够长、且确实有小标题时出现，避免菜单里堆一堆点了没反应的项。
        final List<MarkdownOutline.Heading> outline = currentOutline();
        final boolean hasOutline = !outline.isEmpty();
        final java.util.ArrayList<String> actions = new java.util.ArrayList<>();
        actions.add(getString(R.string.chat_new));
        actions.add(getString(R.string.chat_history));
        actions.add(getString(R.string.workspace_title));
        actions.add(getString(R.string.term_title));
        actions.add(getString(R.string.agent_effort_title, effort.label(), effort.rounds));
        if (!agentTools.todo().isEmpty()) actions.add(getString(R.string.agent_todo_title));
        if (agentTools.journal().size() > 0) actions.add(getString(R.string.agent_journal_title));
        actions.add(getString(R.string.agent_tools_title));
        actions.add(getString(R.string.agent_memory_title));
        actions.add(getString(R.string.agent_bench));
        actions.add(getString(R.string.chat_params));
        actions.add(getString(R.string.chat_system));
        if (hasOutline) actions.add(getString(R.string.chat_outline));
        actions.add(getString(R.string.chat_stats));
        actions.add(getString(R.string.chat_export));
        new MaterialAlertDialogBuilder(this)
                .setItems(actions.toArray(new String[0]), (d, which) -> {
                    String picked = actions.get(which);
                    if (picked.equals(getString(R.string.chat_new))) {
                        newChat();
                    } else if (picked.equals(getString(R.string.chat_history))) {
                        showHistory();
                    } else if (picked.equals(getString(R.string.workspace_title))) {
                        openWorkspace();
                    } else if (picked.equals(getString(R.string.term_title))) {
                        openTerminal();
                    } else if (picked.startsWith(getString(R.string.agent_effort_title, effort.label(), effort.rounds))) {
                        showEffortDialog();
                    } else if (picked.equals(getString(R.string.agent_todo_title))) {
                        showTodoDialog();
                    } else if (picked.equals(getString(R.string.agent_journal_title))) {
                        showJournalDialog();
                    } else if (picked.equals(getString(R.string.agent_tools_title))) {
                        showToolCatalog();
                    } else if (picked.equals(getString(R.string.agent_memory_title))) {
                        showMemoryDialog();
                    } else if (picked.equals(getString(R.string.agent_bench))) {
                        runBench();
                    } else if (picked.equals(getString(R.string.chat_params))) {
                        showParamsDialog();
                    } else if (picked.equals(getString(R.string.chat_system))) {
                        showSystemDialog();
                    } else if (picked.equals(getString(R.string.chat_outline))) {
                        showOutline(outline);
                    } else if (picked.equals(getString(R.string.chat_stats))) {
                        showPerfStats();
                    } else {
                        exportChat();
                    }
                })
                .show();
    }

    /** 最后一条助手回复的标题大纲；不够长或没有小标题时返回空表。 */
    private List<MarkdownOutline.Heading> currentOutline() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if (m.isAssistant()) {
                String text = m.content == null ? "" : m.content;
                return MarkdownOutline.worthOutline(text)
                        ? MarkdownOutline.extract(text)
                        : java.util.Collections.emptyList();
            }
        }
        return java.util.Collections.emptyList();
    }

    private void showOutline(List<MarkdownOutline.Heading> outline) {
        final String[] items = new String[outline.size()];
        for (int i = 0; i < outline.size(); i++) items[i] = outline.get(i).toString();
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.chat_outline)
                .setItems(items, (d, which) -> {
                    // 没有精确的行定位能力时，至少把整段回复滚到顶，长文翻找已经省一大半
                    if (!messages.isEmpty()) chatList.scrollToPosition(messages.size() - 1);
                })
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showPerfStats() {
        if (perf.size() == 0) {
            Toast.makeText(this, R.string.chat_stats_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.chat_stats_recent, perf.size())).append('\n');
        sb.append(perf.summary()).append("\n\n");
        List<PerfStats.Sample> list = perf.snapshot();
        for (int i = 0; i < list.size(); i++) {
            PerfStats.Sample sm = list.get(i);
            sb.append(getString(R.string.chat_stats_row,
                    i + 1, sm.tokens, sm.tokensPerSec, sm.prefillMs)).append('\n');
        }
        String advice = perf.advice();
        if (!advice.isEmpty()) sb.append('\n').append(advice);
        sb.append('\n').append(getString(R.string.chat_stats_hint,
                engine.contextSize(), engine.backendInfo().isEmpty() ? "-" : engine.backendInfo()));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.chat_stats)
                .setMessage(sb.toString().trim())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void newChat() {
        if (generating) {
            turnCancelled = true;
            engine.cancel();
        }
        allowAllWrites = false;                      // 新会话重新逐次确认
        agentTools.resetSession();                   // 待办与工作日志属于会话级，长期记忆保留
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
            sb.append(m.isUser() ? getString(R.string.export_me)
                            : (m.isTool() ? getString(R.string.export_tool) : model.displayName))
              .append(getString(R.string.export_sep))
              .append('\n')
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

    /** 本机事实：模型问「我手机怎么样」时，它得看得见真实数据而不是编。 */
    private List<DeviceFacts.Item> collectFacts() {
        List<DeviceFacts.Item> out = new ArrayList<>();
        out.add(DeviceFacts.of("model", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL,
                "机型", "device"));
        out.add(DeviceFacts.of("android", "Android " + android.os.Build.VERSION.RELEASE
                        + " (SDK " + android.os.Build.VERSION.SDK_INT + ")",
                "系统", "android"));
        StringBuilder abi = new StringBuilder();
        for (String a : android.os.Build.SUPPORTED_ABIS) {
            if (abi.length() > 0) abi.append(", ");
            abi.append(a);
        }
        out.add(DeviceFacts.of("abi", abi.toString(), "ABI", "ABI"));
        try {
            MemoryBudget.Snapshot mem = MemoryBudget.read();
            out.add(DeviceFacts.of("mem", DeviceFacts.bytes(mem.totalKb * 1024) + " / "
                            + (Lang.zh() ? "可用 " : "free ") + DeviceFacts.bytes(mem.availableKb * 1024),
                    "内存", "memory"));
        } catch (Throwable ignored) {
        }
        try {
            java.io.File files = getFilesDir();
            android.os.StatFs sf = new android.os.StatFs(files.getAbsolutePath());
            out.add(DeviceFacts.of("storage", DeviceFacts.bytes(sf.getAvailableBytes()) + " / "
                            + DeviceFacts.bytes(sf.getTotalBytes()),
                    Lang.zh() ? "存储（可用/总）" : "storage (free/total)",
                    "storage (free/total)"));
        } catch (Throwable ignored) {
        }
        try {
            android.content.Intent batt = registerReceiver(null,
                    new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
            if (batt != null) {
                int level = batt.getIntExtra("level", -1);
                int scale = batt.getIntExtra("scale", 100);
                if (level >= 0 && scale > 0) {
                    out.add(DeviceFacts.of("battery", (level * 100 / scale) + "%",
                            "电量", "battery"));
                }
            }
        } catch (Throwable ignored) {
        }
        out.add(DeviceFacts.of("engine", engine.isLoaded()
                        ? (Lang.zh() ? "已加载 " : "loaded ") + (model == null ? "" : model.displayName())
                        + " · " + engine.backendInfo()
                        : (Lang.zh() ? "未加载模型" : "no model loaded"),
                "推理引擎", "engine"));
        out.add(DeviceFacts.of("ctx", engine.cachedTokens() + " / " + engine.contextSize()
                        + (Lang.zh() ? " token（已用/窗口）" : " tokens (used/window)"),
                "上下文", "context"));
        out.add(DeviceFacts.of("speed", String.format(Locale.ROOT, "%.1f tok/s", engine.lastTokensPerSec()),
                "实测解速", "measured decode speed"));
        out.add(DeviceFacts.of("kb", agentTools.index().stats(Lang.zh()),
                "工作区索引", "workspace index"));
        return out;
    }

    /* ------------------------------------------------------ 档位 / 待办 / 日志 / 基准 */

    /** 单轮预算档位：步数与时间的取舍，让用户按任务复杂度自己选。 */
    private void showEffortDialog() {
        Rounds.Effort[] all = Rounds.Effort.values();
        String[] labels = new String[all.length];
        int checked = 0;
        for (int i = 0; i < all.length; i++) {
            labels[i] = getString(R.string.agent_effort_item, all[i].label(), all[i].rounds,
                    all[i].budgetMs / 60000);
            if (all[i] == effort) checked = i;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.agent_effort_title_plain)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    effort = all[which];
                    prefs.edit().putString(KEY_EFFORT, effort.name()).apply();
                    d.dismiss();
                    updateCtx();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 模型自己维护的待办清单：看得见进度，它就不容易把同一件事做两遍。 */
    private void showTodoDialog() {
        List<Planner.Item> items = agentTools.todo();
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.agent_todo_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.agent_todo_title) + " (" + Planner.doneCount(items)
                        + "/" + items.size() + ")")
                .setMessage(Planner.render(items))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** 工作日志：上下文被裁掉之后，模型还能靠它「想起」自己做过什么。 */
    private void showJournalDialog() {
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        android.widget.TextView tv = new android.widget.TextView(this);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        tv.setTextIsSelectable(true);
        tv.setText(agentTools.journal().render(0, Lang.zh()));
        sv.addView(tv);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.agent_journal_title)
                .setView(sv)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** 当前会话实际启用的工具清单（带分组与「需确认」标记）。 */
    private void showToolCatalog() {
        Set<String> on = enabledTools();
        List<String[]> all = AgentTools.catalog(Lang.zh());
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.agent_tools_count, on.size(), all.size())).append("\n\n");
        String lastGroup = "";
        for (String[] t : all) {
            boolean active = on.contains(t[0]);
            if (!t[2].equals(lastGroup)) {
                lastGroup = t[2];
                sb.append("【").append(lastGroup).append("】\n");
            }
            sb.append(active ? "● " : "○ ").append(t[0]);
            if ("1".equals(t[3])) sb.append(Lang.zh() ? "（需确认）" : " (asks first)");
            sb.append("\n    ").append(t[1]).append('\n');
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        android.widget.TextView tv = new android.widget.TextView(this);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        tv.setTextIsSelectable(true);
        tv.setText(sb.toString());
        sv.addView(tv);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.agent_tools_title)
                .setView(sv)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** 长期记忆：跨会话的事实，写入即脱敏，可单条删除。 */
    private void showMemoryDialog() {
        final List<com.mscope.browser.core.Memory.Fact> facts = agentTools.memoryStore().all();
        if (facts.isEmpty()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.agent_memory_title)
                    .setMessage(R.string.agent_memory_empty)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        final String[] items = new String[facts.size()];
        for (int i = facts.size() - 1, k = 0; i >= 0; i--, k++) {
            items[k] = facts.get(i).text;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.agent_memory_title) + " (" + facts.size() + ")")
                .setItems(items, (d, which) -> {
                    // 点一条 = 删一条：记忆是「当前生效」的，不需要留存版本
                    int at = facts.size() - 1 - which;
                    agentTools.memoryStore().remove(facts.get(at).id);
                    Toast.makeText(this, R.string.agent_memory_removed, Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton(R.string.agent_memory_clear, (d, w) -> {
                    agentTools.memoryStore().clear();
                    Toast.makeText(this, R.string.agent_memory_cleared, Toast.LENGTH_SHORT).show();
                })
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** 跑一次端侧基准：回答「我这台机器跑这个模型到底什么速度」。 */
    private void runBench() {
        if (!engine.isLoaded()) {
            Toast.makeText(this, R.string.agent_bench_no_model, Toast.LENGTH_SHORT).show();
            return;
        }
        if (engine.isGenerating()) {
            Toast.makeText(this, R.string.agent_bench_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        final Toast t = Toast.makeText(this, R.string.agent_bench_running, Toast.LENGTH_LONG);
        t.show();
        tools.execute(() -> {
            List<Bench.Result> rs = engine.runBench(Lang.zh());
            ui.post(() -> {
                t.cancel();
                if (!alive) return;
                if (rs.isEmpty()) {
                    Toast.makeText(this, R.string.agent_bench_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                StringBuilder sb = new StringBuilder();
                for (Bench.Result r : rs) sb.append(r.line(Lang.zh())).append('\n');
                sb.append('\n').append(Bench.verdict(rs, Lang.zh()));
                sb.append('\n').append(engine.advice(Lang.zh()));
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.agent_bench)
                        .setMessage(sb.toString())
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                updateCtx();
            });
        });
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
        EditText etRepeatPenalty = v.findViewById(R.id.etRepeatPenalty);
        if (etRepeatPenalty != null) etRepeatPenalty.setText(String.valueOf(params.repeatPenalty));
        TextView ctxTip = v.findViewById(R.id.tvCtxTip);
        if (ctxTip != null) {
            MemoryBudget.Snapshot mem = MemoryBudget.read();
            long size = model == null ? 0 : model.size;
            String device = MemoryBudget.explain(mem, size, autoCtx, Lang.zh());
            ctxTip.setText(getString(R.string.p_ctx_tip) + "\n\n"
                    + getString(R.string.p_ctx_device_tip, device));
        }

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
                    params.repeatLastN = def.repeatLastN;
                    params.repeatPenalty = def.repeatPenalty;
                    invalidatePrefix();
                    if (nCtx != DEFAULT_CTX) {
                        nCtx = DEFAULT_CTX;
                        prefs.edit().putInt(KEY_CTX, nCtx).apply();
                        doLoad();
                    }
                    Toast.makeText(this, R.string.p_reset, Toast.LENGTH_SHORT).show();
                })
                .setPositiveButton(R.string.p_apply, (d, w) -> {
                    // 统一走 Validators：宽松解析（全角数字、逗号小数点）+ 范围收敛 +
                    // 输出上限压到上下文的 3/4，避免「填了个 32768 结果永远触不到 EOS」。
                    params.maxTokens = Validators.parseInt(etMax.getText().toString(), params.maxTokens);
                    params.temp = Validators.parseFloat(etTemp.getText().toString(), params.temp);
                    params.topP = Validators.parseFloat(etTopP.getText().toString(), params.topP);
                    params.topK = Validators.parseInt(etTopK.getText().toString(), params.topK);
                    if (etRepeatPenalty != null) {
                        params.repeatPenalty = Validators.parseFloat(
                                etRepeatPenalty.getText().toString(), params.repeatPenalty);
                    }
                    int ctx = Validators.parseInt(etCtx.getText().toString(), nCtx);
                    Validators.Sampling s = new Validators.Sampling(
                            params.maxTokens, params.temp, params.topP, params.topK, ctx,
                            params.repeatLastN, params.repeatPenalty);
                    String note = Validators.normalize(s);
                    params.maxTokens = s.maxTokens;
                    params.temp = s.temp;
                    params.topP = s.topP;
                    params.topK = s.topK;
                    params.repeatLastN = s.repeatLastN;
                    params.repeatPenalty = s.repeatPenalty;
                    if (s.nCtx != nCtx) {
                        nCtx = s.nCtx;
                        prefs.edit().putInt(KEY_CTX, nCtx).apply();
                        if (generating) engine.cancel();
                        doLoad();     // 上下文变化需要重建 llama context
                    } else {
                        invalidatePrefix();   // 采样参数变化：作废旧 KV，避免新旧参数混用
                    }
                    if (!note.isEmpty()) {
                        Toast.makeText(this, note, Toast.LENGTH_LONG).show();
                    }
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
                    String next = et.getText().toString().trim();
                    boolean changed = !next.equals(systemPrompt);
                    systemPrompt = next;
                    prefs.edit().putString(KEY_SYS, systemPrompt).apply();
                    // 系统提示词在最前面：改了它，整个 KV 前缀都不再匹配。
                    // 不主动 reset 的话，引擎会退化成「从第一个不同的 token 起重算」，
                    // 虽然结果正确但会白算一遍长前缀；这里直接清掉更省事也更好理解。
                    if (changed) invalidatePrefix();
                })
                .show();
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
                // 不能直接用绑定时的 position：流式生成期间列表会不断插入/删除，
                // 长按发生时这一行可能早已不是当初绑定的那条消息（「删除本条」就会删错）。
                int at = h.getBindingAdapterPosition();
                if (at != RecyclerView.NO_POSITION) showMessageMenu(at);
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
            // 流式过程中用纯文本，避免每个 token 都重新解析 Markdown；
            // 但依然要剥掉思考块与工具调用标签——否则用户会看到满屏 <function name=...> 原文，
            // 而这几行随后会被「只显示正文」的结果替换掉，看起来像回答被吞了。
            boolean streaming = generating && position == messages.size() - 1;
            if (streaming) {
                String live = agentMode
                        ? ToolCall.stripThinking(ToolCall.strip(m.content, enabledTools()))
                        : ToolCall.stripThinking(m.content);
                live = live.trim();
                a.bubble.setText(TextUtils.isEmpty(live)
                        ? getString(R.string.chat_thinking) : live);
            } else if (TextUtils.isEmpty(m.content)) {
                a.bubble.setText("");
            } else {
                // 历史消息里可能残留思考块（老版本存下来的），渲染前统一剥掉
                String body = ToolCall.stripThinking(m.content).trim();
                markwon.setMarkdown(a.bubble, body.isEmpty() ? m.content : body);
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