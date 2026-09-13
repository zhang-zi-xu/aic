package com.nongxin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nongxin.agent.AgentResult;
import com.nongxin.agent.AgentRunner;
import com.nongxin.agent.AgriTools;
import com.nongxin.agent.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ChatStreamIntegrationTest {
    private static final Path DATABASE = temporaryDatabase();
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @MockBean private AgentRunner runner;
    @MockBean private AgriTools agriTools;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
    }

    private static Path temporaryDatabase() {
        try {
            return Files.createTempDirectory("nongxin-stream-test-").resolve("test.db");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Test
    void streamEndpointEmitsStatusAndDoneWithReplyPlanAndDegradedFlag() throws Exception {
        when(agriTools.buildRegistry()).thenReturn(new ToolRegistry());
        Map<String, Object> plan = Map.of("title", "排水方案", "items", List.of(Map.of("task", "检查排水沟")));
        when(runner.run(any(), anyList(), any())).thenReturn(new AgentResult(
                "先检查排水情况。", List.of(new com.nongxin.agent.ToolSubmission("submit_farm_plan", plan, "方案")), 1, false));

        MvcResult pending = mvc.perform(post("/api/chat/stream").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body("帮我安排农事"))))
                .andExpect(request().asyncStarted())
                .andReturn();
        String content = mvc.perform(asyncDispatch(pending)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(content).contains("event:status", "event:done");
        assertThat(content).contains("\"reply\":\"先检查排水情况。\"", "\"degraded\":false", "\"title\":\"排水方案\"");
        assertThat(content).doesNotContain("test-key", "reasoning");
    }

    @Test
    void streamEndpointEmitsErrorEventForInvalidRequestsInsteadOfFakeSuccess() throws Exception {
        when(agriTools.buildRegistry()).thenReturn(new ToolRegistry());

        MvcResult pending = mvc.perform(post("/api/chat/stream").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body(List.of()))))
                .andExpect(request().asyncStarted())
                .andReturn();
        String content = mvc.perform(asyncDispatch(pending)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(content).contains("event:error", "对话内容格式不正确");
        assertThat(content).doesNotContain("event:done");
    }

    @Test
    void streamEndpointRejectsShortKeysWithErrorEvent() throws Exception {
        when(agriTools.buildRegistry()).thenReturn(new ToolRegistry());

        MvcResult pending = mvc.perform(post("/api/chat/stream").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("provider", "openai", "model", "m", "apiKey", "short",
                                "messages", List.of(Map.of("role", "user", "content", "你好"))))))
                .andExpect(request().asyncStarted())
                .andReturn();
        String content = mvc.perform(asyncDispatch(pending)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(content).contains("event:error", "API 密钥");
    }

    @Test
    void legacyChatEndpointStillReturnsJson() throws Exception {
        when(agriTools.buildRegistry()).thenReturn(new ToolRegistry());
        when(runner.run(any(), anyList())).thenReturn(new AgentResult("可以。", List.of(), 1, false));

        mvc.perform(post("/api/chat").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body("你好"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("可以。"))
                .andExpect(jsonPath("$.degraded").value(false));
    }

    private Map<String, Object> body(String content) {
        return body(List.of(Map.of("role", "user", "content", content)));
    }

    private Map<String, Object> body(List<Map<String, String>> messages) {
        return Map.of("provider", "openai", "model", "test-model", "apiKey", "test-key-no-provider-call", "messages", messages);
    }
}
