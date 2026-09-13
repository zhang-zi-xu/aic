package com.nongxin.service;

import com.nongxin.model.KnowledgeChunk;

import java.util.List;

/**
 * 知识片段向量索引（SQLite 持久化 + 内存缓存）。
 * 只存 chunkId → 向量；片段正文仍以来源库为唯一事实源。
 */
public interface VectorIndexService {

    record Scored(String chunkId, double similarity) {}

    /** 索引状态：已索引片段数 */
    int indexedCount();

    /** 索引所用模型名（与当前 embedding 模型不一致时需重建） */
    String indexedModel();

    /** 当前索引是否可用于检索（有向量且模型匹配） */
    boolean ready();

    /**
     * 为片段建立/更新向量索引（增量：只处理缺失或模型变更的片段）。
     * @return 本次新写入的向量条数；失败返回 -1
     */
    int indexChunks(List<KnowledgeChunk> chunks, boolean force);

    /** 向量相似检索（余弦），返回按相似度降序的片段 */
    List<Scored> search(float[] queryVector, int topK);

    /** 清空索引 */
    void clear();
}
