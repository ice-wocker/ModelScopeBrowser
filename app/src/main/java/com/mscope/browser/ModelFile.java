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
        return Format.sizeOrDash(size);
    }
}