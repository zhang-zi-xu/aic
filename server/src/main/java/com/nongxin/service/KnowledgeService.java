package com.nongxin.service;

import com.nongxin.model.KbEntry;

import java.util.List;

/**
 * 农业知识库接口：条目加载 + 关键词加权检索。
 */
public interface KnowledgeService {

    record Hit(KbEntry entry, int score) {}

    int entryCount();

    List<KbEntry> list();

    /** 检索知识（cropFilter 可为 null），按相关度排序取前 topK 条 */
    List<Hit> search(String query, String cropFilter, int topK);

    /** 检索结果 → Agent 上下文文本 */
    String formatHits(List<Hit> hits);
}
