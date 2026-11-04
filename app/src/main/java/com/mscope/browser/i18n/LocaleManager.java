package com.mscope.browser.i18n;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.LocaleList;

import java.util.Locale;

/**
 * 应用内语言切换（不需要改系统设置）。
 *
 * <p>两件事必须一起做，否则会出现「界面英文、提示词还是中文」：
 * <ol>
 *   <li>给每个 Activity 换一份 Configuration —— 资源文件才会换语言；</li>
 *   <li>同步 {@link Lang} —— 代码里那些不在资源里的文案（提示词、工具说明、错误信息）跟着换。</li>
 * </ol>
 *
 * <p>没有用 {@code AppCompatDelegate.setApplicationLocales}：它在 minSdk 24 上要通过
 * 1.6+ 的 AppCompat 才真正生效，而且切换时会重建 Activity 栈 —— 对下载/推理都在跑的
 * 应用代价偏大。这里只在 Activity 起来前绑定一次，行为可预测。
 */
public final class LocaleManager {

    public static final String MODE_SYSTEM = "system";
    public static final String MODE_ZH = "zh";
    public static final String MODE_EN = "en";

    private static final String PREFS = "app_prefs";
    private static final String KEY_LANG = "lang_mode";

    private LocaleManager() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 当前模式：system / zh / en。 */
    public static String mode(Context c) {
        return prefs(c).getString(KEY_LANG, MODE_SYSTEM);
    }

    public static void setMode(Context c, String mode) {
        prefs(c).edit().putString(KEY_LANG, mode).apply();
        Lang.setChinese(isChinese(mode));
    }

    /** 是否中文语境；system 模式按系统 locale 判断。 */
    public static boolean isChinese(String mode) {
        if (MODE_ZH.equals(mode)) return true;
        if (MODE_EN.equals(mode)) return false;
        return "zh".equals(Locale.getDefault().getLanguage());
    }

    private static Locale localeOf(String mode) {
        if (MODE_ZH.equals(mode)) return Locale.SIMPLIFIED_CHINESE;
        if (MODE_EN.equals(mode)) return Locale.ENGLISH;
        return Locale.getDefault();
    }

    /**
     * 在每个 Activity 的 {@code attachBaseContext} 里调用。
     *
     * <p>顺带把 {@link Lang} 定下来 —— 这是代码文案的唯一信息来源，不能遗漏。
     */
    public static Context wrap(Context base) {
        String mode = mode(base);
        Lang.setChinese(isChinese(mode));

        Locale locale = localeOf(mode);
        Locale.setDefault(locale);

        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        // minSdk 24 = Android 7.0，LocaleList 是 7.0 起就有的 API，不需要再判断版本
        cfg.setLocales(new LocaleList(locale));
        return base.createConfigurationContext(cfg);
    }

    /**
     * 切换语言并重建界面。
     *
     * <p>用 {@code recreate()} 而不是重启进程：已加载进内存的模型、正在跑的下载
     * 都挂在进程级对象上，重启会把它们全丢掉。
     */
    public static void apply(Activity activity, String mode) {
        setMode(activity, mode);
        activity.recreate();
    }
}
