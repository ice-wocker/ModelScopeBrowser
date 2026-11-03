package com.mscope.browser.core;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 双语资源必须逐条对齐。
 *
 * <p>少一条不会崩，只会让那一句在另一种语言界面下显示成别的语言 ——
 * 而没人会为了一个单词去翻资源文件，于是它就一直错着。所以交给测试。
 *
 * <p>从 Gradle 单测的工作目录（模块根目录）往上找资源文件；
 * 找不到就跳过（不是失败），避免换个构建布局就误报。
 */
public class StringsParityTest {

    private static File find(String rel) {
        File dir = new File("").getAbsoluteFile();
        for (int i = 0; i < 4 && dir != null; i++) {
            File f = new File(dir, rel);
            if (f.exists()) return f;
            dir = dir.getParentFile();
        }
        return null;
    }

    @Test
    public void enAndZhStayInSync() throws Exception {
        File en = find("app/src/main/res/values/strings.xml");
        File zh = find("app/src/main/res/values-zh/strings.xml");
        if (en == null || zh == null) {
            en = find("app/src/main/res/values/strings.xml");
            zh = find("app/src/main/res/values-zh/strings.xml");
        }
        assertTrue("找不到 strings.xml 资源文件", en != null && zh != null);
        String a = new String(Files.readAllBytes(en.toPath()), StandardCharsets.UTF_8);
        String b = new String(Files.readAllBytes(zh.toPath()), StandardCharsets.UTF_8);
        LangKey.Diff d = LangKey.diff(a, b);
        assertTrue("双语资源不一致：\n" + d.report(), d.clean());
    }
}
