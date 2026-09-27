package com.mscope.browser.local;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.mscope.browser.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 应用级下载中心：串行下载 GGUF（避免抢带宽），支持进度、取消、断点续传与完整性校验。
 *
 * <p>为提高成功率：</p>
 * <ul>
 *   <li>有 {@code .part} 残留时带 {@code Range} 头续传，服务端不支持则自动从头下；</li>
 *   <li>下载完成后校验「字节数」与「GGUF 魔数」，避免把错误页/半截文件当成模型；</li>
 *   <li>取消会主动断开连接，不必等到下一次读超时；</li>
 *   <li>任务信息落盘，进程被杀后再次进入应用会自动续传。</li>
 * </ul>
 */
public class DownloadCenter {

    private static final String TAG = "DownloadCenter";
    private static final String PENDING = "downloads.json";

    private static final String UA =
            "Mozilla/5.0 (Linux; Android " + Build.VERSION.RELEASE + ") AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

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
        /** 正在使用的连接，取消时用来立即打断阻塞的 read。 */
        volatile HttpURLConnection conn;

        Task(LocalModel m, String url) {
            this.model = m;
            this.url = url;
        }

        public int percent() {
            if (total <= 0) return -1;
            return (int) Math.min(100, done * 100 / total);
        }
    }

    private enum Finish {OK, FAILED, CANCELLED}

    private static volatile DownloadCenter instance;

    public static DownloadCenter get(Context ctx) {
        if (instance == null) {
            synchronized (DownloadCenter.class) {
                if (instance == null) instance = new DownloadCenter(ctx.getApplicationContext());
            }
        }
        return instance;
    }

    private final Context ctx;
    private final LocalModelStore store;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final List<Listener> listeners = new ArrayList<>();

    private DownloadCenter(Context context) {
        ctx = context;
        store = new LocalModelStore(ctx);
        executor.execute(this::resumePending);   // 上次被中断的下载，接着下
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
        addPending(t);
        executor.execute(() -> run(t));
        return true;
    }

    public void cancel(String localPath) {
        Task t = tasks.get(localPath);
        if (t == null) return;
        t.cancelled = true;
        HttpURLConnection c = t.conn;
        if (c != null) {
            try {
                c.disconnect();     // 立即打断阻塞中的 read，不必等读超时
            } catch (Throwable ignored) {
            }
        }
    }

    /* ------------------------------------------------------------- 下载主流程 */

    private void run(Task t) {
        if (t.cancelled) {
            clearPending(t);
            finish(t, Finish.CANCELLED, null);
            return;
        }

        final File part = new File(t.model.localPath + ".part");
        final File dst = new File(t.model.localPath);
        HttpURLConnection conn = null;
        try {
            File parent = dst.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }

            long existing = part.exists() ? part.length() : 0;

            conn = (HttpURLConnection) new URL(t.url).openConnection();
            t.conn = conn;
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "*/*");
            if (existing > 0) conn.setRequestProperty("Range", "bytes=" + existing + "-");

            final int code = conn.getResponseCode();
            if (code >= 400) throw new IllegalStateException("HTTP " + code);

            final boolean resuming = (code == HttpURLConnection.HTTP_PARTIAL);
            if (resuming) {
                t.total = existing + conn.getContentLengthLong();
                t.done = existing;
            } else {
                existing = 0;                    // 服务端不支持 Range，只能从头下
                t.total = conn.getContentLengthLong();
                t.done = 0;
            }

            DownloadService.start(ctx, notifText(t), t.percent());

            byte[] buf = new byte[256 * 1024];
            long lastNotify = 0;
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(part, resuming)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (t.cancelled) throw new CancelledException();
                    out.write(buf, 0, n);
                    t.done += n;
                    long now = System.currentTimeMillis();
                    if (now - lastNotify > 400) {
                        lastNotify = now;
                        notifyProgress(t);
                    }
                }
                out.flush();
            }

            if (t.cancelled) throw new CancelledException();

            // ---- 完整性校验：长度 + GGUF 魔数 ----
            final long len = part.length();
            if (t.total > 0 && len != t.total) {
                throw new IllegalStateException("文件不完整 " + len + "/" + t.total);
            }
            if (!isGguf(part)) throw new IllegalStateException("不是有效的 GGUF 文件");

            if (dst.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dst.delete();
            }
            if (!part.renameTo(dst)) throw new IllegalStateException("重命名失败");

            t.model.size = dst.length();
            t.model.localPath = dst.getAbsolutePath();
            t.model.addedAt = System.currentTimeMillis();
            store.add(t.model);

            clearPending(t);
            finish(t, Finish.OK, null);
        } catch (CancelledException ce) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            clearPending(t);
            finish(t, Finish.CANCELLED, null);
        } catch (Exception e) {
            if (t.cancelled) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                clearPending(t);
                finish(t, Finish.CANCELLED, null);
            } else {
                Log.w(TAG, "下载失败", e);
                // 保留 .part 与待续传记录：下次进入应用可自动接着下
                finish(t, Finish.FAILED, String.valueOf(e.getMessage()));
            }
        } finally {
            t.conn = null;
            if (conn != null) conn.disconnect();
        }
    }

    private void finish(Task t, Finish kind, String error) {
        t.finished = true;
        t.conn = null;
        tasks.remove(t.model.localPath);
        final LocalModel m = t.model;
        final String err = error == null ? "" : error;
        ui.post(() -> {
            synchronized (listeners) {
                for (Listener l : new ArrayList<>(listeners)) {
                    switch (kind) {
                        case OK:
                            l.onFinished(m);
                            break;
                        case FAILED:
                            l.onFailed(m, err);
                            break;
                        default:
                            l.onCancelled(m);
                            break;
                    }
                }
            }
            if (!anyActive()) DownloadService.stop(ctx);
        });
    }

    private boolean anyActive() {
        for (Task t : tasks.values()) {
            if (!t.finished) return true;
        }
        return false;
    }

    private void notifyProgress(Task t) {
        final long done = t.done;
        final long total = t.total;
        DownloadService.update(ctx, notifText(t), t.percent());
        ui.post(() -> {
            synchronized (listeners) {
                for (Listener l : new ArrayList<>(listeners)) l.onProgress(t.model, done, total);
            }
        });
    }

    private String notifText(Task t) {
        int pct = t.percent();
        return pct >= 0
                ? ctx.getString(R.string.dl_notif_progress, t.model.displayName(), pct)
                : t.model.displayName();
    }

    /** GGUF 文件头固定为 "GGUF"，用它挡掉错误页/HTML 等无效内容。 */
    private static boolean isGguf(File f) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] m = new byte[4];
            if (in.read(m) != 4) return false;
            return m[0] == 'G' && m[1] == 'G' && m[2] == 'U' && m[3] == 'F';
        } catch (Exception e) {
            return false;
        }
    }

    /* --------------------------------------------------------- 待续传任务落盘 */

    private static class PendingRec {
        String localPath = "";
        String url = "";
        String owner = "";
        String name = "";
        String filePath = "";
        String displayName = "";
        long size = 0;
    }

    private File pendingFile() {
        return new File(ctx.getFilesDir(), PENDING);
    }

    private synchronized void addPending(Task t) {
        List<PendingRec> all = readPending();
        for (PendingRec r : all) {
            if (r.localPath.equals(t.model.localPath)) return;
        }
        PendingRec r = new PendingRec();
        r.localPath = t.model.localPath;
        r.url = t.url;
        r.owner = t.model.repoOwner;
        r.name = t.model.repoName;
        r.filePath = t.model.filePath;
        r.displayName = t.model.displayName;
        r.size = t.model.size;
        all.add(r);
        writePending(all);
    }

    private synchronized void clearPending(Task t) {
        List<PendingRec> all = readPending();
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).localPath.equals(t.model.localPath)) all.remove(i);
        }
        writePending(all);
    }

    /** 启动时扫描待续传记录：只有确实下过一部分（有非空 .part）才自动继续。 */
    private void resumePending() {
        for (PendingRec r : readPending()) {
            File dst = new File(r.localPath);
            File part = new File(r.localPath + ".part");
            if (dst.exists() && dst.length() > 0) continue;      // 已经下好了
            if (!part.exists() || part.length() == 0) continue;  // 没有断点，交给用户重新点下载
            LocalModel m = new LocalModel();
            m.localPath = r.localPath;
            m.repoOwner = r.owner;
            m.repoName = r.name;
            m.filePath = r.filePath;
            m.displayName = r.displayName;
            m.size = r.size;
            Task t = new Task(m, r.url);
            tasks.put(m.localPath, t);
            executor.execute(() -> run(t));
        }
    }

    private List<PendingRec> readPending() {
        List<PendingRec> out = new ArrayList<>();
        File f = pendingFile();
        if (!f.exists()) return out;
        final long len = f.length();
        if (len <= 0 || len > 1024 * 1024) return out;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) len];
            int n = in.read(buf);
            if (n <= 0) return out;
            JSONObject root = new JSONObject(new String(buf, 0, n, StandardCharsets.UTF_8));
            JSONArray arr = root.optJSONArray("tasks");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                PendingRec r = new PendingRec();
                r.localPath = o.optString("localPath", "");
                r.url = o.optString("url", "");
                r.owner = o.optString("owner", "");
                r.name = o.optString("name", "");
                r.filePath = o.optString("filePath", "");
                r.displayName = o.optString("displayName", "");
                r.size = o.optLong("size", 0);
                if (!r.localPath.isEmpty() && !r.url.isEmpty()) out.add(r);
            }
        } catch (Exception e) {
            Log.w(TAG, "读取下载记录失败", e);
        }
        return out;
    }

    private void writePending(List<PendingRec> all) {
        JSONArray arr = new JSONArray();
        for (PendingRec r : all) {
            JSONObject o = new JSONObject();
            try {
                o.put("localPath", r.localPath);
                o.put("url", r.url);
                o.put("owner", r.owner);
                o.put("name", r.name);
                o.put("filePath", r.filePath);
                o.put("displayName", r.displayName);
                o.put("size", r.size);
            } catch (Exception ignored) {
            }
            arr.put(o);
        }
        JSONObject root = new JSONObject();
        try {
            root.put("tasks", arr);
        } catch (Exception ignored) {
        }
        final byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
        File dst = pendingFile();
        File tmp = new File(dst.getParentFile(), PENDING + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
            out.flush();
        } catch (Exception e) {
            Log.w(TAG, "写入下载记录失败", e);
            return;
        }
        if (tmp.renameTo(dst)) return;
        //noinspection ResultOfMethodCallIgnored
        dst.delete();
        if (tmp.renameTo(dst)) return;
        try (FileOutputStream out = new FileOutputStream(dst)) {
            out.write(bytes);
        } catch (Exception e) {
            Log.w(TAG, "写入下载记录失败", e);
        }
    }

    private static class CancelledException extends Exception {
    }
}