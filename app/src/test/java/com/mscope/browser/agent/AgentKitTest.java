package com.mscope.browser.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.mscope.browser.core.DigestKit;
import com.mscope.browser.core.JsonAccess;
import com.mscope.browser.i18n.Lang;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 智能体层（不依赖 Android 的部分）的行为约定。 */
public class AgentKitTest {

    /**
     * 这些用例断言中文文案，必须显式钉住语言。
     * 不钉的话在英文 CI 机器上会挂在「文案本来就是英文」上，看起来像功能坏了。
     */
    @Before
    public void pinChinese() {
        Lang.setChinese(true);
    }

    @After
    public void reset() {
        Lang.followSystem();
    }

    // ---------------------------------------------------------------- 注册表

    @Test
    public void registry_hasNoDuplicateAndDescribesBothLanguages() {
        assertTrue(ToolRegistry.all().size() >= 50);
        Lang.setChinese(true);
        String zh = ToolRegistry.describe(null, false, true);
        Lang.setChinese(false);
        String en = ToolRegistry.describe(null, false, false);
        Lang.setChinese(true);
        assertTrue(zh.contains("shell"));
        assertTrue(en.contains("shell"));
        // 每条工具都应各出现一次，不能漏
        for (ToolSpec s : ToolRegistry.all()) {
            assertTrue("missing in zh: " + s.name, zh.contains("- " + s.name + ":"));
            assertTrue("missing in en: " + s.name, en.contains("- " + s.name + ":"));
        }
        assertEquals(ToolRegistry.all().size(), ToolRegistry.names().size());
    }

    @Test
    public void registry_riskyMatchesWrittenExpectation() {
        assertTrue(ToolRegistry.risky("shell"));
        assertTrue(ToolRegistry.risky("write_file"));
        assertTrue(ToolRegistry.risky("delete_file"));
        assertFalse(ToolRegistry.risky("read_file"));
        assertFalse(ToolRegistry.risky("calc"));
        assertFalse(ToolRegistry.risky("nope-not-a-tool"));
    }

    @Test
    public void registry_groupFilterHidesOtherGroups() {
        java.util.Set<ToolSpec.Group> onlyCalc = java.util.EnumSet.of(ToolSpec.Group.CALC);
        String d = ToolRegistry.describe(onlyCalc, false, true);
        assertTrue(d.contains("calc"));
        assertFalse(d.contains("- shell:"));
        assertEquals(ToolRegistry.group(ToolSpec.Group.WEB).size(),
                countOccurrences(ToolRegistry.describe(java.util.EnumSet.of(ToolSpec.Group.WEB), false, true), "\n"));
    }

