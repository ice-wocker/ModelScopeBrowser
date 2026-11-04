package com.mscope.browser.core;

/**
 * KV 公共前缀的「回放许可」状态机。
 *
 * <p>为什么单独抽出来：这段逻辑此前散在引擎里，且从未真正接上（判定算了、参数没传），
 * 结果是一条错误的规则在文档里存活了很久。它只有三个状态转移，抽成纯逻辑才好写测试，
 * 也才敢改。规则只有一条要紧的：<b>许可只能用一次，而且只属于紧接着的那一轮</b>。
 *
 * <ul>
 *   <li>{@link #finished(boolean)} —— 一轮生成结束，KV 里多出了这轮的产出，打脏；</li>
 *   <li>{@link #markReplayable()} —— 上层确认「这轮产出可以丢」（工具结果只在当轮可见）；</li>
 *   <li>{@link #take()} —— 下一轮开跑前取走许可，取走即清零；</li>
 *   <li>{@link #invalidate()} —— 历史被真正改动（编辑/删除/重新生成/换会话）。</li>
 * </ul>
 *
 * <p>注意 {@link #markReplayable()} 在「没产出内容」的轮次里是空操作：
 * 那种情况下 KV 里并没有多出东西，放行只会让原生层白跑一次前缀校验。
 */
public final class KvReplay {

    /** 上一轮的产出可以丢弃（KV 里多出了一截本该消失的尾巴）。 */
    private boolean dirty;
    /** 上一轮有没有真的产出过内容。 */
    private boolean armed;

    /** 一轮生成结束。 */
    public synchronized void finished(boolean producedContent) {
        dirty = producedContent;
        armed = false;
    }

    /** 允许下一轮沿用前缀（丢掉本轮产出）。 */
    public synchronized void markReplayable() {
        if (dirty) armed = true;
    }

    /** 取走许可；无论结果如何都会清零。 */
    public synchronized boolean take() {
        boolean replay = dirty && armed;
        dirty = false;
        armed = false;
        return replay;
    }

    /** 历史被改动，KV 里的东西一概不认。 */
    public synchronized void invalidate() {
        dirty = false;
        armed = false;
    }

    public synchronized boolean dirty() {
        return dirty;
    }

    public synchronized boolean armed() {
        return armed;
    }
}
