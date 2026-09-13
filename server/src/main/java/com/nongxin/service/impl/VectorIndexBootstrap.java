package com.nongxin.service.impl;

import com.nongxin.service.EmbeddingService;
import com.nongxin.service.KnowledgeLibrary;
import com.nongxin.service.VectorIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动时自动建立向量索引（后台线程，不阻塞服务启动）。
 * 未配置 embedding key 或索引已就绪时跳过；失败不影响服务可用性。
 */
@Component
public class VectorIndexBootstrap {

    private static final Logger log = LoggerFactory.getLogger(VectorIndexBootstrap.class);

    private final KnowledgeLibrary library;
    private final EmbeddingService embedding;
    private final VectorIndexService vectorIndex;

    public VectorIndexBootstrap(KnowledgeLibrary library, EmbeddingService embedding, VectorIndexService vectorIndex) {
        this.library = library;
        this.embedding = embedding;
        this.vectorIndex = vectorIndex;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void indexOnStartup() {
        if (!embedding.available()) {
            log.info("跳过向量索引：未配置 embedding 密钥（检索将使用纯关键词模式）");
            return;
        }
        if (vectorIndex.ready()) {
            log.info("向量索引已就绪：{} 条（model={}）", vectorIndex.indexedCount(), vectorIndex.indexedModel());
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                long start = System.currentTimeMillis();
                int written = vectorIndex.indexChunks(library.chunks(), false);
                log.info("启动向量索引完成：写入 {} 条，累计 {} 条，用时 {}ms",
                        written, vectorIndex.indexedCount(), System.currentTimeMillis() - start);
            } catch (Exception e) {
                log.warn("启动向量索引失败（不影响关键词检索）：{}", e.getMessage());
            }
        }, "vector-index-bootstrap");
        worker.setDaemon(true);
        worker.start();
    }
}
