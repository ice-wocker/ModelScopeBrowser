package com.mscope.browser.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.mscope.browser.i18n.Lang;

import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** core 包的行为约定：改坏了不会编译失败，只会在真机上炸。 */
public class CoreKitTest {

    /**
     * 这些用例断言的是中文文案，必须显式把语言钉住 —— 否则在英文 CI 机器上跑，
     * 断言会挂在「文案本来就是英文」上，看起来像功能坏了。
     */
    @After
    public void resetLang() {
        Lang.followSystem();
    }

    @Test
    public void parseMeminfo_readsTotalAndAvailable() {
        String text = "MemTotal:        7825432 kB\nMemFree: 123456 kB\nMemAvailable: 3123456 kB\n";
        MemoryBudget.Snapshot s = MemoryBudget.parseMeminfo(text);
        assertEquals(7825432L, s.totalKb);
        assertEquals(3123456L, s.availableKb);
    }

    @Test
    public void parseMeminfo_fallsBackToMemFree() {
        assertEquals(40L, MemoryBudget.parseMeminfo("MemTotal: 100 kB\nMemFree: 40 kB\n").availableKb);
    }

    @Test
    public void parseMeminfo_handlesGarbage() {
        MemoryBudget.Snapshot s = MemoryBudget.parseMeminfo("nonsense\n\n::: 12");
        assertEquals(0L, s.totalKb);
        assertEquals(0L, s.availableKb);
    }

    @Test
    public void estimateExtraKb_growsDenserWhenNotQuantized() {
        long q8 = MemoryBudget.estimateExtraKb(8192, true);
        long fp16 = MemoryBudget.estimateExtraKb(8192, false);
        assertTrue(fp16 > q8);
        assertEquals(0, MemoryBudget.estimateExtraKb(0, true));
    }

    @Test
    public void suggestCtx_smallDeviceNeverSuggestsHugeCtx() {
        MemoryBudget.Snapshot mem = new MemoryBudget.Snapshot(2L * 1024 * 1024, 900 * 1024, 0);
        int ctx = MemoryBudget.suggestCtx(mem, 900L * 1024 * 1024, true, 8192);
        assertTrue("ctx=" + ctx, ctx <= 8192 && ctx >= 512);
    }

    @Test
    public void suggestCtx_bigDeviceUsesDefault() {
        MemoryBudget.Snapshot mem = new MemoryBudget.Snapshot(12L * 1024 * 1024, 8L * 1024 * 1024, 0);
        int ctx = MemoryBudget.suggestCtx(mem, 2L * 1024 * 1024 * 1024, true, 8192);
        assertTrue("got " + ctx, ctx >= 8192);
    }

    @Test
    public void suggestCtx_noMeminfoUsesFallback() {
        assertEquals(4096, MemoryBudget.suggestCtx(null, 0, true, 4096));
    }

    @Test
    public void suggestCtx_tightMemoryGoesMinimal() {
        MemoryBudget.Snapshot mem = new MemoryBudget.Snapshot(1024 * 1024, 200 * 1024, 0);
        assertEquals(512, MemoryBudget.suggestCtx(mem, 1024L * 1024 * 1024, true, 8192));
    }

    @Test
    public void check_criticalWhenAlmostFull() {
        StorageGuard.Check c = StorageGuard.check(10L * 1024 * 1024, 1024, 0);
        assertEquals(StorageGuard.Verdict.CRITICAL, c.verdict);
        assertFalse(c.canStart());
    }

    @Test
    public void check_insufficientWhenNeedMoreThanFree() {
        Lang.setChinese(true);
        StorageGuard.Check c = StorageGuard.check(500L * 1024 * 1024, 800L * 1024 * 1024, 0);
        assertEquals(StorageGuard.Verdict.INSUFFICIENT, c.verdict);
        assertFalse(c.canStart());
        assertTrue(c.message.contains("还差"));
    }

    @Test
    public void check_tightWhenBelowSafetyMargin() {
        StorageGuard.Check c = StorageGuard.check(1024L * 1024 * 1024, 900L * 1024 * 1024, 0);
        assertEquals(StorageGuard.Verdict.TIGHT, c.verdict);
        assertTrue(c.canStart());
        assertTrue(c.shouldWarn());
    }

