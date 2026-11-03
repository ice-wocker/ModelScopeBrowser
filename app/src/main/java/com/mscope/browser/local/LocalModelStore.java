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
    /** 阻止系统媒体扫描器索引模型文件（外部存储目录下动辄几个 GB，扫一遍很贵）。 */
    private static final String NOMEDIA = ".nomedia";

    private final File dir;

    public LocalModelStore(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        dir = new File(base, "models");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "无法创建模型目录: " + dir);
        }
        // 名字以 "." 开头、长度固定：备份规则里按路径前缀排除要靠它，
        // 而 "models" 这种太通用的名字容易和别的东西撞上。
        try {
            //noinspection ResultOfMethodCallIgnored
            new File(dir, NOMEDIA).createNewFile();
        } catch (Exception ignored) {
            // 只是个标记文件，建不出来不影响功能
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
            // 顺手清理已被外部删除的记录，并顺手去重：同一个文件被重复登记（旧版本多线程下
            // 可能写出两条）会让本地模型列表出现两个一模一样的条目。
            if (!f.exists() || f.length() <= 0) continue;
            boolean dup = false;
            for (int j = out.size() - 1; j >= 0; j--) {
                if (out.get(j).localPath.equals(m.localPath)) { dup = true; break; }
            }
            if (!dup) out.add(m);
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
        if (!f.exists()) {
            // 文件已经不在（用户清理过），但索引里可能还留着：不能因为「文件不存在」就跳过删索引，
            // 否则列表里会永远挂着一个点不动的幽灵模型。
            Log.w(TAG, "待删除的模型文件已不存在: " + m.localPath);
        } else if (!f.delete()) {
            Log.w(TAG, "删除模型文件失败: " + m.localPath);
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
            int read = 0, r;                    // read() 可能短读，循环读满再解析
            while (read < buf.length && (r = in.read(buf, read, buf.length - read)) > 0) read += r;
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