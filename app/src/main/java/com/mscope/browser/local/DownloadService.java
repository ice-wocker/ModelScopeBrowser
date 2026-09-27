package com.mscope.browser.local;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import com.mscope.browser.MainActivity;
import com.mscope.browser.R;

/**
 * 下载期间的前台服务：让大文件在应用退到后台时不被系统回收。
 * 服务本身不执行下载（下载在 {@link DownloadCenter} 的线程池里），只负责前台通知。
 */
public class DownloadService extends Service {

    private static final String CHANNEL = "download";
    private static final int NOTIF_ID = 1001;

    private static final String ACTION_START = "com.mscope.browser.dl.START";
    private static final String ACTION_UPDATE = "com.mscope.browser.dl.UPDATE";
    private static final String EXTRA_TEXT = "text";
    private static final String EXTRA_PCT = "pct";

    public static void start(Context ctx, String text, int pct) {
        send(ctx, ACTION_START, text, pct);
    }

    public static void update(Context ctx, String text, int pct) {
        send(ctx, ACTION_UPDATE, text, pct);
    }

    public static void stop(Context ctx) {
        try {
            ctx.stopService(new Intent(ctx, DownloadService.class));
        } catch (Throwable ignored) {
        }
    }

    private static void send(Context ctx, String action, String text, int pct) {
        Intent it = new Intent(ctx, DownloadService.class);
        it.setAction(action);
        it.putExtra(EXTRA_TEXT, text);
        it.putExtra(EXTRA_PCT, pct);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(it);
            } else {
                ctx.startService(it);
            }
        } catch (Throwable ignored) {
            // 受限环境下可能不允许拉起前台服务；退化为进程内下载，不影响下载本身
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String text = intent == null ? null : intent.getStringExtra(EXTRA_TEXT);
        final int pct = intent == null ? -1 : intent.getIntExtra(EXTRA_PCT, -1);
        try {
            startForeground(NOTIF_ID, build(text, pct));
        } catch (Throwable ignored) {
            stopSelf();     // 起不来就退出，避免 5s 内未 startForeground 被系统判定异常
        }
        return START_NOT_STICKY;
    }

    private Notification build(String text, int pct) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL,
                        getString(R.string.dl_notif_channel), NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_model)
                .setContentTitle(getString(R.string.dl_notif_title))
                .setContentText(text == null ? getString(R.string.dl_notif_running) : text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .setProgress(100, Math.max(0, Math.min(100, pct)), pct < 0)
                .build();
    }
}