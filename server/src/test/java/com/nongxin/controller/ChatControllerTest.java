package com.nongxin.controller;

import com.nongxin.agent.AgentResult;
import com.nongxin.agent.AgentRunner;
import com.nongxin.agent.AgriTools;
import com.nongxin.agent.ToolRegistry;
import com.nongxin.agent.ToolSubmission;
import com.nongxin.model.ChatMsg;
import com.nongxin.model.ChatRequest;
import com.nongxin.model.ChatResponse;
import com.nongxin.model.KnowledgeChunk;
import com.nongxin.model.KnowledgeDocument;
import com.nongxin.service.ApiKeyService;
import com.nongxin.service.KnowledgeLibrary;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatControllerTest {
    private final AgentRunner runner = mock(AgentRunner.class);
    private final AgriTools tools = mock(AgriTools.class);
    private final KnowledgeLibrary library = mock(KnowledgeLibrary.class);
    private final ChatController controller = withPassthroughKeys(new ChatController(runner, tools, mock(ChatStreams.class), library,
            mock(com.nongxin.service.UploadService.class), new com.nongxin.service.VisionSupport()));

    /**
     * ChatController 的演示 Key 兜底走字段注入（并行开发时避免改构造器签名），
     * 单测里手工 new 出来的实例需要自己塞一个"用户自带 Key 直接放行"的实现，
     * 否则 apiKeys 为 null。成本护栏本身由 CostGuardTest 覆盖。
     */
    private static ChatController withPassthroughKeys(ChatController controller) {
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "apiKeys", new ApiKeyService() {
            @Override
            public Resolution resolve(String apiKey, String provider, String model) {
                return new Resolution(apiKey, false, null, provider, model);
            }

            @Override
            public Map<String, Object> status() {
                return Map.of("serverKeyConfigured", false);
            }
        });
        return controller;
    }

    @Test
    void answersWithoutOptionalFieldLocationOrWeather() {
        when(tools.buildRegistry()).thenReturn(new ToolRegistry());
        when(runner.run(any(), anyList())).thenReturn(new AgentResult("可以先查看土壤墒情。", List.of(), 1, false));

        var response = controller.chat(request(List.of(new ChatMsg("user", "如何判断土壤是否缺水？"))));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(((ChatResponse) response.getBody()).reply()).contains("土壤墒情");
        assertThat(((ChatResponse) response.getBody()).degraded()).isFalse();
        ArgumentCaptor<AgentRunner.Config> config = ArgumentCaptor.forClass(AgentRunner.Config.class);
        verify(runner).run(config.capture(), anyList());
        assertThat(config.getValue().ctx().extra("field")).isNull();
        assertThat(config.getValue().ctx().extra("location")).isNull();
        assertThat(config.getValue().systemPrompt())
                .contains("只能来自 search_agri_knowledge 返回的来源ID", "不要假设用户所在地区或种植作物");
    }

    @Test
    void clarifyingCardRoundDoesNotMarkACompleteAnswerAsPartial() {
        when(tools.buildRegistry()).thenReturn(new ToolRegistry());
        // 第一轮：正文完整、但口头说了"请确认"，没有卡片。
        when(runner.run(any(), anyList()))
                .thenReturn(new AgentResult("破口期是穗颈瘟关键期，最佳窗口是破口前3—5天。请确认田里有没有病斑。", List.of(), 1, false))
                // 补卡轮：拿到卡片，但 maxRounds=1 用尽 → AgentRunner 会返回 degraded=true 的兜底文案。
                .thenReturn(new AgentResult("工具调用轮次已用尽，已为你整理出上方结构化结果，可继续追问。",
                        List.of(new ToolSubmission("submit_clarify", Map.of("intro", "再确认两点", "items", List.of(Map.of("question", "有病斑吗"))), "已登记确认清单")), 1, true));

        var response = controller.chat(request(List.of(new ChatMsg("user", "稻瘟病怎么防"))));

        ChatResponse chat = (ChatResponse) response.getBody();
        assertThat(chat.degraded()).as("补卡轮的轮次用尽不代表回答不完整").isFalse();
        assertThat(chat.reply()).isEqualTo("破口期是穗颈瘟关键期，最佳窗口是破口前3—5天。请确认田里有没有病斑。");
        assertThat(chat.clarify()).isNotNull();
    }

    @Test
    void failedClarificationRetryDoesNotInventRiceQuestions() {
        when(tools.buildRegistry()).thenReturn(new ToolRegistry());
        when(runner.run(any(), anyList()))
                .thenReturn(new AgentResult("请告诉我需要了解哪种作物。", List.of(), 1, false))
                .thenThrow(new IllegalStateException("private upstream error"));

        var response = controller.chat(request(List.of(new ChatMsg("user", "帮我安排农事"))));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        ChatResponse chat = (ChatResponse) response.getBody();
        assertThat(chat.clarify()).isNull();
        assertThat(chat.reply()).isEqualTo("请告诉我需要了解哪种作物。");
        assertThat(chat.reply()).doesNotContain("稻", "叶斑", "private");
    }

    @Test
    void degradedToolResultsAreMarkedAndCardsStillReturned() {
        when(tools.buildRegistry()).thenReturn(new ToolRegistry());
        Map<String, Object> risk = Map.of("overall", "高", "items", List.of());
        when(runner.run(any(), anyList())).thenReturn(new AgentResult("本次对话处理超时，已生成的工具结果保留在下方。",
                List.of(new ToolSubmission("submit_risk_report", risk, "风险报告")), 2, true));

        var response = controller.chat(request(List.of(new ChatMsg("user", "分析湿度数据"))));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        ChatResponse chat = (ChatResponse) response.getBody();
        assertThat(chat.degraded()).isTrue();
        assertThat(chat.risk()).isEqualTo(risk);
        assertThat(chat.reply()).contains("保留在下方");
    }

    @Test
    void ignoresNullHistoryEntries() {
        when(tools.buildRegistry()).thenReturn(new ToolRegistry());
        when(runner.run(any(), anyList())).thenReturn(new AgentResult("请查看地块记录。", List.of(), 1, false));

        var response = controller.chat(request(Arrays.asList(null, new ChatMsg("user", "查看档案"))));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void keepsOnlyTheLastTwentyValidMessages() {
        when(tools.buildRegistry()).thenReturn(new ToolRegistry());
        when(runner.run(any(), anyList())).thenReturn(new AgentResult("好的。", List.of(), 1, false));
        List<ChatMsg> messages = new ArrayList<>();
        for (int i = 0; i < 25; i++) messages.add(new ChatMsg(i % 2 == 0 ? "user" : "assistant", "内容" + i));
        messages.set(24, new ChatMsg("user", "最后的问题"));

        controller.chat(request(messages));

        ArgumentCaptor<List<Map<String, Object>>> history = ArgumentCaptor.forClass(List.class);
        verify(runner).run(any(), history.capture());
        assertThat(history.getValue()).hasSize(20);
        assertThat(history.getValue().getFirst().get("content")).isEqualTo("内容5");
        assertThat(history.getValue().get(19).get("content")).isEqualTo("最后的问题");
    }

    @Test
    void rejectsMissingKeyBlankModelEmptyHistoryAndNonUserTail() {
        when(tools.buildRegistry()).thenReturn(new ToolRegistry());
        when(runner.run(any(), anyList())).thenReturn(new AgentResult("好的。", List.of(), 1, false));

        assertThat(controller.chat(request(List.of(new ChatMsg("user", "你好")), "test-model", "short")).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.chat(request(List.of(new ChatMsg("user", "你好")), " ", "test-key-no-provider-call")).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.chat(request(List.of(), "test-model", "test-key-no-provider-call")).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.chat(request(List.of(new ChatMsg("assistant", "没有用户问题")))).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void rejectsOversizedSingleMessageAndTooManyMessages() {
        when(tools.buildRegistry()).thenReturn(new ToolRegistry());
        when(runner.run(any(), anyList())).thenReturn(new AgentResult("好的。", List.of(), 1, false));

        assertThat(controller.chat(request(List.of(new ChatMsg("user", "字".repeat(20001))))).getStatusCode().value()).isEqualTo(400);
        List<ChatMsg> tooMany = new ArrayList<>();
        for (int i = 0; i < 501; i++) tooMany.add(new ChatMsg("user", "重复"));
        assertThat(controller.chat(request(tooMany)).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void hidesUnexpectedInternalErrors() {
        when(tools.buildRegistry()).thenThrow(new IllegalStateException("secret-key internal-host"));

        var response = controller.chat(request(List.of(new ChatMsg("user", "你好"))));

        assertThat(response.getStatusCode().value()).isEqualTo(502);
        assertThat(((Map<?, ?>) response.getBody()).get("error")).isNotNull();
        assertThat(response.getBody().toString()).doesNotContain("secret-key", "internal-host");
    }

    @Test
    void representsProviderTimeoutAsGatewayTimeout() {
        when(tools.buildRegistry()).thenReturn(new ToolRegistry());
        when(runner.run(any(), anyList())).thenThrow(new AgentRunner.ProviderException("连接对话供应商超时，请稍后重试", true));

        var response = controller.chat(request(List.of(new ChatMsg("user", "你好"))));

        assertThat(response.getStatusCode().value()).isEqualTo(504);
    }

    /** 模型不在看图名单里：直接拒绝，且**一次都不调用供应商**（不发伪视觉请求）。 */
    @Test
    void imagesAreRejectedForModelsWithoutVisionAndNothingIsSentToTheProvider() {
        when(tools.buildRegistry()).thenReturn(new ToolRegistry());
        com.nongxin.service.UploadService uploads = mock(com.nongxin.service.UploadService.class);
        when(uploads.find(any())).thenReturn(List.of(new com.nongxin.service.UploadService.Stored(
                "img-1", "image/jpeg", "jpg", 1024, 800, 600, "abc", "2026-09-11T09:00:00", null, null, "2026-09-11", "", null)));
        ChatController strict = withPassthroughKeys(new ChatController(runner, tools, mock(ChatStreams.class), library,
                uploads, new com.nongxin.service.VisionSupport()));

        var response = strict.chat(new ChatRequest("openai", "text-only-model", null, "test-key-no-provider-call",
                List.of(new ChatMsg("user", "看看这张叶子")), null, null, null, null, List.of("img-1"), null, null));

        assertThat(response.getStatusCode().value()).isEqualTo(415);
        assertThat(String.valueOf(((Map<?, ?>) response.getBody()).get("error"))).contains("不在支持看图的名单里");
        verify(runner, never()).run(any(), anyList());
    }

    /** 用户明确声明模型支持看图时：图片以多模态消息发给供应商，且只传 id、由服务端读盘。 */
    @Test
    void declaredVisionModelReceivesImagesAsMultimodalContent() {
        when(tools.buildRegistry()).thenReturn(new ToolRegistry());
        when(runner.run(any(), anyList())).thenReturn(new AgentResult("图上是叶缘焦枯。", List.of(), 1, false));
        com.nongxin.service.UploadService uploads = mock(com.nongxin.service.UploadService.class);
        when(uploads.find(any())).thenReturn(List.of(new com.nongxin.service.UploadService.Stored(
                "img-1", "image/jpeg", "jpg", 1024, 800, 600, "abc", "2026-09-11T09:00:00", null, null, "2026-09-11", "", null)));
        when(uploads.read("img-1")).thenReturn(new byte[] {1, 2, 3});
        ChatController withVision = withPassthroughKeys(new ChatController(runner, tools, mock(ChatStreams.class), library,
                uploads, new com.nongxin.service.VisionSupport()));

        withVision.chat(new ChatRequest("openai", "gpt-4o-mini", null, "test-key-no-provider-call",
                List.of(new ChatMsg("user", "看看这张叶子")), null, null, null, null, List.of("img-1"), "on", null));

        ArgumentCaptor<AgentRunner.Config> config = ArgumentCaptor.forClass(AgentRunner.Config.class);
        verify(runner).run(config.capture(), anyList());
        assertThat(config.getValue().systemPrompt()).contains("【图片排查 · 有照片时必须遵守】", "不得给出「置信度百分比」");
    }

    /** 模型不在名单里但用户手动开启时，同样按多模态发送（用户比名单更清楚自己的模型）。 */
    @Test
    void userOverrideAllowsUnknownModelToReceiveImages() {
        when(tools.buildRegistry()).thenReturn(new ToolRegistry());
        when(runner.run(any(), anyList())).thenReturn(new AgentResult("看到了。", List.of(), 1, false));
        com.nongxin.service.UploadService uploads = mock(com.nongxin.service.UploadService.class);
        when(uploads.find(any())).thenReturn(List.of(new com.nongxin.service.UploadService.Stored(
                "img-1", "image/jpeg", "jpg", 1024, 800, 600, "abc", "2026-09-11T09:00:00", null, null, "2026-09-11", "", null)));
        when(uploads.read("img-1")).thenReturn(new byte[] {9});
        ChatController withVision = withPassthroughKeys(new ChatController(runner, tools, mock(ChatStreams.class), library,
                uploads, new com.nongxin.service.VisionSupport()));

        var response = withVision.chat(new ChatRequest("openai", "unknown-vl-model-x", null, "test-key-no-provider-call",
                List.of(new ChatMsg("user", "看看这张叶子")), null, null, null, null, List.of("img-1"), "on", null));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(runner).run(any(), anyList());
    }

    /** 引用了不存在的图片 id：报错并且不调用供应商。 */
    @Test
    void unknownImageIdIsRejectedBeforeCallingTheProvider() {
        when(tools.buildRegistry()).thenReturn(new ToolRegistry());
        com.nongxin.service.UploadService uploads = mock(com.nongxin.service.UploadService.class);
        when(uploads.find(any())).thenReturn(List.of());
        ChatController strict = withPassthroughKeys(new ChatController(runner, tools, mock(ChatStreams.class), library,
                uploads, new com.nongxin.service.VisionSupport()));

        var response = strict.chat(new ChatRequest("openai", "gpt-4o-mini", null, "test-key-no-provider-call",
                List.of(new ChatMsg("user", "看看这张")), null, null, null, null, List.of("img-gone"), null, null));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(String.valueOf(((Map<?, ?>) response.getBody()).get("error"))).contains("已被清理");
        verify(runner, never()).run(any(), anyList());
    }

    /** 只汇报卡片、没有判断句时，服务端补一轮"只写判断段"，并把判断放在回答最前面。 */
    @Test
    void cardOnlyRepliesGetAVerdictParagraphAddedInFront() {
        when(tools.buildRegistry()).thenReturn(new ToolRegistry());
        when(runner.run(any(), anyList())).thenReturn(
                new AgentResult("处方单已经提交好了，一共 3 项：下田核查、补施药、复查。", List.of(planSubmission()), 3, false),
                new AgentResult("最可能是稻瘟病（叶瘟）：叶尖有梭形斑、边缘褐色；不太像胡麻斑，那种斑更圆、中心发白。如果穗颈发黑，我上面的判断就要改。", List.of(), 1, false));

        var response = controller.chat(request(List.of(new ChatMsg("user", "我的水稻到底怎么了？"))));

        String reply = ((ChatResponse) response.getBody()).reply();
        assertThat(reply).startsWith("最可能是稻瘟病");
        assertThat(reply).contains("处方单已经提交好了");
        assertThat(((ChatResponse) response.getBody()).plan()).isNotNull();
        verify(runner, times(2)).run(any(), anyList());
    }

    private static ToolSubmission planSubmission() {
        return new ToolSubmission("submit_farm_plan", Map.of("title", "水稻方案", "items",
                List.of(Map.of("task", "下田核查", "date", "2026-09-11", "window", "今天", "condition", "晴天",
                        "materials", "待确认", "review", "3 天后复查", "evidence", List.of()))), "已登记处方单");
    }

    @Test
    void mapsWorkerPoolSaturationToOverloadedFor429Mapping() {        ChatStreams streams = mock(ChatStreams.class);
        when(streams.open(any())).thenThrow(new ChatStreams.Overloaded());
        ChatController saturated = new ChatController(runner, tools, streams, library,
                mock(com.nongxin.service.UploadService.class), new com.nongxin.service.VisionSupport());

        assertThatThrownBy(() -> saturated.stream(request(List.of(new ChatMsg("user", "你好"))),
                new org.springframework.mock.web.MockHttpServletResponse()))
                .isInstanceOf(ChatStreams.Overloaded.class);
    }

    @Test
    void returnsOnlySourcesThatWereRetrievedAndExistInTheLibrary() {
        when(tools.buildRegistry()).thenReturn(new ToolRegistry());
        when(runner.run(any(), anyList())).thenAnswer(invocation -> {
            // Simulate the knowledge tool recording a retrieved chunk during this request.
            AgentRunner.Config config = invocation.getArgument(0);
            config.ctx().put(AgriTools.RETRIEVED_CHUNKS, java.util.Set.of("chunk-pest-rice-blast"));
            return new AgentResult("依据如下。", List.of(), 1, false);
        });
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("id", "chunk-pest-rice-blast");
        card.put("title", "来源标题");
        card.put("institution", "全国农技推广服务中心");
        card.put("url", "https://example.org/a");
        card.put("status", "verified");
        when(library.cards(any())).thenReturn(List.of(card));

        var response = controller.chat(request(List.of(new ChatMsg("user", "稻瘟病怎么防"))));

        ChatResponse chat = (ChatResponse) response.getBody();
        assertThat(chat.sources()).hasSize(1);
        assertThat(chat.sources().getFirst()).containsEntry("id", "chunk-pest-rice-blast")
                .containsEntry("status", "verified").containsEntry("institution", "全国农技推广服务中心")
                .containsEntry("url", "https://example.org/a");
    }

    @Test
    void noRetrievalMeansNoSourceCards() {
        when(tools.buildRegistry()).thenReturn(new ToolRegistry());
        when(runner.run(any(), anyList())).thenReturn(new AgentResult("没有查到依据。", List.of(), 1, false));

        var response = controller.chat(request(List.of(new ChatMsg("user", "某个问题"))));

        assertThat(((ChatResponse) response.getBody()).sources()).isEmpty();
    }

    @Test
    void sourcesRetrievedInEarlierTurnsStayCitable() {
        when(tools.buildRegistry()).thenReturn(new ToolRegistry());
        // 本轮没有重新检索（模型沿用前几轮的来源），正文引用了上一轮的来源ID
        when(runner.run(any(), anyList()))
                .thenReturn(new AgentResult("按 chunk-pest-rice-blast 的破口前 3—5 天窗口执行。", List.of(), 1, false));
        KnowledgeChunk chunk = new KnowledgeChunk("chunk-pest-rice-blast", "doc-1", "稻瘟病", "第一节", "水稻", "全国",
                "破口前", "病害防治", "原文摘录", List.of("稻瘟病"));
        when(library.resolve(any())).thenReturn(List.of(
                new KnowledgeLibrary.SourcedHit(chunk, null, 0)));

        var response = controller.chat(new ChatRequest("openai", "test-model", null, "test-key-no-provider-call",
                List.of(new ChatMsg("user", "那齐穗期呢？")), null, null, null, List.of("chunk-pest-rice-blast"), null, null, null));

        String reply = ((ChatResponse) response.getBody()).reply();
        assertThat(reply).contains("chunk-pest-rice-blast").doesNotContain("来源ID未在本次检索结果中");
    }

    @Test
    void fabricatedSourceIdsInTheReplyAreMarkedInsteadOfShown() {
        when(tools.buildRegistry()).thenReturn(new ToolRegistry());
        when(runner.run(any(), anyList())).thenAnswer(invocation -> {
            invocation.<AgentRunner.Config>getArgument(0).ctx()
                    .put(AgriTools.RETRIEVED_CHUNKS, java.util.Set.of("chunk-pest-rice-blast"));
            return new AgentResult("按 chunk-pest-rice-blast 与 chunk-fake-prose 处理。", List.of(), 1, false);
        });
        when(library.cards(any())).thenReturn(List.of(Map.of("id", "chunk-pest-rice-blast")));

        var response = controller.chat(request(List.of(new ChatMsg("user", "怎么防"))));

        String reply = ((ChatResponse) response.getBody()).reply();
        assertThat(reply).contains("chunk-pest-rice-blast").contains("（来源ID未在本次检索结果中）")
                .doesNotContain("chunk-fake-prose");
    }

    @Test
    void planEvidenceIsFilteredAgainBeforeReturning() {
        when(tools.buildRegistry()).thenReturn(new ToolRegistry());
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("task", "防治稻瘟病");
        item.put("evidence", new ArrayList<>(List.of("chunk-pest-rice-blast", "chunk-fake")));
        Map<String, Object> plan = Map.of("title", "方案", "items", new ArrayList<>(List.of(item)));
        when(runner.run(any(), anyList())).thenAnswer(invocation -> {
            invocation.<AgentRunner.Config>getArgument(0).ctx()
                    .put(AgriTools.RETRIEVED_CHUNKS, java.util.Set.of("chunk-pest-rice-blast"));
            return new AgentResult("好的。", List.of(new ToolSubmission("submit_farm_plan", plan, "方案")), 1, false);
        });
        when(library.cards(any())).thenReturn(List.of());

        var response = controller.chat(request(List.of(new ChatMsg("user", "安排农事"))));

        Map<String, Object> returned = ((ChatResponse) response.getBody()).plan();
        List<?> items = (List<?>) returned.get("items");
        List<?> evidence = (List<?>) ((Map<?, ?>) items.getFirst()).get("evidence");
        assertThat(evidence.stream().map(String::valueOf).toList()).containsExactly("chunk-pest-rice-blast");
    }

    @Test
    void exceptionHandlerMapsOverloadedTo429() {
        var response = new ApiExceptionHandler().overloaded();
        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(((Map<?, ?>) response.getBody()).get("error")).isEqualTo("当前对话请求较多，请稍后重试");
    }

    @Test
    void promptCarriesCurrentTimeAndAntiRepeatRules() {
        when(tools.buildRegistry()).thenReturn(new ToolRegistry());
        when(runner.run(any(), anyList())).thenReturn(new AgentResult("好的。", List.of(), 1, false));

        controller.chat(request(List.of(new ChatMsg("user", "稻瘟病怎么防"))));

        ArgumentCaptor<AgentRunner.Config> config = ArgumentCaptor.forClass(AgentRunner.Config.class);
        verify(runner, org.mockito.Mockito.atLeastOnce()).run(config.capture(), anyList());
        String prompt = config.getAllValues().get(0).systemPrompt();
        assertThat(prompt).contains("【当前时间】")
                .contains(String.valueOf(java.time.Year.now().getValue()))
                .contains("已经过去的时段")
                .contains("禁止出现整篇只写")
                .contains("不得原样重复追问")
                .contains("禁止再调用 submit_clarify")
                .contains("必须与引用来源一致")
                .contains("必须说明偏差")
                .contains("不得编造或推断用户所在的行政区划")
                .contains("一律用泛称")
                .contains("这是用户「加入任务」的唯一入口")
                .contains("时间安排必须与自己的结论一致")
                .contains("【执行与复查记录】")
                .contains("才算「真的做了」")
                .contains("必须说明「哪里变了、为什么变」")
                .contains("追问不是推迟给方案的理由")
                .contains("一轮一件事")
                .contains("信息没问完就不要给方案")
                .contains("正文长度预算");
    }

    private ChatRequest request(List<ChatMsg> messages) {
        return request(messages, "test-model", "test-key-no-provider-call");
    }

    private ChatRequest request(List<ChatMsg> messages, String model, String key) {
        return new ChatRequest("openai", model, null, key, messages, null, null, null, null, null, null, null);
    }
}
