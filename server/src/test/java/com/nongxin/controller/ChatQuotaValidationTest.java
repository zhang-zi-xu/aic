package com.nongxin.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nongxin.agent.AgentResult;
import com.nongxin.agent.AgentRunner;
import com.nongxin.agent.AgriTools;
import com.nongxin.agent.StreamObserver;
import com.nongxin.agent.ToolRegistry;
import com.nongxin.agent.ToolSubmission;
import com.nongxin.model.ChatRequest;
import com.nongxin.service.KnowledgeLibrary;
import com.nongxin.service.QuotaClient;
import com.nongxin.service.UploadService;
import com.nongxin.service.VisionSupport;
import com.nongxin.service.impl.ApiKeyServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Real quota accounting + mock provider; all writes stay in a fresh temporary SQLite database. */
class ChatQuotaValidationTest {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 15);
    private static final String KEY = "fake-server-key-for-validation-tests";
    private static final String IP = "192.0.2.10";
    private static final String MODEL = "demo-text-model";
    private static final AgentResult ANSWER = new AgentResult("离线测试回答。", List.of(), 1, false);
    private static final UploadService.Stored PHOTO = new UploadService.Stored(
            "img-test", "image/jpeg", "jpg", 3, 1, 1, "test-hash", "2026-09-15T00:00:00",
            null, null, "2026-09-15", "", null);

    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate jdbc;
    private AgentRunner runner;
    private UploadService uploads;
    private ChatController controller;
    private ChatStreams streams;
    private MockMvc mvc;
    private MockedStatic<LocalDate> dates;

    @BeforeEach
    void setUp() {
        var source = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("validation.db"));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        runner = mock(AgentRunner.class);
        uploads = mock(UploadService.class);
        when(runner.run(any(), anyList())).thenReturn(ANSWER);
        when(runner.run(any(), anyList(), any())).thenReturn(ANSWER);
        var tools = mock(AgriTools.class);
        when(tools.buildRegistry()).thenReturn(new ToolRegistry());
        streams = new ChatStreams() {
            @Override public SseEmitter open(Function<StreamObserver, ResponseEntity<?>> work) {
                return super.open(observer -> {
                    try (var workerDate = fixedDay()) { return work.apply(observer); }
                });
            }
        };
        controller = new ChatController(runner, tools, streams, mock(KnowledgeLibrary.class), uploads, new VisionSupport(), new com.nongxin.service.CurrentUser());
        configure("openai", MODEL);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
        dates = fixedDay();
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        if (streams != null) streams.close();
        if (dates != null) dates.close();
    }

    static Stream<Object[]> invalidRequests() {
        return Stream.of("missing-messages", "empty-messages", "blank-message", "assistant-last",
                "too-many-messages", "long-message", "missing-image", "unsupported-image", "unreadable-image",
                "unreadable-auto-image", "custom-http", "custom-no-host", "invalid-server-provider", "missing-effective-model")
                .flatMap(kind -> Stream.of(new Object[] {false, kind}, new Object[] {true, kind}));
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void locallyInvalidRequestsNeverConsumeDemoQuota(boolean streaming, String kind) throws Exception {
        var body = validBody();
        int expectedStatus = 400;
        switch (kind) {
            case "missing-messages" -> body.remove("messages");
            case "empty-messages" -> body.put("messages", List.of());
            case "blank-message" -> body.put("messages", List.of(Map.of("role", "user", "content", "  ")));
            case "assistant-last" -> body.put("messages", List.of(Map.of("role", "assistant", "content", "你好")));
            case "too-many-messages" -> body.put("messages", java.util.Collections.nCopies(501, Map.of("role", "user", "content", "你好")));
            case "long-message" -> body.put("messages", List.of(Map.of("role", "user", "content", "字".repeat(20001))));
            case "missing-image" -> body.put("imageIds", List.of("missing-image"));
            case "unsupported-image" -> {
                body.put("imageIds", List.of(PHOTO.id()));
                body.put("model", "gpt-4o"); // Only the actual server model should determine vision capability.
                when(uploads.find(any())).thenReturn(List.of(PHOTO));
                expectedStatus = 415;
            }
            case "unreadable-image" -> {
                body.put("imageIds", List.of(PHOTO.id()));
                body.put("imageInput", "on");
                when(uploads.find(any())).thenReturn(List.of(PHOTO));
            }
            case "unreadable-auto-image" -> {
                body.put("autoFieldPhotos", true);
                body.put("imageInput", "on");
                body.put("field", Map.of("id", "test-field", "name", "测试田", "crop", "测试作物"));
                when(uploads.latestForField("test-field", 3)).thenReturn(List.of(PHOTO));
            }
            // Custom URLs are now usable only with a user key; keep testing their syntax independently.
            case "custom-http", "custom-no-host" -> {
                body.put("provider", "custom");
                body.put("apiKey", "fake-user-key-for-url-validation");
                body.put("baseUrl", "custom-http".equals(kind) ? "http://example.invalid" : "https://");
            }
            case "invalid-server-provider" -> configure("unsupported-provider", MODEL);
            case "missing-effective-model" -> configure("openai", "");
            default -> throw new AssertionError(kind);
        }
        var result = call(streaming, body, IP);
        assertNoQuota();
        assertThat(result.logicalStatus()).isEqualTo(expectedStatus);
        assertThat(result.payload().path("error").asText()).isNotBlank();
        assertThat(result.event()).isNotEqualTo("done");
        verifyNoInteractions(runner);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void validRequestReservesExactlyOnceBeforeTheProviderRuns(boolean streaming) throws Exception {
        var assertReserved = (org.mockito.stubbing.Answer<AgentResult>) invocation -> {
            assertCount("global", "all", 1);
            assertCount("ip", IP, 1);
            AgentRunner.Config config = invocation.getArgument(0);
            assertThat(config.apiKey()).isEqualTo(KEY);
            assertThat(config.model()).isEqualTo(MODEL);
            return ANSWER;
        };
        doAnswer(assertReserved).when(runner).run(any(), anyList());
        doAnswer(assertReserved).when(runner).run(any(), anyList(), any());
        var body = validBody();
        body.put("provider", "client-provider-not-used");
        body.put("model", "client-model-not-used");
        body.put("baseUrl", "http://not-used.invalid");
        var result = call(streaming, body, IP);
        assertThat(result.logicalStatus()).isEqualTo(200);
        assertThat(result.payload().path("reply").asText()).contains("离线测试回答");
        assertCount("global", "all", 1);
        assertCount("ip", IP, 1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void serverDefaultModelIsValidatedAndUsedWhenRequestModelIsMissing(boolean streaming) throws Exception {
        var body = validBody();
        body.remove("model");
        assertThat(call(streaming, body, IP).logicalStatus()).isEqualTo(200);
        assertCount("global", "all", 1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void quotaExhaustionHasA429CodeWithoutAdditionalDebit(boolean streaming) throws Exception {
        seed("global", "all", 1);
        seed("ip", IP, 1);
        var result = call(streaming, validBody(), IP);
        assertThat(result.logicalStatus()).isEqualTo(429);
        assertThat(result.payload().path("code").asText()).isEqualTo("QUOTA_EXHAUSTED");
        assertCount("global", "all", 1);
        assertCount("ip", IP, 1);
        verifyNoInteractions(runner);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unavailableQuotaStoreHasA503CodeNotAnExhaustedQuotaCode(boolean streaming) throws Exception {
        jdbc.execute("DROP TABLE api_usage");
        var result = call(streaming, validBody(), IP);
        assertThat(result.logicalStatus()).isEqualTo(503);
        assertThat(result.payload().path("code").asText()).isEqualTo("QUOTA_UNAVAILABLE");
        assertThat(result.payload().path("error").asText()).contains("暂时无法核验")
                .doesNotContain("api_usage", "SELECT", "SQL", directory.toString(), KEY);
        verifyNoInteractions(runner);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingSourceIsA503WithoutDebitingOrInventingAClient(boolean streaming) throws Exception {
        var result = call(streaming, validBody(), null);
        assertThat(result.logicalStatus()).isEqualTo(503);
        assertThat(result.payload().path("code").asText()).isEqualTo("CLIENT_UNAVAILABLE");
        assertNoQuota();
        verifyNoInteractions(runner);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void providerFailureDoesNotAutomaticallyRefundPossiblyUsedQuota(boolean streaming) throws Exception {
        when(runner.run(any(), anyList())).thenThrow(new IllegalStateException("private-provider-error"));
        when(runner.run(any(), anyList(), any())).thenThrow(new IllegalStateException("private-provider-error"));
        var result = call(streaming, validBody(), IP);
        assertThat(result.logicalStatus()).isEqualTo(502);
        assertThat(result.payload().path("error").asText()).doesNotContain("private-provider-error", KEY);
        assertCount("global", "all", 1);
        assertCount("ip", IP, 1);
    }

    @Test
    void modelSelectionIsCredentialFreeAndDoesNotAccessQuotaStorage() throws Exception {
        jdbc.execute("DROP TABLE api_usage");
        var keys = new ApiKeyServiceImpl(jdbc, KEY, "openai", MODEL, true, 1, 20);
        var server = keys.select(null, "ignored-provider", "ignored-model");
        assertThat(server.provider()).isEqualTo("openai");
        assertThat(server.model()).isEqualTo(MODEL);
        assertThat(server.keyAvailable()).isTrue();
        assertThat(server.serverSide()).isTrue();
        assertThat(server.serverEndpointUnavailable()).isFalse();
        var user = keys.select("fake-user-key-for-tests", "custom", "user-model");
        assertThat(user.provider()).isEqualTo("custom");
        assertThat(user.model()).isEqualTo("user-model");
        assertThat(user.keyAvailable()).isTrue();
        assertThat(user.serverSide()).isFalse();
        assertThat(user.serverEndpointUnavailable()).isFalse();
        assertThat(json.writeValueAsString(List.of(server, user))).doesNotContain(KEY, "fake-user-key-for-tests", "apiKey");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void invalidInputStillReportsValidationErrorWhenQuotaIsExhausted(boolean streaming) throws Exception {
        seed("global", "all", 1);
        seed("ip", IP, 1);
        var body = validBody();
        body.put("messages", List.of());
        var result = call(streaming, body, IP);
        assertThat(result.logicalStatus()).isEqualTo(400);
        assertThat(result.payload().path("error").asText()).contains("对话内容格式不正确");
        assertCount("global", "all", 1);
        assertCount("ip", IP, 1);
        verifyNoInteractions(runner);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void actualServerVisionModelIsUsedForPreflightAndRequest(boolean streaming) throws Exception {
        configure("openai", "gpt-4o");
        when(uploads.find(any())).thenReturn(List.of(PHOTO));
        when(uploads.read(PHOTO.id())).thenReturn(new byte[] {1, 2, 3});
        var body = validBody();
        body.put("imageIds", List.of(PHOTO.id()));
        var inspect = (org.mockito.stubbing.Answer<AgentResult>) invocation -> {
            AgentRunner.Config config = invocation.getArgument(0);
            assertThat(config.model()).isEqualTo("gpt-4o");
            assertThat(invocation.getArgument(1).toString()).contains("image_url", "base64,AQID");
            return ANSWER;
        };
        doAnswer(inspect).when(runner).run(any(), anyList());
        doAnswer(inspect).when(runner).run(any(), anyList(), any());
        assertThat(call(streaming, body, IP).logicalStatus()).isEqualTo(200);
        assertCount("global", "all", 1);
        assertCount("ip", IP, 1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anAdditionalClarificationRunDoesNotReserveQuotaAgain(boolean streaming) throws Exception {
        var first = new AgentResult("请确认这次需要观察哪种作物。", List.of(), 1, false);
        var next = new AgentResult("确认卡已生成。", List.of(new ToolSubmission("submit_clarify",
                Map.of("intro", "请补充作物信息", "items", List.of(Map.of("question", "作物是什么？"))), "确认卡")), 1, false);
        when(runner.run(any(), anyList())).thenReturn(first, next);
        when(runner.run(any(), anyList(), any())).thenReturn(first, next);
        var body = validBody();
        body.put("provider", "custom");
        body.put("baseUrl", "https://client-selected.invalid/v1");
        var result = call(streaming, body, IP);
        assertThat(result.logicalStatus()).isEqualTo(200);
        assertThat(result.payload().path("clarify").isObject()).isTrue();
        var configs = org.mockito.ArgumentCaptor.forClass(AgentRunner.Config.class);
        if (streaming) verify(runner, times(2)).run(configs.capture(), anyList(), any());
        else verify(runner, times(2)).run(configs.capture(), anyList());
        assertThat(configs.getAllValues()).allSatisfy(config -> {
            assertThat(config.apiKey()).isEqualTo(KEY);
            assertThat(config.endpoint()).isEqualTo("https://api.openai.com/v1/chat/completions");
        });
        assertCount("global", "all", 1);
        assertCount("ip", IP, 1);
    }

    @Test
    void cancellationObservedBeforeReservationDoesNotConsumeQuota() throws Exception {
        var request = json.readValue(json.writeValueAsString(validBody()), ChatRequest.class);
        var cancelled = new StreamObserver() {
            public boolean cancelled() { return true; }
            public void event(String name, Object data) { }
        };
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(controller, "chat", request, cancelled, new QuotaClient(IP)))
                .isInstanceOf(java.util.concurrent.CancellationException.class);
        assertNoQuota();
        verifyNoInteractions(runner);
    }

    static Stream<Object[]> serverCustomRequests() {
        // Short/blank values still select the server fallback and must not bypass its endpoint boundary.
        return Stream.of(false, true).flatMap(streaming -> Stream.of(false, true)
                .flatMap(limited -> Stream.of(null, "", "  ", "short-key")
                        .map(key -> new Object[] {streaming, limited, key})));
    }

    @ParameterizedTest
    @MethodSource("serverCustomRequests")
    void serverCustomKeyCannotBePairedWithAClientEndpoint(boolean streaming, boolean limited, String requestKey) throws Exception {
        configure("custom", MODEL, limited);
        var body = validBody();
        body.put("apiKey", requestKey);
        body.put("baseUrl", "https://client-selected.invalid/v1");
        // Client provider/model do not change the actual server fallback configuration.
        var result = call(streaming, body, IP);
        assertThat(result.logicalStatus()).isEqualTo(503);
        assertThat(result.payload().path("code").asText()).isEqualTo("SERVER_ENDPOINT_UNAVAILABLE");
        assertThat(result.payload().path("error").asText()).contains("服务端", "自定义", "自己的 API Key")
                .doesNotContain(KEY, "client-selected.invalid");
        assertNoQuota();
        verifyNoInteractions(runner, uploads);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingClientUrlCannotTurnServerConfigurationErrorIntoAClientInputError(boolean streaming) throws Exception {
        configure("custom", MODEL);
        var result = call(streaming, validBody(), IP);
        assertThat(result.logicalStatus()).isEqualTo(503);
        assertThat(result.payload().path("code").asText()).isEqualTo("SERVER_ENDPOINT_UNAVAILABLE");
        assertNoQuota();
        verifyNoInteractions(runner);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void absentServerKeyStillPromptsForAUserKeyInsteadOfReportingAnEndpointFailure(boolean streaming) throws Exception {
        ReflectionTestUtils.setField(controller, "apiKeys", new ApiKeyServiceImpl(jdbc, "", "custom", MODEL, true, 1, 20));
        var body = validBody();
        body.put("provider", "custom");
        body.put("baseUrl", "https://user-selected.invalid/v1");
        var result = call(streaming, body, IP);
        assertThat(result.logicalStatus()).isEqualTo(400);
        assertThat(result.payload().path("error").asText()).contains("请填写有效的 API 密钥");
        assertThat(result.payload().has("code")).isFalse();
        assertNoQuota();
        verifyNoInteractions(runner);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void userCustomKeyStillUsesItsOwnEndpointEvenWhenServerCustomFallbackIsUnavailable(boolean streaming) throws Exception {
        configure("custom", MODEL);
        seed("global", "all", 20);
        seed("ip", IP, 1);
        String userKey = "fake-user-key-for-endpoint-tests";
        var body = validBody();
        body.put("provider", "custom");
        body.put("apiKey", "  " + userKey + "  ");
        body.put("baseUrl", "https://user-selected.invalid/v1/");
        assertThat(call(streaming, body, IP).logicalStatus()).isEqualTo(200);
        var config = capturedConfig(streaming);
        assertThat(config.apiKey()).isEqualTo(userKey).isNotEqualTo(KEY);
        assertThat(config.endpoint()).isEqualTo("https://user-selected.invalid/v1/chat/completions");
        assertThat(config.model()).isEqualTo("client-model");
        assertCount("global", "all", 20);
        assertCount("ip", IP, 1);
    }

    static Stream<Object[]> presetRequests() {
        return Stream.of(new String[] {"openai", "https://api.openai.com/v1/chat/completions"},
                        new String[] {"deepseek", "https://api.deepseek.com/v1/chat/completions"},
                        new String[] {"siliconflow", "https://api.siliconflow.cn/v1/chat/completions"})
                .flatMap(preset -> Stream.of(false, true).flatMap(streaming -> Stream.of(false, true)
                        .map(limited -> new Object[] {streaming, limited, preset[0], preset[1]})));
    }

    @ParameterizedTest
    @MethodSource("presetRequests")
    void serverPresetKeysStayBoundToTheirFixedEndpoint(boolean streaming, boolean limited, String provider, String endpoint) throws Exception {
        configure(provider, MODEL, limited);
        var body = validBody();
        body.put("provider", "custom");
        body.put("baseUrl", "https://client-selected.invalid/v1");
        assertThat(call(streaming, body, IP).logicalStatus()).isEqualTo(200);
        var config = capturedConfig(streaming);
        assertThat(config.apiKey()).isEqualTo(KEY);
        assertThat(config.endpoint()).isEqualTo(endpoint);
        assertThat(config.model()).isEqualTo(MODEL);
        if (limited) {
            assertCount("global", "all", 1);
            assertCount("ip", IP, 1);
        } else assertNoQuota();
    }

    private AgentRunner.Config capturedConfig(boolean streaming) {
        var config = org.mockito.ArgumentCaptor.forClass(AgentRunner.Config.class);
        if (streaming) verify(runner).run(config.capture(), anyList(), any());
        else verify(runner).run(config.capture(), anyList());
        return config.getValue();
    }

    private void configure(String provider, String model) {
        configure(provider, model, true);
    }

    private void configure(String provider, String model, boolean limited) {
        ReflectionTestUtils.setField(controller, "apiKeys", new ApiKeyServiceImpl(jdbc, KEY, provider, model, limited, 1, 20));
    }

    private Map<String, Object> validBody() {
        return new LinkedHashMap<>(Map.of("provider", "openai", "model", "client-model",
                "messages", List.of(Map.of("role", "user", "content", "你好"))));
    }

    private Outcome call(boolean streaming, Map<String, Object> body, String ip) throws Exception {
        var pending = mvc.perform(post(streaming ? "/api/chat/stream" : "/api/chat")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))
                .with(request -> { request.setRemoteAddr(ip); return request; })).andReturn();
        if (!streaming) {
            var payload = json.readTree(pending.getResponse().getContentAsString(StandardCharsets.UTF_8));
            return new Outcome(pending.getResponse().getStatus(), payload, "json");
        }
        assertThat(pending.getRequest().isAsyncStarted()).isTrue();
        pending.getAsyncResult(5000);
        var response = mvc.perform(asyncDispatch(pending)).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200); // SSE has already established its HTTP transport.
        String text = response.getContentAsString(StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertThat(text).doesNotContain(KEY);
        var terminal = Arrays.stream(text.split("\n\n"))
                .filter(event -> event.startsWith("event:error\n") || event.startsWith("event:done\n")).toList();
        assertThat(terminal).hasSize(1);
        String event = terminal.getFirst().startsWith("event:error") ? "error" : "done";
        var payload = json.readTree(terminal.getFirst().substring(terminal.getFirst().indexOf("data:") + 5));
        return new Outcome("done".equals(event) ? 200 : payload.path("status").asInt(), payload, event);
    }

    private void assertNoQuota() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_usage", Integer.class)).isZero();
    }

    private void assertCount(String scope, String key, int count) {
        assertThat(jdbc.queryForList("SELECT count FROM api_usage WHERE day = ? AND scope = ? AND scope_key = ?",
                Integer.class, DAY.toString(), scope, key)).containsExactly(count);
    }

    private void seed(String scope, String key, int count) {
        jdbc.update("INSERT INTO api_usage (day, scope, scope_key, count) VALUES (?, ?, ?, ?)", DAY.toString(), scope, key, count);
    }

    private static MockedStatic<LocalDate> fixedDay() {
        var dates = mockStatic(LocalDate.class, CALLS_REAL_METHODS);
        dates.when(LocalDate::now).thenReturn(DAY);
        return dates;
    }

    private record Outcome(int logicalStatus, JsonNode payload, String event) { }
}
