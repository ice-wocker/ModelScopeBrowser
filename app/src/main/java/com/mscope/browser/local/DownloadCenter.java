package com.mscope.browser.local;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 应用级下载中心：串行下载 GGUF（避免抢带宽），支持进度、取消、失败重试。
 * 下载在应用进程内进行，界面退出再进入可恢复进度显示。
 */
public class DownloadCenter {

    private static final String TAG = "DownloadCenter";
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Mobile Safari/537.36";

    public interface Listener {
        void onProgress(LocalModel model, long done, long total);

        void onFinished(LocalModel model);

        void onFailed(LocalModel model, String error);

        void onCancelled(LocalModel model);
    }

    public static class Task {
        public final LocalModel model;
        public final String url;
        public volatile long done;
        public volatile long total = -1;
        public volatile boolean cancelled;
        public volatile boolean finished;

        Task(LocalModel m, String url) {
            this.model = m;
            this.url = url;
        }

        public int percent() {
            if (total <= 0) return -1;
            return (int) Math.min(100, done * 100 / total);
        }
    }

    private static volatile DownloadCenter instance;

    public static DownloadCenter get(Context ctx) {
        if (instance == null) {
            synchronized (DownloadCenter.class) {
                if (instance == null) instance = new DownloadCenter(ctx.getApplicationContext());
            }
        }
        return instance;
    }

    private final LocalModelStore store;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final List<Listener> listeners = new ArrayList<>();

    private DownloadCenter(Context ctx) {
        store = new LocalModelStore(ctx);
    }

    public void addListener(Listener l) {
        synchronized (listeners) {
            if (!listeners.contains(l)) listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        synchronized (listeners) {
            listeners.remove(l);
        }
    }

    /** 当前正在下载的任务（可能为 null）。 */
    public Task task(String localPath) {
        Task t = tasks.get(localPath);
        return (t != null && !t.finished) ? t : null;
    }

    public boolean start(LocalModel model, String url) {
        if (task(model.localPath) != null) return false;
        Task t = new Task(model, url);
        tasks.put(model.localPath, t);
        executor.execute(() -> run(t));
        return true;
    }

    public void cancel(String localPath) {
        Task t = tasks.get(localPath);
        if (t != null) t.cancelled = true;
    }

    private void run(Task t) {
        File part = new File(t.model.localPath + ".part");
        File dst = new File(t.model.localPath);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(t.url).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "*/*");

            int code = conn.getResponseCode();
            if (code >= 400) throw new IllegalStateException("HTTP " + code);
            t.total = conn.getContentLengthLong();

            File parent = dst.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }

            byte[] buf = new byte[256 * 1024];
            long lastNotify = 0;
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(part)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (t.cancelled) throw new CancelledException();
                    out.write(buf, 0, n);
                    t.done += n;
                    long now = System.currentTimeMillis();
                    if (now - lastNotify > 250) {
                        lastNotify = now;
                        notifyProgress(t);
                    }
                }
                out.flush();
            }

            if (t.cancelled) throw new CancelledException();

            if (dst.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dst.delete();
            }
            if (!part.renameTo(dst)) throw new IllegalStateException("重命名失败");

            t.model.size = dst.length();
            t.model.localPath = dst.getAbsolutePath();
            t.model.addedAt = System.currentTimeMillis();
            store.add(t.model);

            t.finished = true;
            tasks.remove(t.model.localPath);
            ui.post(() -> {
                synchronized (listeners) {
                    for (Listener l : new ArrayList<>(listeners)) l.onFinished(t.model);
                }
            });
        } catch (CancelledException ce) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            t.finished = true;
            tasks.remove(t.model.localPath);
            ui.post(() -> {
                synchronized (listeners) {
                    for (Listener l : new ArrayList<>(listeners)) l.onCancelled(t.model);
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "下载失败", e);
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            t.finished = true;
            tasks.remove(t.model.localPath);
            final String err = String.valueOf(e.getMessage());
            ui.post(() -> {
                synchronized (listeners) {
                    for (Listener l : new ArrayList<>(listeners)) l.onFailed(t.model, err);
                }
            });
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void notifyProgress(Task t) {
        final long done = t.done;
        final long total = t.total;
        ui.post(() -> {
            synchronized (listeners) {
                for (Listener l : new ArrayList<>(listeners)) l.onProgress(t.model, done, total);
            }
        });
    }

    private static class CancelledException extends Exception {
    }
}