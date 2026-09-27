package com.mscope.browser.local;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 本地模型库：模型文件与索引都放在 App 私有目录，无需任何存储权限。 */
public class LocalModelStore {

    private static final String TAG = "LocalModelStore";
    private static final String INDEX = "index.json";

    private final File dir;

    public LocalModelStore(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        dir = new File(base, "models");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "无法创建模型目录: " + dir);
        }
    }

    public File dir() {
        return dir;
    }

    public File fileOf(String fileName) {
        return new File(dir, fileName);
    }

    public synchronized List<LocalModel> list() {
        List<LocalModel> out = new ArrayList<>();
        JSONArray arr = readIndex();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            LocalModel m = new LocalModel();
            m.repoOwner = o.optString("owner", "");
            m.repoName = o.optString("name", "");
            m.filePath = o.optString("filePath", "");
            m.displayName = o.optString("displayName", "");
            m.localPath = o.optString("localPath", "");
            m.size = o.optLong("size", 0);
            m.addedAt = o.optLong("addedAt", 0);
            File f = new File(m.localPath);
            if (f.exists() && f.length() > 0) out.add(m);   // 顺手清理已被外部删除的记录
        }
        if (out.size() != arr.length()) writeIndex(out);
        return out;
    }

    public synchronized void add(LocalModel m) {
        List<LocalModel> all = list();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).localPath.equals(m.localPath)) {
                all.set(i, m);
                writeIndex(all);
                return;
            }
        }
        all.add(0, m);
        writeIndex(all);
    }

    public synchronized void remove(LocalModel m) {
        File f = new File(m.localPath);
        if (f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        List<LocalModel> all = list();
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).localPath.equals(m.localPath)) all.remove(i);
        }
        writeIndex(all);
    }

    public long totalSize() {
        long n = 0;
        for (LocalModel m : list()) n += m.size;
        return n;
    }

    /** 剩余可用空间（字节）。 */
    public long freeSpace() {
        try {
            return dir.getUsableSpace();
        } catch (Exception e) {
            return -1;
        }
    }

    public static boolean isExternalMounted() {
        return Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState());
    }

    /* ---------------------------------------------------------------- 索引 */

    private File indexFile() {
        return new File(dir, INDEX);
    }

    private JSONArray readIndex() {
        File f = indexFile();
        if (!f.exists()) return new JSONArray();
        final long len = f.length();
        // 索引不可能这么大；异常值（损坏/被截断）直接当空，避免 int 溢出或 OOM
        if (len <= 0 || len > 8L * 1024 * 1024) return new JSONArray();
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[(int) len];
            int read = in.read(buf);
            if (read <= 0) return new JSONArray();
            return new JSONArray(new String(buf, 0, read, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "读取索引失败", e);
            return new JSONArray();
        }
    }

    private void writeIndex(List<LocalModel> models) {
        JSONArray arr = new JSONArray();
        for (LocalModel m : models) {
            JSONObject o = new JSONObject();
            try {
                o.put("owner", m.repoOwner);
                o.put("name", m.repoName);
                o.put("filePath", m.filePath);
                o.put("displayName", m.displayName);
                o.put("localPath", m.localPath);
                o.put("size", m.size);
                o.put("addedAt", m.addedAt);
            } catch (Exception ignored) {
            }
            arr.put(o);
        }
        final byte[] bytes = arr.toString().getBytes(StandardCharsets.UTF_8);
        final File dst = indexFile();
        final File tmp = new File(dir, INDEX + ".tmp");
        // 先写临时文件再原子改名：中途崩溃也不会留下半截索引（否则本地模型会「全部消失」）
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
            out.flush();
        } catch (Exception e) {
            Log.w(TAG, "写入索引（临时文件）失败", e);
            return;
        }
        if (tmp.renameTo(dst)) return;
        // 个别文件系统不支持覆盖式改名，退回「删除后改名」，最后才直接覆盖写
        //noinspection ResultOfMethodCallIgnored
        dst.delete();
        if (tmp.renameTo(dst)) return;
        try (FileOutputStream out = new FileOutputStream(dst)) {
            out.write(bytes);
        } catch (Exception e) {
            Log.w(TAG, "写入索引失败", e);
        }
    }
}