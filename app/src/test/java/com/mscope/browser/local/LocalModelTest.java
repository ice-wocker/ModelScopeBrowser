package com.mscope.browser.local;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 本地模型的量化识别、展示名回退与体积文案。 */
public class LocalModelTest {

    @Test
    public void quant_recognizesCommonTypes() {
        LocalModel m = new LocalModel();
        m.displayName = "Qwen3-0.6B-Q4_K_M.gguf";
        assertEquals("Q4_K_M", m.quant());

        m.displayName = "model-Q8_0.gguf";
        assertEquals("Q8_0", m.quant());
    }

    @Test
    public void quant_emptyWhenUnknown() {
        LocalModel m = new LocalModel();
        m.displayName = "model.gguf";
        assertEquals("", m.quant());
    }

    @Test
    public void repoFullName() {
        LocalModel m = new LocalModel();
        m.repoName = "Qwen3";
        assertEquals("Qwen3", m.repoFullName());
        m.repoOwner = "Qwen";
        assertEquals("Qwen/Qwen3", m.repoFullName());
    }

    @Test
    public void displayName_fallsBackToFileName() {
        LocalModel m = new LocalModel();
        m.filePath = "sub/x.gguf";
        assertEquals("x.gguf", m.displayName());
    }

    @Test
    public void sizeText() {
        LocalModel m = new LocalModel();
        assertEquals("-", m.sizeText());
        m.size = 1024;
        assertEquals("1.0 KB", m.sizeText());
    }
}