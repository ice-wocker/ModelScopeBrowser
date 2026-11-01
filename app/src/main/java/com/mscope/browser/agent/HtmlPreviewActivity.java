package com.mscope.browser.agent;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.google.android.material.appbar.MaterialToolbar;
import com.mscope.browser.R;
import com.mscope.browser.Ui;

import java.io.File;

/** 预览工作区里生成的 HTML：本地 file:// 加载，支持脚本、样式与相对资源。 */
public class HtmlPreviewActivity extends AppCompatActivity {

    private static final String EXTRA_PATH = "ws_path";

    /** 打开工作区内某个 HTML 文件的预览页。 */
    public static void start(android.content.Context ctx, String relPath) {
        Intent it = new Intent(ctx, HtmlPreviewActivity.class);
        it.putExtra(EXTRA_PATH, relPath);
        ctx.startActivity(it);
    }

    private static final String UA_DESKTOP =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Safari/537.36";

    private WebView webView;
    private ProgressBar progress;
    private File file;
    private boolean desktop;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_html_preview);
        Ui.edgeToEdge(this, findViewById(R.id.pRoot));

        webView = findViewById(R.id.pWebView);
        progress = findViewById(R.id.pProgress);
        // 桌面版预览需要重置缩放，这里先按移动端初始化
        webView.setInitialScale(0);

        Workspace ws = new Workspace(this);
        String rel = getIntent().getStringExtra(EXTRA_PATH);
        try {
            file = ws.resolve(rel == null ? "" : rel);
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (!file.exists() || file.isDirectory()) {
            Toast.makeText(this, R.string.workspace_view + "：" + rel, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        MaterialToolbar toolbar = findViewById(R.id.pToolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setSubtitle(ws.relOf(file));
        toolbar.setOnMenuItemClickListener(this::onMenu);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        // 本地预览需要 file:// 访问能力（页面里的相对资源都在 file:// 下）。
        //
        // 但**不能**开 allowUniversalAccessFromFileURLs：那等于允许 file:// 页面发起跨域请求，
        // 而这里渲染的正是模型生成的 HTML —— 一段 fetch('file:///data/data/.../chats.json')
        // 就能把对话历史 / 索引文件读出来，再 POST 到任意地址（应用本身有 INTERNET 权限）。
        // 关掉它之后，file:// 页面无法用 XHR/fetch 读取本机文件，也无法跨源读取外网内容；
        // 相对路径的 <img>/<script>/<link> 仍然正常，网页预览的核心需求不受影响。
        s.setAllowContentAccess(false);            // 预览页不需要 content:// 资源
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? android.view.View.GONE : android.view.View.VISIBLE);
            }
        });
        webView.setWebViewClient(new WebViewClient());

        load();
    }

    private void load() {
        if (webView != null && file != null) {
            webView.loadUrl("file://" + file.getAbsolutePath());
        }
    }

    private boolean onMenu(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_reload) {
            load();
            return true;
        }
        if (id == R.id.action_desktop) {
            desktop = !desktop;
            WebSettings s = webView.getSettings();
            s.setUserAgentString(desktop ? UA_DESKTOP : null);
            s.setUseWideViewPort(desktop);
            s.setLoadWithOverviewMode(!desktop);
            webView.setInitialScale(desktop ? 100 : 0);
            load();
            Toast.makeText(this, R.string.preview_desktop, Toast.LENGTH_SHORT).show();
            return true;
        }
        if (id == R.id.action_open_browser) {
            try {
                Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", file);
                Intent it = new Intent(Intent.ACTION_VIEW);
                it.setDataAndType(uri, "text/html");
                it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(it);
            } catch (Exception e) {
                Toast.makeText(this, getString(R.string.workspace_share_failed,
                        String.valueOf(e.getMessage())), Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        return false;
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.loadUrl("about:blank");
            webView.destroy();
        }
        super.onDestroy();
    }
}