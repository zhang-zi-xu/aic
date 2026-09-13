package com.nongxin.service.impl;

import com.nongxin.model.KnowledgeChunk;
import com.nongxin.service.KnowledgeGraphService;
import com.nongxin.service.KnowledgeLibrary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识图谱实现（内存邻接表，来源仍是知识片段——图谱只是索引结构，不新增事实）。
 *
 * 构图规则：
 * 1) 归属边：作物节点 / 主题节点 / 关键词节点 → 片段节点；
 * 2) 共现边：同一来源文档内的片段互为邻居（同一篇原文的上下文关联）；
 * 3) 扩展召回：从查询中识别实体 → 取实体邻居 + 已命中片段的共现邻居，按节点度数排序。
 *
 * 说明：节点集合完全由已核验/草稿片段推导，因此不会出现"图谱里有、资料库里没有"的凭空事实。
 */
@Service
public class KnowledgeGraphServiceImpl implements KnowledgeGraphService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeGraphServiceImpl.class);

    private record Node(String type, String name) {
        String key() { return type + ":" + name; }
    }

    private final KnowledgeLibrary library;

    /** 实体节点 → 片段 id 集合 */
    private final Map<String, Set<String>> entityToChunks = new LinkedHashMap<>();
    /** 片段 id → 实体节点集合（反向索引） */
    private final Map<String, Set<String>> chunkToEntities = new LinkedHashMap<>();
    /** 片段 id → 同文档共现片段 */
    private final Map<String, Set<String>> cooccurrence = new LinkedHashMap<>();
    private int edgeCount = 0;

    public KnowledgeGraphServiceImpl(@Lazy KnowledgeLibrary library) {
        this.library = library;
    }

    private volatile boolean built = false;

    @EventListener(ApplicationReadyEvent.class)
    public void build() {
        if (built) return;
        built = true;
        for (KnowledgeChunk chunk : library.chunks()) {
            List<Node> nodes = new ArrayList<>();
            if (notBlank(chunk.crop())) nodes.add(new Node("作物", chunk.crop()));
            if (notBlank(chunk.topic())) nodes.add(new Node("主题", chunk.topic()));
            if (notBlank(chunk.growthStage())) nodes.add(new Node("生育期", chunk.growthStage()));
            if (chunk.keywords() != null) {
                for (String keyword : chunk.keywords()) {
                    if (notBlank(keyword)) nodes.add(new Node("关键词", keyword));
                }
            }
            for (Node node : nodes) {
                entityToChunks.computeIfAbsent(node.key(), k -> new LinkedHashSet<>()).add(chunk.id());
                chunkToEntities.computeIfAbsent(chunk.id(), k -> new LinkedHashSet<>()).add(node.key());
                edgeCount++;
            }
            cooccurrence.computeIfAbsent(chunk.documentId(), k -> new LinkedHashSet<>()).add(chunk.id());
        }
        // 同文档片段互为邻居
        int coEdges = 0;
        for (Set<String> group : cooccurrence.values()) {
            if (group.size() < 2) continue;
            for (String a : group) {
                for (String b : group) {
                    if (!a.equals(b)) coEdges++;
                }
            }
        }
        log.info("知识图谱构建完成：实体节点 {} 个、归属边 {} 条、共现边 {} 条、片段 {} 个",
                entityToChunks.size(), edgeCount, coEdges, chunkToEntities.size());
    }

    @Override
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entityNodes", entityToChunks.size());
        out.put("chunkNodes", chunkToEntities.size());
        out.put("attributionEdges", edgeCount);
        out.put("documents", cooccurrence.size());
        Map<String, Integer> byType = new LinkedHashMap<>();
        for (String key : entityToChunks.keySet()) {
            String type = key.substring(0, key.indexOf(':'));
            byType.merge(type, 1, Integer::sum);
        }
        out.put("nodesByType", byType);
        return out;
    }

    @Override
    public List<String> expand(String query, String crop, Set<String> hitChunkIds, int limit) {
        if (query == null || query.isBlank() || limit <= 0) return List.of();
        Set<String> excluded = hitChunkIds == null ? Set.of() : hitChunkIds;

        // 1) 识别查询命中的实体节点（作物/主题/生育期/关键词的字面命中）
        Map<String, Integer> score = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : entityToChunks.entrySet()) {
            String name = entry.getKey().substring(entry.getKey().indexOf(':') + 1);
            if (name.length() < 2 || !query.contains(name)) continue;
            for (String chunkId : entry.getValue()) {
                if (excluded.contains(chunkId)) continue;
                score.merge(chunkId, 1, Integer::sum);
            }
        }

        // 2) 已命中片段的共现邻居（同文档上下文）
        if (!excluded.isEmpty()) {
            for (String hitId : excluded) {
                String documentId = documentOf(hitId);
                if (documentId == null) continue;
                for (String sibling : cooccurrence.getOrDefault(documentId, Set.of())) {
                    if (excluded.contains(sibling)) continue;
                    score.merge(sibling, 1, Integer::sum);
                }
            }
        }

        // 3) 作物聚焦：作物节点下的片段优先（与检索层的作物硬过滤保持一致）
        if (crop != null && !crop.isBlank()) {
            for (String chunkId : entityToChunks.getOrDefault("作物:" + crop, Set.of())) {
                if (!excluded.contains(chunkId)) score.merge(chunkId, 1, Integer::sum);
            }
        }

        return score.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, Integer> e) -> e.getValue()).reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    private final Map<String, String> chunkDocument = new LinkedHashMap<>();

    private String documentOf(String chunkId) {
        if (chunkDocument.isEmpty()) {
            for (KnowledgeChunk chunk : library.chunks()) chunkDocument.put(chunk.id(), chunk.documentId());
        }
        return chunkDocument.get(chunkId);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
