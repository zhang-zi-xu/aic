package com.nongxin.service;

import java.util.Map;

/**
 * 对话 API Key 解析与成本护栏。
 *
 * 设计（面向不懂配置的农户与评委）：
 * - 用户自带 Key：直接放行，不计数（花的是用户自己的额度）；
 * - 未带 Key 且服务端配置了演示 Key：使用服务端 Key 并计入限额（按 IP + 全局双限额），
 *   超限或无法安全预留额度时明确拒绝并给出可读原因；
 * - 都没有：返回空 Key，由调用方按原有逻辑提示"请填写 API 密钥"。
 */
public interface ApiKeyService {

    enum Denial { QUOTA_EXHAUSTED, QUOTA_UNAVAILABLE, CLIENT_UNAVAILABLE, SERVER_ENDPOINT_UNAVAILABLE }

    String SERVER_ENDPOINT_UNAVAILABLE_MESSAGE =
            "服务端自定义供应商尚未配置可信的演示接口，已暂停本次演示请求。请联系管理员使用预设供应商，"
                    + "或在设置中填入自己的 API Key 和接口地址继续使用。";

    /** Credential-free selection; serverSide is derived only from server configuration/key selection. */
    record ModelSelection(String provider, String model, boolean keyAvailable, boolean serverSide) {
        /** No trusted server custom endpoint exists yet; a client URL must never fill that gap. */
        public boolean serverEndpointUnavailable() {
            return serverSide && "custom".equals(provider);
        }
    }

    ModelSelection select(String requestApiKey, String requestProvider, String requestModel);

    /**
     * @param apiKey        实际可用的 Key（拒绝请求时为空）
     * @param serverSide    是否使用了服务端兜底 Key（true 时需要计数）
     * @param denyReason    非空表示应拒绝该请求，调用方按 denial 区分额度耗尽与暂不可用
     * @param provider      实际使用的供应商（服务端兜底时使用服务端配置）
     * @param model         实际使用的模型（服务端兜底时使用服务端配置）
     * @param denial        机器可读拒绝原因；为空表示没有拒绝
     */
    record Resolution(String apiKey, boolean serverSide, String denyReason, String provider, String model, Denial denial) {
        public Resolution {
            if (denial != null && (denyReason == null || denyReason.isBlank())) {
                throw new IllegalArgumentException("A denial requires a readable reason");
            }
            if (denyReason != null) {
                apiKey = "";
                if (denial == null) denial = Denial.QUOTA_UNAVAILABLE;
            }
        }

        /** Compatibility for success/no-key results; untyped errors conservatively mean unavailable. */
        public Resolution(String apiKey, boolean serverSide, String denyReason, String provider, String model) {
            this(apiKey, serverSide, denyReason, provider, model, null);
        }

        public boolean allowed() {
            return denial == null;
        }
    }

    /** Synchronous callers may capture on the current servlet thread; do not use from queued work. */
    default Resolution resolve(String requestApiKey, String requestProvider, String requestModel) {
        return resolve(requestApiKey, requestProvider, requestModel, QuotaClient.captureCurrent());
    }

    /** Async callers explicitly pass the immutable source captured before dispatch. */
    Resolution resolve(String requestApiKey, String requestProvider, String requestModel, QuotaClient client);

    /** 供前端与运维查看：是否启用兜底、今日用量与剩余额度 */
    default Map<String, Object> status() {
        return status(QuotaClient.captureCurrent());
    }

    Map<String, Object> status(QuotaClient client);
}
