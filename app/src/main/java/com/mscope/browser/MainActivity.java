package com.mscope.browser;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 模型列表主界面：分页浏览 + 搜索 + 排序 + 按维度筛选。 */
public class MainActivity extends AppCompatActivity {

    private static final int PAGE_SIZE = 20;

    private static final String[] SORT_LABELS = {"综合排序", "最多下载", "最多收藏", "最近更新"};
    private static final String[] SORT_VALUES = {
            ModelApi.SORT_DEFAULT, ModelApi.SORT_DOWNLOADS, ModelApi.SORT_STARS, ModelApi.SORT_UPDATED};

    private RecyclerView listView;
    private SwipeRefreshLayout refresh;
    private EditText etSearch;
    private Chip chipSort;
    private Chip chipFilter;
    private TextView tvStatus;
    private ProgressBar progress;
    private View stateBox;
    private TextView stateTitle;
    private TextView stateDesc;
    private MaterialButton stateBtn;

    private final List<ModelItem> items = new ArrayList<>();
    private final List<ModelApi.Facet> facets = new ArrayList<>();
    private ModelAdapter adapter;

    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final Handler ui = new Handler(Looper.getMainLooper());

    // ---- 分页与请求状态 ----
    private boolean loading = false;
    private boolean hasMore = true;
    private int nextPage = 1;      // 下一次要请求的页码（由加载流程内部维护，避免外部自增导致跳页）
    private int reqSeq = 0;        // 请求序号，用于丢弃过期响应

    private String keyword = "";
    private int sortIndex = 0;
    private String sortBy = ModelApi.SORT_DEFAULT;
    private String order = "desc";
    private String filterCategory = "";
    private String filterValue = "";
    private String filterLabel = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Ui.edgeToEdge(this, findViewById(R.id.root));

        listView = findViewById(R.id.list);
        refresh = findViewById(R.id.refresh);
        etSearch = findViewById(R.id.etSearch);
        chipSort = findViewById(R.id.chipSort);
        chipFilter = findViewById(R.id.chipFilter);
        tvStatus = findViewById(R.id.tvStatus);
        progress = findViewById(R.id.progress);
        stateBox = findViewById(R.id.stateBox);
        stateTitle = findViewById(R.id.stateTitle);
        stateDesc = findViewById(R.id.stateDesc);
        stateBtn = findViewById(R.id.stateBtn);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setOnMenuItemClickListener(this::onMenu);

