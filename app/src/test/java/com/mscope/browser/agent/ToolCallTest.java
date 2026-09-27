package com.mscope.browser.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;
import java.util.Set;

/**
 * 工具调用解析器的单元测试。整条智能体链路都依赖它把模型输出解析成调用，
 * 所以这里把各种可能出现的格式与误判场景都覆盖一遍。
 */
public class ToolCallTest {

    private static final Set<String> KNOWN = AgentTools.names();

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
}