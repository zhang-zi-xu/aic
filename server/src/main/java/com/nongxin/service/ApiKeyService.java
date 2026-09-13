package com.nongxin.service;

import java.util.Map;

/**
 * 对话 API Key 解析与成本护栏。
 *
 * 设计（面向不懂配置的农户与评委）：
 * - 用户自带 Key：直接放行，不计数（花的是用户自己的额度）；
 * - 未带 Key 且服务端配置了演示 Key：使用服务端 Key 并计入限额（按 IP + 全局双限额），
 *   超限时明确拒绝并给出可读原因，避免被刷爆；
 * - 都没有：返回空 Key，由调用方按原有逻辑提示"请填写 API 密钥"。
 */
public interface ApiKeyService {

    /**
     * @param requestApiKey 前端传入的 Key（可为空）
     * @param serverSide    是否使用了服务端兜底 Key（true 时需要计数）
     * @param denyReason    非空表示应拒绝该请求（超限），调用方应返回 429
     * @param provider      实际使用的供应商（服务端兜底时使用服务端配置）
     * @param model         实际使用的模型（服务端兜底时使用服务端配置）
     */
    record Resolution(String apiKey, boolean serverSide, String denyReason, String provider, String model) {
        public boolean allowed() {
            return denyReason == null;
        }
    }

    Resolution resolve(String requestApiKey, String requestProvider, String requestModel);

    /** 供前端与运维查看：是否启用兜底、今日用量与剩余额度 */
    Map<String, Object> status();
}
