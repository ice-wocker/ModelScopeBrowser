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
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.mscope.browser.Format;
import com.mscope.browser.MainActivity;
import com.mscope.browser.R;
import com.mscope.browser.Ui;
import com.mscope.browser.llama.ChatActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 本地模型库：查看已下载的 GGUF、占用空间，一键进入离线对话或删除。 */
public class LocalModelsActivity extends AppCompatActivity implements DownloadCenter.Listener {

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
            ui.post(() -> {
                if (!alive) return;
                apply(list, total, free);
            });
        });
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
        int idx = indexOf(model.localPath);
        if (idx >= 0) adapter.notifyItemChanged(idx);
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