package com.mscope.browser;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 仓库内文件路径的拆解与体积展示。 */
public class ModelFileTest {

    @Test
    public void displayNameAndDir_withDirectory() {
        ModelFile f = new ModelFile();
        f.path = "sub/dir/model.gguf";
        assertEquals("model.gguf", f.displayName());
        assertEquals("sub/dir/", f.dir());
    }

    @Test
    public void displayNameAndDir_withoutDirectory() {
        ModelFile f = new ModelFile();
        f.path = "model.gguf";
        assertEquals("model.gguf", f.displayName());
        assertEquals("", f.dir());
    }

    @Test
    public void sizeText() {
        ModelFile f = new ModelFile();
        assertEquals("-", f.sizeText());
        f.size = 2048;
        assertEquals("2.0 KB", f.sizeText());
    }
}