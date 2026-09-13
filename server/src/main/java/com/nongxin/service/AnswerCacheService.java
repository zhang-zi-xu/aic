package com.nongxin.service;

import java.util.Optional;

/**
 * 答案缓存：同问 + 同田块上下文直接复用上次结果，降低模型调用成本。
 *
 * 缓存策略（安全优先）：
 * - 缓存键包含：田块档案指纹（作物/品种/播期/面积）+ 归一化问题 + 是否有挂载数据；
 * - **含天气快照的请求不缓存**：天气随时效变化，缓存会导致"过期的施药窗口"被当成新答案；
 * - 缓存条目带 TTL（默认 24 小时），过期自动失效并清理。
 */
public interface AnswerCacheService {

    record Cached(String reply, String planJson, String riskJson, String clarifyJson) {}

    /** 读取缓存；未命中或已过期返回 empty */
    Optional<Cached> get(String cacheKey);

    /** 写入缓存（同键覆盖） */
    void put(String cacheKey, String reply, String planJson, String riskJson, String clarifyJson);

    /** 清理过期条目，返回删除条数 */
    int evictExpired();

    /** 统计信息（前端/运维可见） */
    java.util.Map<String, Object> stats();

    /**
     * 生成缓存键。
     *
     * @param fieldFingerprint 田块档案指纹（可为空）
     * @param question         用户问题原文（内部做归一化：去空白/标点、转小写）
     * @param hasAttachedData  是否附带农情数据（数据不同则答案不同，必须区分）
     * @param weatherSensitive 是否与实时天气相关；为 true 时调用方不应读写缓存
     */
    String buildKey(String fieldFingerprint, String question, boolean hasAttachedData, boolean weatherSensitive);
}
