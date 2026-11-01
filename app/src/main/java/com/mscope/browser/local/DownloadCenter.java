package com.mscope.browser.local;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.mscope.browser.R;
import com.mscope.browser.core.FsKit;
import com.mscope.browser.core.StorageGuard;

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

import com.mscope.browser.i18n.Lang;

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
        /** 本次连接的进度基线：续传时是已有字节数，用于按「速度窗口」估算瞬时速度。 */
        volatile long base;
        public volatile boolean cancelled;
        public volatile boolean finished;
        /** 正在使用的连接，取消时用来立即打断阻塞的 read。 */
        volatile HttpURLConnection conn;
        /** 创建时刻，用于计算下载速度（仅用于通知文案）。 */
        final long startedAt = System.currentTimeMillis();

        Task(LocalModel m, String url) {
            this.model = m;
            this.url = url;
        }

        public int percent() {
            if (total <= 0) return -1;
            return (int) Math.min(100, done * 100 / total);
        }
    }

    private enum Finish {OK, FAILED, CANCELLED, RESTART}

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

    /**
     * 开始下载。返回 false 表示该文件已有任务在跑。
     *
     * <p>注意顺序：必须先登记任务再投递到线程池。反过来的话，单线程池可能在上面的
     * 代码执行<em>之前</em>就跑完了 run() 并调用 finish() → tasks.remove()，
     * 随后任务又被塞回 tasks，留下一个「永远在下载但什么也没发生」的僵尸任务。</p>
     */
    public boolean start(LocalModel model, String url) {
        final Task t = new Task(model, url);
        if (tasks.putIfAbsent(model.localPath, t) != null) return false;
        try {
            addPending(t);
        } catch (RuntimeException e) {
            tasks.remove(model.localPath, t);
            throw e;
        }
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
        // 这里刻意不预先判断 t.cancelled：start() 与队列执行之间存在间隙，
        // 用户在这个间隙里点取消时，若在这里直接 return，就会留下「已取消但永远不结束」的任务。
        // 取消统一由下面的主流程处理（不会发请求，也不会写文件）。
        final File dst = new File(t.model.localPath);
        final File part = partFileOf(dst);
        HttpURLConnection conn = null;
        try {
            if (t.cancelled) throw new CancelledException();

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
                // 远端可能换了文件（同名不同版本），此时 206 的区间并不是我们以为的那一段。
                // 解析 Content-Range: bytes start-end/total，start 与本地区间对不上就整体重下，
                // 否则会把两代文件拼成一个「校验能过、加载就崩」的畸形 GGUF。
                long[] range = parseContentRange(conn.getHeaderField("Content-Range"));
                if (range != null && range[0] != existing) {
                    Log.w(TAG, "续传区间不连续（本地 " + existing + "，服务端从 " + range[0]
                            + " 开始），改为完整下载");
                    conn.disconnect();
                    //noinspection ResultOfMethodCallIgnored
                    part.delete();
                    throw new RestartException();
                }
                t.total = existing + conn.getContentLengthLong();
                t.done = existing;
                t.base = existing;
            } else {
                existing = 0;                    // 服务端不支持 Range，只能从头下
                t.total = conn.getContentLengthLong();
                t.done = 0;
                t.base = 0;
            }

            DownloadService.start(ctx, notifText(t), t.percent());

            guardSpace(dst, t.done, t.total);

            byte[] buf = new byte[256 * 1024];
            long lastNotify = 0;
            long lastSpaceCheck = System.currentTimeMillis();
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
                        // 总长未知（没有 Content-Length）时开头那次空间检查是跳过的，
                        // 每 5 秒补一次，别等写到分区满才报一个看不懂的 IOException。
                        if (t.total <= 0 && now - lastSpaceCheck > 5000) {
                            lastSpaceCheck = now;
                            guardSpacePartial(dst);
                        }
                    }
                }
                out.flush();
            }

            if (t.cancelled) throw new CancelledException();

            // ---- 完整性校验：长度 + GGUF 魔数 ----
            final long len = part.length();
            if (t.total > 0 && len != t.total) {
                throw new IllegalStateException(Lang.zh()
                        ? "文件不完整 " + len + "/" + t.total
                        : "incomplete file " + len + "/" + t.total);
            }
            if (!isGguf(part)) throw new IllegalStateException(Lang.zh()
                    ? "不是有效的 GGUF 文件" : "not a valid GGUF file");

            if (dst.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dst.delete();
            }
            if (!part.renameTo(dst)) throw new IllegalStateException(Lang.zh()
                    ? "重命名失败" : "rename failed");

            t.model.size = dst.length();
            t.model.localPath = dst.getAbsolutePath();
            t.model.addedAt = System.currentTimeMillis();
            store.add(t.model);

            clearPending(t);
            finish(t, Finish.OK, null);
        } catch (CancelledException ce) {
            // 取消不删 .part、也不清待续传记录：用户点「取消」常常只是想省流量，
            // 下次再点下载应该接着下，而不是从 0 重来几个 GB。
            // 真正想清干净的用户会按「删除」——那条路径会删掉整个模型目录。
            finish(t, Finish.CANCELLED, null);
        } catch (RestartException re) {
            finish(t, Finish.RESTART, null);
        } catch (Exception e) {
            if (t.cancelled) {
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
        if (kind == Finish.RESTART) {
            // 同一 Task 对象直接重排：先摘掉再重新占位，保证任何时刻都不出现两个同路径任务。
            t.conn = null;
            tasks.remove(t.model.localPath, t);
            if (t.cancelled) {
                finish(t, Finish.CANCELLED, null);
                return;
            }
            if (tasks.putIfAbsent(t.model.localPath, t) == null) {
                executor.execute(() -> run(t));
                return;
            }
            // 期间被别人占了：放弃这次重排，交回给新任务
            t.finished = true;
            return;
        }
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
        if (pct < 0) return t.model.displayName();
        final long secs = Math.max(1, (System.currentTimeMillis() - t.startedAt) / 1000);
        // 续传时 t.done 含已有字节，直接除以本次耗时会把速度吹成几倍；
        // 减去基线才是这次真正下载的量。
        final long moved = Math.max(0, t.done - t.base);
        final String speed = android.text.format.Formatter.formatFileSize(ctx, moved / secs) + "/s";
        return ctx.getString(R.string.dl_notif_progress_speed, t.model.displayName(), pct, speed);
    }

    /** 空间体检 + 把结论写成一句人话（差多少 / 下载后还剩多少）。 */
    private void guardSpace(File dst, long done, long total) {
        @SuppressWarnings({"deprecation", "UsableSpace"})
        final long usable = dst.getParentFile() == null ? -1 : dst.getParentFile().getUsableSpace();
        if (usable > 0) guardSpace(usable, Math.max(0, total - done));
    }

    /** 总长未知时没法算「还差多少」，只判断当前剩余空间是否还高于系统安全水位。 */
    private void guardSpacePartial(File dst) {
        @SuppressWarnings({"deprecation", "UsableSpace"})
        final long usable = dst.getParentFile() == null ? -1 : dst.getParentFile().getUsableSpace();
        guardSpace(usable, StorageGuard.HARD_FLOOR_BYTES);
    }

    private void guardSpace(long usable, long need) {
        if (need <= 0) return;
        StorageGuard.Check check = StorageGuard.check(usable, need, 0, Lang.zh());
        if (!check.canStart()) throw new IllegalStateException(check.message);
        if (check.shouldWarn()) Log.w(TAG, check.message);
    }

    /** 断点临时文件：与目标同目录、同名前缀，保证重启后还能找回上一次的进度。 */
    private static File partFileOf(File dst) {
        return new File(dst.getParentFile(), dst.getName() + ".part");
    }

    /** 校验真的是 GGUF：读文件头（magic "GGUF"）而不是只看后缀，挡住 HTML 错误页与半截文件。 */
    private static boolean isGguf(File f) {
        return FsKit.sniff(f).matches;
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
            File part = partFileOf(dst);
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
            int n = 0, got;                     // read() 可能短读，循环读满再解析
            while (n < buf.length && (got = in.read(buf, n, buf.length - n)) > 0) n += got;
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

    /** 续传区间对不上，需要丢弃断点从零重下（不对外抛）。 */
    private static class RestartException extends Exception {
    }

    /** 解析 {@code Content-Range: bytes 1024-2047/4096}，返回 {start, end, total}；认不出返回 null。 */
    static long[] parseContentRange(String header) {
        if (header == null) return null;
        String h = header.trim();
        int sp = h.indexOf(' ');
        if (sp >= 0) h = h.substring(sp + 1).trim();          // 去掉 "bytes"
        int slash = h.indexOf('/');
        int dash = h.indexOf('-');
        if (dash <= 0 || slash < 0) return null;
        try {
            long start = Long.parseLong(h.substring(0, dash).trim());
            long end = Long.parseLong(h.substring(dash + 1, slash).trim());
            String totalStr = h.substring(slash + 1).trim();
            long total = "*".equals(totalStr) ? -1 : Long.parseLong(totalStr);
            return new long[]{start, end, total};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}