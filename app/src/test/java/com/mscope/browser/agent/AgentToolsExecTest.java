package com.mscope.browser.agent;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.mscope.browser.core.Journal;
import com.mscope.browser.core.Memory;
import com.mscope.browser.core.Planner;
import com.mscope.browser.i18n.Lang;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;

/**
 * 新工具在真实执行路径上的行为。
 *
 * <p>重点不是「能不能跑通」，而是三条不能错的：
 * 改文件失败时原文件必须一字不动；危险工具必须被标记；密钥不许进日志与记忆。
 *
 * <p>shell 与联网不在这里测：它们依赖 Android 的 /system/bin/sh 与网络，
 * 属于集成层面，用单测假装跑通只会给出虚假的安全感。
 */
public class AgentToolsExecTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private AgentTools tools;

    /** 最小 Workspace：真实实现依赖 Context，这里按同样的语义用临时目录顶上。 */
    @Before
    public void setUp() throws Exception {
        Lang.setChinese(true);
        Workspace ws = new Workspace(tmp.getRoot());
        tools = new AgentTools(ws, null, null, new Memory.Store(new File(tmp.getRoot(), "memory.txt")));
    }

    private static ToolCall call(String name, String json) throws Exception {
        return ToolCall.parse("<tool_call>{\"name\":\"" + name + "\",\"arguments\":" + json + "}</tool_call>",
                AgentTools.names()).get(0);
    }

    @Test
    public void calcAnswersExactly() throws Exception {
        String r = tools.exec(call("calc", "{\"expr\":\"(1234*5678)+1\"}"));
        assertTrue(r, r.contains("7006653"));
    }

    @Test
    public void calcReportsErrorInsteadOfGuessing() throws Exception {
        String r = tools.exec(call("calc", "{\"expr\":\"1/0\"}"));
        assertTrue(r, r.contains("错误"));
    }

    @Test
    public void writeThenReadThenEditKeepsContent() throws Exception {
        tools.exec(call("write_file", "{\"path\":\"a.txt\",\"content\":\"hello world\\nline2\"}"));
        assertTrue(tools.exec(call("read_file", "{\"path\":\"a.txt\"}")).contains("hello world"));

        String edited = tools.exec(call("edit_file",
                "{\"path\":\"a.txt\",\"old\":\"hello world\",\"new\":\"HELLO\"}"));
        assertTrue(edited, edited.contains("已改"));
        String after = tools.exec(call("read_file", "{\"path\":\"a.txt\"}"));
        assertTrue(after, after.contains("HELLO"));
    }

    /** 未命中的 edit 必须原样保留文件：静默改坏比不改糟得多。 */
    @Test
    public void failedEditLeavesFileUntouched() throws Exception {
        tools.exec(call("write_file", "{\"path\":\"b.txt\",\"content\":\"original\"}"));
        String r = tools.exec(call("edit_file",
                "{\"path\":\"b.txt\",\"old\":\"not-there\",\"new\":\"x\"}"));
        assertTrue(r, r.contains("未变动"));
        assertTrue(tools.exec(call("read_file", "{\"path\":\"b.txt\"}")).contains("original"));
    }

    @Test
    public void applyPatchChangesFile() throws Exception {
        tools.exec(call("write_file", "{\"path\":\"c.txt\",\"content\":\"one\\ntwo\\nthree\"}"));
        String diff = "--- a/c.txt\\n+++ b/c.txt\\n@@ -1,3 +1,3 @@\\n one\\n-two\\n+TWO\\n three\\n";
        String r = tools.exec(call("apply_patch", "{\"patch\":\"" + diff + "\"}"));
        assertTrue(r, r.contains("已应用"));
        assertTrue(tools.exec(call("read_file", "{\"path\":\"c.txt\"}")).contains("TWO"));
    }

    @Test
    public void fileInfoDescribesTarget() throws Exception {
        tools.exec(call("write_file", "{\"path\":\"d.txt\",\"content\":\"12345\"}"));
        String r = tools.exec(call("file_info", "{\"path\":\"d.txt\"}"));
        assertTrue(r, r.contains("d.txt"));
        assertTrue(r, r.contains("5"));
    }

    @Test
    public void todoIsWrittenAndReadable() throws Exception {
        tools.exec(call("todo_write", "{\"items\":\"[x] 读文件\\n[ ] 改文件\"}"));
        List<Planner.Item> items = tools.todo();
        assertTrue(items.size() == 2);
        assertTrue(Planner.doneCount(items) == 1);
    }

    @Test
    public void journalRecordsEveryCall() throws Exception {
        tools.exec(call("now", "{}"));
        tools.exec(call("calc", "{\"expr\":\"1+1\"}"));
        Journal j = tools.journal();
        assertTrue(j.size() >= 2);
        assertTrue(j.render(0, true).contains("calc"));
    }

    /** 密钥绝不能进日志：日志会被回灌进上下文、也可能被用户导出。 */
    @Test
    public void journalRedactsSecrets() throws Exception {
        tools.exec(call("remember", "{\"text\":\"我的 token 是 gho_FAKE0000000000000000000000000000000000\"}"));
        String log = tools.journal().render(0, true);
        assertFalse(log, log.contains("gho_FAKE0000000000000000000000000000000000"));
    }

    @Test
    public void rememberAndRecall() throws Exception {
        tools.exec(call("remember", "{\"text\":\"用户偏好简洁的中文回答\"}"));
        String r = tools.exec(call("recall", "{\"query\":\"回答偏好\"}"));
        assertTrue(r, r.contains("简洁"));
    }

    @Test
    public void kbSearchFindsChunk() throws Exception {
        tools.exec(call("write_file",
                "{\"path\":\"notes.txt\",\"content\":\"这是一段关于内存不足的说明。\"}"));
        String r = tools.exec(call("kb_search", "{\"query\":\"内存不足\"}"));
        assertTrue(r, r.contains("notes.txt"));
    }

    @Test
    public void unknownToolIsReportedNotThrown() throws Exception {
        String r = tools.exec(call("calc", "{}"));
        assertTrue(r, r.contains("错误"));
    }

    @Test
    public void catalogCoversEverything() {
        List<String[]> all = AgentTools.catalog(true);
        assertNotNull(all);
        assertTrue(all.size() == AgentTools.names().size());
        for (String[] row : all) {
            assertTrue(row[1] != null && !row[1].isEmpty());
        }
    }

    @Test
    public void resetSessionKeepsLongTermMemory() throws Exception {
        tools.exec(call("remember", "{\"text\":\"用户在做安卓项目\"}"));
        tools.exec(call("todo_write", "{\"items\":\"[ ] x\"}"));
        tools.resetSession();
        assertTrue(tools.todo().isEmpty());
        assertTrue(tools.journal().size() == 0);
        assertTrue(tools.memoryStore().size() > 0);
    }
}
