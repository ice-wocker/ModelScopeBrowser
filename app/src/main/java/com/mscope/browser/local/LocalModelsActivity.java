package com.mscope.browser.local;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.mscope.browser.Format;
import com.mscope.browser.MainActivity;
import com.mscope.browser.R;
import com.mscope.browser.Ui;
import com.mscope.browser.core.StorageGuard;
import com.mscope.browser.llama.ChatActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.mscope.browser.i18n.BaseActivity;
import com.mscope.browser.i18n.Lang;

/** 本地模型库：查看已下载的 GGUF、占用空间，一键进入离线对话或删除。 */
public class LocalModelsActivity extends BaseActivity implements DownloadCenter.Listener {

    private LocalModelStore store;
    private DownloadCenter downloads;

    private final List<LocalModel> data = new ArrayList<>();
    private Adapter adapter;
    private RecyclerView listView;
    private View emptyBox;
    private TextView tvUsage;
    private TextView tvFree;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean alive = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_local_models);
        Ui.edgeToEdge(this, findViewById(R.id.root));

        store = new LocalModelStore(this);
        downloads = DownloadCenter.get(this);

        MaterialToolbar toolbar = findViewById(R.id.lToolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        listView = findViewById(R.id.localList);
        emptyBox = findViewById(R.id.emptyBox);
        tvUsage = findViewById(R.id.tvUsage);
        tvFree = findViewById(R.id.tvFree);

        adapter = new Adapter();
        listView.setLayoutManager(new LinearLayoutManager(this));
        listView.setAdapter(adapter);

        findViewById(R.id.btnFind).setOnClickListener(v -> {
            Intent it = new Intent(this, MainActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(it);
            finish();
        });

        downloads.addListener(this);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        alive = false;
        ui.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        downloads.removeListener(this);
        super.onDestroy();
    }

    /* ----------------------------------------------------------------- 数据 */

    /** 读索引/统计空间都是磁盘 IO，放到后台线程，避免模型多时卡主线程。 */
    private void refresh() {
        executor.execute(() -> {
            final List<LocalModel> list = store.list();
            long sum = 0;
            for (LocalModel m : list) sum += m.size;
            final long total = sum;
            final long free = store.freeSpace();
            final String audit = auditLibrary(list);
            ui.post(() -> {
                if (!alive) return;
                apply(list, total, free);
                // 体检结论只作为一次提示，不打扰正常浏览；-1 表示本次已经提示过
                if (!audit.isEmpty() && !audit.equals(lastAudit)) {
                    lastAudit = audit;
                    Toast.makeText(this, audit, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    /** 上次提示过的体检结论，避免每次进页面都弹同一个 Toast。 */
    private String lastAudit = "";

    /**
     * 交叉比对索引与磁盘：只剩索引没文件的「幽灵条目」、有文件没索引的「孤儿」、
     * 以及 0 字节的空壳。这些都是历史版本并发写留下的，静默存在只会让用户
     * 点开报「文件不存在」或白占空间。
     */
    private String auditLibrary(List<LocalModel> list) {
        try {
            List<StorageGuard.Entry> entries = new ArrayList<>();
            for (LocalModel m : list) {
                if (m.localPath != null && !m.localPath.isEmpty()) {
                    entries.add(new StorageGuard.Entry(m.localPath, m.size));
                }
            }
            List<StorageGuard.Entry> existing = new ArrayList<>();
            File dir = store.dir();
            File[] files = dir == null ? null : dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    String name = f.getName();
                    if (!f.isFile() || !name.toLowerCase(java.util.Locale.ROOT).endsWith(".gguf")) continue;
                    existing.add(new StorageGuard.Entry(f.getAbsolutePath(), f.length()));
                }
            }
            return StorageGuard.summarize(StorageGuard.audit(entries, existing), Lang.zh());
        } catch (Throwable t) {
            return "";
        }
    }

    private void apply(List<LocalModel> list, long totalSize, long free) {
        data.clear();
        data.addAll(list);
        adapter.notifyDataSetChanged();

        tvUsage.setText(getString(R.string.local_usage, data.size(), Format.size(totalSize)));
        tvFree.setText(free >= 0 ? getString(R.string.local_free, Format.size(free)) : "");
        emptyBox.setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);
        listView.setVisibility(data.isEmpty() ? View.GONE : View.VISIBLE);

        // 有正在下载的任务时，同步显示进度
        for (LocalModel m : data) {
            DownloadCenter.Task t = downloads.task(m.localPath);
            if (t != null) adapter.notifyItemChanged(data.indexOf(m));
        }
    }

    private void confirmDelete(LocalModel m) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.local_delete)
                .setMessage(getString(R.string.local_delete_confirm, m.displayName(), m.sizeText()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.local_delete, (d, w) -> {
                    downloads.cancel(m.localPath);
                    store.remove(m);
                    Toast.makeText(this, R.string.local_deleted, Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .show();
    }

    /* ------------------------------------------------------- 下载进度回调 */

    @Override
    public void onProgress(LocalModel model, long done, long total) {
        if (!alive) return;
        // 只刷有变化的行：下载中每秒若干次回调，整表 notifyDataSetChanged 会让
        // 其它行的进度条与按钮反复重绑，长列表上肉眼可见地卡。
        // 另外，正在下载的文件此时还没进索引（完成后才 add），data 里根本找不到它——
        // 用局部刷新的话界面会一直停在「未下载」，所以找不到时退回整表刷新。
        int idx = indexOf(model.localPath);
        if (idx >= 0) adapter.notifyItemChanged(idx);
        else adapter.notifyDataSetChanged();
    }

    @Override
    public void onFinished(LocalModel model) {
        if (!alive) return;
        refresh();
    }

    @Override
    public void onFailed(LocalModel model, String error) {
        if (!alive) return;
        Toast.makeText(this, getString(R.string.dl_failed, error), Toast.LENGTH_LONG).show();
        refresh();
    }

    @Override
    public void onCancelled(LocalModel model) {
        if (!alive) return;
        // 取消不再删除 .part（下次点下载可以接着下），所以这里只需要把行状态刷回可下载
        Toast.makeText(this, R.string.dl_cancelled, Toast.LENGTH_SHORT).show();
        refresh();
    }

    private int indexOf(String localPath) {
        for (int i = 0; i < data.size(); i++) {
            if (data.get(i).localPath.equals(localPath)) return i;
        }
        return -1;
    }

    /* ------------------------------------------------------------- 适配器 */

    private class Adapter extends RecyclerView.Adapter<Adapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_local_model, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            LocalModel m = data.get(position);

            h.name.setText(m.displayName());
            h.repo.setText(m.repoFullName());

            String q = m.quant();
            if (q.isEmpty()) {
                h.quant.setVisibility(View.GONE);
            } else {
                h.quant.setVisibility(View.VISIBLE);
                h.quant.setText(q);
            }

            DownloadCenter.Task t = downloads.task(m.localPath);
            if (t != null) {
                int pct = t.percent();
                h.size.setText(pct >= 0
                        ? getString(R.string.local_downloading_pct, pct)
                        : getString(R.string.local_downloading_size, Format.size(t.done)));
                h.progress.setVisibility(View.VISIBLE);
                if (pct >= 0) h.progress.setProgress(pct, true);
                h.chat.setEnabled(false);
                h.state.setVisibility(View.VISIBLE);
                h.state.setText(R.string.local_downloading);
            } else {
                h.size.setText(m.sizeText());
                h.progress.setVisibility(View.GONE);
                h.chat.setEnabled(true);
                h.state.setVisibility(View.GONE);
            }

            h.chat.setOnClickListener(v -> ChatActivity.start(LocalModelsActivity.this, m));
            h.delete.setOnClickListener(v -> confirmDelete(m));
            h.itemView.setOnClickListener(v -> ChatActivity.start(LocalModelsActivity.this, m));
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final TextView name;
            final TextView repo;
            final TextView quant;
            final TextView size;
            final TextView state;
            final ProgressBar progress;
            final MaterialButton chat;
            final MaterialButton delete;

            VH(@NonNull View itemView) {
                super(itemView);
                name = itemView.findViewById(R.id.tvName);
                repo = itemView.findViewById(R.id.tvRepo);
                quant = itemView.findViewById(R.id.tvQuant);
                size = itemView.findViewById(R.id.tvSize);
                state = itemView.findViewById(R.id.tvState);
                progress = itemView.findViewById(R.id.progress);
                chat = itemView.findViewById(R.id.btnChat);
                delete = itemView.findViewById(R.id.btnDelete);
            }
        }
    }
}