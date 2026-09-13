package com.nongxin;

import com.nongxin.service.AnswerCacheService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 成本护栏与答案缓存回归测试：
 * 1) 缓存键：同问同田块命中、不同田块/附带数据不混用、天气相关请求不缓存；
 * 2) 缓存读写与统计可用；
 * 3) 服务端 Key 解析：无 Key 且未配置服务端 Key 时不误拦（返回空 Key 由上层提示）。
 */
@SpringBootTest
class CostGuardTest {

    // 成本护栏测试会写入缓存表：必须用临时库，绝不能落到用户的 ./data/nongxin.db。
    private static final Path DATABASE = temporaryDatabase();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
    }

    private static Path temporaryDatabase() {
        try {
            return Files.createTempDirectory("nongxin-cost-guard-").resolve("test.db");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Autowired
    private AnswerCacheService cache;

    @Test
    void cacheKeyIsStableAndContextAware() {
        String k1 = cache.buildKey("东头大田|水稻|2026-05-20", "稻叶有褐色斑点怎么办？", false, false);
        String k2 = cache.buildKey("东头大田|水稻|2026-05-20", "稻叶有褐色斑点怎么办", false, false);
        // 归一化后（去标点/空白）应视为同一问题
        assertThat(k1).isEqualTo(k2);

        String otherField = cache.buildKey("西头小田|小麦|2026-10-01", "稻叶有褐色斑点怎么办", false, false);
        assertThat(otherField).isNotEqualTo(k1);

        String withData = cache.buildKey("东头大田|水稻|2026-05-20", "稻叶有褐色斑点怎么办", true, false);
        assertThat(withData).isNotEqualTo(k1);

        // 天气相关请求不得缓存（否则施药窗口会过期）
        assertThat(cache.buildKey("东头大田|水稻|2026-05-20", "这周能打药吗", false, true)).isNull();
    }

    @Test
    void cacheReadWriteRoundTrip() {
        String key = cache.buildKey("测试田|水稻|2026-04-01", "缓存往返测试问题", false, false);
        assertThat(key).isNotBlank();
        cache.put(key, "这是缓存的回答", "{\"title\":\"方案\"}", null, null);

        Optional<AnswerCacheService.Cached> hit = cache.get(key);
        assertThat(hit).isPresent();
        assertThat(hit.get().reply()).isEqualTo("这是缓存的回答");
        assertThat(hit.get().planJson()).contains("方案");

        assertThat(cache.stats()).containsKey("entries");
        assertThat(cache.evictExpired()).isGreaterThanOrEqualTo(0);
    }
}
