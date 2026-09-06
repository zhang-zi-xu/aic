package com.nongxin.controller;

import com.nongxin.agent.AgentResult;
import com.nongxin.agent.AgentRunner;
import com.nongxin.agent.AgriTools;
import com.nongxin.agent.ToolRegistry;
import com.nongxin.model.ChatMsg;
import com.nongxin.model.ChatRequest;
import com.nongxin.model.ChatResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatControllerTest {
    private final AgentRunner runner = mock(AgentRunner.class);
    private final AgriTools tools = mock(AgriTools.class);
    private final ChatController controller = new ChatController(runner, tools);

    @Test
    void answersWithoutOptionalFieldLocationOrWeather() {
        when(tools.buildRegistry()).thenReturn(new ToolRegistry());
        when(runner.run(any(), anyList())).thenReturn(new AgentResult("可以先查看土壤墒情。", List.of(), 1, false));

        var response = controller.chat(request(List.of(new ChatMsg("user", "如何判断土壤是否缺水？"))));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(((ChatResponse) response.getBody()).reply()).contains("土壤墒情");
        ArgumentCaptor<AgentRunner.Config> config = ArgumentCaptor.forClass(AgentRunner.Config.class);
        verify(runner).run(config.capture(), anyList());
        assertThat(config.getValue().ctx().extra("field")).isNull();
        assertThat(config.getValue().ctx().extra("location")).isNull();
        assertThat(config.getValue().systemPrompt()).contains("来源待核验", "不要假设用户所在地区或种植作物");
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
    void ignoresNullHistoryEntries() {
        when(tools.buildRegistry()).thenReturn(new ToolRegistry());
        when(runner.run(any(), anyList())).thenReturn(new AgentResult("请查看地块记录。", List.of(), 1, false));

        var response = controller.chat(request(Arrays.asList(null, new ChatMsg("user", "查看档案"))));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
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

    private ChatRequest request(List<ChatMsg> messages) {
        return new ChatRequest("openai", "test-model", null, "test-key-no-provider-call", messages, null, null, null);
    }
}
