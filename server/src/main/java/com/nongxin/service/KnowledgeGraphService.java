package com.nongxin.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 轻量知识图谱（从来源片段自动构建，不引入图数据库）：
 * 节点 = 作物 / 主题 / 关键词 / 片段；边 = 归属关系 + 同文档共现关系。
 *
 * 作用：为检索提供"第三路召回"——当查询命中某个实体（如"水稻"+"稻瘟病"）时，
 * 图谱可扩展出同一实体下、以及同文档共现的相邻片段，补足纯文本相似度抓不到的结构关联。
 */
public interface KnowledgeGraphService {

    /** 图谱规模（节点/边） */
    Map<String, Object> stats();

    /**
     * 图谱扩展召回：基于查询实体与已命中片段，返回相邻片段 id（不含已命中项）。
     *
     * @param query       原始查询
     * @param crop        田块作物（用于实体聚焦）
     * @param hitChunkIds 已由关键词/向量路召回的片段
     * @param limit       最多返回条数
     */
    List<String> expand(String query, String crop, Set<String> hitChunkIds, int limit);
}
