package com.mscope.browser.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 原生工具调用解析。
 *
 * <p>这些用例的字符串都是从各家模板文档里**逐字抄下来的**：DSML 那些标签结尾的单 {@code >}
 * 不是笔误，模板就长这样。所以这里不做「美化」，留着原样才能防住回归。
 */
public class ServerToolCallTest {

    private static final Set<String> KNOWN = new HashSet<>(Arrays.asList(
            "shell", "read_file", "write_file", "web_search", "list_files"));

    @Test
    public void dsmlSingleInvoke() {
        String text = "我来看看。\n"
                + "<|DSML|tool_calls>\n"
                + "<|DSML|invoke name=\"shell\">\n"
                + "<|DSML|parameter name=\"command\" string=\"true\">ls -la\n"
                + "<|DSML|/parameter>\n"
                + "<|DSML|/invoke>\n"
                + "<|DSML|/tool_calls>";
        ServerToolCall.Parsed p = ServerToolCall.parse(text, KNOWN);
        assertEquals(ServerToolCall.Family.DSML, p.family);
        assertEquals(1, p.calls.size());
        assertEquals("shell", p.calls.get(0).name);
        assertEquals("ls -la", p.calls.get(0).arg("command", ""));
        assertTrue("正文要保留", p.visible.contains("我来看看"));
        assertFalse("调用片段必须被剔除", p.visible.contains("DSML"));
    }

    @Test
    public void dsmlTwoInvokesInOneTurn() {
        String text = "<|DSML|tool_calls>\n"
                + "<|DSML|invoke name=\"read_file\">\n"
                + "<|DSML|parameter name=\"path\" string=\"true\">a.txt\n"
                + "<|DSML|/parameter>\n"
                + "<|DSML|/invoke>\n"
                + "<|DSML|invoke name=\"read_file\">\n"
                + "<|DSML|parameter name=\"path\" string=\"true\">b.txt\n"
                + "<|DSML|/parameter>\n"
                + "<|DSML|/invoke>\n"
                + "<|DSML|/tool_calls>";
        ServerToolCall.Parsed p = ServerToolCall.parse(text, KNOWN);
        assertEquals(2, p.calls.size());
        assertEquals("a.txt", p.calls.get(0).arg("path", ""));
        assertEquals("b.txt", p.calls.get(1).arg("path", ""));
    }

    @Test
    public void dsmlUnclosedIsIncomplete() {
        String text = "<|DSML|tool_calls>\n<|DSML|invoke name=\"shell\">\n"
                + "<|DSML|parameter name=\"command\" string=\"true\">ls";
        ServerToolCall.Parsed p = ServerToolCall.parse(text, KNOWN);
        assertEquals(ServerToolCall.Family.DSML, p.family);
        assertTrue("写到一半没有闭合标签，要标记为不完整", p.incomplete);
    }

    @Test
    public void dsmlMultilineArgumentKeepsNewlines() {
        String text = "<|DSML|tool_calls>\n"
                + "<|DSML|invoke name=\"write_file\">\n"
                + "<|DSML|parameter name=\"path\" string=\"true\">a.sh\n"
                + "<|DSML|/parameter>\n"
                + "<|DSML|parameter name=\"content\" string=\"true\">echo 1\necho 2\n"
                + "<|DSML|/parameter>\n"
                + "<|DSML|/invoke>\n"
                + "<|DSML|/tool_calls>";
        ServerToolCall.Parsed p = ServerToolCall.parse(text, KNOWN);
        assertEquals("echo 1\necho 2", p.calls.get(0).arg("content", ""));
    }

    @Test
    public void toolAliasIsNormalized() {
        String text = "<|DSML|tool_calls>\n<|DSML|invoke name=\"bash\">\n"
                + "<|DSML|parameter name=\"command\" string=\"true\">pwd\n<|DSML|/parameter>\n"
                + "<|DSML|/invoke>\n<|DSML|/tool_calls>";
        ServerToolCall.Parsed p = ServerToolCall.parse(text, KNOWN);
        assertEquals("shell", p.calls.get(0).name);
    }

    @Test
    public void unknownToolIsIgnored() {
        String text = "<|DSML|tool_calls>\n<|DSML|invoke name=\"rm_rf_everything\">\n"
                + "<|DSML|/invoke>\n<|DSML|/tool_calls>";
        ServerToolCall.Parsed p = ServerToolCall.parse(text, KNOWN);
        assertFalse("白名单外的东西一律不认", p.hasCalls());
    }

    @Test
    public void mistralArray() {
        String text = "Let me check.\n[TOOL_CALLS][{\"name\":\"shell\",\"arguments\":{\"command\":\"ls\"}}]";
        ServerToolCall.Parsed p = ServerToolCall.parse(text, KNOWN);
        assertEquals(ServerToolCall.Family.MISTRAL, p.family);
        assertEquals(1, p.calls.size());
        assertEquals("ls", p.calls.get(0).arg("command", ""));
        assertEquals("Let me check.", p.visible.trim());
    }

