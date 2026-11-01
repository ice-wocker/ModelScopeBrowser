package com.mscope.browser.agent;

import com.mscope.browser.core.FsKit;
import com.mscope.browser.core.PromptTemplate;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 长期偏好（记忆）的落盘与裁剪。
 *
 * <p>为什么放在工作区而不是 SharedPreferences：记忆是**数据**，用户应该能看、能改、能删，
 * 也能跟着工作区一起导出。放 {@code /memory.txt}，一行一条，透明到不需要解释。
 *
 * <p>条数与长度限制沿用 {@link PromptTemplate#sanitizeMemory}：记忆每轮都会被拼进 prompt，
 * 攒到几十条就会把小模型的注意力全吃掉。
 */
public class MemoryStore {

    public static final String FILE = "memory.txt";
    private final Workspace ws;

    public MemoryStore(Workspace ws) {
        this.ws = ws;
    }

    private File file() {
        return new File(ws.root(), FILE);
    }

    public synchronized List<String> list() {
        List<String> out = new ArrayList<>();
        String text = FsKit.readText(file(), 32 * 1024);
        for (String line : text.split("\n")) {
            String s = line.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    /** 添加一条；返回是否新增（重复则返回 false）。 */
    public synchronized boolean add(String text) {
        String t = clean(text);
        if (t.isEmpty()) return false;
        List<String> cur = list();
        if (cur.contains(t)) return false;
        cur.add(t);
        return save(cur);
    }

    /** 按下标删（从 1 开始）；index <= 0 表示全清。 */
    public synchronized boolean remove(int index) {
        List<String> cur = list();
        if (index <= 0) return save(new ArrayList<>());
        if (index > cur.size()) return false;
        cur.remove(index - 1);
        return save(cur);
    }

    /** 交给提示词拼装用（已裁剪）。 */
    public synchronized List<String> forPrompt() {
        return PromptTemplate.sanitizeMemory(list());
    }

    private boolean save(List<String> items) {
        List<String> capped = PromptTemplate.sanitizeMemory(items);
        StringBuilder sb = new StringBuilder();
        for (String s : capped) sb.append(s).append('\n');
        return FsKit.writeAtomic(file(), sb.toString());
    }

    private static String clean(String s) {
        if (s == null) return "";
        String t = s.trim().replaceAll("\\s+", " ");
        if (t.length() > PromptTemplate.MAX_MEMORY_ITEM) {
            t = t.substring(0, PromptTemplate.MAX_MEMORY_ITEM);
        }
        return t;
    }

    public synchronized String render(boolean zh) {
        List<String> items = list();
        if (items.isEmpty()) {
            return zh ? "还没有记住任何偏好。" : "No preferences remembered yet.";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) sb.append(i + 1).append(". ").append(items.get(i)).append('\n');
        return sb.toString().trim();
    }
}
