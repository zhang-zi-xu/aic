package com.nongxin.model;

import java.util.List;

/** knowledge/sources.json 根结构：来源文档 + 片段。 */
public record KnowledgeBundle(List<KnowledgeDocument> documents, List<KnowledgeChunk> chunks) {}
