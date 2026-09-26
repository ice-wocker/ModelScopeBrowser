package com.mscope.browser;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

/** 网页兜底模式：直接加载魔搭官网，保证任何情况下都能浏览全部模型。 */
public class WebActivity extends AppCompatActivity {

    public static final String EXTRA_URL = "url";

    private WebView webView;
    private ProgressBar progress;
    private String startUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_web);
        Ui.edgeToEdge(this, findViewById(R.id.root));

        webView = findViewById(R.id.webView);
        progress = findViewById(R.id.wProgress);

        startUrl = getIntent().getStringExtra(EXTRA_URL);
        if (startUrl == null || startUrl.isEmpty()) startUrl = ModelApi.BASE + "/models";

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
        });
        webView.setWebViewClient(new WebViewClient());

        MaterialToolbar toolbar = findViewById(R.id.wToolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        MaterialButton external = findViewById(R.id.wExternal);
        external.setOnClickListener(v -> {
            String url = webView.getUrl() == null ? startUrl : webView.getUrl();
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception e) {
                Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show();
            }
        });

        // targetSdk 33+ 推荐用 OnBackPressedCallback 处理返回
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        webView.loadUrl(startUrl);
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