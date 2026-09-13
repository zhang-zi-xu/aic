package com.nongxin.service.impl;

import com.nongxin.service.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地向量实现（离线兜底 / 管线验证用）：
 * 中文按字符 n-gram 哈希到固定维度向量（带符号累加 + L2 归一化），
 * 不调用任何外部服务，因此断网、无 key 时也能跑通「向量化 → 索引 → 混合检索」全链路。
 *
 * 能力边界（必须在材料中如实说明）：
 * - 它能捕捉"字面部分重合"（叶瘟/白穗 ↔ 叶片发白），比纯关键词宽松；
 * - 但不具备真实语义模型的跨词泛化能力（"像开水烫过" → 稻瘟病 这类需要 bge-m3 等模型）。
 * 因此正式演示与评估使用 remote 模式（BAAI/bge-m3），本实现仅作离线降级与链路自检。
 */
@Service
@ConditionalOnProperty(name = "nongxin.embedding.mode", havingValue = "local")
public class LocalHashEmbeddingServiceImpl implements EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(LocalHashEmbeddingServiceImpl.class);
    private static final int DIM = 256;

    public LocalHashEmbeddingServiceImpl() {
        log.info("向量检索使用本地哈希实现（离线模式，仅字面近似，语义泛化弱于远程模型）");
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String modelName() {
        return "local-hash-ngram-256";
    }

    @Override
    public void updateApiKey(String apiKey) {
        // 本地实现无需 key
    }

    @Override
    public float[] embed(String text) {
        float[] vec = new float[DIM];
        if (text == null || text.isBlank()) return vec;
        String cleaned = text.replaceAll("[\\s，。？！、；：\"'“”（）【】\\-—…·%]", "");
        List<String> grams = new ArrayList<>();
        for (int i = 0; i < cleaned.length(); i++) {
            grams.add(String.valueOf(cleaned.charAt(i)));
            if (i + 1 < cleaned.length()) grams.add(cleaned.substring(i, i + 2));
        }
        for (String gram : grams) {
            int h = gram.hashCode();
            int index = Math.floorMod(h, DIM);
            float sign = ((h >>> 16) & 1) == 0 ? 1f : -1f;
            vec[index] += sign;
        }
        double norm = 0;
        for (float v : vec) norm += v * v;
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < DIM; i++) vec[i] /= (float) norm;
        }
        return vec;
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) return null;
        List<float[]> out = new ArrayList<>(texts.size());
        for (String text : texts) out.add(embed(text));
        return out;
    }
}
