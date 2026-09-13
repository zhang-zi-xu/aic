package com.nongxin.service.impl;

import com.nongxin.service.AnswerCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SQLite 答案缓存实现。
 * 键为 SHA-256 指纹，避免把用户问题原文当主键直接落库检索。
 */
@Service
public class AnswerCacheServiceImpl implements AnswerCacheService {

    private static final Logger log = LoggerFactory.getLogger(AnswerCacheServiceImpl.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final int ttlHours;

    public AnswerCacheServiceImpl(
            JdbcTemplate jdbc,
            @Value("${nongxin.cache.enabled:true}") boolean enabled,
            @Value("${nongxin.cache.ttl-hours:24}") int ttlHours) {
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.ttlHours = Math.max(1, ttlHours);
        log.info("答案缓存：{}，TTL {} 小时", enabled ? "启用" : "停用", this.ttlHours);
    }

    @Override
    public Optional<Cached> get(String cacheKey) {
        if (!enabled || cacheKey == null) return Optional.empty();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT reply, plan_json, risk_json, clarify_json, cached_at FROM answer_cache WHERE cache_key = ?", cacheKey);
            if (rows.isEmpty()) return Optional.empty();
            Map<String, Object> row = rows.get(0);
            String cachedAt = String.valueOf(row.get("cached_at"));
            if (isExpired(cachedAt)) {
                jdbc.update("DELETE FROM answer_cache WHERE cache_key = ?", cacheKey);
                return Optional.empty();
            }
            jdbc.update("UPDATE answer_cache SET hit_count = hit_count + 1 WHERE cache_key = ?", cacheKey);
            return Optional.of(new Cached(
                    str(row.get("reply")),
                    str(row.get("plan_json")),
                    str(row.get("risk_json")),
                    str(row.get("clarify_json"))));
        } catch (Exception e) {
            log.warn("读缓存失败（忽略并继续调用模型）：{}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void put(String cacheKey, String reply, String planJson, String riskJson, String clarifyJson) {
        if (!enabled || cacheKey == null || reply == null || reply.isBlank()) return;
        try {
            jdbc.update("INSERT OR REPLACE INTO answer_cache (cache_key, reply, plan_json, risk_json, clarify_json, cached_at, hit_count) "
                            + "VALUES (?,?,?,?,?,?, COALESCE((SELECT hit_count FROM answer_cache WHERE cache_key = ?), 0))",
                    cacheKey, reply, planJson, riskJson, clarifyJson, LocalDateTime.now().format(TS), cacheKey);
        } catch (Exception e) {
            log.warn("写缓存失败（不影响回答）：{}", e.getMessage());
        }
    }

    @Override
    public int evictExpired() {
        if (!enabled) return 0;
        try {
            String before = LocalDateTime.now().minusHours(ttlHours).format(TS);
            return jdbc.update("DELETE FROM answer_cache WHERE cached_at < ?", before);
        } catch (Exception e) {
            log.warn("清理过期缓存失败：{}", e.getMessage());
            return 0;
        }
    }

    @Override
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        out.put("ttlHours", ttlHours);
        try {
            Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM answer_cache", Integer.class);
            Integer hits = jdbc.queryForObject("SELECT COALESCE(SUM(hit_count),0) FROM answer_cache", Integer.class);
            out.put("entries", total == null ? 0 : total);
            out.put("totalHits", hits == null ? 0 : hits);
        } catch (Exception e) {
            out.put("statsError", e.getMessage());
        }
        return out;
    }

    @Override
    public String buildKey(String fieldFingerprint, String question, boolean hasAttachedData, boolean weatherSensitive) {
        if (!enabled || weatherSensitive) return null;
        String normalized = question == null ? "" : question
                .replaceAll("[\\s，。？！、；：\"'“”（）【】\\-—…·]", "")
                .toLowerCase();
        String raw = (fieldFingerprint == null ? "-" : fieldFingerprint) + "|" + normalized + "|" + (hasAttachedData ? "data" : "nodata");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 32);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isExpired(String cachedAt) {
        try {
            LocalDateTime time = LocalDateTime.parse(cachedAt, TS);
            return time.isBefore(LocalDateTime.now().minusHours(ttlHours));
        } catch (Exception e) {
            return true;
        }
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
