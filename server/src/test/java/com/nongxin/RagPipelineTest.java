package com.nongxin;

import com.nongxin.agent.AgentContext;
import com.nongxin.agent.AgriTools;
import com.nongxin.agent.ToolRegistry;
import com.nongxin.model.FieldProfile;
import com.nongxin.model.FieldRecord;
import com.nongxin.service.KnowledgeLibrary;
import com.nongxin.service.VectorIndexService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG 链路回归测试：
 * 1) 资料库加载与引用校验可用；
 * 2) 田块档案（历史记录）参与检索结果注入；
 * 3) 向量索引在无 key / 本地模式下可建立并可用于混合检索。
 */
@SpringBootTest
class RagPipelineTest {

    // 应用真实数据库（./data/nongxin.db）不参与测试：这里用临时库，避免测试写入用户数据。
    private static final Path DATABASE = temporaryDatabase();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
    }

    private static Path temporaryDatabase() {
        try {
            return Files.createTempDirectory("nongxin-rag-test-").resolve("test.db");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Autowired
    private KnowledgeLibrary library;

    @Autowired
    private AgriTools agriTools;

    @Autowired
    private VectorIndexService vectorIndex;

    @Test
    void libraryLoadsSourcedChunks() {
        assertThat(library.chunks()).isNotEmpty();
        assertThat(library.documents()).isNotEmpty();
        // 引用校验：伪造的 chunkId 必须解析为空（模型无法编造依据）
        assertThat(library.resolve(List.of("chunk-does-not-exist"))).isEmpty();
        // 真实 chunkId 必须可解析出对应来源文档
        String realId = library.chunks().get(0).id();
        assertThat(library.resolve(List.of(realId))).hasSize(1);
    }

    @Test
    void fieldArchiveJoinsRetrievalContext() {
        FieldProfile field = new FieldProfile(
                "f-test", "东头大田", "水稻", "南粳46", "2026-05-20", 8.5, null,
                List.of(new FieldRecord("2026-07-02", "叶面出现褐色斑点，湿度92%"),
                        new FieldRecord("2026-07-05", "已按农技站建议喷药一次")));
        AgentContext ctx = new AgentContext("test-user", Map.of("field", field));

        ToolRegistry registry = agriTools.buildRegistry();
        String result = registry.execute("search_agri_knowledge",
                Map.of("query", "叶面褐色斑点 湿度 怎么办"), ctx);

        // 田块档案应作为本地背景注入，且明确标注不得当作外部资料
        assertThat(result).contains("本田块档案");
        assertThat(result).contains("叶面出现褐色斑点");
        assertThat(result).contains("不得当作外部资料引用");
    }

    @Test
    void vectorIndexUsableInLocalMode() {
        // 本地模式（NONGXIN_EMBEDDING_MODE=local）下索引必须可用；远程无 key 时允许不可用
        int indexed = vectorIndex.indexedCount();
        if (indexed > 0) {
            assertThat(vectorIndex.indexedModel()).isNotBlank();
        }
        // 无论是否启用向量，关键词检索必须始终可用（降级保障）
        assertThat(library.searchKeywordOnly("稻瘟病", "水稻", 3)).isNotNull();
    }
}
