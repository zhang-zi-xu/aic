package com.nongxin.model;

import java.util.List;

/**
 * 农技资料来源登记（一篇原文一条记录）。
 * reviewStatus: verified = 已逐条对照原文登记；unverified = 本地整理草稿，尚未核对原文。
 * url/fetchedAt 为 null 时表示没有可核验的原文出处，不得宣称已核验。
 */
public record KnowledgeDocument(
        String id,
        String title,
        String institution,
        String url,
        String publishedAt,
        String fetchedAt,
        String region,
        List<String> crops,
        String topic,
        String version,
        String license,
        String reviewStatus,
        String reviewNote) {

    public boolean verified() { return "verified".equals(reviewStatus); }
}
