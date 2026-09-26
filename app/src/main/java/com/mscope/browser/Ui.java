package com.mscope.browser;

import android.app.Activity;
import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * targetSdk 35+ 会强制边到边（edge-to-edge）布局，这里统一处理：
 * 关闭系统自动适配 → 把状态栏/导航栏/输入法的 Insets 作为根布局内边距，避免内容被系统栏遮挡。
 */
public final class Ui {

    private Ui() {
    }

    public static void edgeToEdge(Activity activity, View root) {
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);

        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(activity.getWindow(), activity.getWindow().getDecorView());
        // 品牌色为深橙，状态栏图标用浅色
        controller.setAppearanceLightStatusBars(false);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return windowInsets;
        });
    }
}