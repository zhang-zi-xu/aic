package com.nongxin.service;

import java.util.List;

/**
 * 文本向量化服务（embedding）。
 * 设计原则：可用时可作第二路语义召回；不可用（无 key / 网络失败）时整条链路自动降级，
 * 不得影响已有的关键词检索与引用校验。
 */
public interface EmbeddingService {

    /** 是否可用（配置了 key 且模型名非空） */
    boolean available();

    /** 当前模型名（用于向量索引的版本校验） */
    String modelName();

    /** 单条文本向量化；失败返回 null */
    float[] embed(String text);

    /** 批量向量化；失败返回 null */
    List<float[]> embedBatch(List<String> texts);

    /** 运行时注入 key（用户在前端填写后触发索引） */
    void updateApiKey(String apiKey);
}
