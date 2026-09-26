package com.mscope.browser;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.noties.markwon.Markwon;

/** 模型详情页：基本信息 + 模型文件列表 + Markdown 简介。 */
public class DetailActivity extends AppCompatActivity {

    public static final String EXTRA_OWNER = "owner";
    public static final String EXTRA_NAME = "name";

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
    private android.widget.ProgressBar progress;
    private RecyclerView fileList;

    private final List<ModelFile> files = new ArrayList<>();
    private FileAdapter fileAdapter;
    private Markwon markwon;

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

        loadDetail();
        loadFiles();
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
            h.download.setOnClickListener(v -> {
                try {
                    openExternal(ModelApi.downloadUrl(owner, name, f.path));
                } catch (Exception e) {
                    Toast.makeText(DetailActivity.this, R.string.no_browser, Toast.LENGTH_SHORT).show();
                }
            });
        }

        @Override
        public int getItemCount() {
            return files.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final TextView name;
            final TextView meta;
            final MaterialButton download;

            VH(@NonNull View itemView) {
                super(itemView);
                name = itemView.findViewById(R.id.fName);
                meta = itemView.findViewById(R.id.fMeta);
                download = itemView.findViewById(R.id.fDownload);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}