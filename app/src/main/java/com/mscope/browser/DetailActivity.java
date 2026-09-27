package com.mscope.browser;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MenuItem;
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
import com.mscope.browser.llama.ChatActivity;
import com.mscope.browser.local.DownloadCenter;
import com.mscope.browser.local.LocalModel;
import com.mscope.browser.local.LocalModelStore;
import com.mscope.browser.local.LocalModelsActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.noties.markwon.Markwon;

/** 模型详情页：基本信息 + 模型文件（.gguf 可应用内下载并直接对话）+ Markdown 简介。 */
public class DetailActivity extends AppCompatActivity implements DownloadCenter.Listener {

    public static final String EXTRA_OWNER = "owner";
    public static final String EXTRA_NAME = "name";

    /** 超过该体积时下载前提示一句。 */
    private static final long BIG_FILE = 300L * 1024 * 1024;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());

    private String owner;
    private String name;

    private TextView tvTitle;
    private TextView tvOwner;
    private TextView tvMeta;
    private TextView tvDesc;
    private TextView tvTask;
    private TextView tvTime;
    private TextView tvFilesStatus;
    private ProgressBar progress;
    private RecyclerView fileList;

    private final List<ModelFile> files = new ArrayList<>();
    private FileAdapter fileAdapter;
    private Markwon markwon;

    private DownloadCenter downloads;
    private LocalModelStore store;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        Ui.edgeToEdge(this, findViewById(R.id.root));

        owner = getIntent().getStringExtra(EXTRA_OWNER);
        name = getIntent().getStringExtra(EXTRA_NAME);
        if (owner == null) owner = "";
        if (name == null) name = "";

        markwon = Markwon.create(this);
        downloads = DownloadCenter.get(this);
        store = new LocalModelStore(this);

        tvTitle = findViewById(R.id.dTitle);
        tvOwner = findViewById(R.id.dOwner);
        tvMeta = findViewById(R.id.dMeta);
        tvTask = findViewById(R.id.dTask);
        tvTime = findViewById(R.id.dTime);
        tvDesc = findViewById(R.id.dDesc);
        tvFilesStatus = findViewById(R.id.dFilesStatus);
        progress = findViewById(R.id.dProgress);
        fileList = findViewById(R.id.dFiles);

        MaterialToolbar toolbar = findViewById(R.id.dToolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setOnMenuItemClickListener(this::onMenu);

        tvTitle.setText(name);
        tvOwner.setText(owner + "/" + name);

        final String url = ModelApi.BASE + "/models/" + owner + "/" + name;

        MaterialButton btnOpen = findViewById(R.id.dBtnOpen);
        MaterialButton btnCopy = findViewById(R.id.dBtnCopy);
        MaterialButton btnBrowser = findViewById(R.id.dBtnBrowser);

        btnOpen.setOnClickListener(v -> {
            Intent it = new Intent(this, WebActivity.class);
            it.putExtra(WebActivity.EXTRA_URL, url);
            startActivity(it);
        });
        btnCopy.setOnClickListener(v -> {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("modelscope", url));
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
        });
        btnBrowser.setOnClickListener(v -> openExternal(url));

        fileAdapter = new FileAdapter();
        fileList.setLayoutManager(new LinearLayoutManager(this));
        fileList.setAdapter(fileAdapter);

        downloads.addListener(this);
        loadDetail();
        loadFiles();
    }

    private boolean onMenu(MenuItem item) {
        if (item.getItemId() == R.id.action_local) {
            startActivity(new Intent(this, LocalModelsActivity.class));
            return true;
        }
        return false;
    }

    private void openExternal(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show();
        }
    }

    private void loadDetail() {
        executor.execute(() -> {
            try {
                final ModelItem m = ModelApi.getModelDetail(owner, name);
                ui.post(() -> render(m));
            } catch (final Exception e) {
                ui.post(() -> {
                    progress.setVisibility(View.GONE);
                    tvDesc.setText(getString(R.string.load_failed, String.valueOf(e.getMessage())));
                });
            }
        });
    }

    private void loadFiles() {
        executor.execute(() -> {
            try {
                final List<ModelFile> list = ModelApi.listFiles(owner, name);
                ui.post(() -> {
                    files.clear();
                    files.addAll(list);
                    fileAdapter.notifyDataSetChanged();
                    tvFilesStatus.setText(list.isEmpty()
                            ? getString(R.string.files_empty)
                            : getString(R.string.files_title) + " · " + list.size());
                });
            } catch (final Exception e) {
                ui.post(() -> tvFilesStatus.setText(getString(R.string.files_empty)));
            }
        });
    }

    private void render(ModelItem m) {
        progress.setVisibility(View.GONE);
        tvTitle.setText(m.displayName());
        tvOwner.setText(m.fullName());

        tvMeta.setText("下载 " + ModelAdapter.formatCount(m.downloads)
                + "   ★ " + ModelAdapter.formatCount(m.stars)
                + (m.license.isEmpty() ? "" : "   许可 " + m.license));

        if (m.task.isEmpty()) {
            tvTask.setVisibility(View.GONE);
        } else {
            tvTask.setVisibility(View.VISIBLE);
            tvTask.setText("任务类型：" + m.task);
        }

        tvTime.setText("创建 " + m.createdText() + "    更新 " + m.updatedText());

        String desc = m.description == null ? "" : m.description.trim();
        if (desc.isEmpty() && !m.tags.isEmpty()) desc = m.tags;
        if (desc.isEmpty()) {
            tvDesc.setText(R.string.no_desc);
        } else {
            // 简介在魔搭上是 Markdown，这里渲染成富文本
            try {
                markwon.setMarkdown(tvDesc, desc);
            } catch (Exception e) {
                tvDesc.setText(desc);
            }
        }
    }

    /* ------------------------------------------------------- GGUF 本地下载 */

    private static boolean isGguf(ModelFile f) {
        return f.displayName().toLowerCase(Locale.ROOT).endsWith(".gguf");
    }

    /** 本地文件名带仓库前缀，避免不同仓库的同名文件互相覆盖。 */
    private LocalModel localModelOf(ModelFile f) {
        LocalModel m = new LocalModel();
        m.repoOwner = owner;
        m.repoName = name;
        m.filePath = f.path;
        m.displayName = f.displayName();
        m.size = f.size;
        String fileName = (owner + "_" + name + "__" + f.displayName())
                .replace('/', '_').replace('\\', '_');
        m.localPath = store.fileOf(fileName).getAbsolutePath();
        return m;
    }

    private void startDownload(ModelFile f) {
        final LocalModel m = localModelOf(f);
        String url;
        try {
            url = ModelApi.downloadUrl(owner, name, f.path);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.dl_failed, String.valueOf(e.getMessage())),
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (downloads.start(m, url)) {
            Toast.makeText(this, getString(R.string.dl_started, m.displayName), Toast.LENGTH_SHORT).show();
            notifyFile(m);
        }
    }

    private void notifyFile(LocalModel m) {
        int idx = indexOfLocal(m.localPath);
        if (idx >= 0) fileAdapter.notifyItemChanged(idx);
    }

    private int indexOfLocal(String localPath) {
        for (int i = 0; i < files.size(); i++) {
            if (localModelOf(files.get(i)).localPath.equals(localPath)) return i;
        }
        return -1;
    }

    @Override
    public void onProgress(LocalModel model, long done, long total) {
        notifyFile(model);
    }

    @Override
    public void onFinished(LocalModel model) {
        notifyFile(model);
        if (!model.repoOwner.equals(owner) || !model.repoName.equals(name)) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dl_done_title)
                .setMessage(getString(R.string.dl_done_msg, model.displayName))
                .setNegativeButton(R.string.dl_done_later, null)
                .setPositiveButton(R.string.dl_done_chat,
                        (d, w) -> ChatActivity.start(this, model))
                .show();
    }

    @Override
    public void onFailed(LocalModel model, String error) {
        notifyFile(model);
        Toast.makeText(this, getString(R.string.dl_failed, error), Toast.LENGTH_LONG).show();
    }

    @Override
    public void onCancelled(LocalModel model) {
        notifyFile(model);
        Toast.makeText(this, R.string.dl_cancelled, Toast.LENGTH_SHORT).show();
    }

    /* ------------------------------------------------------------- 文件适配器 */

    private class FileAdapter extends RecyclerView.Adapter<FileAdapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_file, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            ModelFile f = files.get(position);
            h.name.setText(f.dir() + f.displayName());
            h.meta.setText(f.sizeText() + (f.lfs ? " · LFS" : ""));

            if (!isGguf(f)) {
                h.progress.setVisibility(View.GONE);
                h.download.setText(R.string.file_open);
                h.download.setOnClickListener(v -> {
                    try {
                        openExternal(ModelApi.downloadUrl(owner, name, f.path));
                    } catch (Exception e) {
                        Toast.makeText(DetailActivity.this, R.string.no_browser, Toast.LENGTH_SHORT).show();
                    }
                });
                return;
            }

            final LocalModel m = localModelOf(f);
            final boolean downloaded = new File(m.localPath).exists();
            final DownloadCenter.Task task = downloads.task(m.localPath);

            if (downloaded) {
                h.progress.setVisibility(View.GONE);
                h.download.setText(R.string.local_chat);
                h.download.setOnClickListener(v -> ChatActivity.start(DetailActivity.this, m));
            } else if (task != null) {
                int pct = task.percent();
                h.download.setText(pct >= 0 ? pct + "%" : getString(R.string.local_downloading));
                h.progress.setVisibility(View.VISIBLE);
                if (pct >= 0) h.progress.setProgress(pct, true);
                h.download.setOnClickListener(v -> {
                    downloads.cancel(m.localPath);
                    Toast.makeText(DetailActivity.this, R.string.dl_cancelled, Toast.LENGTH_SHORT).show();
                    notifyFile(m);
                });
            } else {
                h.progress.setVisibility(View.GONE);
                h.download.setText(R.string.file_download);
                h.download.setOnClickListener(v -> confirmDownload(f, m));
            }
        }

        @Override
        public int getItemCount() {
            return files.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final TextView name;
            final TextView meta;
            final ProgressBar progress;
            final MaterialButton download;

            VH(@NonNull View itemView) {
                super(itemView);
                name = itemView.findViewById(R.id.fName);
                meta = itemView.findViewById(R.id.fMeta);
                progress = itemView.findViewById(R.id.fProgress);
                download = itemView.findViewById(R.id.fDownload);
            }
        }
    }

    private void confirmDownload(ModelFile f, LocalModel m) {
        if (f.size > BIG_FILE) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.file_download)
                    .setMessage(getString(R.string.dl_need_wifi, f.sizeText()))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.file_download, (d, w) -> startDownload(f))
                    .show();
        } else {
            startDownload(f);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        downloads.removeListener(this);
        executor.shutdownNow();
    }
}