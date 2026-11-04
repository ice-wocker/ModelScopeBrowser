package com.mscope.browser.agent;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;

import com.mscope.browser.core.DocConverter;
import com.mscope.browser.core.MemoryBudget;

import java.io.File;
import java.util.Locale;

/**
 * 设备画像：给模型的「我在哪台机器上」。
 *
 * <p>为什么模型需要知道这个：同样一句「帮我跑个本地模型」，在 8 GB 旗舰机和 3 GB 老机器上
 * 应该给完全不同的建议。把这些事实作为工具结果说出来，比让模型猜靠谱得多。
 *
 * <p>只读公开 API，不额外申请权限（电量走 sticky broadcast，读不到就写「未知」）。
 */
public final class DeviceInfo {

    private DeviceInfo() {
    }

    /** 一段人/模型都能读的画像文本。 */
    public static String describe(Context ctx, boolean zh) {
        if (ctx == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(zh ? "机型：" : "device: ")
          .append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
          .append(" (").append(Build.DEVICE).append(")\n");
        sb.append(zh ? "系统：Android " : "OS: Android ").append(Build.VERSION.RELEASE)
          .append(" / API ").append(Build.VERSION.SDK_INT)
          .append(" / ").append(Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?")
          .append('\n');
        sb.append(zh ? "CPU：" : "CPU: ").append(Build.SUPPORTED_ABIS.length)
          .append(zh ? " 个 ABI，逻辑核 " : " ABIs, cores ")
          .append(Runtime.getRuntime().availableProcessors()).append('\n');
        MemoryBudget.Snapshot mem = MemoryBudget.read();
        if (mem != null) {
            sb.append(zh ? "内存：总 " : "memory: total ")
              .append(DocConverter.compactSize(mem.totalKb * 1024))
              .append(zh ? "，可用 " : ", available ")
              .append(DocConverter.compactSize(mem.availableKb * 1024)).append('\n');
        }
        sb.append(zh ? "应用可用堆：" : "app heap limit: ")
          .append(DocConverter.compactSize(Runtime.getRuntime().maxMemory())).append('\n');
        try {
            StatFs fs = new StatFs(ctx.getFilesDir().getAbsolutePath());
            sb.append(zh ? "内部存储：可用 " : "internal storage: free ")
              .append(DocConverter.compactSize(fs.getAvailableBytes()))
              .append(zh ? " / 共 " : " / total ")
              .append(DocConverter.compactSize(fs.getTotalBytes())).append('\n');
        } catch (Exception ignored) {
            // 少数设备上 StatFs 会抛，跳过
        }
        try {
            StatFs fs = new StatFs(Environment.getExternalStorageDirectory().getAbsolutePath());
            sb.append(zh ? "外部存储：可用 " : "external storage: free ")
              .append(DocConverter.compactSize(fs.getAvailableBytes())).append('\n');
        } catch (Exception ignored) {
            // 同上
        }
        sb.append(zh ? "电量：" : "battery: ").append(battery(ctx, zh)).append('\n');
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            sb.append(zh ? "系统可用内存：" : "system available: ")
              .append(DocConverter.compactSize(mi.availMem))
              .append(mi.lowMemory ? (zh ? "（吃紧）" : " (low)") : "").append('\n');
            sb.append(zh ? "低内存机型：" : "low-RAM device: ")
              .append(am.isLowRamDevice() ? (zh ? "是" : "yes") : (zh ? "否" : "no"));
        }
        return sb.toString().trim();
    }

    private static String battery(Context ctx, boolean zh) {
        try {
            Intent it = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (it == null) return zh ? "未知" : "unknown";
            int level = it.getIntExtra("level", -1);
            int scale = it.getIntExtra("scale", 100);
            int status = it.getIntExtra("status", -1);
            if (level < 0 || scale <= 0) return zh ? "未知" : "unknown";
            int pct = (int) Math.round(level * 100.0 / scale);
            boolean charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                    || status == android.os.BatteryManager.BATTERY_STATUS_FULL;
            return pct + "%" + (charging ? (zh ? "（充电中）" : " (charging)") : "");
        } catch (Exception e) {
            return zh ? "未知" : "unknown";
        }
    }

    /** 应用各目录占用，回答「空间都去哪了」。 */
    public static String storageReport(Context ctx, Workspace ws, boolean zh) {
        if (ctx == null) return "";
        StringBuilder sb = new StringBuilder();
        long files = dirSize(ctx.getFilesDir());
        long cache = dirSize(ctx.getCacheDir());
        long apk = new File(ctx.getApplicationInfo().sourceDir).length();
        long wsSize = ws == null ? 0 : ws.totalSize();
        sb.append(zh ? "应用私有 files/：" : "app files/: ").append(DocConverter.compactSize(files)).append('\n');
        sb.append(zh ? "工作区：" : "workspace: ").append(DocConverter.compactSize(wsSize)).append('\n');
        sb.append(zh ? "缓存：" : "cache: ").append(DocConverter.compactSize(cache)).append('\n');
        sb.append(zh ? "程序本体：" : "apk: ").append(DocConverter.compactSize(apk)).append('\n');
        try {
            StatFs fs = new StatFs(ctx.getFilesDir().getAbsolutePath());
            sb.append(zh ? "剩余可用：" : "free: ").append(DocConverter.compactSize(fs.getAvailableBytes()));
        } catch (Exception ignored) {
            sb.append(zh ? "剩余可用：未知" : "free: unknown");
        }
        return sb.toString();
    }

    private static long dirSize(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long total = 0;
        File[] fs = f.listFiles();
        if (fs == null) return 0;
        for (File c : fs) total += dirSize(c);
        return total;
    }

    /** 供状态条用的简短机型串。 */
    public static String shortModel() {
        String m = Build.MODEL == null ? "Android" : Build.MODEL;
        return m.length() > 24 ? m.substring(0, 24) : m;
    }

    /** 按可用内存给一句「能跑多大模型」的建议。 */
    public static String advice(boolean zh) {
        MemoryBudget.Snapshot mem = MemoryBudget.read();
        long avail = mem == null ? 0 : mem.availableKb * 1024;
        String tier;
        if (avail > 5L * 1024 * 1024 * 1024) tier = zh ? "7B Q4 勉强能跑" : "7B Q4 is borderline";
        else if (avail > 2L * 1024 * 1024 * 1024) tier = zh ? "3B Q4 比较稳" : "3B Q4 is comfortable";
        else if (avail > 800L * 1024 * 1024) tier = zh ? "1.5B Q4 比较稳" : "1.5B Q4 is comfortable";
        else tier = zh ? "0.5B Q4 甚至更小" : "0.5B Q4 or smaller";
        return String.format(Locale.ROOT, zh ? "按当前可用内存，%s" : "given available memory, %s", tier);
    }
}