    private static int countOccurrences(String s, String sub) {
        int n = 0;
        int i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) {
            n++;
            i += sub.length();
        }
        return n;
    }

    // ---------------------------------------------------------------- PatchKit

    @Test
    public void patch_replacesAndReportsSuccess() {
        PatchKit.Result r = PatchKit.apply("a\nb\nc\n",
                Arrays.asList(new PatchKit.Hunk("b", "B")), true);
        assertTrue(r.ok);
        assertEquals("a\nB\nc\n", r.text);
        assertEquals(1, r.applied);
    }

    @Test
    public void patch_failureLeavesFileUntouched() {
        String original = "alpha\nbeta\n";
        PatchKit.Result r = PatchKit.apply(original,
                Arrays.asList(new PatchKit.Hunk("gamma", "G")), true);
        assertFalse(r.ok);
        assertEquals(original, r.text);
        assertTrue(r.error.contains("找不到") || r.error.contains("not found"));
        assertFalse(r.hint.isEmpty());
    }

    @Test
    public void patch_partialFailureRollsBack() {
        String original = "one\ntwo\n";
        PatchKit.Result r = PatchKit.apply(original, Arrays.asList(
                new PatchKit.Hunk("one", "1"),
                new PatchKit.Hunk("missing", "x")), true);
        assertFalse(r.ok);
        assertEquals(original, r.text);
    }

    @Test
    public void patch_toleratesQuoteAndWhitespaceVariants() {
        assertTrue(PatchKit.apply("say \u201chello\u201d\n",
                Arrays.asList(new PatchKit.Hunk("say \"hello\"", "say hi")), true).ok);
        assertTrue(PatchKit.apply("  x = 1;\n",
                Arrays.asList(new PatchKit.Hunk("x = 1;", "x = 2;")), true).ok);
    }

    @Test
    public void patch_parsesPatchText() {
        String patch = "<<<<<<< FIND\nold\n=======\nnew\n>>>>>>> REPLACE\n";
        List<PatchKit.Hunk> hs = PatchKit.parsePatchText(patch);
        assertEquals(1, hs.size());
        assertEquals("old\n", hs.get(0).find);
        assertEquals("new\n", hs.get(0).replace);
        assertTrue(PatchKit.looksLikePatch(patch));
    }

    @Test
    public void patch_parsesJsonHunks() {
        Object json = JsonAccess.parse("[{\"find\":\"a\",\"replace\":\"b\"}]");
        List<PatchKit.Hunk> hs = PatchKit.parseHunks(json);
        assertEquals(1, hs.size());
        assertEquals("a", hs.get(0).find);
    }

    // ---------------------------------------------------------------- SecureRedactor

    @Test
    public void redact_masksKnownSecretShapes() {
        String text = "key: sk-abcdefghijklmnopqrstuvwxyz012345\n"
                + "aws AKIAIOSFODNN7EXAMPLE\n"
                + "url https://user:p4ssw0rd@example.com/x\n"
                + "Authorization: Bearer abcdefghijklmn\n";
        SecureRedactor.Result r = SecureRedactor.redact(text);
        assertTrue(r.changed());
        assertFalse(r.text.contains("sk-abcdefghijklmnopqrstuvwxyz012345"));
        assertFalse(r.text.contains("p4ssw0rd"));
        assertFalse(r.text.contains("AKIAIOSFODNN7EXAMPLE"));
        assertFalse(r.text.contains("abcdefghijklmn"));
        // 保留结构，别把整段抹成一片***
        assertTrue(r.text.contains("https://user:"));
        assertTrue(r.hits >= 4);
    }

    @Test
    public void redact_leavesPlainTextAlone() {
        String plain = "今天读了一遍 3B 模型的量化文档，Q4_K_M 最均衡。";
        SecureRedactor.Result r = SecureRedactor.redact(plain);
        assertFalse(r.changed());
        assertEquals(plain, r.text);
    }

    @Test
    public void redact_privateKeyBlock() {
        String pem = "-----BEGIN RSA PRIVATE KEY-----\nMIIEow\n-----END RSA PRIVATE KEY-----";
        SecureRedactor.Result r = SecureRedactor.redact(pem);
        assertTrue(r.changed());
        assertFalse(r.text.contains("MIIEow"));
    }

    @Test
    public void redact_fieldNameAloneIsEnough() {
        // 字段名叫 password 就已经是强信号，值长什么样都要抹
        assertTrue(SecureRedactor.redact("password = hunter2hunter2").changed());
        // 没有字段名、只是「看起来随机的长串」属于宽松规则，默认不动 —— 误伤比漏报更难解释
        String blob = "id 3f9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c end";
        assertTrue(SecureRedactor.containsSecret(blob));
        assertEquals(blob, SecureRedactor.redact(blob, false).text);
    }

    // ---------------------------------------------------------------- SkillLibrary

    @Test
    public void skill_parsesFrontMatter() {
        String md = "---\nid: review\nname: Code review\ndesc: facts first\ntrigger: review bug\n---\n\n## Steps\n1. x\n";
        SkillLibrary.Skill s = SkillLibrary.parse(md);
        assertEquals("review", s.id);
        assertEquals("Code review", s.name);
        assertEquals("facts first", s.desc);
        assertTrue(s.body.startsWith("## Steps"));
    }

    @Test
    public void skill_withoutFrontMatterUsesFirstLine() {
        SkillLibrary.Skill s = SkillLibrary.parse("# 标题在这里\n正文");
        assertEquals("标题在这里", s.name);
        assertTrue(s.body.contains("正文"));
        assertNull(SkillLibrary.parseOrNull("   "));
    }

    @Test
    public void skill_builtinsAreNonEmptyInBothLanguages() {
        List<SkillLibrary.Skill> zh = SkillLibrary.builtins(true);
        List<SkillLibrary.Skill> en = SkillLibrary.builtins(false);
        assertEquals(zh.size(), en.size());
        assertTrue(zh.size() >= 7);
        for (int i = 0; i < zh.size(); i++) {
            assertEquals(zh.get(i).id, en.get(i).id);
            assertFalse(zh.get(i).body.isEmpty());
            assertFalse(en.get(i).body.isEmpty());
            assertFalse(zh.get(i).trigger.isEmpty());
        }
    }

    @Test
    public void skill_searchFindsByTrigger() {
        SkillLibrary lib = new SkillLibrary(null);
        List<SkillLibrary.Skill> all = SkillLibrary.builtins(true);
        com.mscope.browser.core.Bm25 idx = new com.mscope.browser.core.Bm25();
        for (SkillLibrary.Skill s : all) idx.add(s.id, s.trigger + " " + s.name + " " + s.body);
        idx.index();
        List<com.mscope.browser.core.Bm25.Hit> hits = idx.search("报错 排查", 3);
        assertFalse(hits.isEmpty());
        assertEquals("debug", hits.get(0).id);
        assertNotNull(lib);
    }

    @Test
    public void skill_jsonRoundTrip() {
        SkillLibrary.Skill s = SkillLibrary.builtins(false).get(0);
        String json = "[{\"id\":\"" + s.id + "\",\"name\":\"" + s.name + "\",\"desc\":\"d\"}]";
        List<SkillLibrary.Skill> back = SkillLibrary.fromJson(json);
        assertEquals(1, back.size());
        assertEquals(s.id, back.get(0).id);
    }

    // ---------------------------------------------------------------- Time / Calc

    @Test
    public void calc_arithmeticAndFunctions() {
        assertEquals("1+2*3 = 7", CalcTools.calc("1+2*3"));
        assertEquals("(1+2)*3 = 9", CalcTools.calc("(1+2)*3"));
        assertEquals("2^10 = 1024", CalcTools.calc("2^10"));
        assertTrue(CalcTools.calc("200*15%").endsWith("= 30"));
        assertTrue(CalcTools.calc("sqrt(16)").endsWith("= 4"));
        assertTrue(CalcTools.calc("1/0").contains("错误") || CalcTools.calc("1/0").contains("error"));
    }

    @Test
    public void calc_fullWidthIsNormalised() {
        assertEquals("１２３＋４ = 127", CalcTools.calc("１２３＋４"));
    }

    @Test
    public void calc_neverExecutesCode() {
        String r = CalcTools.calc("java.lang.Runtime.getRuntime()");
        assertFalse(r.contains("="));
    }

    @Test
    public void calc_numberFormatting() {
        assertEquals("1", CalcTools.format(1.0));
        assertEquals("0.5", CalcTools.format(0.5));
        assertEquals("NaN", CalcTools.format(Double.NaN));
    }

    @Test
    public void unit_conversionSameFamilyOnly() {
        assertTrue(CalcTools.unitConvert("1", "km", "m").endsWith("1000 m"));
        assertTrue(CalcTools.unitConvert("1024", "MB", "GB").endsWith("1 GB"));
        assertTrue(CalcTools.unitConvert("100", "c", "f").contains("212"));
        // 小写 c 与大写 C 都是摄氏度：模型经常给错大小写，不该因此报「不认识」
        assertTrue(CalcTools.unitConvert("100", "C", "F").contains("212"));
        assertTrue(CalcTools.unitConvert("1", "kg", "km").contains("不是同一类"));
        assertTrue(CalcTools.unitConvert("1", "bogus", "m").contains("不认识"));
    }

    @Test
    public void date_mathUnderstandsOffsets() {
        String r = CalcTools.dateMath("2026-01-01", "+3d");
        assertTrue(r.contains("2026-01-04"));
        assertTrue(CalcTools.dateMath("2026-01-01", "-1w").contains("2025-12-25"));
        assertTrue(CalcTools.dateMath("not-a-date", "+1d").contains("看不懂"));
    }

    @Test
    public void encode_decodeRoundTrip() {
        // base64 走 android.util.Base64（minSdk 24 不支持 java.util.Base64）。
        // 这个类在 JVM 单测里是「返回默认值」的桩，所以只能断言「不抛异常」，
        // 真实编码往返属于设备回归项，不在单测里假装验证。
        CalcTools.base64("中文 ok", "encode");
        // url 编解码是纯 JDK 实现，可以真验证往返
        assertEquals("a+b%26c", CalcTools.urlCodec("a b&c", "encode"));
        assertEquals("a b&c", CalcTools.urlCodec("a+b%26c", "decode"));
        assertEquals(DigestKit.sha256("x"), CalcTools.hashText("x", "sha256"));
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", CalcTools.hashText("abc", "sha1"));
    }

    // ---------------------------------------------------------------- ExternalModel

    @Test
    public void externalModel_buildsOpenAiBody() {
        String body = ExternalModel.buildBody("gpt-x", "hi", "sys", 99999, 5);
        Object o = JsonAccess.parse(body);
        assertEquals("gpt-x", JsonAccess.str(o, "model", ""));
        assertEquals(8192, JsonAccess.i(o, "max_tokens", 0));
        assertEquals(2.0, JsonAccess.d(o, "temperature", 0), 1e-9);
        assertEquals("system", JsonAccess.str(o, "messages[0].role", ""));
        assertEquals("user", JsonAccess.str(o, "messages[1].role", ""));
        assertFalse(JsonAccess.bool(o, "stream", true));
    }

    @Test
    public void externalModel_parsesAnswersAndErrors() {
        ExternalModel.Answer a = ExternalModel.parse(
                "{\"choices\":[{\"message\":{\"content\":\" hello \"}}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":4}}",
                true);
        assertTrue(a.ok);
        assertEquals("hello", a.text);
        assertEquals(4, a.completionTokens);

        ExternalModel.Answer err = ExternalModel.parse("{\"error\":{\"message\":\"bad key\"}}", true);
        assertFalse(err.ok);
        assertEquals("bad key", err.error);

        assertFalse(ExternalModel.parse("", true).ok);
    }

    @Test
    public void externalModel_joinsBaseUrlSafely() {
        assertEquals("https://a.b/v1/chat/completions", ExternalModel.joinUrl("https://a.b/v1/", "/chat/completions"));
        assertEquals("https://a.b/v1/chat/completions",
                ExternalModel.joinUrl("a.b/v1", "/chat/completions"));
        assertEquals("https://a.b/v1/chat/completions",
                ExternalModel.joinUrl("https://a.b/v1/chat/completions", ""));
    }

    @Test
    public void externalModel_notUsableWithoutConfig() {
        ExternalModel m = new ExternalModel(new ExternalModel.Config());
        assertFalse(m.usable());
        assertFalse(m.ask("q", "", 10).ok);
    }

    // ---------------------------------------------------------------- PathGuard

    @Test
    public void guard_blocksTheObviouslyDestructive() {
        assertFalse(PathGuard.check("rm -rf /").allowed);
        assertFalse(PathGuard.check("rm -rf ~").allowed);
        assertFalse(PathGuard.check("dd if=/dev/zero of=/dev/block/mmcblk0").allowed);
        assertFalse(PathGuard.check("mkfs.ext4 /dev/block/x").allowed);
        assertFalse(PathGuard.check("sudo rm x").allowed);
        assertFalse(PathGuard.check("").allowed);
    }

    @Test
    public void guard_warnsButAllowsRecursiveWork() {
        PathGuard.Verdict v = PathGuard.check("rm -rf build");
        assertTrue(v.allowed);
        assertTrue(v.warn);
        PathGuard.Verdict ok = PathGuard.check("ls -la");
        assertTrue(ok.allowed);
        assertFalse(ok.warn);
    }

    @Test
    public void guard_flagsAbsolutePathsOutsideRoot() throws Exception {
        java.io.File root = java.nio.file.Files.createTempDirectory("ws").toFile();
        assertEquals("/data/data/other/files/x", PathGuard.offendingPath("cat /data/data/other/files/x", root));
        assertNull(PathGuard.offendingPath("ls ./notes", root));
        assertNull(PathGuard.offendingPath("cat /system/bin/sh", root));
    }
}
