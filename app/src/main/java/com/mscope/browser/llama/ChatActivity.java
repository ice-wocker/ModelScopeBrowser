package com.mscope.browser.llama;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import com.mscope.browser.local.LocalModel;

import java.util.ArrayList;
import java.util.List;

/** 与本地 GGUF 模型对话：llama.cpp 流式生成、可调参数、可中断，历史会话自动持久化。 */
public class ChatActivity extends AppCompatActivity {

    private static final String EXTRA_PATH = "m_path";
    private static final String EXTRA_NAME = "m_name";
    private static final String EXTRA_REPO = "m_repo";
    private static final String EXTRA_FILE = "m_file";
    private static final String EXTRA_SIZE = "m_size";

    private static final String PREF = "chat_prefs";
    private static final String KEY_CTX = "n_ctx";
    private static final String KEY_SYS = "system";
    private static final String DEFAULT_SYS = "你是一个运行在手机本地的中文 AI 助手，请用简体中文准确、简洁地回答。";

    private static final int DEFAULT_CTX = 2048;

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
    private ProgressBar loadProgress;

    private SharedPreferences prefs;
    private String systemPrompt = DEFAULT_SYS;
    private int nCtx = DEFAULT_CTX;

    private boolean generating;
    private boolean ready;
    private boolean stopping;
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
        prefs = getSharedPreferences(PREF, MODE_PRIVATE);
        nCtx = prefs.getInt(KEY_CTX, DEFAULT_CTX);
        systemPrompt = prefs.getString(KEY_SYS, DEFAULT_SYS);

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
        loadProgress = findViewById(R.id.loadProgress);

        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        adapter = new Adapter();
        chatList.setLayoutManager(lm);
        chatList.setAdapter(adapter);

        btnSend.setOnClickListener(v -> onSendClicked());

        // 打开该模型最近一次会话；没有就新建
        openSession(store.latest(model.localPath));
        loadModel();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /* -------------------------------------------------------------- 会话切换 */

    private void openSession(ChatStore.Session s) {
        session = (s != null) ? s : store.create(model.localPath);
        session.align();
        messages = session.messages;
        stats = session.stats;
    }

