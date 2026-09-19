package com.nongxin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.nongxin.agent.AgentResult;
import com.nongxin.agent.AgentRunner;
import com.nongxin.agent.AgriTools;
import com.nongxin.agent.StreamObserver;
import com.nongxin.agent.ToolRegistry;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Mock HTTP + real SSE workers + isolated SQLite; no server sockets or provider calls. */
class ChatClientIdentityTest {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 15);
    private static final String KEY = "fake-server-key-for-identity-tests";
    private static final String FIRST_IP = "192.0.2.10";
    private static final String SECOND_IP = "192.0.2.20";
    private static final AgentResult ANSWER = new AgentResult("这是离线测试回复。", List.of(), 1, false);

    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private JdbcTemplate jdbc;
    private ApiKeyServiceImpl keys;
    private AgentRunner runner;
    private ChatStreams streams;
    private MockMvc mvc;
    private MockedStatic<LocalDate> dates;

    @BeforeEach
    void setUp() {
        var source = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("identity.db"));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        keys = new ApiKeyServiceImpl(jdbc, KEY, "openai", "test-model", true, 1, 20);
        runner = mock(AgentRunner.class);
        when(runner.run(any(), anyList())).thenReturn(ANSWER);
        when(runner.run(any(), anyList(), any())).thenAnswer(invocation -> {
            assertThat(Thread.currentThread().getName()).startsWith("chat-stream-");
            assertThat(RequestContextHolder.getRequestAttributes()).isNull();
            return ANSWER;
        });
        // Fix only the test clock on each real worker, never copy servlet/thread identity state.
        streams = new ChatStreams() {
            @Override
            public SseEmitter open(Function<StreamObserver, ResponseEntity<?>> work) {
                return super.open(observer -> {
                    try (var workerDate = fixedDay()) { return work.apply(observer); }
                });
            }
        };
        mvc = MockMvcBuilders.standaloneSetup(controller(streams)).build();
        dates = fixedDay();
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        if (streams != null) streams.close();
        if (dates != null) dates.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void syncAndStreamShareTheSameClientQuota(boolean streamFirst) throws Exception {
        assertReply(call(streamFirst, FIRST_IP, null, null));
        assertQuotaDenied(call(!streamFirst, FIRST_IP, null, null));
        assertCount("global", "all", 1);
        assertCount("ip", FIRST_IP, 1);
        assertNoUnknownBucket();
    }

    @Test
    void differentStreamingClientsDoNotShareAnUnknownBucket() throws Exception {
        assertReply(call(true, FIRST_IP, null, null));
        assertReply(call(true, SECOND_IP, null, null));
        assertQuotaDenied(call(true, FIRST_IP, null, null));
        assertCount("global", "all", 2);
        assertCount("ip", FIRST_IP, 1);
        assertCount("ip", SECOND_IP, 1);
        assertNoUnknownBucket();
    }

    @ParameterizedTest
    @CsvSource({"false,X-Forwarded-For", "false,X-Real-IP", "false,Forwarded",
            "true,X-Forwarded-For", "true,X-Real-IP", "true,Forwarded"})
    void changingUntrustedForwardingHeadersCannotResetQuota(boolean streaming, String header) throws Exception {
        assertReply(call(streaming, FIRST_IP, header, "198.51.100.1"));
        assertQuotaDenied(call(streaming, FIRST_IP, header, "198.51.100.2"));
        assertCount("global", "all", 1);
        assertCount("ip", FIRST_IP, 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_usage WHERE scope = 'ip'", Integer.class)).isEqualTo(1);
        assertNoUnknownBucket();
    }

    @Test
    void parallelStreamsKeepTheirOwnRequestSource() throws Exception {
        var pending = new ArrayList<MvcResult>();
        for (int i = 0; i < 4; i++) pending.add(start(true, "192.0.2." + (30 + i), null, null));
        for (var request : pending) assertReply(finish(true, request));
        assertCount("global", "all", 4);
        for (int i = 0; i < 4; i++) assertCount("ip", "192.0.2." + (30 + i), 1);
        assertNoUnknownBucket();
    }

    @Test
    void queuedWorkUsesSnapshotEvenAfterTheServletRequestChanges() throws Exception {
        var queued = new AtomicReference<Function<StreamObserver, ResponseEntity<?>>>();
        var heldStreams = mock(ChatStreams.class);
        when(heldStreams.open(any())).thenAnswer(invocation -> {
            queued.set(invocation.getArgument(0));
            return new SseEmitter();
        });
        doReturn(ANSWER).when(runner).run(any(), anyList(), any());
        var servlet = bind(FIRST_IP);
        controller(heldStreams).stream(json.readValue(body(), ChatRequest.class), new MockHttpServletResponse());
        servlet.setRemoteAddr(SECOND_IP);
        servlet.addHeader("X-Forwarded-For", "198.51.100.5");
        RequestContextHolder.resetRequestAttributes();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var result = executor.submit(() -> {
                try (var workerDate = fixedDay()) {
                    assertThat(RequestContextHolder.getRequestAttributes()).isNull();
                    return queued.get().apply(new StreamObserver() {
                        public boolean cancelled() { return false; }
                        public void event(String name, Object data) { }
                    });
                }
            }).get(5, TimeUnit.SECONDS);
            assertThat(result.getStatusCode().value()).isEqualTo(200);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        assertCount("ip", FIRST_IP, 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_usage WHERE scope = 'ip'", Integer.class)).isEqualTo(1);
        assertNoUnknownBucket();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingConnectionSourceDeniesDemoWithoutInventingAnIdentity(boolean streaming) throws Exception {
        var result = call(streaming, null, "X-Forwarded-For", "198.51.100.7");
        assertThat(result).contains("无法识别", "API Key").doesNotContain("event:done", "离线测试回复");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_usage", Integer.class)).isZero();
        verifyNoInteractions(runner);
    }

    @Test
    void equivalentIpv6SourcesShareOneQuota() throws Exception {
        assertReply(call(false, "::1", null, null));
        assertQuotaDenied(call(true, "0:0:0:0:0:0:0:1", null, null));
        assertCount("global", "all", 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_usage WHERE scope = 'ip'", Integer.class)).isEqualTo(1);
    }

    @Test
    void ipv4AndIpv4MappedIpv6UseTheSameQuota() throws Exception {
        assertReply(call(false, FIRST_IP, null, null));
        assertQuotaDenied(call(true, "::ffff:" + FIRST_IP, null, null));
        assertCount("ip", FIRST_IP, 1);
        assertCount("global", "all", 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "127.1", "192.000.2.10", "192.0.2.10:80", "::ffff:localhost",
            "fe80::1%eth0", "::::", "192.0.2.10,192.0.2.20"})
    void malformedSourcesCannotCreateArbitraryQuotaBuckets(String source) {
        var client = new QuotaClient(source);
        assertThat(client.known()).isFalse();
        var result = keys.resolve(null, null, null, client);
        assertThat(result.allowed()).isFalse();
        assertThat(result.apiKey()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_usage", Integer.class)).isZero();
    }

    @Test
    void explicitlyMissingSnapshotDoesNotFallBackToAmbientRequestState() {
        bind(FIRST_IP);
        var result = keys.resolve(null, null, null, null);
        assertThat(result.allowed()).isFalse();
        assertThat(result.apiKey()).isEmpty();
        assertThat(keys.status(null)).containsKey("usageError").doesNotContainKey("ipUsedToday");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_usage", Integer.class)).isZero();
    }

    @Test
    void networkIdentityIsNotAddedToTheModelContext() throws Exception {
        doAnswer(invocation -> {
            AgentRunner.Config config = invocation.getArgument(0);
            assertThat(config.systemPrompt()).doesNotContain(FIRST_IP, SECOND_IP, "198.51.100");
            assertThat(invocation.getArgument(1).toString()).doesNotContain(FIRST_IP, SECOND_IP, "198.51.100");
            assertThat(config.ctx().extra("clientIp")).isNull();
            assertThat(config.ctx().extra("quotaClient")).isNull();
            return ANSWER;
        }).when(runner).run(any(), anyList());
        assertReply(call(false, FIRST_IP, "X-Forwarded-For", SECOND_IP));
        verify(runner).run(any(), anyList());
    }

    @Test
    void statusUsesTheSameDirectSourceAndIgnoresHeaders() throws Exception {
        assertReply(call(false, FIRST_IP, null, null));
        var servlet = bind(FIRST_IP);
        servlet.addHeader("X-Forwarded-For", SECOND_IP);
        assertThat(keys.status()).containsEntry("ipUsedToday", 1).doesNotContainKey("usageError");
    }

    @Test
    void noRequestContextDoesNotEnableSharedUnknownDemoAccess() {
        RequestContextHolder.resetRequestAttributes();
        var result = keys.resolve(null, null, null);
        assertThat(result.allowed()).isFalse();
        assertThat(result.apiKey()).isEmpty();
        assertThat(result.denyReason()).contains("无法识别");
        assertThat(keys.status()).containsKey("usageError").doesNotContainKey("ipUsedToday");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_usage", Integer.class)).isZero();
    }

    @Test
    void missingIdentityDoesNotChangeExplicitBypassModes() {
        RequestContextHolder.resetRequestAttributes();
        assertThat(keys.resolve("fake-user-key-for-tests", "openai", "m").apiKey()).isEqualTo("fake-user-key-for-tests");
        var disabled = new ApiKeyServiceImpl(jdbc, KEY, "openai", "m", false, 1, 20);
        assertThat(disabled.resolve(null, null, null).apiKey()).isEqualTo(KEY);
        var unconfigured = new ApiKeyServiceImpl(jdbc, "", "openai", "m", true, 1, 20);
        assertThat(unconfigured.resolve(null, null, null).apiKey()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_usage", Integer.class)).isZero();
    }

    @Test
    void bundledConfigurationDoesNotAutomaticallyTrustForwardingHeaders() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        assertThat(yaml.getObject().getProperty("server.forward-headers-strategy")).isEqualTo("none");
    }

    private ChatController controller(ChatStreams chatStreams) {
        var tools = mock(AgriTools.class);
        when(tools.buildRegistry()).thenReturn(new ToolRegistry());
        var controller = new ChatController(runner, tools, chatStreams, mock(KnowledgeLibrary.class),
                mock(UploadService.class), new VisionSupport(), new com.nongxin.service.CurrentUser());
        ReflectionTestUtils.setField(controller, "apiKeys", keys);
        return controller;
    }

    private String call(boolean streaming, String ip, String header, String value) throws Exception {
        return finish(streaming, start(streaming, ip, header, value));
    }

    private MvcResult start(boolean streaming, String ip, String header, String value) throws Exception {
        var request = post(streaming ? "/api/chat/stream" : "/api/chat").contentType(MediaType.APPLICATION_JSON)
                .content(body()).with(servlet -> { servlet.setRemoteAddr(ip); return servlet; });
        if (header != null) request.header(header, "Forwarded".equals(header) ? "for=" + value : value);
        return mvc.perform(request).andReturn();
    }

    private String finish(boolean streaming, MvcResult pending) throws Exception {
        MvcResult result = pending;
        if (streaming) {
            assertThat(pending.getRequest().isAsyncStarted()).isTrue();
            pending.getAsyncResult(5000);
            result = mvc.perform(asyncDispatch(pending)).andReturn();
        }
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String body() throws Exception {
        return json.writeValueAsString(Map.of("provider", "openai", "model", "test-model",
                "clientIp", "198.51.100.99", "messages", List.of(Map.of("role", "user", "content", "你好"))));
    }

    private MockHttpServletRequest bind(String ip) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(ip);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }

    private static MockedStatic<LocalDate> fixedDay() {
        var dates = mockStatic(LocalDate.class, CALLS_REAL_METHODS);
        dates.when(LocalDate::now).thenReturn(DAY);
        return dates;
    }

    private void assertCount(String scope, String key, int count) {
        assertThat(jdbc.queryForList("SELECT count FROM api_usage WHERE day = ? AND scope = ? AND scope_key = ?",
                Integer.class, DAY.toString(), scope, key)).containsExactly(count);
    }

    private void assertNoUnknownBucket() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_usage WHERE scope_key = 'unknown'", Integer.class)).isZero();
    }

    private void assertReply(String content) {
        assertThat(content).contains("离线测试回复").doesNotContain("event:error", KEY, FIRST_IP, SECOND_IP, "198.51.100");
    }

    private void assertQuotaDenied(String content) {
        assertThat(content).contains("额度已用完").doesNotContain("event:done", "离线测试回复", KEY);
    }
}
