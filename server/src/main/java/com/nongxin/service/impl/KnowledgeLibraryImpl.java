package com.nongxin.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nongxin.model.KbData;
import com.nongxin.model.KbEntry;
import com.nongxin.model.KnowledgeBundle;
import com.nongxin.model.KnowledgeChunk;
import com.nongxin.service.EmbeddingService;
import com.nongxin.service.KnowledgeGraphService;
import com.nongxin.service.VectorIndexService;
import com.nongxin.model.KnowledgeDocument;
import com.nongxin.service.KnowledgeLibrary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 资料库实现：sources.json（已核验原文）+ kb.json（本地草稿，统一标为未核验）。
 * 打分权重：标题 10 > 关键词 8 > 作物 5 > 正文 3；同分时已核验优先。
 */
@Service
public class KnowledgeLibraryImpl implements KnowledgeLibrary {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeLibraryImpl.class);

    private static final List<String[]> SYNONYMS = List.of(
            new String[]{"稻瘟病", "稻瘟", "叶瘟", "穗颈瘟"},
            new String[]{"纹枯病", "纹枯", "云纹"},
            new String[]{"飞虱", "稻飞虱", "褐飞虱", "白背飞虱", "灰飞虱"},
            new String[]{"螟虫", "二化螟", "三化螟", "大螟", "钻心虫"},
            new String[]{"卷叶螟", "稻纵卷叶螟", "束叶"},
            new String[]{"稻曲病", "曲病"},
            new String[]{"赤霉病", "赤霉", "穗腐"},
            new String[]{"锈病", "条锈", "叶锈"},
            new String[]{"蚜虫", "麦蚜", "穗蚜"},
            new String[]{"干热风", "高温逼熟"},
            new String[]{"倒伏", "抗倒", "控旺"},
            new String[]{"涝", "积水", "洪涝", "渍", "梅涝"},
            new String[]{"晒田", "烤田", "控蘖"},
            new String[]{"药害", "烧叶", "畸形"},
            new String[]{"高温", "热害", "花粉败育"},
            new String[]{"磷酸二氢钾", "叶面肥", "叶面喷施"},
            new String[]{"穗肥", "追肥", "施肥"},
            new String[]{"破口", "抽穗", "齐穗"});

    /** 泛化 2-gram 会造成"柑橘黄龙病防治"命中一切含"防治"的片段，检索前先剔除。 */
    private static final Set<String> STOP_TERMS = Set.of(
            "防治", "防控", "管理", "技术", "措施", "方法", "注意", "工作", "情况", "发生",
            "进行", "加强", "做好", "及时", "各地", "要点", "指导", "意见", "如何", "怎么",
            "什么", "时候", "需要", "可以", "是否", "怎样", "为什么", "要求", "建议");

    private final List<KnowledgeDocument> documents = new ArrayList<>();
    private final List<KnowledgeChunk> chunks = new ArrayList<>();
    private final Map<String, KnowledgeChunk> chunksById = new LinkedHashMap<>();
    private final Map<String, KnowledgeDocument> documentsById = new LinkedHashMap<>();

    /** 混合检索组件（可为 null：向量不可用时整条链路降级为纯关键词） */
    private final EmbeddingService embeddingService;
    private final VectorIndexService vectorIndexService;
    private final int rrfK;
    private final int vectorTopK;
    /** 知识图谱（第三路召回：实体/共现扩展）；为 null 时该路自动跳过 */
    private final KnowledgeGraphService graphService;
    private final int graphTopK;

    @org.springframework.beans.factory.annotation.Autowired
    public KnowledgeLibraryImpl(
            @Value("${nongxin.kb-resource}") Resource draftResource,
            @Value("${nongxin.sources-resource:classpath:knowledge/sources.json}") Resource sourcesResource,
            @Value("${nongxin.retrieval.rrf-k:60}") int rrfK,
            @Value("${nongxin.retrieval.vector-top-k:12}") int vectorTopK,
            ObjectMapper objectMapper,
            ObjectProvider<EmbeddingService> embeddingProvider,
            ObjectProvider<VectorIndexService> vectorIndexProvider,
            ObjectProvider<KnowledgeGraphService> graphProvider,
            @Value("${nongxin.retrieval.graph-top-k:8}") int graphTopK) {
        this.embeddingService = embeddingProvider == null ? null : embeddingProvider.getIfAvailable();
        this.vectorIndexService = vectorIndexProvider == null ? null : vectorIndexProvider.getIfAvailable();
        // 图谱 bean 在 ApplicationReadyEvent 中构图，这里只保留引用（延迟调用）
        this.graphService = graphProvider == null ? null : graphProvider.getIfAvailable();
        this.graphTopK = Math.max(1, graphTopK);
        this.rrfK = Math.max(1, rrfK);
        this.vectorTopK = Math.max(1, vectorTopK);
        loadSources(sourcesResource, objectMapper);
        loadDrafts(draftResource, objectMapper);
        log.info("资料库加载完成：{} 篇来源文档（已核验 {} 篇）、{} 条片段；混合检索={}",
                documents.size(), documents.stream().filter(KnowledgeDocument::verified).count(), chunks.size(),
                embeddingService != null && embeddingService.available() ? "关键词 + 向量(RRF)" : "关键词（向量未启用）");
    }

    /**
     * 兼容构造器（测试与嵌入式使用）：不带向量与图谱组件，等价于纯关键词检索。
     * 生产环境由 Spring 注入完整构造器。
     */
    public KnowledgeLibraryImpl(Resource draftResource, Resource sourcesResource, ObjectMapper objectMapper) {
        this(draftResource, sourcesResource, 60, 12, objectMapper, null, null, null, 8);
    }
    private void loadSources(Resource resource, ObjectMapper json) {
        try {
            KnowledgeBundle bundle = json.readValue(resource.getInputStream(), KnowledgeBundle.class);
            if (bundle == null) return;
            if (bundle.documents() != null) for (KnowledgeDocument document : bundle.documents()) register(document);
            if (bundle.chunks() != null) for (KnowledgeChunk chunk : bundle.chunks()) {
                if (!documentsById.containsKey(chunk.documentId())) {
                    throw new IllegalStateException("片段 " + chunk.id() + " 指向不存在的文档 " + chunk.documentId());
                }
                if (chunksById.containsKey(chunk.id())) {
                    throw new IllegalStateException("片段 ID 重复：" + chunk.id());
                }
                chunks.add(chunk);
                chunksById.put(chunk.id(), chunk);
            }
        } catch (Exception e) {
            log.error("来源资料加载失败：{}", e.getMessage());
            throw new IllegalStateException("来源资料无法加载", e);
        }
    }

    /** 本地草稿统一登记为 unverified：没有 URL 与原文核验，绝不能宣称已核实。 */
    private void loadDrafts(Resource resource, ObjectMapper json) {
        try {
            KbData data = json.readValue(resource.getInputStream(), KbData.class);
            if (data == null || data.entries() == null) return;
            for (KbEntry entry : data.entries()) {
                String docId = "doc-draft-" + entry.id();
                register(new KnowledgeDocument(docId, entry.title(), "本地整理草稿（未核验）", null, null, null,
                        null, List.of(entry.crop()), entry.topic(), null, null, "unverified",
                        "本地整理条目，来源名称尚未逐条核验原文；不得据此宣称官方已确认或现行登记。"));
                KnowledgeChunk chunk = new KnowledgeChunk("chunk-draft-" + entry.id(), docId,
                        entry.title(), "本地整理条目（无原文定位）", entry.crop(), null, null, entry.topic(),
                        draftText(entry), entry.keywords() == null ? List.of() : entry.keywords());
                chunks.add(chunk);
                chunksById.put(chunk.id(), chunk);
            }
        } catch (Exception e) {
            log.error("本地草稿加载失败：{}", e.getMessage());
        }
    }

    private String draftText(KbEntry entry) {
        StringBuilder sb = new StringBuilder();
        if (entry.symptom() != null && !entry.symptom().isBlank()) sb.append("症状：").append(entry.symptom()).append('\n');
        if (entry.diagnosis() != null && !entry.diagnosis().isBlank()) sb.append("判断：").append(entry.diagnosis()).append('\n');
        if (entry.advice() != null && !entry.advice().isEmpty()) {
            sb.append("处置：");
            for (int i = 0; i < entry.advice().size(); i++) sb.append(i + 1).append(". ").append(entry.advice().get(i)).append('；');
            sb.append('\n');
        }
        if (entry.review() != null && !entry.review().isBlank()) sb.append("复查：").append(entry.review()).append('\n');
        if (entry.source() != null && !entry.source().isBlank()) sb.append("草稿标注来源：").append(entry.source());
        return sb.toString().trim();
    }

    private void register(KnowledgeDocument document) {
        if (documentsById.containsKey(document.id())) {
            throw new IllegalStateException("来源文档 ID 重复：" + document.id());
        }
        documents.add(document);
        documentsById.put(document.id(), document);
    }

    @Override
    public List<KnowledgeDocument> documents() {
        return documents.stream()
                .sorted(Comparator.comparing((KnowledgeDocument d) -> d.verified() ? 0 : 1).thenComparing(KnowledgeDocument::id))
                .toList();
    }

    @Override
    public List<KnowledgeChunk> chunks() {
        return List.copyOf(chunks);
    }

    @Override
    public List<SourcedHit> search(String query, String crop, String region, int topK) {
        if (query == null || query.isBlank() || topK <= 0) return List.of();
        Set<String> terms = expandTerms(ngrams(query));
        if (terms.isEmpty()) return List.of();
        String cropFinal = crop == null || crop.isBlank() ? null : crop.trim();
        String regionFinal = region == null || region.isBlank() ? null : region.trim();

        // 候选池：作物硬过滤（水稻问题绝不能拿小麦资料当依据），两条召回路径共用
        List<KnowledgeChunk> pool = chunks.stream()
                .filter(chunk -> cropFinal == null || chunk.crop() == null || chunk.crop().isBlank()
                        || "通用".equals(chunk.crop()) || cropFinal.equals(chunk.crop()))
                .toList();

        // ---- 路 A：关键词稀疏检索（保留标题/关键词级命中约束，防止正文偶然命中）----
        List<SourcedHit> keywordHits = rankByKeyword(pool, terms, regionFinal);

        // ---- 降级路径：向量不可用（无 key / 索引未就绪）时与纯关键词检索完全一致 ----
        boolean vectorUsable = embeddingService != null && embeddingService.available()
                && vectorIndexService != null && vectorIndexService.ready();
        if (!vectorUsable) {
            return keywordHits.stream().limit(topK).toList();
        }

        // ---- 路 B：向量语义检索（可召回"叶子像开水烫过"这类无字面重合的口语描述）----
        List<VectorIndexService.Scored> vectorHits = List.of();
        float[] queryVector = embeddingService.embed(query);
        if (queryVector != null) {
            Map<String, KnowledgeChunk> poolById = new LinkedHashMap<>();
            for (KnowledgeChunk chunk : pool) poolById.put(chunk.id(), chunk);
            vectorHits = vectorIndexService.search(queryVector, vectorTopK).stream()
                    .filter(scored -> poolById.containsKey(scored.chunkId()))
                    .toList();
        }

        // ---- 路 C：知识图谱扩展（实体命中 + 同文档共现），补足结构化关联 ----
        List<String> graphHits = List.of();
        if (graphService != null) {
            Set<String> already = new LinkedHashSet<>();
            for (SourcedHit hit : keywordHits) already.add(hit.chunk().id());
            for (VectorIndexService.Scored scored : vectorHits) already.add(scored.chunkId());
            Map<String, KnowledgeChunk> poolIndex = new LinkedHashMap<>();
            for (KnowledgeChunk chunk : pool) poolIndex.put(chunk.id(), chunk);
            graphHits = graphService.expand(query, cropFinal, already, graphTopK).stream()
                    .filter(poolIndex::containsKey)
                    .toList();
        }

        // ---- RRF 融合：score = Σ 1/(k + rank)，三路互补、无需调权重 ----
        Map<String, Double> fused = new LinkedHashMap<>();
        Map<String, SourcedHit> byId = new LinkedHashMap<>();
        for (int rank = 0; rank < keywordHits.size(); rank++) {
            SourcedHit hit = keywordHits.get(rank);
            fused.merge(hit.chunk().id(), 1.0 / (rrfK + rank + 1), Double::sum);
            byId.putIfAbsent(hit.chunk().id(), hit);
        }
        Map<String, KnowledgeChunk> chunkById = new LinkedHashMap<>();
        for (KnowledgeChunk chunk : pool) chunkById.put(chunk.id(), chunk);
        for (int rank = 0; rank < vectorHits.size(); rank++) {
            String chunkId = vectorHits.get(rank).chunkId();
            fused.merge(chunkId, 1.0 / (rrfK + rank + 1), Double::sum);
            KnowledgeChunk chunk = chunkById.get(chunkId);
            // 纯向量命中：score 记 1（表示"有依据但非字面命中"，排序由融合分决定）
            if (chunk != null) byId.putIfAbsent(chunkId, new SourcedHit(chunk, documentsById.get(chunk.documentId()), 1));
        }

        Map<String, KnowledgeChunk> graphChunkIndex = new LinkedHashMap<>();
        for (KnowledgeChunk chunk : pool) graphChunkIndex.put(chunk.id(), chunk);
        for (int rank = 0; rank < graphHits.size(); rank++) {
            String chunkId = graphHits.get(rank);
            fused.merge(chunkId, 1.0 / (rrfK + rank + 1), Double::sum);
            KnowledgeChunk chunk = graphChunkIndex.get(chunkId);
            if (chunk != null) byId.putIfAbsent(chunkId, new SourcedHit(chunk, documentsById.get(chunk.documentId()), 1));
        }

        return fused.entrySet().stream()
                .sorted((a, b) -> {
                    int cmp = Double.compare(b.getValue(), a.getValue());
                    return cmp != 0 ? cmp : a.getKey().compareTo(b.getKey());
                })
                .map(entry -> byId.get(entry.getKey()))
                .filter(Objects::nonNull)
                .limit(topK)
                .toList();
    }

    /** 关键词路排序（标题/关键词级命中约束 + 加权分）：抽成独立方法便于评估对照与复用。 */
    private List<SourcedHit> rankByKeyword(List<KnowledgeChunk> pool, Set<String> terms, String regionFinal) {
        return pool.stream()
                .filter(chunk -> headlineHit(chunk, terms))
                .map(chunk -> new SourcedHit(chunk, documentsById.get(chunk.documentId()),
                        weightedScore(chunk, terms, regionFinal)))
                .filter(hit -> hit.score() > 0)
                .sorted(Comparator
                        .comparingInt(SourcedHit::score).reversed()
                        .thenComparing(hit -> hit.chunk().id()))
                .toList();
    }

    @Override
    public List<SourcedHit> searchKeywordOnly(String query, String crop, int topK) {
        if (query == null || query.isBlank() || topK <= 0) return List.of();
        Set<String> terms = expandTerms(ngrams(query));
        if (terms.isEmpty()) return List.of();
        String cropFinal = crop == null || crop.isBlank() ? null : crop.trim();
        List<KnowledgeChunk> pool = chunks.stream()
                .filter(chunk -> cropFinal == null || chunk.crop() == null || chunk.crop().isBlank()
                        || "通用".equals(chunk.crop()) || cropFinal.equals(chunk.crop()))
                .toList();
        return rankByKeyword(pool, terms, null).stream().limit(topK).toList();
    }
    /**
     * 加权分：相关度 + 已核验原文优先（+4）+ 与请求地区同区优先（+4）。
     * 地区不做硬过滤——同一条资料常跨省适用，硬过滤会让结果随"是否勾选天气"而变化；
     * 改为加权排序，并在来源卡与模型文本里标注适用地区。
     */
    private int weightedScore(KnowledgeChunk chunk, Set<String> terms, String requestRegion) {
        int score = score(chunk, terms);
        if (score <= 0) return 0;
        KnowledgeDocument document = documentsById.get(chunk.documentId());
        if (document != null && document.verified()) score += 4;
        score += regionAffinity(requestRegion, chunk.region());
        return score;
    }

    /** 请求地区与片段地区的亲缘度：同省/同大区 4，全国性 1，其它 0。 */
    private static int regionAffinity(String requestRegion, String chunkRegion) {
        if (requestRegion == null || chunkRegion == null || chunkRegion.isBlank()) return 0;
        if (chunkRegion.contains("全国")) return 1;
        for (String alias : REGION_ALIASES.getOrDefault(requestRegion, List.of(requestRegion))) {
            if (chunkRegion.contains(alias)) return 4;
        }
        return 0;
    }

    /** 是否至少有一个检索词命中标题或关键词——纯正文命中不作为依据（作物词也不算）。 */
    private boolean headlineHit(KnowledgeChunk chunk, Set<String> terms) {
        String heading = chunk.heading() == null ? "" : chunk.heading().toLowerCase();
        List<String> keywords = chunk.keywords() == null ? List.of() : chunk.keywords();
        for (String term : terms) {
            String t = term.toLowerCase();
            if (heading.contains(t)) return true;
            if (keywords.stream().anyMatch(k -> k.toLowerCase().contains(t) || t.contains(k.toLowerCase()))) return true;
        }
        return false;
    }

    @Override
    public String formatForModel(List<SourcedHit> hits) {
        if (hits.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("【可引用的资料依据】只能引用下面列出的来源ID；不得引用未列出的来源，也不得凭记忆补充剂量或登记信息。\n");
        for (int i = 0; i < hits.size(); i++) {
            SourcedHit hit = hits.get(i);
            KnowledgeChunk chunk = hit.chunk();
            KnowledgeDocument document = hit.document();
            sb.append("\n[").append(i + 1).append("] 来源ID：").append(chunk.id())
                    .append("（").append(document != null && document.verified() ? "已核验原文" : "本地草稿·未核验原文").append("）\n");
            if (document != null) {
                sb.append("标题：").append(document.title()).append('\n')
                        .append("机构：").append(document.institution() == null ? "未登记" : document.institution()).append('\n')
                        .append("发布日期：").append(document.publishedAt() == null ? "未登记" : document.publishedAt()).append('\n')
                        .append("原文链接：").append(document.url() == null ? "无（未核验草稿）" : document.url()).append('\n');
            }
            sb.append("适用作物：").append(orUnknown(chunk.crop()))
                    .append("；适用地区：").append(orUnknown(chunk.region()))
                    .append("；生育期：").append(orUnknown(chunk.growthStage())).append('\n')
                    .append("定位：").append(orUnknown(chunk.heading())).append(" · ").append(orUnknown(chunk.locator())).append('\n')
                    .append("原文摘录：").append(chunk.text()).append('\n');
        }
        return sb.toString();
    }

    private String orUnknown(String value) {
        return value == null || value.isBlank() ? "未标注" : value;
    }

    @Override
    public List<SourcedHit> resolve(Collection<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) return List.of();
        List<SourcedHit> out = new ArrayList<>();
        for (String id : chunkIds) {
            if (id == null || id.isBlank()) continue;
            KnowledgeChunk chunk = chunksById.get(id.trim());
            if (chunk != null) out.add(new SourcedHit(chunk, documentsById.get(chunk.documentId()), 0));
        }
        return out;
    }

    @Override
    public String detectCrop(String query) {
        if (query == null || query.isBlank()) return null;
        for (KnowledgeChunk chunk : chunks) {
            String crop = chunk.crop();
            if (crop == null || crop.isBlank() || "通用".equals(crop)) continue;
            if (query.contains(crop.trim())) return crop.trim();
        }
        return null;
    }

    /** 来源卡由后端从资料库生成：状态与链接都不接受客户端输入。 */
    @Override
    public List<Map<String, Object>> cards(Collection<String> chunkIds) {        List<Map<String, Object>> out = new ArrayList<>();
        for (SourcedHit hit : resolve(chunkIds)) {
            KnowledgeChunk chunk = hit.chunk();
            KnowledgeDocument document = hit.document();
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("id", chunk.id());
            card.put("title", document == null ? "未登记来源" : document.title());
            card.put("institution", document == null ? null : document.institution());
            card.put("url", document == null ? null : document.url());
            card.put("publishedAt", document == null ? null : document.publishedAt());
            card.put("region", chunk.region());
            card.put("crop", chunk.crop());
            card.put("growthStage", chunk.growthStage());
            card.put("heading", chunk.heading());
            card.put("status", document != null && document.verified() ? "verified" : "unverified");
            card.put("excerpt", excerpt(chunk.text()));
            out.add(card);
        }
        return out;
    }

    private static String excerpt(String text) {
        if (text == null) return "";
        String trimmed = text.strip();
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200) + "…";
    }

    private int score(KnowledgeChunk chunk, Set<String> terms) {
        int score = 0;
        String heading = chunk.heading() == null ? "" : chunk.heading().toLowerCase();
        String body = chunk.text() == null ? "" : chunk.text().toLowerCase();
        String crop = chunk.crop() == null ? "" : chunk.crop().toLowerCase();
        List<String> keywords = chunk.keywords() == null ? List.of() : chunk.keywords();
        for (String term : terms) {
            String t = term.toLowerCase();
            if (heading.contains(t)) score += 10;
            else if (keywords.stream().anyMatch(k -> k.toLowerCase().contains(t) || t.contains(k.toLowerCase()))) score += 8;
            else if (!crop.isEmpty() && (crop.contains(t) || t.contains(crop))) score += 5;
            else if (body.contains(t)) score += 3;
        }
        return score;
    }

    private Set<String> ngrams(String query) {
        Set<String> out = new LinkedHashSet<>();
        String cleaned = query.replaceAll("[\\s，。？！、；：\"'“”（）【】\\-—…·%0-9a-zA-Z]", "");
        if (cleaned.length() >= 2 && !STOP_TERMS.contains(cleaned)) out.add(cleaned);
        for (int i = 0; i + 2 <= cleaned.length(); i++) {
            String gram = cleaned.substring(i, i + 2);
            if (!STOP_TERMS.contains(gram)) out.add(gram);
        }
        return out;
    }

    private Set<String> expandTerms(Set<String> terms) {
        Set<String> expanded = new LinkedHashSet<>(terms);
        for (String[] group : SYNONYMS) {
            boolean hit = false;
            for (String t : terms) {
                for (String alias : group) {
                    if (t.contains(alias) || alias.contains(t)) { hit = true; break; }
                }
                if (hit) break;
            }
            if (hit) expanded.addAll(List.of(group));
        }
        return expanded;
    }
}