    @Test
    public void check_okWithResumeHint() {
        Lang.setChinese(true);
        StorageGuard.Check c = StorageGuard.check(8L * 1024 * 1024 * 1024, 1024L * 1024 * 1024,
                512L * 1024 * 1024);
        assertEquals(StorageGuard.Verdict.OK, c.verdict);
        assertTrue(c.message.contains("已下载"));
    }

    @Test
    public void human_isReadable() {
        Lang.setChinese(true);
        assertEquals("512 B", StorageGuard.human(512));
        assertEquals("1.0 MB", StorageGuard.human(1024L * 1024));
        assertTrue(StorageGuard.human(3L * 1024 * 1024 * 1024).endsWith("GB"));
        assertEquals("未知", StorageGuard.human(-1));
    }

    @Test
    public void audit_findsGhostOrphanAndEmpty() {
        Lang.setChinese(true);
        List<StorageGuard.Entry> entries = Arrays.asList(
                new StorageGuard.Entry("/m/a.gguf", 100),
                new StorageGuard.Entry("/m/ghost.gguf", 200),
                new StorageGuard.Entry("/m/empty.gguf", 0));
        List<StorageGuard.Entry> existing = Arrays.asList(
                new StorageGuard.Entry("/m/a.gguf", 100),
                new StorageGuard.Entry("/m/empty.gguf", 0),
                new StorageGuard.Entry("/m/orphan.gguf", 300));
        List<StorageGuard.Finding> f = StorageGuard.audit(entries, existing);
        assertEquals(3, f.size());
        String sum = StorageGuard.summarize(f);
        assertTrue(sum.contains("1 条记录找不到文件"));
        assertTrue(sum.contains("1 个文件没被索引"));
        assertTrue(sum.contains("1 个空文件"));
    }

    @Test
    public void audit_cleanLibraryIsSilent() {
        List<StorageGuard.Entry> one = Arrays.asList(new StorageGuard.Entry("/m/a.gguf", 1));
        assertTrue(StorageGuard.summarize(StorageGuard.audit(one, one)).isEmpty());
        assertTrue(StorageGuard.summarize(null).isEmpty());
    }

    @Test
    public void trimUtf8_neverCutsThroughChar() {
        String s = "中文中文中文";
        for (int i = 0; i <= s.getBytes(StandardCharsets.UTF_8).length; i++) {
            String t = TextKit.trimUtf8(s, i);
            assertTrue(t.getBytes(StandardCharsets.UTF_8).length <= i);
            assertFalse("replacement mark in: " + t, t.contains("\uFFFD"));
        }
    }

    @Test
    public void endsWithCompleteChar_detectsSplitHanzi() {
        byte[] han = "中".getBytes(StandardCharsets.UTF_8);
        assertFalse(TextKit.endsWithCompleteChar(han, 1));
        assertFalse(TextKit.endsWithCompleteChar(han, 2));
        assertTrue(TextKit.endsWithCompleteChar(han, 3));
        assertTrue(TextKit.endsWithCompleteChar(new byte[0], 0));
    }

    @Test
    public void safeFileName_stripsIllegalChars() {
        assertEquals("a_b_c", TextKit.safeFileName("a/b:c", 64));
        assertEquals("index.html", TextKit.safeFileName("  index.html  ", 64));
        assertEquals("_con", TextKit.safeFileName("con", 64));
        assertEquals("", TextKit.safeFileName("   ", 64));
        assertEquals("", TextKit.safeFileName(null, 64));
        assertFalse(TextKit.safeFileName("a\u0000b\nc", 64).contains("\u0000"));
    }