    @Test
    public void hermesToolCallBlock() {
        String text = "<tool_call>{\"name\":\"list_files\",\"arguments\":{\"path\":\".\"}}</tool_call>";
        ServerToolCall.Parsed p = ServerToolCall.parse(text, KNOWN);
        assertEquals(ServerToolCall.Family.JSON, p.family);
        assertEquals("list_files", p.calls.get(0).name);
        assertEquals("", p.visible.trim());
    }

    @Test
    public void hermesArgumentsAsStringAreDecoded() {
        // 部分模型把 arguments 序列化成字符串，这是真实会遇到的形状
        String text = "<tool_call>{\"name\":\"shell\",\"arguments\":\"{\\\"command\\\":\\\"echo hi\\\"}\"}"
                + "</tool_call>";
        ServerToolCall.Parsed p = ServerToolCall.parse(text, KNOWN);
        assertEquals("echo hi", p.calls.get(0).arg("command", ""));
    }

    @Test
    public void plainTextIsNotACall() {
        ServerToolCall.Parsed p = ServerToolCall.parse("今天天气不错，适合散步。", KNOWN);
        assertEquals(ServerToolCall.Family.NONE, p.family);
        assertFalse(p.hasCalls());
        assertEquals("今天天气不错，适合散步。", p.visible);
    }

    @Test
    public void jsonLookingProseIsNotACall() {
        // 正文里出现 JSON 但没有 name/arguments 结构，不能被当成调用
        String text = "配置长这样：{\"temperature\": 0.7, \"top_p\": 0.9}";
        ServerToolCall.Parsed p = ServerToolCall.parse(text, KNOWN);
        assertFalse(p.hasCalls());
    }

    @Test
    public void responseBlockFollowsFamily() {
        assertTrue(ServerToolCall.responseBlock(ServerToolCall.Family.JSON, "ok", true)
                .contains("<tool_response>"));
        // DSML 走消息序列，不需要额外标签；再包一层反而会让模型以为多了个工具
        assertEquals("ok", ServerToolCall.responseBlock(ServerToolCall.Family.DSML, "ok", true));
    }

    @Test
    public void truncatedDetection() {
        // 单行没句号的短句不算截断：中文回复本来就常省略句号
        assertFalse(ServerToolCall.looksTruncated("这是半句"));
        assertTrue(ServerToolCall.looksTruncated("<tool_call>{\"name\":\"shell\""));
        assertTrue(ServerToolCall.looksTruncated("<|DSML|tool_calls><|DSML|invoke name=\"shell\">"));
        assertFalse(ServerToolCall.looksTruncated("完整的一句话。"));
        assertFalse(ServerToolCall.looksTruncated(""));
    }

    @Test
    public void multipleFamiliesInOneTurnPreferStrongerSignal() {
        // 有的模型两边都写；DSML 特征更强，优先按 DSML 解，别把同一个动作执行两遍
        String text = "<|DSML|tool_calls>\n<|DSML|invoke name=\"shell\">\n"
                + "<|DSML|parameter name=\"command\" string=\"true\">ls\n<|DSML|/parameter>\n"
                + "<|DSML|/invoke>\n<|DSML|/tool_calls>\n"
                + "<tool_call>{\"name\":\"shell\",\"arguments\":{\"command\":\"ls\"}}</tool_call>";
        ServerToolCall.Parsed p = ServerToolCall.parse(text, KNOWN);
        assertEquals(ServerToolCall.Family.DSML, p.family);
        assertEquals("只执行一次", 1, p.calls.size());
    }

    @Test
    public void numericParameterIsCoercedWhenTemplateSaysSo() {
        String text = "<|DSML|tool_calls>\n<|DSML|invoke name=\"shell\">\n"
                + "<|DSML|parameter name=\"timeout\" string=\"false\">30\n<|DSML|/parameter>\n"
                + "<|DSML|/invoke>\n<|DSML|/tool_calls>";
        ServerToolCall.Parsed p = ServerToolCall.parse(text, KNOWN);
        assertEquals("30", p.calls.get(0).arg("timeout", ""));
    }

    @Test
    public void stripOnlyTheCallNotTheRest() {
        String text = "前面\n<|DSML|tool_calls>\n<|DSML|invoke name=\"shell\">\n"
                + "<|DSML|parameter name=\"command\" string=\"true\">ls\n<|DSML|/parameter>\n"
                + "<|DSML|/invoke>\n<|DSML|/tool_calls>\n后面";
        ServerToolCall.Parsed p = ServerToolCall.parse(text, KNOWN);
        List<ToolCall> calls = p.calls;
        assertEquals(1, calls.size());
        assertTrue(p.visible.contains("前面"));
        assertTrue(p.visible.contains("后面"));
    }
}
