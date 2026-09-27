package com.mscope.browser.llama;

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
import java.util.Locale;

/** 与本地 GGUF 模型对话：llama.cpp 流式生成、可调参数、可中断。 */
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

    private final LlamaEngine.Params params = new LlamaEngine.Params();
    private final List<ChatMessage> messages = new ArrayList<>();
    private final List<String> stats = new ArrayList<>();

    private RecyclerView chatList;
    private EditText etInput;
    private ImageButton btnSend;
    private View loadBox;
    private View chatHint;
    private TextView tvLoadStatus;
    private TextView tvHintTitle;
    private TextView tvHintDesc;
    private ProgressBar loadProgress;
    private MaterialToolbar toolbar;

    private SharedPreferences prefs;
    private String systemPrompt = DEFAULT_SYS;
    private int nCtx = DEFAULT_CTX;

    private boolean generating;
    private boolean ready;
    private boolean alive = true;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private Adapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);
        Ui.edgeToEdge(this, findViewById(R.id.root));

        engine = LlamaEngine.get();
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

        toolbar = findViewById(R.id.cToolbar);
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
        tvHintTitle = findViewById(R.id.tvHintTitle);
        tvHintDesc = findViewById(R.id.tvHintDesc);
        loadProgress = findViewById(R.id.loadProgress);

        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        adapter = new Adapter();
        chatList.setLayoutManager(lm);
        chatList.setAdapter(adapter);

        btnSend.setOnClickListener(v -> onSendClicked());

        loadModel();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
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

        engine.load(this, model, nCtx, (ok, msg) -> {
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

        if (messages.isEmpty()) {
            messages.add(new ChatMessage(ChatMessage.ASSISTANT,
                    getString(R.string.chat_greeting, model.displayName)));
            stats.add("");
            adapter.notifyDataSetChanged();
        }
    }

    private void showHint(String title, String desc) {
        chatHint.setVisibility(View.VISIBLE);
        tvHintTitle.setText(title);
        tvHintDesc.setText(desc);
    }

    /* -------------------------------------------------------------- 发送流程 */

    private void onSendClicked() {
        if (generating) {
            engine.cancel();
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
        messages.add(new ChatMessage(ChatMessage.ASSISTANT, ""));
        stats.add("");
        final int aiIndex = messages.size() - 1;

        adapter.notifyDataSetChanged();
        scrollToBottom();
        setGenerating(true);

        // 请求消息 = 系统提示 + 历史（不含最后的空占位）
        List<ChatMessage> req = new ArrayList<>();
        if (!TextUtils.isEmpty(systemPrompt.trim())) {
            req.add(new ChatMessage(ChatMessage.SYSTEM, systemPrompt.trim()));
        }
        for (int i = 0; i < messages.size() - 1; i++) {
            ChatMessage m = messages.get(i);
            if (!TextUtils.isEmpty(m.content)) req.add(m);
        }

        engine.generate(req, params, new LlamaEngine.StreamListener() {
            @Override
            public void onToken(String piece) {
                if (!alive) return;
                ChatMessage m = messages.get(aiIndex);
                m.content = m.content + piece;
                adapter.notifyItemChanged(aiIndex);
                scrollToBottom();
            }

            @Override
            public void onDone(String fullText, int tokens, long elapsedMs) {
                if (!alive) return;
                ChatMessage m = messages.get(aiIndex);
                if (!TextUtils.isEmpty(fullText)) m.content = fullText;
                if (TextUtils.isEmpty(m.content)) m.content = getString(R.string.chat_stopped);
                stats.set(aiIndex, getString(R.string.chat_stat, tokens, fmtMs(elapsedMs)));
                adapter.notifyItemChanged(aiIndex);
                setGenerating(false);
                scrollToBottom();
            }

            @Override
            public void onError(String message) {
                if (!alive) return;
                ChatMessage m = messages.get(aiIndex);
                m.content = getString(R.string.chat_load_failed, message);
                adapter.notifyItemChanged(aiIndex);
                setGenerating(false);
            }
        });
    }

    private void setGenerating(boolean b) {
        generating = b;
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

    private static String fmtMs(long ms) {
        if (ms < 1000) return ms + " ms";
        return String.format(Locale.CHINA, "%.1f s", ms / 1000.0);
    }

    /* ------------------------------------------------------------------ 菜单 */

    private boolean onMenu(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_new_chat) {
            newChat();
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
        messages.clear();
        stats.clear();
        adapter.notifyDataSetChanged();
        chatHint.setVisibility(View.GONE);
        if (ready) {
            engine.reset();
            messages.add(new ChatMessage(ChatMessage.ASSISTANT,
                    getString(R.string.chat_greeting, model.displayName)));
            stats.add("");
            adapter.notifyDataSetChanged();
        }
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
    protected void onDestroy() {
        alive = false;
        if (generating) engine.cancel();
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