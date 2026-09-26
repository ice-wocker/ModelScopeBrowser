package com.mscope.browser;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 模型详情页：调用官方详情接口展示完整信息。 */
public class DetailActivity extends Activity {

    public static final String EXTRA_OWNER = "owner";
    public static final String EXTRA_NAME = "name";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private String owner;
    private String name;

    private TextView tvTitle, tvOwner, tvMeta, tvDesc, tvTask, tvTime;
    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);

        owner = getIntent().getStringExtra(EXTRA_OWNER);
        name = getIntent().getStringExtra(EXTRA_NAME);
        if (owner == null) owner = "";
        if (name == null) name = "";

        tvTitle = findViewById(R.id.dTitle);
        tvOwner = findViewById(R.id.dOwner);
        tvMeta = findViewById(R.id.dMeta);
        tvTask = findViewById(R.id.dTask);
        tvTime = findViewById(R.id.dTime);
        tvDesc = findViewById(R.id.dDesc);
        progress = findViewById(R.id.dProgress);

        tvTitle.setText(name);
        tvOwner.setText(owner + "/" + name);

        Button btnOpen = findViewById(R.id.dBtnOpen);
        Button btnCopy = findViewById(R.id.dBtnCopy);

        final String url = ModelApi.BASE + "/models/" + owner + "/" + name;

        btnOpen.setOnClickListener(v -> {
            Intent it = new Intent(DetailActivity.this, WebActivity.class);
            it.putExtra(WebActivity.EXTRA_URL, url);
            startActivity(it);
        });

        btnCopy.setOnClickListener(v -> {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("modelscope", url));
            Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
        });

        Button btnBrowser = findViewById(R.id.dBtnBrowser);
        btnBrowser.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception e) {
                Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show();
            }
        });

        load();
    }

    private void load() {
        progress.setVisibility(View.VISIBLE);
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

    private void render(ModelItem m) {
        progress.setVisibility(View.GONE);
        tvTitle.setText(m.displayName());
        tvOwner.setText(m.fullName());

        StringBuilder meta = new StringBuilder();
        meta.append("下载 ").append(MainActivity.formatCount(m.downloads));
        meta.append("   ★ ").append(MainActivity.formatCount(m.stars));
        if (!m.license.isEmpty()) meta.append("   许可 ").append(m.license);
        tvMeta.setText(meta.toString());

        if (!m.task.isEmpty()) {
            tvTask.setVisibility(View.VISIBLE);
            tvTask.setText("任务类型：" + m.task);
        } else {
            tvTask.setVisibility(View.GONE);
        }

        tvTime.setText("创建 " + m.createdText() + "    更新 " + m.updatedText());

        String desc = m.description == null ? "" : m.description.trim();
        if (desc.isEmpty() && !m.tags.isEmpty()) desc = m.tags;
        tvDesc.setText(desc.isEmpty() ? getString(R.string.no_desc) : desc);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}