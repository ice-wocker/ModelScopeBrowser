package com.mscope.browser.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;

import com.mscope.browser.R;
import com.mscope.browser.core.DocConverter;

/**
 * 系统通知：长任务跑完、下载结束、定时提醒都走这里。
 *
 * <p>两个坑提前踩掉：Android 8 起必须有 channel，否则 {@code notify} 静默丢弃；
 * Android 13 起要 POST_NOTIFICATIONS 权限，没授权时 notify 也不报错。
 * 所以这里统一返回 boolean，让工具能如实告诉模型「通知没发出去」。
 */
public final class Notifications {

    /** 与 DownloadedService 用的 channel 分开：用户可能想留下载通知、但屏蔽聊天通知。 */
    public static final String CHANNEL = "agent_notes";

    private Notifications() {
    }

    public static boolean post(Context ctx, String title, String text) {
        if (ctx == null) return false;
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return false;
            if (!allowed(ctx, nm)) return false;
            ensureChannel(ctx, nm);
            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(ctx, CHANNEL)
                    : new Notification.Builder(ctx);
            b.setSmallIcon(R.drawable.ic_smart_toy)
                    .setContentTitle(DocConverter.clip(title, 120))
                    .setContentText(DocConverter.clip(text, 240))
                    .setAutoCancel(true)
                    .setShowWhen(true);
            String longText = text == null ? "" : text;
            if (longText.length() > 240) {
                b.setStyle(new Notification.BigTextStyle().bigText(DocConverter.clip(longText, 2000)));
                b.setContentText(DocConverter.clip(longText, 120) + "…");
            }
            nm.notify((int) (System.currentTimeMillis() & 0x7FFFFFFF), b.build());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean allowed(Context ctx, NotificationManager nm) {
        if (Build.VERSION.SDK_INT >= 24) {
            return nm.areNotificationsEnabled();
        }
        return true;
    }

    private static void ensureChannel(Context ctx, NotificationManager nm) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        if (nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL,
                ctx.getString(R.string.notif_channel_agent),
                NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription(ctx.getString(R.string.notif_channel_agent_desc));
        ch.setShowBadge(true);
        nm.createNotificationChannel(ch);
    }
}
