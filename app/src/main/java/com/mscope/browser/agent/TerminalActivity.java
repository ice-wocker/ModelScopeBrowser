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

/**
 * 本机终端：在应用私有工作区内执行受限命令。
 * 命令解释由 {@link Terminal} 完成，运行环境就是「本机」（手机上的应用工作区）。
 */
public class TerminalActivity extends AppCompatActivity {

    private Terminal terminal;
    private TextView out;
    private EditText input;
    private ScrollView scroll;
    private final SpannableStringBuilder buffer = new SpannableStringBuilder();
    private final List<String> history = new ArrayList<>();
    private int historyPos = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_terminal);
        Ui.edgeToEdge(this, findViewById(R.id.termRoot));

        Workspace ws = new Workspace(this);
        terminal = new Terminal(ws);

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
        String cmd = input.getText().toString().trim();
        if (cmd.isEmpty()) return;
        input.setText("");
        if (history.isEmpty() || !cmd.equals(history.get(history.size() - 1))) {
            history.add(cmd);
        }
        historyPos = history.size();

        appendLine("$ " + cmd, R.color.terminal_prompt);
        Terminal.Out result = terminal.run(cmd);
        if (result.text != null && result.text.startsWith("\f")) {   // clear
            buffer.clear();
            setText();
            return;
        }
        if (result.text != null && !result.text.isEmpty()) {
            appendLine(result.text, result.code == 0 ? R.color.terminal_fg : R.color.terminal_err);
        }
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
}