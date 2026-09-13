package com.nongxin.controller;

import com.nongxin.service.EmbeddingService;
import com.nongxin.service.KnowledgeGraphService;
import com.nongxin.service.KnowledgeLibrary;
import com.nongxin.service.VectorIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RAG 向量索引管理：
 * - GET  /api/kb/status   查看混合检索状态（片段数 / 已索引数 / 模型 / 是否就绪）
 * - POST /api/kb/reindex  建立或重建向量索引（可携带 apiKey，用于"用户自带 Key"场景）
 */
@RestController
@RequestMapping("/api/kb")
public class KbIndexController {

    private static final Logger log = LoggerFactory.getLogger(KbIndexController.class);

    private final KnowledgeLibrary library;
    private final EmbeddingService embedding;
    private final VectorIndexService vectorIndex;
    private final KnowledgeGraphService graph;

    public KbIndexController(KnowledgeLibrary library, EmbeddingService embedding,
                             VectorIndexService vectorIndex, KnowledgeGraphService graph) {
        this.library = library;
        this.embedding = embedding;
        this.vectorIndex = vectorIndex;
        this.graph = graph;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("chunks", library.chunks().size());
        out.put("documents", library.documents().size());
        out.put("verifiedDocuments", library.documents().stream().filter(d -> d.verified()).count());
        out.put("embeddingConfigured", embedding.available());
        out.put("embeddingModel", embedding.modelName());
        out.put("indexedChunks", vectorIndex.indexedCount());
        out.put("indexedModel", vectorIndex.indexedModel());
        out.put("vectorReady", vectorIndex.ready());
        out.put("mode", vectorIndex.ready() ? "hybrid(keyword+vector+graph, RRF)" : "keyword(+graph)");
        out.put("graph", graph.stats());
        return out;
    }

    @PostMapping("/reindex")
    public ResponseEntity<?> reindex(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> payload = body == null ? Map.of() : body;
        Object key = payload.get("apiKey");
        if (key instanceof String s && !s.isBlank()) {
            embedding.updateApiKey(s);
        }
        boolean force = Boolean.TRUE.equals(payload.get("force"));
        if (!embedding.available()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "未配置 embedding 密钥：请在请求体传入 apiKey，或设置环境变量 NONGXIN_EMBEDDING_KEY");
            return ResponseEntity.badRequest().body(err);
        }
        long start = System.currentTimeMillis();
        int written = vectorIndex.indexChunks(library.chunks(), force);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", written >= 0);
        out.put("written", written);
        out.put("indexedChunks", vectorIndex.indexedCount());
        out.put("model", embedding.modelName());
        out.put("elapsedMs", System.currentTimeMillis() - start);
        out.put("mode", vectorIndex.ready() ? "hybrid(keyword+vector, RRF)" : "keyword-only");
        log.info("向量索引任务结束：written={} indexed={} 用时 {}ms", written, vectorIndex.indexedCount(), out.get("elapsedMs"));
        return ResponseEntity.ok(out);
    }
}
