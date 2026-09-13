package com.nongxin.model;

import java.util.List;

/**
 * 来源片段：必须挂在某篇文档下，并保留章节/定位信息，避免只有相似度分数和一段无出处文字。
 */
public record KnowledgeChunk(
        String id,
        String documentId,
        String heading,
        String locator,
        String crop,
        String region,
        String growthStage,
        String topic,
        String text,
        List<String> keywords) {}
