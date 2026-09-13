package com.nongxin.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nongxin.service.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 硅基流动（SiliconFlow）embedding 实现，默认模型 BAAI/bge-m3（中文语义表现好、有免费额度）。
 * 兼容任何 OpenAI 协议 /v1/embeddings 接口（可通过配置换成其它供应商）。
 * 无 key 时 available() 返回 false，检索链路自动退回纯关键词。
 */
@Service
@ConditionalOnProperty(name = "nongxin.embedding.mode", havingValue = "remote", matchIfMissing = true)
public class SiliconFlowEmbeddingServiceImpl implements EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(SiliconFlowEmbeddingServiceImpl.class);

    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final String endpoint;
    private final String model;
    private final int batchSize;

    private volatile String apiKey;

    public SiliconFlowEmbeddingServiceImpl(
            ObjectMapper objectMapper,
            @Value("${nongxin.embedding.endpoint:https://api.siliconflow.cn/v1/embeddings}") String endpoint,
            @Value("${nongxin.embedding.model:BAAI/bge-m3}") String model,
            @Value("${nongxin.embedding.api-key:}") String apiKey,
            @Value("${nongxin.embedding.batch-size:16}") int batchSize,
            @Value("${nongxin.embedding.timeout-ms:20000}") int timeoutMs) {
        this.objectMapper = objectMapper;
        this.endpoint = endpoint;
        this.model = model;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.batchSize = Math.max(1, batchSize);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        this.restClient = RestClient.builder().requestFactory(factory).build();
        if (this.apiKey.isEmpty()) {
            log.info("向量检索未启用（未配置 nongxin.embedding.api-key 或 NONGXIN_EMBEDDING_KEY），将使用纯关键词检索");
        } else {
            log.info("向量检索已配置：model={}", this.model);
        }
    }

    @Override
    public boolean available() {
        return apiKey != null && !apiKey.isBlank() && model != null && !model.isBlank();
    }

    @Override
    public String modelName() {
        return model;
    }

    @Override
    public void updateApiKey(String key) {
        this.apiKey = key == null ? "" : key.trim();
    }

    @Override
    public float[] embed(String text) {
        List<float[]> result = embedBatch(List.of(text == null ? "" : text));
        return result == null || result.isEmpty() ? null : result.get(0);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (!available() || texts == null || texts.isEmpty()) return null;
        List<float[]> out = new ArrayList<>();
        for (int start = 0; start < texts.size(); start += batchSize) {
            int end = Math.min(start + batchSize, texts.size());
            List<String> batch = texts.subList(start, end);
            List<float[]> part = requestBatch(batch);
            if (part == null) return null;
            out.addAll(part);
        }
        return out;
    }

    private List<float[]> requestBatch(List<String> batch) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("input", batch);
            body.put("encoding_format", "float");

            String raw = restClient.post()
                    .uri(endpoint)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(String.class);
            if (raw == null || raw.isBlank()) return null;

            JsonNode root = objectMapper.readTree(raw);
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                log.warn("embedding 返回结构异常：{}", raw.length() > 300 ? raw.substring(0, 300) : raw);
                return null;
            }
            List<float[]> vectors = new ArrayList<>();
            for (JsonNode item : data) {
                JsonNode arr = item.path("embedding");
                if (!arr.isArray()) return null;
                float[] vec = new float[arr.size()];
                for (int i = 0; i < arr.size(); i++) vec[i] = (float) arr.get(i).asDouble();
                vectors.add(vec);
            }
            return vectors.size() == batch.size() ? vectors : null;
        } catch (Exception e) {
            log.warn("embedding 调用失败：{}", e.getMessage());
            return null;
        }
    }
}