    @Test
    public void safeFileName_truncatesLongNamesWithHash() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) sb.append('中');
        String name = TextKit.safeFileName(sb.toString(), 40);
        assertTrue(name.getBytes(StandardCharsets.UTF_8).length <= 48);
        assertTrue(name.contains("~"));
    }

    @Test
    public void shortHash_isStable() {
        assertEquals(TextKit.shortHash("abc", 8), TextKit.shortHash("abc", 8));
        assertFalse(TextKit.shortHash("abc", 8).equals(TextKit.shortHash("abd", 8)));
        assertEquals(4, TextKit.shortHash("abc", 4).length());
    }

    @Test
    public void roughTokens_countsCjkHeavier() {
        assertTrue(TextKit.roughTokens("你好世界") >= 4);
        assertTrue(TextKit.roughTokens("hello world") < TextKit.roughTokens("你好世界你好世界"));
        assertEquals(0, TextKit.roughTokens(""));
    }

    @Test
    public void sentences_and_completeness() {
        assertEquals(3, TextKit.sentences("第一句。第二句！没有标点").size());
        assertTrue(TextKit.looksComplete("写完了。"));
        assertTrue(TextKit.looksComplete("```code```"));
        assertFalse(TextKit.looksComplete("写到一半"));
        assertFalse(TextKit.looksComplete(""));
    }

    @Test
    public void guessBillionParams_readsModelName() {
        assertEquals(0.5, PromptTemplate.guessBillionParams("Qwen2.5-0.5B-Instruct-Q4_K_M.gguf"), 0.001);
        assertEquals(1.5, PromptTemplate.guessBillionParams("Qwen2.5-1.5B-Instruct"), 0.001);
        assertEquals(7.0, PromptTemplate.guessBillionParams("qwen2.5-7b-instruct"), 0.001);
        assertEquals(0.0, PromptTemplate.guessBillionParams("model-fp16.gguf"), 0.001);
    }

    @Test
    public void guessPreset_picksBySizeAndKind() {
        assertEquals(PromptTemplate.Preset.MINIMAL, PromptTemplate.guessPreset("Qwen2.5-0.5B"));
        assertEquals(PromptTemplate.Preset.CODER, PromptTemplate.guessPreset("Qwen3-Coder-8B"));
        assertEquals(PromptTemplate.Preset.AGENT, PromptTemplate.guessPreset("DeepSeek-R1-7B"));
    }

    @Test
    public void toolSection_compactKeepsNamesOnly() {
        Lang.setChinese(true);
        String list = "shell(命令) —— 在手机真终端执行\ntime(空) —— 取当前时间\n";
        String compact = PromptTemplate.toolSection(list, true);
        assertTrue(compact.contains("shell(命令)"));
        assertFalse(compact.contains("真终端"));
        assertTrue(compact.contains("time(空)"));
        String full = PromptTemplate.toolSection(list, false);
        assertTrue(full.contains("真终端"));
        assertTrue(full.contains("<function name="));
    }

    @Test
    public void compose_includesMemoryAndDedups() {
        Lang.setChinese(true);
        String out = PromptTemplate.compose("", PromptTemplate.Preset.AGENT, null,
                Arrays.asList("回答用中文", "  ", null));
        assertTrue(out.contains(PromptTemplate.body(PromptTemplate.Preset.AGENT)));
        assertTrue(out.contains("用户长期偏好"));
        assertTrue(out.contains("回答用中文"));
    }

    @Test
    public void sanitizeMemory_capsCountAndLength() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) many.add("item" + i);
        assertEquals(PromptTemplate.MAX_MEMORY_ITEMS, PromptTemplate.sanitizeMemory(many).size());
        StringBuilder longOne = new StringBuilder();
        for (int i = 0; i < 500; i++) longOne.append('x');
        String single = PromptTemplate.sanitizeMemory(Arrays.asList(longOne.toString())).get(0);
        assertTrue(single.length() <= PromptTemplate.MAX_MEMORY_ITEM + 1);
    }

    @Test
    public void compose_userPromptOverridesPreset() {
        Lang.setChinese(true);
        String out = PromptTemplate.compose("我是自定义的", PromptTemplate.Preset.AGENT, null, null);
        assertTrue(out.startsWith("我是自定义的"));
        assertFalse(out.contains("可以调用工具在本机上完成实际任务"));
    }

    @Test
    public void resolveInside_blocksTraversal() throws IOException {
        File root = Files.createTempDirectory("ws").toFile();
        assertNotNull(FsKit.resolveInside(root, "a/b.txt"));
        assertNull(FsKit.resolveInside(root, "../x"));
        assertNull(FsKit.resolveInside(root, "/etc/passwd"));
        assertNull(FsKit.resolveInside(root, " "));
        assertNull(FsKit.resolveInside(root, null));
        assertEquals(root.getCanonicalFile(), FsKit.resolveInside(root, ".").getCanonicalFile());
        assertTrue(FsKit.deleteTree(root, null));
    }

    @Test
    public void writeAtomic_leavesNoTempFile() throws IOException {
        File dir = Files.createTempDirectory("atomic").toFile();
        File target = new File(dir, "a.txt");
        assertTrue(FsKit.writeAtomic(target, "hello"));
        assertEquals("hello", FsKit.readText(target, 1024));
        assertTrue(FsKit.writeAtomic(target, "world"));
        assertEquals("world", FsKit.readText(target, 1024));
        assertFalse(new File(dir, "a.txt.tmp").exists());
        assertTrue(FsKit.deleteTree(dir, null));
    }

    @Test
    public void readText_truncatesHugeFile() throws IOException {
        Lang.setChinese(true);
        File dir = Files.createTempDirectory("big").toFile();
        File f = new File(dir, "big.txt");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) sb.append('x');
        assertTrue(FsKit.writeAtomic(f, sb.toString()));
        String out = FsKit.readText(f, 1024);
        assertTrue(out.contains("已截断"));
        assertTrue(out.length() < 1100);
        assertTrue(FsKit.deleteTree(dir, null));
    }

    @Test
    public void sniff_detectsGgufAndHtml() throws IOException {
        File dir = Files.createTempDirectory("sniff").toFile();
        File gguf = new File(dir, "m.gguf");
        assertTrue(FsKit.writeAtomic(gguf, new byte[]{'G', 'G', 'U', 'F', 3, 0, 0, 0}));
        assertTrue(FsKit.sniff(gguf).matches);
        assertEquals("gguf", FsKit.sniff(gguf).kind);

        File html = new File(dir, "fake.gguf");
        assertTrue(FsKit.writeAtomic(html, "<!DOCTYPE html><html>404</html>"));
        assertFalse(FsKit.sniff(html).matches);
        assertEquals("text", FsKit.sniff(html).kind);

        File empty = new File(dir, "e.gguf");
        assertTrue(FsKit.writeAtomic(empty, ""));
        assertEquals("empty", FsKit.sniff(empty).kind);
        assertEquals("missing", FsKit.sniff(new File(dir, "nope")).kind);
        assertTrue(FsKit.deleteTree(dir, null));
    }

    @Test
    public void statDir_countsRecursively() throws IOException {
        File root = Files.createTempDirectory("stat").toFile();
        assertTrue(FsKit.writeAtomic(new File(root, "a.txt"), "12345"));
        assertTrue(FsKit.writeAtomic(new File(root, "sub/b.txt"), "123"));
        FsKit.DirStat st = FsKit.statDir(root, "");
        assertEquals(2, st.files);
        assertEquals(1, st.dirs);
        assertEquals(8, st.bytes);
        assertTrue(st.maxDepth >= 1);
        assertTrue(st.biggest.contains("a.txt"));
        assertTrue(FsKit.deleteTree(root, null));
    }

    @Test
    public void normalize_capsOutputAtThreeQuartersCtx() {
        Validators.Sampling s = new Validators.Sampling(8192, 0.7f, 0.9f, 40, 1024);
        String note = Validators.normalize(s);
        assertEquals(768, s.maxTokens);
        assertTrue(note.contains("3/4"));
    }

    @Test
    public void normalize_clampsEverything() {
        Validators.Sampling s = new Validators.Sampling(-5, 9f, -1f, 9999, 999999);
        Validators.normalize(s);
        assertEquals(Validators.CTX_MAX, s.nCtx);
        assertTrue(s.maxTokens >= 1);
        assertEquals(Validators.TEMP_MAX, s.temp, 0.001f);
        assertEquals(200, s.topK);
        assertEquals(1f, s.topP, 0.001f);
    }

    @Test
    public void normalize_greedyNeedsTopK() {
        Validators.Sampling s = new Validators.Sampling(512, 0f, 1f, 0, 8192);
        String note = Validators.normalize(s);
        assertEquals(1, s.topK);
        assertTrue(note.contains("top-k"));
    }

    @Test
    public void normalize_cleanInputIsSilent() {
        Validators.Sampling s = new Validators.Sampling(1024, 0.7f, 0.9f, 40, 8192);
        assertEquals("", Validators.normalize(s));
    }

    @Test
    public void parseNumbers_toleratesNoise() {
        assertEquals(123, Validators.parseInt(" x123abc", 0));
        assertEquals(7, Validators.parseInt("x", 7));
        assertEquals(0.5f, Validators.parseFloat("0,5", 0f), 0.001f);
        assertEquals(3f, Validators.parseFloat("nan-ish", 3f), 0.001f);
    }

    @Test
    public void cleanQuery_collapsesWhitespace() {
        assertEquals("科技 新闻", Validators.cleanQuery("  科技\n\t新闻  ", 100));
        assertEquals(3, Validators.cleanQuery("abcdefgh", 3).length());
        assertEquals("", Validators.cleanQuery(null, 10));
    }

    @Test
    public void safeUrl_onlyAllowsHttp() {
        assertEquals("https://a.com/x", Validators.safeUrl("https://a.com/x"));
        assertNull(Validators.safeUrl("javascript:alert(1)"));
        assertNull(Validators.safeUrl("file:///data/data/x"));
        assertNull(Validators.safeUrl("intent://x"));
        assertNull(Validators.safeUrl("  "));
        assertEquals("https://a.com/b", Validators.safeUrl("https://a.com/b\n"));
    }

    @Test
    public void hostAndPrivateDetection() {
        assertEquals("a.com", Validators.hostOf("https://user:pw@a.com:8080/x"));
        assertEquals("", Validators.hostOf("nope"));
        assertTrue(Validators.isPrivateHost("localhost"));
        assertTrue(Validators.isPrivateHost("192.168.1.3"));
        assertTrue(Validators.isPrivateHost("172.16.0.1"));
        assertFalse(Validators.isPrivateHost("172.32.0.1"));
        assertFalse(Validators.isPrivateHost("modelscope.cn"));
    }

    @Test
    public void perfStats_aggregates() {
        PerfStats st = new PerfStats(3);
        st.add(new PerfStats.Sample(100, 500, 10, 1));
        st.add(new PerfStats.Sample(100, 400, 12, 2));
        assertEquals(2, st.size());
        assertEquals(12, st.lastTps(), 0.001);
        assertEquals(11, st.meanTps(), 0.001);
        assertEquals(450, st.meanPrefillMs());
        st.add(new PerfStats.Sample(100, 500, 11, 3));
        st.add(new PerfStats.Sample(100, 500, 13, 4));
        assertEquals(3, st.size());
        st.clear();
        assertEquals(0, st.size());
        assertEquals("", st.summary());
    }

    @Test
    public void perfStats_detectsDegradation() {
        Lang.setChinese(true);
        PerfStats st = new PerfStats(8);
        for (int i = 0; i < 4; i++) st.add(new PerfStats.Sample(50, 300, 20, i));
        assertFalse(st.degraded());
        st.add(new PerfStats.Sample(50, 300, 4, 5));
        assertTrue(st.degraded());
        assertTrue(st.advice().contains("后台"));
        assertTrue(st.percentileTps(0.5) > 0);
    }

    @Test
    public void perfStats_ignoresZeroSamples() {
        PerfStats st = new PerfStats(4);
        st.add(new PerfStats.Sample(0, 0, 0, 0));
        assertEquals(0, st.meanTps(), 0.001);
        assertFalse(st.degraded());
    }

    @Test
    public void outline_skipsFencedCode() {
        String md = "# 标题一\n```bash\n# 不是标题\n```\n## 标题二\n####### 太深\n##\n";
        List<MarkdownOutline.Heading> h = MarkdownOutline.extract(md);
        assertEquals(2, h.size());
        assertEquals(1, h.get(0).level);
        assertEquals("标题一", h.get(0).text);
        assertEquals(2, h.get(1).level);
    }

    @Test
    public void outline_stripsInlineMarks() {
        List<MarkdownOutline.Heading> h = MarkdownOutline.extract("## **粗** `代码` [链接](http://x)\n");
        assertEquals(1, h.size());
        assertEquals("粗 代码 链接", h.get(0).text);
    }

    @Test
    public void outline_worthFlag() {
        assertFalse(MarkdownOutline.worthOutline("# 短\n"));
        assertFalse(MarkdownOutline.worthOutline(null));
        StringBuilder sb = new StringBuilder("# A\n");
        for (int i = 0; i < 400; i++) sb.append("内容内容。");
        sb.append("\n# B\n");
        assertTrue(MarkdownOutline.worthOutline(sb.toString()));
    }

    /* ------------------------------------------------------- UTF-8 安全截断 */

    @Test
    public void trimUtf8_neverSplitsSurrogatePair() {
        // emoji 是 4 字节序列：上限落在它中间时必须整体丢掉，不能留下半个代理项。
        String s = "ab😀cd";
        String cut = TextKit.trimUtf8(s, 4);
        assertEquals("ab", cut);
        assertEquals("ab", TextKit.trimUtf8("ab😀cd", 5));
    }

    @Test
    public void trimUtf8_keepsStringWhenUnderLimit() {
        assertEquals("你好", TextKit.trimUtf8("你好", 100));
        assertEquals("", TextKit.trimUtf8(null, 10));
    }

    @Test
    public void trimUtf8_limitBeyondLengthIsSafe() {
        // 上限比实际字节数还大时不能越界（旧实现会 ArrayIndexOutOfBounds）。
        assertEquals("ok", TextKit.trimUtf8("ok", 999));
        assertEquals("ok", TextKit.trimUtf8("ok", Integer.MAX_VALUE));
    }

    /* ---------------------------------------------------------- 存储体检 */

    @Test
    public void audit_flagsGhostOrphanAndEmpty() {
        List<StorageGuard.Entry> index = new ArrayList<>(Arrays.asList(
                new StorageGuard.Entry("/m/a.gguf", 100),
                new StorageGuard.Entry("/m/b.gguf", 200),
                new StorageGuard.Entry("/m/c.gguf", 0)));
        List<StorageGuard.Entry> disk = new ArrayList<>(Arrays.asList(
                new StorageGuard.Entry("/m/a.gguf", 100),
                new StorageGuard.Entry("/m/c.gguf", 0),
                new StorageGuard.Entry("/m/orphan.gguf", 555)));
        List<StorageGuard.Finding> found = StorageGuard.audit(index, disk);

        boolean ghost = false, orphan = false, empty = false;
        for (StorageGuard.Finding f : found) {
            if (f.kind == StorageGuard.Kind.GHOST_ENTRY && f.path.endsWith("b.gguf")) ghost = true;
            if (f.kind == StorageGuard.Kind.ORPHAN_FILE && f.path.endsWith("orphan.gguf")) orphan = true;
            if (f.kind == StorageGuard.Kind.EMPTY_FILE && f.path.endsWith("c.gguf")) empty = true;
        }
        assertTrue("缺文件的记录要报出来", ghost);
        assertTrue("没索引的文件要报出来", orphan);
        assertTrue("空壳文件要报出来", empty);
    }

    @Test
    public void audit_cleanLibraryReportsNothing() {
        List<StorageGuard.Entry> index = new ArrayList<>(Arrays.asList(
                new StorageGuard.Entry("/m/a.gguf", 100)));
        List<StorageGuard.Entry> disk = new ArrayList<>(Arrays.asList(
                new StorageGuard.Entry("/m/a.gguf", 100)));
        assertTrue(StorageGuard.audit(index, disk).isEmpty());
    }

    /* ------------------------------------------------------------- 参数收敛 */

    @Test
    public void validators_clampsOutputToContext() {
        Lang.setChinese(true);
        // 输出上限不能吃掉整个上下文，否则连提示词都放不下
        Validators.Sampling sp = new Validators.Sampling(8192, 0.7f, 0.9f, 40, 2048);
        String note = Validators.normalize(sp);
        assertTrue("压到上下文的 3/4 以内", sp.maxTokens <= 2048 * 3 / 4);
        assertTrue("要说明为什么被改", note.contains("3/4"));
    }

    @Test
    public void validators_greedyNeedsTopK() {
        // temp=0 + top-k=0 会让采样分布退化，必须至少留 1
        Validators.Sampling sp = new Validators.Sampling(256, 0f, 1f, 0, 4096);
        Validators.normalize(sp, false);
        assertEquals(1, sp.topK);
    }
}
