package com.mscope.browser.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.mscope.browser.i18n.Lang;

import org.junit.After;
import org.junit.Test;

import java.util.List;
import java.util.Locale;

/**
 * 双语行为的约定。
 *
 * <p>这类问题的特点是「不会编译失败、界面上也不难看」，只是英文用户点开一看全是中文 ——
 * 所以必须用手册化的断言钉住：同一条逻辑换语言后要给两种文案，且不能混着来。
 */
public class Bi18nTest {

    @After
    public void reset() {
        Lang.followSystem();
    }

    /* ------------------------------------------------------------------ Lang */

    @Test
    public void lang_followsSystemLocaleByDefault() {
        Locale old = Locale.getDefault();
        try {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
            Lang.followSystem();
            assertTrue(Lang.zh());

            Locale.setDefault(Locale.US);
            Lang.followSystem();
            assertFalse(Lang.zh());
        } finally {
            Locale.setDefault(old);
        }
    }

    /** 显式设置要能压过系统 locale（应用内切换就是靠这个）。 */
    @Test
    public void lang_explicitOverridesSystem() {
        Locale old = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            Lang.setChinese(true);
            assertTrue(Lang.zh());
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
            Lang.setChinese(false);
            assertFalse(Lang.zh());
        } finally {
            Locale.setDefault(old);
            Lang.followSystem();
        }
    }

    /* -------------------------------------------------------- PromptTemplate */

    @Test
    public void promptTemplate_everyPresetHasBothLanguages() {
        for (PromptTemplate.Preset p : PromptTemplate.Preset.values()) {
            String zh = PromptTemplate.body(p, true);
            String en = PromptTemplate.body(p, false);
            assertFalse(p + " zh empty", zh.isEmpty());
            assertFalse(p + " en empty", en.isEmpty());
            assertFalse(p + " en equals zh", zh.equals(en));
            // 英文版不该混进中文，中文版也不该把整段漏成英文
            assertFalse(p + " en contains CJK", containsCjk(en));
            assertTrue(p + " zh lacks CJK", containsCjk(zh));
        }
    }

    @Test
    public void promptTemplate_toolSectionFollowsLanguage() {
        String list = "shell(cmd) —— run\n";
        String zh = PromptTemplate.toolSection(list, false, true);
        String en = PromptTemplate.toolSection(list, false, false);
        assertTrue(zh.contains("可用工具"));
        assertTrue(en.contains("Available tools"));
        assertFalse(en.contains("可用工具"));

        String zhMin = PromptTemplate.toolSection(list, true, true);
        String enMin = PromptTemplate.toolSection(list, true, false);
        assertTrue(zhMin.contains("要动手就输出"));
        assertTrue(enMin.contains("To act"));
    }

    /** 工具清单本身是按当前语言生成的，压缩版只保留「名字(签名)」部分，两种语言都要能截断。 */
    @Test
    public void promptTemplate_memoryHeaderFollowsLanguage() {
        Lang.setChinese(true);
        assertTrue(PromptTemplate.compose("", PromptTemplate.Preset.GENERAL, null,
                java.util.Collections.singletonList("be brief")).contains("用户长期偏好"));
        Lang.setChinese(false);
        assertTrue(PromptTemplate.compose("", PromptTemplate.Preset.GENERAL, null,
                java.util.Collections.singletonList("be brief")).contains("Long-term user preferences"));
    }

    /* ------------------------------------------------------------ Validators */

    @Test
    public void validators_notesFollowLanguage() {
        Validators.Sampling zh = new Validators.Sampling(999999, 9f, 0f, 0, 999999);
        String zhNote = Validators.normalize(zh, true);
        assertTrue(containsCjk(zhNote));
        assertTrue(zhNote.endsWith("已改回 1"));      // 结尾的分隔符被去掉，不留「；」

        Validators.Sampling en = new Validators.Sampling(999999, 9f, 0f, 0, 999999);
        String enNote = Validators.normalize(en, false);
        assertFalse(containsCjk(enNote));
        assertTrue(enNote.contains("top-p"));
        assertFalse(enNote.endsWith(";"));
    }

    @Test
    public void validators_sameNumbersEitherLanguage() {
        Validators.Sampling zh = new Validators.Sampling(999999, 9f, 0f, 0, 999999);
        Validators.Sampling en = zh;
        Validators.normalize(zh, true);
        Validators.Sampling en2 = new Validators.Sampling(999999, 9f, 0f, 0, 999999);
        Validators.normalize(en2, false);
        assertEquals(zh.nCtx, en2.nCtx);
        assertEquals(zh.maxTokens, en2.maxTokens);
        assertEquals(zh.temp, en2.temp, 0.0001);
        assertEquals(zh.topK, en2.topK);
    }

    /* ---------------------------------------------------------- StorageGuard */

    @Test
    public void storageGuard_bothLanguagesCarryTheNumbers() {
        StorageGuard.Check zh = StorageGuard.check(300L << 20, 900L << 20, 0, true);
        StorageGuard.Check en = StorageGuard.check(300L << 20, 900L << 20, 0, false);
        assertEquals(zh.verdict, en.verdict);
        assertTrue(containsCjk(zh.message));
        assertFalse(containsCjk(en.message));
        // 体积文案两边都要在，不然用户不知道差多少
        assertTrue(zh.message.contains("MB"));
        assertTrue(en.message.contains("MB"));
    }

    @Test
    public void storageGuard_summaryBothLanguages() {
        List<StorageGuard.Entry> entries = java.util.Collections.singletonList(
                new StorageGuard.Entry("/m/gone.gguf", 100));
        List<StorageGuard.Entry> existing = java.util.Collections.emptyList();
        List<StorageGuard.Finding> f = StorageGuard.audit(entries, existing);
        assertNotNull(StorageGuard.summarize(f, true));
        assertTrue(StorageGuard.summarize(f, true).contains("本地模型库体检"));
        assertTrue(StorageGuard.summarize(f, false).contains("Local model library check"));
        assertEquals("", StorageGuard.summarize(null, true));
    }

    @Test
    public void storageGuard_humanUnknown() {
        assertEquals("未知", StorageGuard.human(-1, true));
        assertEquals("unknown", StorageGuard.human(-1, false));
    }

    /* --------------------------------------------------------- Default switch */

    @Test
    public void defaultsToCurrentLang() {
        Lang.setChinese(false);
        assertFalse(containsCjk(PromptTemplate.body(PromptTemplate.Preset.GENERAL)));
        Lang.setChinese(true);
        assertTrue(containsCjk(PromptTemplate.body(PromptTemplate.Preset.GENERAL)));
    }

    private static boolean containsCjk(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) return true;
        }
        return false;
    }
}
