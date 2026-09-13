package com.nongxin.agent;

import com.nongxin.model.FieldProfile;
import com.nongxin.model.KnowledgeChunk;
import com.nongxin.model.KnowledgeDocument;
import com.nongxin.service.KnowledgeLibrary;
import com.nongxin.service.PhenologyService;
import com.nongxin.service.impl.RiskServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgriToolsTest {
    private final KnowledgeLibrary knowledge = mock(KnowledgeLibrary.class);
    private final PhenologyService phenology = mock(PhenologyService.class);
    private final com.nongxin.service.TaskService tasks = mock(com.nongxin.service.TaskService.class);
    private final ToolRegistry registry = new AgriTools(knowledge, phenology, new RiskServiceImpl(), tasks,
            mock(com.nongxin.service.UploadService.class)).buildRegistry();

    private KnowledgeLibrary.SourcedHit hit(String chunkId, String crop, String region, boolean verified) {
        KnowledgeChunk chunk = new KnowledgeChunk(chunkId, "doc-1", "标题", "正文第一节", crop, region, "抽穗期",
                "病害防治", "原文摘录内容", List.of("稻瘟病"));
        KnowledgeDocument document = new KnowledgeDocument("doc-1", "来源标题", "某机构", "https://example.org/a",
                "2025-08-09", "2026-09-08", region, List.of(crop), "病害防治", "2025年发布版", "公开信息",
                verified ? "verified" : "unverified", null);
        return new KnowledgeLibrary.SourcedHit(chunk, document, 10);
    }

    @Test
    void knowledgeToolReturnsSourceIdsAndRecordsThemInContext() {
        var hits = List.of(hit("chunk-pest-rice-blast", "水稻", "全国", true));
        when(knowledge.search("稻瘟病", "水稻", null, 3)).thenReturn(hits);
        when(knowledge.formatForModel(hits)).thenReturn("【可引用的资料依据】…");
        AgentContext ctx = new AgentContext("test", Map.of("field", new FieldProfile("f1", "甲田", "水稻", null, "2026-06-01", null, null, List.of())));

        String result = registry.execute("search_agri_knowledge", Map.of("query", "稻瘟病"), ctx);

        assertThat(result).contains("可引用的资料依据");
        assertThat(ctx.extra(AgriTools.RETRIEVED_CHUNKS)).isEqualTo(Set.of("chunk-pest-rice-blast"));
    }

    @Test
    void knowledgeToolFiltersByFieldCropAndMappedRegion() {
        when(knowledge.search(any(), any(), any(), anyInt())).thenReturn(List.of(hit("chunk-1", "水稻", "浙江", true)));
        when(knowledge.formatForModel(any())).thenReturn("文本");
        AgentContext ctx = new AgentContext("test", Map.of(
                "field", new FieldProfile("f1", "甲田", "水稻", null, "2026-06-01", null, null, List.of()),
                "location", Map.of("label", "杭州市")));

        registry.execute("search_agri_knowledge", Map.of("query", "高温热害"), ctx);

        org.mockito.Mockito.verify(knowledge).search("高温热害", "水稻", "浙江", 3);
    }

    @Test
    void noHitsTellsTheModelThereIsNoEvidenceInsteadOfInventing() {
        when(knowledge.search(any(), any(), any(), anyInt())).thenReturn(List.of());
        AgentContext ctx = new AgentContext("test", Map.of());

        String result = registry.execute("search_agri_knowledge", Map.of("query", "某种未知病害"), ctx);

        assertThat(result).contains("没有检索到", "不要凭记忆补充");
        assertThat(ctx.extra(AgriTools.RETRIEVED_CHUNKS)).isNull();
    }

    @Test
    void planEvidenceOutsideThisRetrievalIsRemoved() {
        when(knowledge.search(any(), any(), any(), anyInt())).thenReturn(List.of(hit("chunk-allowed", "水稻", "全国", true)));
        when(knowledge.formatForModel(any())).thenReturn("文本");
        AgentContext ctx = new AgentContext("test", Map.of());
        registry.execute("search_agri_knowledge", Map.of("query", "稻瘟病"), ctx);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("date", "2026-09-10");
        item.put("task", "防治稻瘟病");
        item.put("condition", "田间初见病斑");
        item.put("review", "7天后复查");
        item.put("evidence", new ArrayList<>(List.of("chunk-allowed", "chunk-invented")));
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("title", "水稻病害方案");
        args.put("crop", "水稻");
        args.put("fieldName", "甲田");
        args.put("summary", "按资料执行");
        args.put("items", new ArrayList<>(List.of(item)));

        String result = registry.execute("submit_farm_plan", args, ctx);

        assertThat(result).contains("已登记处方单", "1 个依据不在本次检索结果中");
        List<?> sanitized = (List<?>) args.get("items");
        List<?> evidence = (List<?>) ((Map<?, ?>) sanitized.getFirst()).get("evidence");
        assertThat(evidence.stream().map(String::valueOf).toList()).containsExactly("chunk-allowed");
    }

    @Test
    void questionAboutAnotherCropIsRetrievedForThatCropAndFlaggedAsNotForThisField() {
        var wheatHit = List.of(hit("chunk-pest-wheat-scab", "小麦", "黄淮", true));
        when(knowledge.detectCrop("小麦赤霉病怎么防")).thenReturn("小麦");
        when(knowledge.search("小麦赤霉病怎么防", "小麦", null, 3)).thenReturn(wheatHit);
        when(knowledge.formatForModel(wheatHit)).thenReturn("小麦资料文本");
        AgentContext ctx = new AgentContext("test", Map.of("field",
                new FieldProfile("f1", "甲田", "水稻", null, "2026-06-01", null, null, List.of())));

        String result = registry.execute("search_agri_knowledge", Map.of("query", "小麦赤霉病怎么防"), ctx);

        assertThat(result).contains("小麦资料文本")
                .contains("与当前田块作物（水稻）不同")
                .contains("不得用于当前田块的处方");
        assertThat(ctx.extra(AgriTools.RETRIEVED_CHUNKS)).isEqualTo(Set.of("chunk-pest-wheat-scab"));
    }

    @Test
    void clarificationResultRequiresSubstanceBeforeTheCard() {
        String result = registry.execute("submit_clarify",
                Map.of("intro", "补充两点", "items", List.of(Map.of("question", "田里有病斑吗"))), new AgentContext("test", Map.of()));

        assertThat(result).contains("已登记确认清单", "先用 1-2 句给出用户马上能用的结论或判断")
                .contains("这一轮不要提交处方单")
                .doesNotContain("告知用户信息已记录");
    }

    @Test
    void nonListAndNestedEvidenceCannotBypassValidation() {
        when(knowledge.search(any(), any(), any(), anyInt())).thenReturn(List.of(hit("chunk-allowed", "水稻", "全国", true)));
        when(knowledge.formatForModel(any())).thenReturn("文本");
        AgentContext ctx = new AgentContext("test", Map.of());
        registry.execute("search_agri_knowledge", Map.of("query", "稻瘟病"), ctx);

        Map<String, Object> stringEvidence = new LinkedHashMap<>();
        stringEvidence.put("evidence", "chunk-fake-string");
        Map<String, Object> mapEvidence = new LinkedHashMap<>();
        mapEvidence.put("evidence", Map.of("id", "chunk-fake-map"));
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("steps", List.of(Map.of("evidence", new ArrayList<>(List.of("chunk-fake-nested", "chunk-allowed")))));
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("title", "方案");
        args.put("crop", "水稻");
        args.put("fieldName", "甲田");
        args.put("summary", "要点");
        args.put("items", new ArrayList<>(List.of(stringEvidence, mapEvidence, nested)));

        String result = registry.execute("submit_farm_plan", args, ctx);

        List<?> items = (List<?>) args.get("items");
        assertThat(((Map<?, ?>) items.get(0)).get("evidence")).isEqualTo(List.of());
        assertThat(((Map<?, ?>) items.get(1)).get("evidence")).isEqualTo(List.of());
        List<?> steps = (List<?>) ((Map<?, ?>) items.get(2)).get("steps");
        assertThat(((Map<?, ?>) steps.getFirst()).get("evidence")).isEqualTo(List.of("chunk-allowed"));
        assertThat(result).contains("3 个依据不在本次检索结果中");
    }

    @Test
    void emptyClarificationIsNotReportedAsSuccessful() {
        String result = registry.execute("submit_clarify", Map.of("intro", "补充信息", "items", List.of()), new AgentContext("test", Map.of()));

        assertThat(result).startsWith("工具执行失败").doesNotContain("已登记");
    }

    @Test
    void missingFieldDoesNotRequireAnAccountOrInventAgronomicFacts() {
        String result = registry.execute("get_field_context", Map.of(), new AgentContext("test", Map.of()));

        assertThat(result).contains("本次未提供田块档案", "可继续回答一般问题").doesNotContain("水稻", "当前处于");
    }

    /** 田块上下文必须带上"用户真的做了什么"：任务状态与执行/复查记录。 */
    @Test
    void fieldContextCarriesTasksAndUserRecords() {
        FieldProfile field = new FieldProfile("f1", "甲田", "水稻", null, "2026-06-01", null, null, List.of());
        when(phenology.getPhenology("水稻", "2026-06-01"))
                .thenReturn(new com.nongxin.model.PhenologyResult("抽穗期", 92, "播种后约 92 天，处于抽穗期"));
        when(tasks.forField("f1", 8)).thenReturn(List.of(
                new com.nongxin.model.FarmTask("t1", "破口期预防施药", "2026-09-12", "f1", "甲田", "雨停后", "喷雾", "5 天后查病斑", "",
                        "awaiting_review", null, "破口前 3—5 天", "待确认", "避开高温", List.of(), List.of(), "p1", "m-1",
                        "2026-09-11T09:00:00Z", null, null, "2026-09-12T08:00:00Z", null,
                        List.of(new com.nongxin.model.TaskRecord("r1", "t1", "f1", "execution", "2026-09-12",
                                "上午完成喷施，风力 2 级", "", "m-2", "2026-09-12T09:00:00Z"))),
                new com.nongxin.model.FarmTask("t2", "齐穗期补药", "2026-09-18", "f1", "甲田", "", "", "", "",
                        "pending_confirmation", null, "", "", "", List.of(), List.of(), "p2", "m-1",
                        "2026-09-11T09:00:00Z", null, null, null, null, List.of())));

        String result = registry.execute("get_field_context", Map.of(), new AgentContext("test", Map.of("field", field)));

        assertThat(result).contains("农事任务与用户记录", "[已执行待复查] 2026-09-12 破口期预防施药")
                .contains("执行记录 2026-09-12：上午完成喷施，风力 2 级")
                .contains("[待确认] 2026-09-18 齐穗期补药")
                .contains("标「待确认/待执行」的任务还没做，不得说成已完成")
                .contains("复查后调整建议时必须说明哪里变了、为什么变");
    }

    /** 处方单的每个方案项都要带稳定 ID，前端据此做幂等登记。 */
    @Test
    void planItemsCarryStableIdsForIdempotentRegistration() {
        when(knowledge.search(any(), any(), any(), anyInt())).thenReturn(List.of(hit("chunk-allowed", "水稻", "全国", true)));
        when(knowledge.formatForModel(any())).thenReturn("文本");
        AgentContext ctx = new AgentContext("test", Map.of());
        registry.execute("search_agri_knowledge", Map.of("query", "稻瘟病"), ctx);

        Map<String, Object> first = new LinkedHashMap<>(Map.of("task", "施药", "evidence", List.of("chunk-allowed")));
        Map<String, Object> second = new LinkedHashMap<>(Map.of("task", "观察", "evidence", List.of()));
        Map<String, Object> args = new LinkedHashMap<>(Map.of("title", "方案", "crop", "水稻", "fieldName", "甲田",
                "summary", "要点", "items", new ArrayList<>(List.of(first, second))));

        registry.execute("submit_farm_plan", args, ctx);

        List<?> items = (List<?>) args.get("items");
        assertThat(((Map<?, ?>) items.get(0)).get("itemId")).isEqualTo("p1");
        assertThat(((Map<?, ?>) items.get(1)).get("itemId")).isEqualTo("p2");
    }

    /** 模型就算自己塞了 itemId，也必须被稳定编号覆盖（防止伪造重复键）。 */
    @Test
    void modelSuppliedItemIdIsOverwritten() {
        AgentContext ctx = new AgentContext("test", Map.of());
        Map<String, Object> item = new LinkedHashMap<>(Map.of("task", "施药", "evidence", List.of(), "itemId", "whatever"));
        Map<String, Object> args = new LinkedHashMap<>(Map.of("title", "方案", "crop", "水稻", "fieldName", "甲田",
                "summary", "要点", "items", new ArrayList<>(List.of(item))));

        registry.execute("submit_farm_plan", args, ctx);

        assertThat(((Map<?, ?>) ((List<?>) args.get("items")).getFirst()).get("itemId")).isEqualTo("p1");
    }
}
