package com.mscope.browser.agent;

import android.app.Activity;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.mscope.browser.R;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 「智能体能力」面板：按组开关工具。
 *
 * <p>为什么要按组而不是按单个工具：57 个开关列在手机上没人会拉完。用户的心智是
 * 「要不要让它联网」「要不要让它动文件」，不是「要不要 read_file」。
 * 组内细分留给说明文字，控制粒度留在这里。
 *
 * <p>开关状态回调给调用方自己持久化 —— 这个类不碰 SharedPreferences，
 * 免得「界面改了、值没存」和「存了、界面没刷新」两种不一致。
 */
public final class CapabilityDialog {

    /** 分组顺序与标题。 */
    private static final ToolSpec.Group[] ORDER = {
            ToolSpec.Group.FILE,
            ToolSpec.Group.TEXT,
            ToolSpec.Group.CALC,
            ToolSpec.Group.AGENT,
            ToolSpec.Group.DEVICE,
            ToolSpec.Group.WEB,
            ToolSpec.Group.SHELL,
            ToolSpec.Group.MODEL
    };

    private CapabilityDialog() {
    }

    private static int titleOf(ToolSpec.Group g) {
        switch (g) {
            case FILE:
                return R.string.caps_group_file;
            case TEXT:
                return R.string.caps_group_text;
            case CALC:
                return R.string.caps_group_calc;
            case DEVICE:
                return R.string.caps_group_device;
            case WEB:
                return R.string.caps_group_web;
            case SHELL:
                return R.string.caps_group_shell;
            case MODEL:
                return R.string.caps_group_model;
            case AGENT:
            default:
                return R.string.caps_group_agent;
        }
    }

    /** 显示面板。 */
    public static void show(Activity act, Set<ToolSpec.Group> current, Callback cb) {
        if (act == null || act.isFinishing()) return;
        Set<ToolSpec.Group> chosen = current == null
                ? EnumSet.noneOf(ToolSpec.Group.class) : EnumSet.copyOf(current);

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(act, 20);
        root.setPadding(pad, dp(act, 8), pad, dp(act, 8));

        Map<ToolSpec.Group, android.widget.CheckBox> boxes = new LinkedHashMap<>();
        for (ToolSpec.Group g : ORDER) {
            int count = ToolRegistry.group(g).size();
            if (count == 0) continue;
            android.widget.CheckBox box = new android.widget.CheckBox(act);
            box.setText(act.getString(titleOf(g)) + "  ·  " + count);
            box.setChecked(chosen.contains(g));
            box.setTextSize(16);
            box.setPadding(0, dp(act, 10), 0, dp(act, 10));
            box.setOnCheckedChangeListener((b, checked) -> {
                if (checked) chosen.add(g);
                else chosen.remove(g);
            });
            root.addView(box, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            boxes.put(g, box);
        }

        TextView hint = new TextView(act);
        hint.setText(R.string.caps_confirm_risky);
        hint.setTextSize(13);
        hint.setPadding(0, dp(act, 8), 0, 0);
        if (act.getColorStateList(R.color.chip_text) != null) {
            hint.setTextColor(act.getColorStateList(R.color.chip_text));
        }
        root.addView(hint);

        ScrollView scroll = new ScrollView(act);
        scroll.addView(root);

        new MaterialAlertDialogBuilder(act)
                .setTitle(R.string.caps_title)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    if (cb != null) cb.onChosen(chosen);
                })
                .setNegativeButton(R.string.tool_confirm_cancel, null)
                .show();
    }

    /** 外部模型配置。 */
    public static void showExternalModel(Activity act, Map<String, String> cur, Save save) {
        if (act == null || act.isFinishing()) return;
        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(act, 20);
        root.setPadding(pad, dp(act, 8), pad, dp(act, 8));

        TextView desc = new TextView(act);
        desc.setText(R.string.ext_model_desc);
        desc.setTextSize(13);
        root.addView(desc);

        EditText base = field(act, R.string.ext_model_base, cur == null ? "" : cur.get("base"), false);
        EditText key = field(act, R.string.ext_model_key, cur == null ? "" : cur.get("key"), true);
        EditText name = field(act, R.string.ext_model_name, cur == null ? "" : cur.get("model"), false);
        root.addView(base);
        root.addView(key);
        root.addView(name);

        TextView hint = new TextView(act);
        hint.setText(R.string.ext_model_key_hint);
        hint.setTextSize(12);
        hint.setPadding(0, dp(act, 6), 0, 0);
        root.addView(hint);

        new MaterialAlertDialogBuilder(act)
                .setTitle(R.string.ext_model_title)
                .setView(root)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("base", base.getText().toString().trim());
                    m.put("key", key.getText().toString().trim());
                    m.put("model", name.getText().toString().trim());
                    if (save != null) save.onSave(m);
                })
                .setNeutralButton(R.string.ext_model_clear, (d, w) -> {
                    if (save != null) save.onSave(new LinkedHashMap<>());
                })
                .setNegativeButton(R.string.tool_confirm_cancel, null)
                .show();
    }

    private static EditText field(Activity act, int hintRes, String value, boolean password) {
        EditText et = new EditText(act);
        et.setHint(hintRes);
        et.setText(value == null ? "" : value);
        et.setTextSize(15);
        et.setSingleLine(true);
        if (password) {
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(act, 12);
        et.setLayoutParams(lp);
        return et;
    }

    private static int dp(Activity act, int v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }

    /** 选择结果。 */
    public interface Callback {
        void onChosen(Set<ToolSpec.Group> groups);
    }

    /** 外部模型配置保存。 */
    public interface Save {
        void onSave(Map<String, String> values);
    }

    /** 一句话提示当前开了哪些组。 */
    public static String summaryText(Activity act, Set<ToolSpec.Group> groups) {
        if (act == null || groups == null) return "";
        int n = 0;
        for (ToolSpec.Group g : groups) n += ToolRegistry.group(g).size();
        return act.getString(R.string.caps_count, n);
    }

    /** 轻量提示。 */
    public static void toast(Activity act, String text) {
        if (act != null && !act.isFinishing()) {
            Toast.makeText(act, text, Toast.LENGTH_SHORT).show();
        }
    }
}
