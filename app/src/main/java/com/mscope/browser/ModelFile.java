package com.mscope.browser;

/** 模型仓库中的一个文件。 */
public class ModelFile {

    public String path = "";
    public long size = 0;
    public boolean lfs = false;

    public String displayName() {
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }

    public String dir() {
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(0, i + 1) : "";
    }

    public String sizeText() {
        if (size <= 0) return "-";
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double v = size;
        int u = 0;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return (u == 0 ? String.valueOf((long) v) : String.format(java.util.Locale.CHINA, "%.1f", v)) + " " + units[u];
    }
}