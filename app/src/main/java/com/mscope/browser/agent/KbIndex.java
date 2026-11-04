package com.mscope.browser.agent;

import com.mscope.browser.core.Retriever;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 工作区文本文件的检索索引：让模型不必把整个文件读进上下文。
 *
 * <p>为什么这件事重要：一个 40KB 的日志读进来就是 1 万多 token，
 * 而模型只想知道「哪一行报了错」。索引 + 检索把成本从「全文」降到「几条片段」，
 * 这是本地小模型能不能处理真实文件的分水岭。
 *
 * <p>索引常驻内存（工作区通常几 MB，可接受），带文件修改时间：
 * 重建时只重切变过的文件，别每次按键都全量重算。
 */
public class KbIndex {

    /** 单个文件超过这个大小就不建索引（读了也没意义，还占内存）。 */
    private static final long MAX_FILE = 2L * 1024 * 1024;
    private static final int MAX_FILES = 400;
    private static final int CHUNK = 900;
    private static final int OVERLAP = 2;

    private final Workspace ws;
    private final List<Retriever.Doc> docs = new ArrayList<>();
    private final List<String> sources = new ArrayList<>();
    private final List<Long> stamps = new ArrayList<>();
    private long builtAt = 0;

    public KbIndex(Workspace ws) {
        this.ws = ws;
    }

    /** 索引状态摘要。 */
    public synchronized String stats(boolean zh) {
        if (sources.isEmpty()) {
            return zh ? "索引为空（还没建）。" : "Index is empty (not built yet).";
        }
        int chars = 0;
        for (Retriever.Doc d : docs) chars += d.text.length();
        return zh
                ? "已索引 " + sources.size() + " 个文件 / " + docs.size() + " 个片段 / "
                + (chars / 1024) + " KB 文本，建于 " + builtAt
                : "indexed " + sources.size() + " files / " + docs.size() + " chunks / "
                + (chars / 1024) + " KB, built at " + builtAt;
    }

    /** 全量重建（或增量：只重切改过的文件）。返回本次处理的文件数。 */
    public synchronized int build(boolean force) {
        List<File> files = new ArrayList<>();
        collect(ws.root(), files, 0);
        int changed = 0;
        List<Retriever.Doc> newDocs = new ArrayList<>();
        List<String> newSources = new ArrayList<>();
        List<Long> newStamps = new ArrayList<>();
        for (File f : files) {
            String rel = ws.relOf(f);
            int at = sources.indexOf(rel);
            long stamp = f.lastModified();
            if (!force && at >= 0 && stamps.get(at) == stamp) {
                // 未改动：原样保留该文件的片段
                for (Retriever.Doc d : docs) {
                    if (d.source.startsWith(rel + "#L")) newDocs.add(d);
                }
                newSources.add(rel);
                newStamps.add(stamp);
                continue;
            }
            String text;
            try {
                text = ws.readText(rel);
            } catch (Exception e) {
                continue;
            }
            newDocs.addAll(Retriever.chunk(rel, text, CHUNK, OVERLAP));
            newSources.add(rel);
            newStamps.add(stamp);
            changed++;
        }
        docs.clear();
        docs.addAll(newDocs);
        sources.clear();
        sources.addAll(newSources);
        stamps.clear();
        stamps.addAll(newStamps);
        builtAt = System.currentTimeMillis();
        return changed;
    }

    /** 检索。索引为空时先建一次（用户直接搜也应有结果）。 */
    public synchronized List<Retriever.Result> search(String query, int k) {
        if (docs.isEmpty()) build(false);
        List<Retriever.Result> rs = Retriever.search(docs, query, k);
        if (rs.isEmpty() && docs.isEmpty()) {
            return Collections.emptyList();
        }
        return rs;
    }

    public synchronized int size() {
        return docs.size();
    }

    /** 递归收集可读文本文件，跳过隐藏目录与超大文件。 */
    private void collect(File dir, List<File> out, int depth) {
        if (dir == null || depth > 6 || out.size() >= MAX_FILES) return;
        File[] cs = dir.listFiles();
        if (cs == null) return;
        for (File f : cs) {
            if (out.size() >= MAX_FILES) return;
            String n = f.getName();
            if (n.startsWith(".")) continue;
            if (f.isDirectory()) {
                collect(f, out, depth + 1);
                continue;
            }
            if (f.length() <= 0 || f.length() > MAX_FILE) continue;
            if (!looksText(n)) continue;
            out.add(f);
        }
    }

    private static boolean looksText(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".gguf") || n.endsWith(".bin") || n.endsWith(".part")) return false;
        return n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".json") || n.endsWith(".csv")
                || n.endsWith(".xml") || n.endsWith(".html") || n.endsWith(".htm") || n.endsWith(".js")
                || n.endsWith(".css") || n.endsWith(".java") || n.endsWith(".kt") || n.endsWith(".py")
                || n.endsWith(".c") || n.endsWith(".cpp") || n.endsWith(".h") || n.endsWith(".log")
                || n.endsWith(".yml") || n.endsWith(".yaml") || n.endsWith(".sh") || n.endsWith(".sql")
                || n.endsWith(".ini") || n.endsWith(".toml") || n.endsWith(".conf");
    }
}
