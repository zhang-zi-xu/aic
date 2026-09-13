package com.nongxin.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nongxin.model.KnowledgeChunk;
import com.nongxin.service.EmbeddingService;
import com.nongxin.service.VectorIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SQLite 向量索引实现：
 * - 持久化在 kb_vectors 表（chunk_id / model / dim / vector JSON），重启不重算；
 * - 内存缓存 float[] 便于快速余弦计算（片段数量级很小，无需专用向量库）。
 */
@Service
public class SqliteVectorIndexServiceImpl implements VectorIndexService {

    private static final Logger log = LoggerFactory.getLogger(SqliteVectorIndexServiceImpl.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final EmbeddingService embedding;

    /** chunkId → 向量（内存缓存） */
    private final Map<String, float[]> cache = new LinkedHashMap<>();
    private volatile String cacheModel = "";

    public SqliteVectorIndexServiceImpl(JdbcTemplate jdbc, ObjectMapper objectMapper, EmbeddingService embedding) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.embedding = embedding;
        loadCache();
    }

    private void loadCache() {
        try {
            cache.clear();
            String model = null;
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT chunk_id, model, vector FROM kb_vectors");
            for (Map<String, Object> row : rows) {
                String chunkId = String.valueOf(row.get("chunk_id"));
                String rowModel = String.valueOf(row.get("model"));
                if (model == null) model = rowModel;
                if (!rowModel.equals(model)) continue; // 混模型数据不可比，跳过
                float[] vec = parseVector(String.valueOf(row.get("vector")));
                if (vec != null) cache.put(chunkId, vec);
            }
            cacheModel = model == null ? "" : model;
            log.info("向量索引缓存载入：{} 条（model={}）", cache.size(), cacheModel);
        } catch (Exception e) {
            log.warn("向量索引缓存载入失败（表可能尚未创建）：{}", e.getMessage());
        }
    }

    @Override
    public int indexedCount() {
        return cache.size();
    }

    @Override
    public String indexedModel() {
        return cacheModel;
    }

    @Override
    public boolean ready() {
        return embedding.available()
                && !cache.isEmpty()
                && embedding.modelName().equals(cacheModel);
    }

    @Override
    public int indexChunks(List<KnowledgeChunk> chunks, boolean force) {
        if (!embedding.available() || chunks == null || chunks.isEmpty()) return -1;
        String model = embedding.modelName();
        if (force || !model.equals(cacheModel)) {
            clear();
        }
        List<KnowledgeChunk> todo = new ArrayList<>();
        for (KnowledgeChunk chunk : chunks) {
            if (force || !cache.containsKey(chunk.id())) todo.add(chunk);
        }
        if (todo.isEmpty()) return 0;

        List<String> texts = new ArrayList<>();
        for (KnowledgeChunk chunk : todo) {
            texts.add(embeddingText(chunk));
        }
        List<float[]> vectors = embedding.embedBatch(texts);
        if (vectors == null || vectors.size() != todo.size()) {
            log.warn("向量化失败：期望 {} 条，实际 {} 条", todo.size(), vectors == null ? 0 : vectors.size());
            return -1;
        }
        int written = 0;
        for (int i = 0; i < todo.size(); i++) {
            KnowledgeChunk chunk = todo.get(i);
            float[] vec = vectors.get(i);
            if (vec == null) continue;
            try {
                jdbc.update("INSERT OR REPLACE INTO kb_vectors (chunk_id, model, dim, vector, updated_at) VALUES (?,?,?,?,datetime('now','localtime'))",
                        chunk.id(), model, vec.length, toJson(vec));
                cache.put(chunk.id(), vec);
                written++;
            } catch (Exception e) {
                log.warn("写入向量失败 chunk={}：{}", chunk.id(), e.getMessage());
            }
        }
        cacheModel = model;
        log.info("向量索引更新：新增/更新 {} 条，累计 {} 条（model={}）", written, cache.size(), model);
        return written;
    }

    @Override
    public List<Scored> search(float[] queryVector, int topK) {
        List<Scored> out = new ArrayList<>();
        if (queryVector == null || cache.isEmpty()) return out;
        double qNorm = norm(queryVector);
        if (qNorm == 0) return out;
        for (Map.Entry<String, float[]> entry : cache.entrySet()) {
            float[] vec = entry.getValue();
            if (vec.length != queryVector.length) continue;
            double dot = 0;
            double vNorm = 0;
            for (int i = 0; i < vec.length; i++) {
                dot += vec[i] * queryVector[i];
                vNorm += vec[i] * vec[i];
            }
            if (vNorm == 0) continue;
            out.add(new Scored(entry.getKey(), dot / (Math.sqrt(vNorm) * qNorm)));
        }
        out.sort((a, b) -> Double.compare(b.similarity(), a.similarity()));
        return out.size() > topK ? new ArrayList<>(out.subList(0, topK)) : out;
    }

    @Override
    public void clear() {
        try {
            jdbc.update("DELETE FROM kb_vectors");
        } catch (Exception e) {
            log.warn("清空向量索引失败：{}", e.getMessage());
        }
        cache.clear();
        cacheModel = "";
    }

    /** 向量化文本构成：标题 + 关键词 + 正文（与关键词检索的字段权重思路一致，标题信息前置） */
    private String embeddingText(KnowledgeChunk chunk) {
        StringBuilder sb = new StringBuilder();
        if (chunk.heading() != null && !chunk.heading().isBlank()) sb.append(chunk.heading()).append('。');
        if (chunk.crop() != null && !chunk.crop().isBlank()) sb.append("作物：").append(chunk.crop()).append('。');
        if (chunk.topic() != null && !chunk.topic().isBlank()) sb.append("主题：").append(chunk.topic()).append('。');
        if (chunk.growthStage() != null && !chunk.growthStage().isBlank()) sb.append("生育期：").append(chunk.growthStage()).append('。');
        if (chunk.keywords() != null && !chunk.keywords().isEmpty()) sb.append("关键词：").append(String.join("、", chunk.keywords())).append('。');
        sb.append(chunk.text() == null ? "" : chunk.text());
        return sb.toString();
    }

    private String toJson(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vec[i]);
        }
        return sb.append(']').toString();
    }

    private float[] parseVector(String json) {
        try {
            double[] arr = objectMapper.readValue(json, double[].class);
            float[] vec = new float[arr.length];
            for (int i = 0; i < arr.length; i++) vec[i] = (float) arr[i];
            return vec;
        } catch (Exception e) {
            return null;
        }
    }

    private double norm(float[] vec) {
        double sum = 0;
        for (float v : vec) sum += v * v;
        return Math.sqrt(sum);
    }
}
