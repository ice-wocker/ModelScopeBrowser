package com.mscope.browser.agent;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.mscope.browser.Format;
import com.mscope.browser.R;
import com.mscope.browser.Ui;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 工作区文件管理：浏览 / 新建 / 预览 / 分享 / 删除。
 * 所有内容都在应用私有工作区内，点 .html 直接进本地预览。
 */
public class WorkspaceActivity extends AppCompatActivity {

    private Workspace ws;
    private String dir = "";
    private RecyclerView list;
    private TextView tvPath;
    private TextView tvUsage;
    private View empty;
    private Adapter adapter;
    private final List<Workspace.Entry> entries = new ArrayList<>();

    private static String fmt(long time) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(time));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_workspace);
        Ui.edgeToEdge(this, findViewById(R.id.wsRoot));

        ws = new Workspace(this);
        list = findViewById(R.id.wsList);
        tvPath = findViewById(R.id.tvWsPath);
        tvUsage = findViewById(R.id.tvWsUsage);
        empty = findViewById(R.id.wsEmpty);

        MaterialToolbar toolbar = findViewById(R.id.wsToolbar);
        toolbar.setNavigationOnClickListener(v -> upOrFinish());
        toolbar.setOnMenuItemClickListener(this::onMenu);

        adapter = new Adapter();
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    /* ------------------------------------------------------------------ 导航 */

    private void upOrFinish() {
        if (dir.isEmpty()) {
            finish();
            return;
        }
        int i = dir.lastIndexOf('/');
        dir = i < 0 ? "" : dir.substring(0, i);
        reload();
    }

    private void openDir(String rel) {
        dir = Workspace.normalize(rel);
        reload();
    }

    private void reload() {
        entries.clear();
        entries.addAll(ws.list(dir));
        adapter.notifyDataSetChanged();

        tvPath.setText(dir.isEmpty() ? getString(R.string.workspace_root) : "/" + dir);
        tvUsage.setText(getString(R.string.workspace_usage, countFiles(), Format.size(ws.totalSize())));
        empty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private int countFiles() {
        int n = 0;
        for (Workspace.Entry e : ws.list(dir)) if (!e.dir) n++;
        return n;
    }

    /* ------------------------------------------------------------------ 操作 */

    private void openEntry(Workspace.Entry e) {
        if (e.dir) {
            openDir(e.relPath);
            return;
        }
        if (e.isHtml()) {
            HtmlPreviewActivity.start(this, e.relPath);
        } else if (e.isText()) {
            viewText(e);
        } else {
            share(e);
        }
    }

    private void viewText(Workspace.Entry e) {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_viewer, null);
        TextView tv = v.findViewById(R.id.tvViewer);
        try {
            tv.setText(ws.readText(e.relPath));
        } catch (Exception ex) {
            tv.setText(String.valueOf(ex.getMessage()));
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(e.name)
                .setView(v)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.workspace_share, (d, w) -> share(e))
                .show();
    }

    private void share(Workspace.Entry e) {
        try {
            File f = ws.resolve(e.relPath);
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
            Intent it = new Intent(Intent.ACTION_SEND);
            it.setType(mime(e.name));
            it.putExtra(Intent.EXTRA_STREAM, uri);
            it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(it, getString(R.string.workspace_share)));
        } catch (Exception ex) {
            Toast.makeText(this, getString(R.string.workspace_share_failed,
                    String.valueOf(ex.getMessage())), Toast.LENGTH_SHORT).show();
        }
    }

    private static String mime(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".html") || n.endsWith(".htm")) return "text/html";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".log")) return "text/plain";
        if (n.endsWith(".csv")) return "text/csv";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }

    private void delete(Workspace.Entry e) {
        new MaterialAlertDialogBuilder(this)
                .setMessage(getString(R.string.workspace_delete_confirm, e.name))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.workspace_delete, (d, w) -> {
                    ws.delete(e.relPath);
                    Toast.makeText(this, getString(R.string.workspace_deleted, e.name),
                            Toast.LENGTH_SHORT).show();
                    reload();
                })
                .show();
    }

    private void showMenu(Workspace.Entry e) {
        final String[] actions = e.dir
                ? new String[]{getString(R.string.workspace_delete)}
                : new String[]{getString(e.isHtml() ? R.string.workspace_preview : R.string.workspace_view),
                               getString(R.string.workspace_share),
                               getString(R.string.workspace_delete)};
        new MaterialAlertDialogBuilder(this)
                .setTitle(e.name)
                .setItems(actions, (d, which) -> {
                    if (e.dir) {
                        delete(e);
                    } else if (which == 0) {
                        openEntry(e);
                    } else if (which == 1) {
                        share(e);
                    } else {
                        delete(e);
                    }
                })
                .show();
    }

    private boolean onMenu(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_new_file) {
            newEntry(true);
            return true;
        }
        if (id == R.id.action_new_dir) {
            newEntry(false);
            return true;
        }
        if (id == R.id.action_ws_root) {
            dir = "";
            reload();
            return true;
        }
        return false;
    }

    /** 在当前目录下新建文件 / 文件夹，名称允许带子目录（如 page/index.html）。 */
    private void newEntry(boolean file) {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_text, null);
        EditText et = v.findViewById(R.id.etText);
        et.setHint(R.string.workspace_name_hint);
        new MaterialAlertDialogBuilder(this)
                .setTitle(file ? R.string.workspace_new_file : R.string.workspace_new_dir)
                .setView(v)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.p_apply, (d, w) -> {
                    String name = et.getText().toString().trim();
                    if (TextUtils.isEmpty(name) || name.contains("..") || name.contains("\\")) {
                        Toast.makeText(this, R.string.workspace_name_invalid, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String rel = dir.isEmpty() ? name : dir + "/" + name;
                    try {
                        if (file) ws.writeText(rel, "");
                        else ws.mkdir(rel);
                        Toast.makeText(this, getString(R.string.workspace_created, "/" + Workspace.normalize(rel)),
                                Toast.LENGTH_SHORT).show();
                        reload();
                    } catch (Exception ex) {
                        Toast.makeText(this, String.valueOf(ex.getMessage()), Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    /* ---------------------------------------------------------------- 适配器 */

    private class Adapter extends RecyclerView.Adapter<Adapter.VH> {

        class VH extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView name;
            final TextView meta;

            VH(@NonNull View v) {
                super(v);
                icon = v.findViewById(R.id.ivWsIcon);
                name = v.findViewById(R.id.tvWsName);
                meta = v.findViewById(R.id.tvWsMeta);
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_workspace_file, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Workspace.Entry e = entries.get(position);
            h.icon.setImageResource(e.dir ? R.drawable.ic_folder
                    : (e.isHtml() ? R.drawable.ic_globe : R.drawable.ic_file));
            h.name.setText(e.name);
            h.meta.setText(e.dir
                    ? getString(R.string.file_size_dir) + " · " + fmt(e.modified)
                    : Format.size(e.size) + " · " + fmt(e.modified));
            h.itemView.setOnClickListener(v -> openEntry(e));
            h.itemView.setOnLongClickListener(v -> {
                showMenu(e);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return entries.size();
        }
    }
}