package com.mscope.browser;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 模型列表主界面：分页浏览 + 关键字搜索。 */
public class MainActivity extends Activity {

    private static final int PAGE_SIZE = 20;

    private ListView listView;
    private EditText etSearch;
    private ProgressBar progress;
    private TextView tvStatus;

    private final List<ModelItem> items = new ArrayList<>();
    private ModelAdapter adapter;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private int page = 1;
    private boolean loading = false;
    private boolean hasMore = true;
    private String keyword = "";
    private int total = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        listView = findViewById(R.id.list);
        etSearch = findViewById(R.id.etSearch);
        progress = findViewById(R.id.progress);
        tvStatus = findViewById(R.id.tvStatus);

        adapter = new ModelAdapter();
        listView.setAdapter(adapter);

        listView.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
            }

            @Override
            public void onScroll(AbsListView view, int firstVisible, int visibleCount, int totalCount) {
                if (hasMore && !loading && totalCount > 0 && firstVisible + visibleCount >= totalCount - 2) {
                    page++;
                    load(false);
                }
            }
        });

        listView.setOnItemClickListener((parent, view, position, id) -> {
            ModelItem m = items.get(position);
            Intent it = new Intent(MainActivity.this, DetailActivity.class);
            it.putExtra(DetailActivity.EXTRA_OWNER, m.owner);
            it.putExtra(DetailActivity.EXTRA_NAME, m.name);
            startActivity(it);
        });

        Button btnSearch = findViewById(R.id.btnSearch);
        btnSearch.setOnClickListener(v -> doSearch());

        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                doSearch();
                return true;
            }
            return false;
        });

        Button btnWeb = findViewById(R.id.btnWeb);
        btnWeb.setOnClickListener(v -> {
            Intent it = new Intent(MainActivity.this, WebActivity.class);
            it.putExtra(WebActivity.EXTRA_URL, ModelApi.BASE + "/models");
            startActivity(it);
        });

        load(true);
    }

    private void doSearch() {
        keyword = etSearch.getText().toString().trim();
        load(true);
    }

    private void load(boolean reset) {
        if (loading) return;
        if (reset) {
            page = 1;
            hasMore = true;
            total = -1;
        }
        if (!hasMore) return;

        loading = true;
        progress.setVisibility(View.VISIBLE);
        if (reset) {
            tvStatus.setText(getString(R.string.loading));
        }

        final int reqPage = page;
        final String kw = keyword;

        executor.execute(() -> {
            final ModelApi.Page result = ModelApi.listModels(reqPage, PAGE_SIZE, kw);
            ui.post(() -> applyResult(result, reset));
        });
    }

    private void applyResult(ModelApi.Page result, boolean reset) {
        loading = false;
        progress.setVisibility(View.GONE);

        if (result.items.isEmpty()) {
            if (reset) {
                items.clear();
                adapter.notifyDataSetChanged();
            }
            if (result.error != null && !result.error.isEmpty()) {
                tvStatus.setText(getString(R.string.load_failed, result.error));
            } else {
                tvStatus.setText(items.isEmpty() ? getString(R.string.empty) : getString(R.string.no_more));
            }
            hasMore = false;
            return;
        }

        if (reset) items.clear();
        items.addAll(result.items);
        adapter.notifyDataSetChanged();

        total = result.total;
        hasMore = result.items.size() >= PAGE_SIZE || (total > 0 && items.size() < total);

        if (total > 0) {
            tvStatus.setText(getString(R.string.loaded_status, items.size(), total, keyword));
        } else {
            tvStatus.setText(getString(R.string.loaded_status_unknown, items.size(), keyword));
        }
    }

    /* ---------------------------------------------------------------- 适配器 */

    private class ModelAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = getLayoutInflater().inflate(R.layout.item_model, parent, false);
            }
            ModelItem m = items.get(position);

            TextView title = v.findViewById(R.id.tvTitle);
            TextView owner = v.findViewById(R.id.tvOwner);
            TextView meta = v.findViewById(R.id.tvMeta);
            TextView desc = v.findViewById(R.id.tvDesc);

            title.setText(m.displayName());
            owner.setText(m.fullName());

            StringBuilder sb = new StringBuilder();
            if (!m.task.isEmpty()) sb.append(m.task);
            sb.append("  ↓ ").append(formatCount(m.downloads));
            if (m.stars > 0) sb.append("  ★ ").append(formatCount(m.stars));
            if (!m.license.isEmpty()) sb.append("  · ").append(m.license);
            meta.setText(sb.toString());

            String d = m.tags.isEmpty() ? m.description : (m.tags + "  " + m.description);
            d = d.replace('\n', ' ').trim();
            if (d.isEmpty()) {
                desc.setVisibility(View.GONE);
            } else {
                desc.setVisibility(View.VISIBLE);
                desc.setText(d);
            }
            return v;
        }
    }

    static String formatCount(long n) {
        if (n >= 100000000L) return String.format(java.util.Locale.CHINA, "%.1f亿", n / 100000000.0);
        if (n >= 10000L) return String.format(java.util.Locale.CHINA, "%.1f万", n / 10000.0);
        return String.valueOf(n);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}