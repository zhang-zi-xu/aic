package com.nongxin.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nongxin.service.KnowledgeService;
import com.nongxin.service.PhenologyService;
import com.nongxin.service.impl.RiskServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AgentRunnerTest {
    private static final String ENDPOINT = "https://provider.invalid/v1/chat/completions";
    private static final String KEY = "never-contact-a-provider-key";
    private final ObjectMapper mapper = new ObjectMapper();
    private MockRestServiceServer server;
    private AgentRunner runner;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        runner = new AgentRunner(mapper, builder.build());
    }

    @Test
    void returnsComputedRiskAndPreservesProviderToolCallFormat() throws Exception {
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(toolResponse("{\"data\":\"降水量60毫米\"}"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT))
                .andExpect(jsonPath("$.messages[2].tool_calls[0].type").value("function"))
                .andExpect(jsonPath("$.messages[2].tool_calls[0].function.name").value("submit_risk_report"))
                .andExpect(jsonPath("$.messages[2].tool_calls[0].name").doesNotExist())
                .andExpect(jsonPath("$.messages[3].tool_call_id").value("call-1"))
                .andRespond(withSuccess(textResponse("请核实降水统计时段和田间积水情况。"), MediaType.APPLICATION_JSON));

        AgentResult result = runner.run(config(), history());

        assertThat(result.submissions()).hasSize(1);
        assertThat(result.submissions().get(0).args()).containsKeys("overall", "summary", "items").doesNotContainKey("data");
        assertThat(result.submissions().get(0).args().get("overall")).isEqualTo("高");
        assertThat(result.rounds()).isEqualTo(2);
        server.verify();
    }

    @Test
    void invalidJsonArgumentsDoNotBecomeSuccessfulSubmissions() throws Exception {
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(toolResponse("null"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(textResponse("需要有效数据才能评估。"), MediaType.APPLICATION_JSON));

        assertThat(runner.run(config(), history()).submissions()).isEmpty();
        server.verify();
    }

    @Test
    void failedRiskExecutionDoesNotReturnItsInputAsAReport() throws Exception {
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(toolResponse("{\"data\":\"\"}"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(textResponse("尚未提供数据。"), MediaType.APPLICATION_JSON));

        assertThat(runner.run(config(), history()).submissions()).isEmpty();
        server.verify();
    }

    @Test
    void preservesComputedRiskWhenProviderFailsToSummarize() throws Exception {
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(toolResponse("{\"data\":\"湿度90%\"}"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        AgentResult result = runner.run(config(), history());

        assertThat(result.degraded()).isTrue();
        assertThat(result.submissions()).hasSize(1);
        assertThat(result.submissions().get(0).args().get("overall")).isEqualTo("中");
        server.verify();
    }

    @Test
    void doesNotExposeProviderErrorBodies() {
        server.expect(requestTo(ENDPOINT)).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":{\"message\":\"" + KEY + " private-host\"}}"));

        assertThatThrownBy(() -> runner.run(config(), history()))
                .isInstanceOf(AgentRunner.ProviderException.class).hasMessageContaining("鉴权失败")
                .hasMessageNotContaining(KEY).hasMessageNotContaining("private-host");
        server.verify();
    }

    @Test
    void reportsTimeoutWithoutExposingTransportDetails() {
        server.expect(requestTo(ENDPOINT)).andRespond(request -> {
            throw new SocketTimeoutException(KEY + " transport details");
        });

        assertThatThrownBy(() -> runner.run(config(), history()))
                .isInstanceOfSatisfying(AgentRunner.ProviderException.class, error -> assertThat(error.timeout()).isTrue())
                .hasMessageContaining("超时").hasMessageNotContaining(KEY);
        server.verify();
    }

    @Test
    void handlesMalformedProviderResponse() {
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess("{\"choices\":\"invalid\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> runner.run(config(), history())).isInstanceOf(AgentRunner.ProviderException.class)
                .hasMessageContaining("有效的回答");
        server.verify();
    }

    private AgentRunner.Config config() {
        ToolRegistry registry = new AgriTools(mock(KnowledgeService.class), mock(PhenologyService.class), new RiskServiceImpl()).buildRegistry();
        return new AgentRunner.Config("test", ENDPOINT, KEY, "测试系统提示", registry, new AgentContext("test", Map.of()), 5, null);
    }

    private List<Map<String, Object>> history() {
        return List.of(Map.of("role", "user", "content", "查看风险"));
    }

    private String toolResponse(String arguments) throws Exception {
        return mapper.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("role", "assistant", "content", "",
                "tool_calls", List.of(Map.of("id", "call-1", "type", "function", "function",
                        Map.of("name", "submit_risk_report", "arguments", arguments))))))));
    }

    private String textResponse(String text) throws Exception {
        return mapper.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("role", "assistant", "content", text)))));
    }
}