    /** 会话里没有任何消息时补一条欢迎语（不落盘，用户发消息后才有内容）。 */
    private void ensureGreeting() {
        if (!messages.isEmpty()) return;
        messages.add(new ChatMessage(ChatMessage.ASSISTANT,
                getString(R.string.chat_greeting, model.displayName)));
        stats.add("");
        adapter.notifyDataSetChanged();
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

        engine.load(model, nCtx, (ok, msg) -> {
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
        tvLoadStatus.setText(getString(R.string.chat_loaded, engine.contextSize()));
        loadBox.setVisibility(View.VISIBLE);
        ui.postDelayed(() -> {
            if (alive && ready) loadBox.setVisibility(View.GONE);
        }, 2500);

        etInput.setEnabled(true);
        btnSend.setEnabled(true);
        chatHint.setVisibility(View.GONE);

        ensureGreeting();
        adapter.notifyDataSetChanged();
        updateCtx();
        scrollToBottom();
    }

    private void showHint(String title, String desc) {
        chatHint.setVisibility(View.VISIBLE);
        tvHintTitle.setText(title);
        tvHintDesc.setText(desc);
    }

    /** 刷新上下文用量；生成中/停止中时显示对应状态。 */
    private void updateCtx() {
        if (!ready) {
            tvCtx.setVisibility(View.GONE);
            return;
        }
        tvCtx.setVisibility(View.VISIBLE);
        if (stopping) {
            tvCtx.setText(R.string.chat_stopping);
        } else {
            tvCtx.setText(getString(R.string.chat_ctx_usage, engine.cachedTokens(), engine.contextSize()));
        }
    }

    /* -------------------------------------------------------------- 发送流程 */

    private void onSendClicked() {
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
        messages.add(new ChatMessage(ChatMessage.USER, text));
        stats.add("");
        startGeneration();
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

    /** 请求 = 系统提示 + 历史（不含末尾的空占位）。 */
    private List<ChatMessage> buildRequest() {
        List<ChatMessage> req = new ArrayList<>();
        if (!TextUtils.isEmpty(systemPrompt.trim())) {
            req.add(new ChatMessage(ChatMessage.SYSTEM, systemPrompt.trim()));
        }
        for (int i = 0; i < messages.size() - 1; i++) {
            ChatMessage m = messages.get(i);
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
                persist();
                scrollToBottom();
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

    /* ---------------------------------------------------------- 消息操作 */

    /** 长按气泡：复制 / 重新生成或编辑重发 / 删除本条。 */
    private void showMessageMenu(final int pos) {
        if (pos < 0 || pos >= messages.size()) return;
        if (generating) {
            Toast.makeText(this, R.string.chat_thinking, Toast.LENGTH_SHORT).show();
            return;
        }
        final ChatMessage m = messages.get(pos);
        final boolean user = m.isUser();
        final String[] actions = user
                ? new String[]{getString(R.string.msg_copy), getString(R.string.msg_edit_resend),
                               getString(R.string.msg_delete)}
                : new String[]{getString(R.string.msg_copy), getString(R.string.msg_regenerate),
                               getString(R.string.msg_delete)};

        new MaterialAlertDialogBuilder(this)
                .setItems(actions, (d, which) -> {
                    if (which == 0) {
                        copy(m.content);
                    } else if (which == 1) {
                        if (user) editResend(pos);
                        else regenerate(pos);
                    } else {
                        deleteMessage(pos);
                    }
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
        if (userIdx < 0) return;           // 没有对应的用户消息（如欢迎语），不处理
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

    private void newChat() {
        if (generating) engine.cancel();
        persist();                                   // 先存当前会话
        openSession(store.create(model.localPath));  // 再开新会话
        adapter.notifyDataSetChanged();
        chatHint.setVisibility(View.GONE);
        if (ready) {
            engine.reset();
            ensureGreeting();
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
            sb.append(m.isUser() ? "我" : model.displayName)
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
                    params.maxTokens = clampInt(etMax.getText().toString(), params.maxTokens, 1, 4096);
                    params.temp = clampFloat(etTemp.getText().toString(), params.temp, 0f, 2f);
                    params.topP = clampFloat(etTopP.getText().toString(), params.topP, 0f, 1f);
                    params.topK = clampInt(etTopK.getText().toString(), params.topK, 0, 200);
                    int ctx = clampInt(etCtx.getText().toString(), nCtx, 512, 8192);
                    if (ctx != nCtx) {
                        nCtx = ctx;
                        prefs.edit().putInt(KEY_CTX, nCtx).apply();
                        if (generating) engine.cancel();
                        doLoad();     // 上下文变化需要重建 llama context
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
        super.onDestroy();
    }

    /* ---------------------------------------------------------------- 适配器 */

    private class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int T_USER = 0;
        private static final int T_AI = 1;

        @Override
        public int getItemViewType(int position) {
            return messages.get(position).isUser() ? T_USER : T_AI;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (viewType == T_USER) {
                return new UserVH(inf.inflate(R.layout.item_chat_user, parent, false));
            }
            return new AiVH(inf.inflate(R.layout.item_chat_ai, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
            ChatMessage m = messages.get(position);
            if (h instanceof UserVH) {
                ((UserVH) h).bubble.setText(m.content);
            } else {
                AiVH a = (AiVH) h;
                a.bubble.setText(m.content);
                String st = position < stats.size() ? stats.get(position) : "";
                if (TextUtils.isEmpty(st)) {
                    a.stat.setVisibility(View.GONE);
                } else {
                    a.stat.setVisibility(View.VISIBLE);
                    a.stat.setText(st);
                }
            }
            h.itemView.setOnLongClickListener(v -> {
                showMessageMenu(position);
                return true;
            });
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
    }
}