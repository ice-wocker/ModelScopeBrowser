package com.mscope.browser.agent;

import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.view.MenuItem;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.mscope.browser.R;
import com.mscope.browser.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 本机终端：直接执行 /system/bin/sh 命令（真 shell），工作目录固定在应用工作区。
 *
 * <p>命令在后台线程执行，输出回到界面；受 Android 沙箱限制，只能访问应用私有目录，
 * 无需 root、也不需要任何权限。
 */
public class TerminalActivity extends AppCompatActivity {

    private Shell shell;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    private TextView out;
    private EditText input;
    private ScrollView scroll;
    private final SpannableStringBuilder buffer = new SpannableStringBuilder();
    private final List<String> history = new ArrayList<>();
    private int historyPos = 0;
    private volatile boolean running;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_terminal);
        Ui.edgeToEdge(this, findViewById(R.id.termRoot));

        Workspace ws = new Workspace(this);
        shell = new Shell(ws);

        out = findViewById(R.id.tvTermOut);
        input = findViewById(R.id.etTerm);
        scroll = findViewById(R.id.termScroll);

        MaterialToolbar toolbar = findViewById(R.id.tToolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setTitle(R.string.term_title);
        toolbar.setSubtitle(getString(R.string.term_subtitle, ws.root().getName() + "/"));
        toolbar.setOnMenuItemClickListener(this::onMenu);

        findViewById(R.id.btnTermRun).setOnClickListener(v -> runInput());
        findViewById(R.id.btnTermLast).setOnClickListener(v -> recallPrev());
        input.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                runInput();
                return true;
            }
            return false;
        });

        appendLine(getString(R.string.term_ready), R.color.terminal_dim);
        appendLine(ws.root().getAbsolutePath(), R.color.terminal_dim);
    }

    private void runInput() {
        if (running) return;
        String cmd = input.getText().toString().trim();
        if (cmd.isEmpty()) return;
        input.setText("");
        if (history.isEmpty() || !cmd.equals(history.get(history.size() - 1))) {
            history.add(cmd);
        }
        historyPos = history.size();

        if ("clear".equals(cmd) || "cls".equals(cmd)) {     // 清屏交给界面，避免输出一堆转义符
            buffer.clear();
            setText();
            return;
        }

        appendLine("$ " + cmd, R.color.terminal_prompt);
        running = true;
        exec.execute(() -> {
            final Shell.Out r = shell.run(cmd);
            runOnUiThread(() -> {
                running = false;
                if (isFinishing() || isDestroyed()) return;
                if (r.text != null && !r.text.isEmpty()) {
                    appendLine(r.text, r.code == 0 ? R.color.terminal_fg : R.color.terminal_err);
                }
            });
        });
    }

    private void recallPrev() {
        if (history.isEmpty()) return;
        if (historyPos > 0) historyPos--;
        input.setText(history.get(historyPos));
        input.setSelection(input.getText().length());
    }

    private boolean onMenu(MenuItem item) {
        if (item.getItemId() == R.id.action_term_clear) {
            buffer.clear();
            setText();
            return true;
        }
        return false;
    }

    private void appendLine(String text, int colorRes) {
        int start = buffer.length();
        buffer.append(text == null ? "" : text).append('\n');
        buffer.setSpan(new ForegroundColorSpan(ContextCompat.getColor(this, colorRes)),
                start, buffer.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        setText();
    }

    private void setText() {
        out.setText(buffer);
        scroll.post(() -> scroll.fullScroll(android.view.View.FOCUS_DOWN));
    }

    @Override
    protected void onDestroy() {
        exec.shutdownNow();
        super.onDestroy();
    }
}