        adapter = new ModelAdapter(items, this::openDetail);
        listView.setLayoutManager(new LinearLayoutManager(this));
        listView.setAdapter(adapter);
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy <= 0) return;
                RecyclerView.LayoutManager lm = rv.getLayoutManager();
                if (!(lm instanceof LinearLayoutManager)) return;
                int last = ((LinearLayoutManager) lm).findLastVisibleItemPosition();
                if (last >= adapter.getItemCount() - 3) loadMore();
            }
        });

        refresh.setOnRefreshListener(() -> startLoad(1, true));

        findViewById(R.id.btnSearch).setOnClickListener(v -> doSearch());
        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                doSearch();
                return true;
            }
            return false;
        });

        chipSort.setOnClickListener(v -> showSortDialog());
        chipFilter.setOnClickListener(v -> showFilterDialog());
        stateBtn.setOnClickListener(v -> startLoad(items.isEmpty() ? 1 : nextPage, items.isEmpty()));

        startLoad(1, true);
    }

    private boolean onMenu(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_local) {
            startActivity(new Intent(this, com.mscope.browser.local.LocalModelsActivity.class));
            return true;
        }
        if (id == R.id.action_web) {
            Intent it = new Intent(this, WebActivity.class);
            it.putExtra(WebActivity.EXTRA_URL, ModelApi.BASE + "/models");
            startActivity(it);
            return true;
        }
        if (id == R.id.action_refresh) {
            startLoad(1, true);
            return true;
        }
        if (id == R.id.action_about) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.about)
                    .setMessage(getString(R.string.about_msg, versionName()))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return true;
        }
        return false;
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "2.0";
        }
    }

    private void openDetail(ModelItem m) {
        Intent it = new Intent(this, DetailActivity.class);
        it.putExtra(DetailActivity.EXTRA_OWNER, m.owner);
        it.putExtra(DetailActivity.EXTRA_NAME, m.name);
        startActivity(it);
    }

    /* --------------------------------------------------------------- 加载流程 */

    private void doSearch() {
        keyword = etSearch.getText().toString().trim();
        startLoad(1, true);
    }

    private void loadMore() {
        if (loading || !hasMore || items.isEmpty()) return;
        startLoad(nextPage, false);
    }

    /**
     * 唯一的加载入口。页码由内部维护，请求带序号；
     * 快速连续搜索/切换筛选时，旧请求的响应会被丢弃，不会覆盖新结果。
     */
    private void startLoad(int page, boolean reset) {
        final int seq = ++reqSeq;
        final int reqPage = reset ? 1 : page;
        loading = true;

        if (reset) {
            progress.setVisibility(View.VISIBLE);
            tvStatus.setText(R.string.loading);
            if (items.isEmpty()) showState(false, null, null, null);
        }

        final String kw = keyword;
        final String sb = sortBy;
        final String od = order;
        final String fc = filterCategory;
        final String fv = filterValue;

        executor.execute(() -> {
            final ModelApi.Page result = ModelApi.listModels(reqPage, PAGE_SIZE, kw, sb, od, fc, fv);
            ui.post(() -> {
                if (seq != reqSeq) return;   // 过期响应，直接丢弃
                applyResult(result, reqPage, reset);
            });
        });
    }

    private void applyResult(ModelApi.Page r, int page, boolean reset) {
        loading = false;
        progress.setVisibility(View.GONE);
        refresh.setRefreshing(false);

        if (r.fallback) hasMore = false;
        if (!r.facets.isEmpty()) {
            facets.clear();
            facets.addAll(r.facets);
        }

        if (r.items.isEmpty()) {
            if (reset) {
                items.clear();
                adapter.notifyDataSetChanged();
            }
            if (r.error != null && !r.error.isEmpty()) {
                if (items.isEmpty()) {
                    showState(true, getString(R.string.err_title), r.error, getString(R.string.retry));
                } else {
                    hasMore = false;
                    tvStatus.setText(getString(R.string.load_failed, r.error));
                }
            } else if (items.isEmpty()) {
                showState(true, getString(R.string.empty_title), getString(R.string.empty_desc),
                        getString(R.string.retry));
            } else {
                hasMore = false;
                tvStatus.setText(R.string.no_more);
            }
            return;
        }

        showState(false, null, null, null);
        if (reset) items.clear();
        items.addAll(r.items);
        adapter.notifyDataSetChanged();

        nextPage = page + 1;
        if (!r.fallback) {
            hasMore = r.total > 0 ? items.size() < r.total : r.items.size() >= PAGE_SIZE;
        }

        if (!r.sortApplied) Toast.makeText(this, R.string.sort_fallback, Toast.LENGTH_SHORT).show();
        if (!r.filterApplied) Toast.makeText(this, R.string.filter_fallback, Toast.LENGTH_SHORT).show();

        StringBuilder cond = new StringBuilder();
        if (!keyword.isEmpty()) cond.append("（").append(keyword).append("）");
        if (!filterLabel.isEmpty()) cond.append("（").append(filterLabel).append("）");

        tvStatus.setText(r.total > 0
                ? getString(R.string.loaded_status, items.size(), r.total, cond.toString())
                : getString(R.string.loaded_status_unknown, items.size(), cond.toString()));
    }

    private void showState(boolean show, String title, String desc, String btn) {
        stateBox.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        if (title != null) stateTitle.setText(title);
        if (desc != null) stateDesc.setText(desc);
        if (btn != null) stateBtn.setText(btn);
    }

    /* ----------------------------------------------------------- 排序 / 筛选 */

    private void showSortDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sort_title)
                .setSingleChoiceItems(SORT_LABELS, sortIndex, (d, which) -> {
                    sortIndex = which;
                    sortBy = SORT_VALUES[which];
                    order = "desc";
                    chipSort.setText(SORT_LABELS[which]);
                    d.dismiss();
                    startLoad(1, true);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 一级：选择筛选维度（来自接口返回的聚合数据，保证是服务端真正支持的条件）。 */
    private void showFilterDialog() {
        final LinkedHashMap<String, String> groups = new LinkedHashMap<>();
        for (ModelApi.Facet f : facets) {
            if (!groups.containsKey(f.group)) groups.put(f.group, f.groupLabel);
        }
        if (groups.isEmpty()) {
            Toast.makeText(this, R.string.filter_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        final List<String> keys = new ArrayList<>(groups.keySet());
        final String[] labels = new String[keys.size() + 1];
        labels[0] = getString(R.string.filter_all);
        for (int i = 0; i < keys.size(); i++) labels[i + 1] = groups.get(keys.get(i));

        int checked = 0;
        if (!filterCategory.isEmpty()) {
            int idx = keys.indexOf(filterCategory);
            if (idx >= 0) checked = idx + 1;
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.filter_title)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    d.dismiss();
                    if (which == 0) {
                        clearFilter();
                        startLoad(1, true);
                    } else {
                        showValueDialog(keys.get(which - 1), groups.get(keys.get(which - 1)));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 二级：选择该维度下的具体取值（带模型数量）。 */
    private void showValueDialog(final String group, final String groupLabel) {
        final List<ModelApi.Facet> values = new ArrayList<>();
        for (ModelApi.Facet f : facets) {
            if (f.group.equals(group)) values.add(f);
        }
        if (values.isEmpty()) return;

        final String[] labels = new String[values.size() + 1];
        labels[0] = getString(R.string.filter_all);
        int checked = 0;
        for (int i = 0; i < values.size(); i++) {
            ModelApi.Facet f = values.get(i);
            labels[i + 1] = f.value + "  (" + f.count + ")";
            if (group.equals(filterCategory) && f.value.equals(filterValue)) checked = i + 1;
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(groupLabel)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    d.dismiss();
                    if (which == 0) {
                        clearFilter();
                    } else {
                        ModelApi.Facet f = values.get(which - 1);
                        filterCategory = f.group;
                        filterValue = f.value;
                        filterLabel = f.groupLabel + "：" + f.value;
                        chipFilter.setText(filterLabel);
                    }
                    startLoad(1, true);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void clearFilter() {
        filterCategory = "";
        filterValue = "";
        filterLabel = "";
        chipFilter.setText(R.string.filter_title);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}