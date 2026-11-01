package com.mscope.browser.i18n;

import android.content.Context;

import androidx.appcompat.app.AppCompatActivity;

import com.mscope.browser.R;

/**
 * 所有 Activity 的基类：只做一件事 —— 把应用内选择的语言绑定到这一层。
 *
 * <p>必须放在 {@code attachBaseContext}：资源在 {@code onCreate} 里就已经按
 * Configuration 解析好了，改晚了这一屏就还是旧语言。
 */
public abstract class BaseActivity extends AppCompatActivity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleManager.wrap(newBase));
    }

    /** 弹出语言选择。放在「关于」里，避免主界面再多一个按钮。 */
    protected void showLanguageDialog() {
        final String[] modes = {LocaleManager.MODE_SYSTEM, LocaleManager.MODE_ZH, LocaleManager.MODE_EN};
        String[] labels = {
                getString(R.string.lang_system), getString(R.string.lang_zh), getString(R.string.lang_en)};
        int current = 0;
        String mode = LocaleManager.mode(this);
        for (int i = 0; i < modes.length; i++) {
            if (modes[i].equals(mode)) current = i;
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.lang_title)
                .setSingleChoiceItems(labels, current, (d, which) -> {
                    d.dismiss();
                    LocaleManager.apply(this, modes[which]);
                })
                .show();
    }
}
