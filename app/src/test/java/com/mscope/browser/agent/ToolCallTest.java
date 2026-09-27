package com.mscope.browser.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;
import java.util.Set;

/**
 * 工具调用解析器的单元测试。整条智能体链路都依赖它把模型输出解析成调用，
 * 所以这里把各家模型的格式、截断、误判等场景都覆盖一遍。
 */
public class ToolCallTest {

    private static final Set<String> KNOWN = AgentTools.names();

    /** 思考块标签拆开拼接，避免字面量被工具链过滤。 */
    private static final String TL = "<thi" + "nk>";
    private static final String TR = "</thi" + "nk>";

    private static String fence(String body) {
        return "```tool\n" + body + "\n```";
    }

    @Test
    public void parsesFencedToolBlock() {
        List<ToolCall> calls = ToolCall.parse(fence(
                "{\"name\": \"shell\", \"arguments\": {\"command\": \"ls -la\"}}"), KNOWN);

        assertEquals(1, calls.size());
        assertEquals("shell", calls.get(0).name);
        assertEquals("ls -la", calls.get(0).arg("command", ""));
    }

    @Test
    public void parsesToolCallTag() {
        List<ToolCall> calls = ToolCall.parse(
                "好的，我来查一下\n<tool_call>{\"name\":\"web_search\",\"arguments\":{\"query\":\"安卓 15\"}}</tool_call>",
                KNOWN);

        assertEquals(1, calls.size());
        assertEquals("web_search", calls.get(0).name);
        assertEquals("安卓 15", calls.get(0).arg("query", ""));
    }

    @Test
    public void parsesToolCallLine() {
        List<ToolCall> calls = ToolCall.parse(
                "TOOL_CALL: {\"name\": \"list_files\", \"path\": \".\"}", KNOWN);

        assertEquals(1, calls.size());
        assertEquals("list_files", calls.get(0).name);
        assertEquals(".", calls.get(0).arg("path", ""));
    }

    /** 参数平铺在顶层（没有 arguments 字段）也要能取到。 */
    @Test
    public void acceptsFlatArguments() {
        List<ToolCall> calls = ToolCall.parse(fence(
                "{\"name\": \"write_file\", \"path\": \"a.txt\", \"content\": \"你好\"}"), KNOWN);

        assertEquals(1, calls.size());
        assertEquals("a.txt", calls.get(0).arg("path", ""));
        assertEquals("你好", calls.get(0).arg("content", ""));
    }

    /** name 不在已注册工具里就当普通文本，不能误执行。 */
    @Test
    public void rejectsUnknownToolName() {
        List<ToolCall> calls = ToolCall.parse(fence(
                "{\"name\": \"format_disk\", \"arguments\": {\"path\": \"/\"}}"), KNOWN);
        assertTrue(calls.isEmpty());
    }

    /** 普通 ```json 示例（没有工具名）不能被当成工具调用。 */
    @Test
    public void rejectsPlainJsonExample() {
        List<ToolCall> calls = ToolCall.parse(
                "```json\n{\"city\": \"北京\", \"temp\": 22}\n```", KNOWN);
        assertTrue(calls.isEmpty());
    }

    /** 常见别名要能归一化到规范工具名。 */
    @Test
    public void normalizesAliases() {
        assertEquals("shell", ToolCall.parse(
                fence("{\"name\":\"terminal\",\"arguments\":{\"command\":\"pwd\"}}"), KNOWN).get(0).name);
        assertEquals("web_search", ToolCall.parse(
                fence("{\"name\":\"search\",\"arguments\":{\"query\":\"x\"}}"), KNOWN).get(0).name);
        assertEquals("list_files", ToolCall.parse(
                fence("{\"name\":\"ls\",\"arguments\":{\"path\":\".\"}}"), KNOWN).get(0).name);
    }

    @Test
    public void parsesMultipleCallsInOrder() {
        String text = fence("{\"name\":\"list_files\",\"arguments\":{\"path\":\".\"}}")
                + "\n中间说明\n"
                + fence("{\"name\":\"shell\",\"arguments\":{\"command\":\"cat a.txt\"}}");
        List<ToolCall> calls = ToolCall.parse(text, KNOWN);

        assertEquals(2, calls.size());
        assertEquals("list_files", calls.get(0).name);
        assertEquals("shell", calls.get(1).name);
    }

    @Test
    public void stripRemovesToolBlocks() {
        String text = "先看看目录。\n" + fence("{\"name\":\"list_files\",\"arguments\":{\"path\":\".\"}}")
                + "\n然后我再回答。";
        String visible = ToolCall.strip(text, KNOWN);

        assertTrue(visible.contains("先看看目录"));
        assertTrue(visible.contains("然后我再回答"));
        assertFalse(visible.contains("list_files"));
    }

    /** 带工具标记但 JSON 坏掉的块也要被剥掉，免得界面上留一坨坏 JSON。 */
    @Test
    public void stripRemovesBrokenToolBlocks() {
        String text = "思路如下\n```tool\n{\"name\": \"shell\", bad json\n```\n结束";
        String visible = ToolCall.strip(text, KNOWN);

        assertFalse(visible.contains("```"));
        assertTrue(visible.contains("思路如下"));
    }

