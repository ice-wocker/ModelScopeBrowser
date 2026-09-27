package com.mscope.browser.local;

import com.mscope.browser.Format;

/** 一个已下载到本机的 GGUF 模型。 */
public class LocalModel {

    public String repoOwner = "";
    public String repoName = "";
    public String filePath = "";      // 仓库内路径，如 Qwen3-0.6B-Q4_K_M.gguf
    public String displayName = "";
    public String localPath = "";     // 本机绝对路径
    public long size = 0;
    public long addedAt = 0;

    /** 从文件名里猜量化类型，如 Q4_K_M。 */
    public String quant() {
        String n = displayName == null ? "" : displayName.toUpperCase();
        String[] keys = {"IQ1_S", "IQ2_XXS", "IQ2_XS", "IQ2_S", "IQ2_M", "IQ3_XXS", "IQ3_XS", "IQ3_S", "IQ3_M",
                "Q2_K", "Q3_K_S", "Q3_K_M", "Q3_K_L", "Q4_0", "Q4_1", "Q4_K_S", "Q4_K_M", "Q5_0", "Q5_1",
                "Q5_K_S", "Q5_K_M", "Q6_K", "Q8_0", "F16", "BF16", "F32"};
        for (String k : keys) {
            if (n.contains(k)) return k;
        }
        return "";
    }

    public String repoFullName() {
        return repoOwner.isEmpty() ? repoName : repoOwner + "/" + repoName;
    }

    /** 展示名：优先用索引里的名称，缺失时退回仓库内文件名。 */
    public String displayName() {
        if (displayName != null && !displayName.isEmpty()) return displayName;
        int i = filePath.lastIndexOf('/');
        String n = i >= 0 ? filePath.substring(i + 1) : filePath;
        return n.isEmpty() ? "本地模型" : n;
    }

    public String sizeText() {
        return Format.sizeOrDash(size);
    }
}