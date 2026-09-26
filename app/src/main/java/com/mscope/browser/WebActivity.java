package com.mscope.browser;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Toast;

/** 网页兜底模式：直接加载魔搭官网，保证任何情况下都能浏览全部模型。 */
public class WebActivity extends Activity {

    public static final String EXTRA_URL = "url";

    private WebView webView;
    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_web);

        webView = findViewById(R.id.webView);
        progress = findViewById(R.id.wProgress);

        String url = getIntent().getStringExtra(EXTRA_URL);
        if (url == null || url.isEmpty()) url = ModelApi.BASE + "/models";

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);

        final String finalUrl = url;
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }
        });
        webView.setWebViewClient(new WebViewClient());

        Button btnBack = findViewById(R.id.wBack);
        Button btnRefresh = findViewById(R.id.wRefresh);
        Button btnExternal = findViewById(R.id.wExternal);

        btnBack.setOnClickListener(v -> {
            if (webView.canGoBack()) webView.goBack();
            else finish();
        });
        btnRefresh.setOnClickListener(v -> webView.reload());
        btnExternal.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(webView.getUrl() == null ? finalUrl : webView.getUrl())));
            } catch (Exception e) {
                Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show();
            }
        });

        webView.loadUrl(url);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
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