    /** 危险操作判定：命令 / 写文件 / 删除需要用户确认。 */
    @Test
    public void riskyToolsNeedConfirmation() {
        assertTrue(AgentTools.risky("shell"));
        assertTrue(AgentTools.risky("write_file"));
        assertTrue(AgentTools.risky("delete_file"));
        assertFalse(AgentTools.risky("web_search"));
        assertFalse(AgentTools.risky("read_file"));
        assertFalse(AgentTools.risky("list_files"));
    }

    /* ------------------------------------------- MiniCPM5 原生 XML 工具格式 */

    @Test
    public void parsesMiniCpm5FunctionFormat() {
        List<ToolCall> calls = ToolCall.parse(
                "<function name=\"web_search\"><param name=\"query\">科技新闻</param></function>",
                KNOWN);

        assertEquals(1, calls.size());
        assertEquals("web_search", calls.get(0).name);
        assertEquals("科技新闻", calls.get(0).arg("query", ""));
    }

    /** 真实场景：先思考，再给一个原生调用，前后还带说明文字。 */
    @Test
    public void parsesMiniCpm5AfterThinking() {
        String text = TL + "\n用户想找工作区文件，我用 list_files。\n" + TR
                + "\n\n我先看看。\n"
                + "<function name=\"list_files\"><param name=\"path\">.</param></function>";

        List<ToolCall> calls = ToolCall.parse(text, KNOWN);
        assertEquals(1, calls.size());
        assertEquals("list_files", calls.get(0).name);
        assertEquals(".", calls.get(0).arg("path", ""));

        String visible = ToolCall.stripThinking(ToolCall.strip(text, KNOWN)).trim();
        assertEquals("我先看看。", visible);
    }

    /** 多个参数。 */
    @Test
    public void parsesMiniCpm5MultipleParams() {
        List<ToolCall> calls = ToolCall.parse(
                "<function name=\"write_file\"><param name=\"path\">a.txt</param>"
                        + "<param name=\"content\">你好</param></function>", KNOWN);

        assertEquals(1, calls.size());
        assertEquals("a.txt", calls.get(0).arg("path", ""));
        assertEquals("你好", calls.get(0).arg("content", ""));
    }

    /** 参数值用 CDATA 包裹（值里含 </param> 时必须这么写）。 */
    @Test
    public void unwrapsCdataParamValue() {
        List<ToolCall> calls = ToolCall.parse(
                "<function name=\"write_file\"><param name=\"path\">a.html</param>"
                        + "<param name=\"content\"><![CDATA[<p>hi</p>]]></param></function>", KNOWN);

        assertEquals(1, calls.size());
        assertEquals("<p>hi</p>", calls.get(0).arg("content", ""));
    }

    /** 输出被截断、缺少闭合标签时也要能解析出来。 */
    @Test
    public void parsesTruncatedMiniCpm5Call() {
        List<ToolCall> calls = ToolCall.parse(
                "<function name=\"shell\"><param name=\"command\">ls -la", KNOWN);

        assertEquals(1, calls.size());
        assertEquals("shell", calls.get(0).name);
        assertEquals("ls -la", calls.get(0).arg("command", ""));
    }

    /** Qwen3-Coder 风格：function=x / parameter=k。 */
    @Test
    public void parsesQwenCoderXmlFormat() {
        List<ToolCall> calls = ToolCall.parse(
                "<tool_call>\n<function=web_search>\n<parameter=query>安卓 15</parameter>\n"
                        + "</function>\n</tool_call>", KNOWN);

        assertEquals(1, calls.size());
        assertEquals("web_search", calls.get(0).name);
        assertEquals("安卓 15", calls.get(0).arg("query", ""));
    }

    @Test
    public void stripRemovesMiniCpm5Call() {
        String text = "好的，先看看目录：\n"
                + "<function name=\"list_files\"><param name=\"path\">.</param></function>";
        String visible = ToolCall.strip(text, KNOWN);

        assertFalse(visible.contains("<function"));
        assertTrue(visible.contains("好的，先看看目录"));
    }

    /** 名字不认识（解析不出调用）的标签也要剥掉，别在界面上留残渣。 */
    @Test
    public void stripRemovesUnknownXmlTag() {
        String text = "开始\n<function name=\"format_disk\"><param name=\"path\">/</param></function>\n结束";
        String visible = ToolCall.strip(text, KNOWN);

        assertFalse(visible.contains("function"));
        assertTrue(visible.contains("开始"));
        assertTrue(visible.contains("结束"));
    }

    /* ------------------------------------------------------------ 思考块 */

    @Test
    public void stripsThinkingBlocks() {
        assertEquals("答案", ToolCall.stripThinking(TL + "内心戏" + TR + "答案").trim());
        assertEquals("答案", ToolCall.stripThinking(TL + "内心戏" + TR + "\n\n答案").trim());
        assertEquals("", ToolCall.stripThinking(TL + "被截断的思考").trim());
        assertEquals("答案", ToolCall.stripThinking("答案").trim());
    }
}