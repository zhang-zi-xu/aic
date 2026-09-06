package com.nongxin.agent;

import com.nongxin.service.KnowledgeService;
import com.nongxin.service.PhenologyService;
import com.nongxin.service.impl.RiskServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgriToolsTest {
    private final KnowledgeService knowledge = mock(KnowledgeService.class);
    private final ToolRegistry registry = new AgriTools(knowledge, mock(PhenologyService.class), new RiskServiceImpl()).buildRegistry();

    @Test
    void knowledgeResultsExplicitlyMarkSourcesAsUnverified() {
        var hits = List.of(new KnowledgeService.Hit(null, 1));
        when(knowledge.search("灌溉", null, 3)).thenReturn(hits);
        when(knowledge.formatHits(hits)).thenReturn("本地条目内容");

        String result = registry.execute("search_agri_knowledge", Map.of("query", "灌溉"), new AgentContext("test", Map.of()));

        assertThat(result).startsWith("【本地知识库，来源待核验】").contains("未逐条核验原文", "本地条目内容");
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
}
