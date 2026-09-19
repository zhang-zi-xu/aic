package com.nongxin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nongxin.agent.*;
import com.nongxin.model.ChatRequest;
import com.nongxin.service.*;
import com.nongxin.service.impl.ApiKeyServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real temporary ownership/quotas/images and worker threads, never a provider or login system. */
class ChatDataIdentityTest {
    private static final AgentResult ANSWER = new AgentResult("离线测试回答。", List.of(), 1, false);
    private static final String ERROR = "当前用户身份暂时无法确认，请稍后重试";
    @TempDir Path directory;
    private final CurrentUser user = new CurrentUser();
    private final ThreadLocal<String> actor = new ThreadLocal<>();
    private final AtomicInteger resolutions = new AtomicInteger();
    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate jdbc;
    private UploadService uploads;
    private TaskService tasks;
    private ApiKeyServiceImpl keys;
    private AgentRunner runner;
    private AgriTools tools;
    private ChatStreams streams;
    private ChatController controller;
    private MockMvc mvc;
    private UploadService.Stored photoA;
    private UploadService.Stored photoB;

    @BeforeEach
    void setUp() throws Exception {
        var source = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("chat-owner.db"));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        user.setResolver(() -> { resolutions.incrementAndGet(); return actor.get(); });
        uploads = spy(new UploadService(jdbc, directory.resolve("images").toString(), 8_388_608, 7, user));
        tasks = new TaskService(jdbc, json, user);
        photoA = createPhoto("a", 0x228833);
        photoB = createPhoto("b", 0x882233);
        actor.remove();
        resolutions.set(0);
        clearInvocations(uploads);
        keys = spy(new ApiKeyServiceImpl(jdbc, "fake-server-key-for-data-identity", "openai", "test-model", true, 50, 100));
        runner = mock(AgentRunner.class);
        when(runner.run(any(), anyList())).thenReturn(ANSWER);
        when(runner.run(any(), anyList(), any())).thenReturn(ANSWER);
        tools = mock(AgriTools.class);
        when(tools.buildRegistry()).thenReturn(new ToolRegistry());
        streams = new ChatStreams();
        controller = controller(streams);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @AfterEach
    void tearDown() throws Exception {
        streams.close();
        var workers = (ExecutorService) ReflectionTestUtils.getField(streams, "workers");
        assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        actor.remove();
        RequestContextHolder.resetRequestAttributes();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ownPhotoAndDatabaseToolUseTheEntryIdentity(boolean streaming) throws Exception {
        var registry = new ToolRegistry();
        registry.register(ToolDefinition.of("test_archive", "offline fixture", Map.of(), (args, ctx) -> {
            assertThat(user.id()).isEqualTo("u-a");
            assertThat(uploads.byField("f-a", 10)).extracting(UploadService.Stored::id).containsExactly(photoA.id());
            assertThat(tasks.forField("f-a", 10)).extracting(com.nongxin.model.FarmTask::id).containsExactly("t-a");
            return "archive checked";
        }));
        when(tools.buildRegistry()).thenReturn(registry);
        var expectedUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(
                java.nio.file.Files.readAllBytes(uploads.fileFor(photoA.id(), "jpg")));
        org.mockito.stubbing.Answer<AgentResult> check = invocation -> {
            AgentRunner.Config config = invocation.getArgument(0);
            assertThat(config.ctx().userId()).isEqualTo("u-a");
            if (streaming) assertThat(RequestContextHolder.getRequestAttributes()).isNull();
            assertThat(json.writeValueAsString(invocation.getArgument(1))).contains(expectedUrl).doesNotContain(photoB.id());
            assertThat(config.tools().execute("test_archive", Map.of(), config.ctx())).isEqualTo("archive checked");
            return ANSWER;
        };
        when(runner.run(any(), anyList())).thenAnswer(check);
        when(runner.run(any(), anyList(), any())).thenAnswer(check);
        var result = finish(start(streaming, "u-a", body(photoA.id())));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(text(result)).contains("离线测试回答").doesNotContain("event:error");
        assertThat(resolutions.get()).isEqualTo(1);
        assertThat(globalCount()).isEqualTo(1);
    }

    static Stream<Object[]> failures() {
        return Stream.of("null", "blank", "exception").flatMap(mode -> Stream.of(new Object[]{false, mode}, new Object[]{true, mode}));
    }

    @ParameterizedTest
    @MethodSource("failures")
    void missingEntryIdentityRejectsEvenTextOnlyChatBeforeAnyWork(boolean streaming, String mode) throws Exception {
        user.setResolver(() -> {
            resolutions.incrementAndGet();
            return switch (mode) {
                case "null" -> null;
                case "blank" -> " \t";
                default -> throw new IllegalStateException("private resolver detail");
            };
        });
        var result = start(streaming, null, body(null));
        assertThat(result.getRequest().isAsyncStarted()).isFalse();
        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        assertThat(result.getResponse().getContentType()).startsWith("application/json");
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(text(result)).contains(ERROR, "IDENTITY_UNAVAILABLE").doesNotContain("private resolver", "event:done");
        assertThat(resolutions.get()).isEqualTo(1);
        verifyNoInteractions(keys, runner, uploads);
        assertThat(globalCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anotherThreadsIdentityCannotAuthorizeForeignPhotos(boolean streaming) throws Exception {
        // Simulate an unrelated worker identity. This is trusted test wiring, not a request header resolver.
        user.setResolver(() -> actor.get() == null ? "u-b" : actor.get());
        for (String id : List.of(photoB.id(), "img-missing")) {
            var result = finish(start(streaming, "u-a", body(id)));
            assertThat(text(result)).contains("有图片不存在或已被清理").doesNotContain("离线测试回答", "event:done");
            if (!streaming) assertThat(result.getResponse().getStatus()).isEqualTo(400);
        }
        verifyNoInteractions(runner);
        assertThat(globalCount()).isZero();
    }

    @Test
    void synchronousPhotoMetadataAndBytesCannotSwitchOwnerMidRequest() throws Exception {
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            actor.set("u-b");
            return result;
        }).when(uploads).find(anyList());
        var result = start(false, "u-a", body(photoA.id()));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(text(result)).contains("离线测试回答");
        assertThat(resolutions.get()).isEqualTo(1);
    }

    @Test
    void queuedChatKeepsItsSnapshotAndRestoresTheWorkerIdentity() throws Exception {
        var queued = new AtomicReference<Function<StreamObserver, ResponseEntity<?>>>();
        var heldStreams = mock(ChatStreams.class);
        when(heldStreams.open(any())).thenAnswer(invocation -> { queued.set(invocation.getArgument(0)); return new SseEmitter(); });
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.40");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        actor.set("u-a");
        controller(heldStreams).stream(json.readValue(body(photoA.id()), ChatRequest.class), new MockHttpServletResponse());
        actor.remove();
        request.setRemoteAddr("192.0.2.99");
        RequestContextHolder.resetRequestAttributes();
        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> {
                actor.set("u-b");
                try {
                    var result = queued.get().apply(observer());
                    assertThat(result.getStatusCode().value()).isEqualTo(200);
                    assertThat(resolutions.get()).isEqualTo(1);
                    assertThat(user.id()).isEqualTo("u-b");
                } finally { actor.remove(); }
            }).get(5, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForList("SELECT scope_key FROM api_usage WHERE scope='ip'", String.class)).containsExactly("192.0.2.40");
    }

    @Test
    void concurrentStreamsKeepIndependentOwners() throws Exception {
        var bothInside = new CountDownLatch(2);
        var owners = new ConcurrentLinkedQueue<String>();
        when(runner.run(any(), anyList(), any())).thenAnswer(invocation -> {
            String owner = user.id();
            owners.add(owner);
            bothInside.countDown();
            assertThat(bothInside.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(user.id()).isEqualTo(owner);
            assertThat(RequestContextHolder.getRequestAttributes()).isNull();
            String suffix = owner.substring(2);
            assertThat(uploads.byField("f-" + suffix, 10)).extracting(UploadService.Stored::id)
                    .containsExactly(owner.equals("u-a") ? photoA.id() : photoB.id());
            return ANSWER;
        });
        var first = start(true, "u-a", body(photoA.id()));
        var second = start(true, "u-b", body(photoB.id()));
        assertThat(text(finish(first))).contains("event:done").doesNotContain("event:error");
        assertThat(text(finish(second))).contains("event:done").doesNotContain("event:error");
        assertThat(owners).containsExactlyInAnyOrder("u-a", "u-b");
        assertThat(resolutions.get()).isEqualTo(2);
        assertThat(globalCount()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void automaticFieldPhotosUseTheSameSnapshot(boolean streaming) throws Exception {
        var request = json.readTree(body(null));
        ((com.fasterxml.jackson.databind.node.ObjectNode) request).put("autoFieldPhotos", true)
                .set("field", json.valueToTree(Map.of("id", "f-a", "name", "测试田", "crop", "测试作物", "sowDate", "2026-09-01")));
        var expectedUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(
                java.nio.file.Files.readAllBytes(uploads.fileFor(photoA.id(), "jpg")));
        org.mockito.stubbing.Answer<AgentResult> check = invocation -> {
            assertThat(json.writeValueAsString(invocation.getArgument(1))).contains(expectedUrl);
            assertThat(user.id()).isEqualTo("u-a");
            return ANSWER;
        };
        when(runner.run(any(), anyList())).thenAnswer(check);
        when(runner.run(any(), anyList(), any())).thenAnswer(check);
        assertThat(text(finish(start(streaming, "u-a", request.toString())))).contains("离线测试回答").doesNotContain("event:error");
        assertThat(resolutions.get()).isEqualTo(1);
    }

    static Stream<Object[]> failureStages() {
        return Stream.of("tool", "clarify", "explanation").flatMap(stage -> Stream.of(new Object[]{false, stage}, new Object[]{true, stage}));
    }

    @ParameterizedTest
    @MethodSource("failureStages")
    void identityFailureCannotBeSwallowedByToolsOrSupplementaryRounds(boolean streaming, String stage) throws Exception {
        var unavailable = new CurrentUser();
        unavailable.setResolver(() -> null);
        var registry = new ToolRegistry();
        registry.register(ToolDefinition.of("test_failure", "offline failure", Map.of(), (args, ctx) -> unavailable.id()));
        when(tools.buildRegistry()).thenReturn(registry);
        org.mockito.stubbing.Answer<AgentResult> fail = invocation -> {
            assertThat(user.id()).isEqualTo("u-a");
            if (stage.equals("tool")) {
                AgentRunner.Config config = invocation.getArgument(0);
                config.tools().execute("test_failure", Map.of(), config.ctx());
            } else unavailable.id();
            return ANSWER;
        };
        if (stage.equals("tool")) {
            when(runner.run(any(), anyList())).thenAnswer(fail);
            when(runner.run(any(), anyList(), any())).thenAnswer(fail);
        } else {
            AgentResult first = stage.equals("clarify")
                    ? new AgentResult("请确认记录是否齐全。", List.of(), 1, false)
                    : new AgentResult("确认卡已生成。", List.of(new ToolSubmission("submit_clarify", Map.of(), "test")), 1, false);
            if (stage.equals("explanation")) assertThat(ChatController.isOnlyCardReceipt(first.reply())).isTrue();
            when(runner.run(any(), anyList())).thenReturn(first).thenAnswer(fail);
            when(runner.run(any(), anyList(), any())).thenReturn(first).thenAnswer(fail);
        }
        var result = finish(start(streaming, "u-a", body(null)));
        assertThat(text(result)).contains(ERROR, "IDENTITY_UNAVAILABLE", "503").doesNotContain("event:done", "离线测试回答");
        if (!streaming) assertThat(result.getResponse().getStatus()).isEqualTo(503);
        int expectedCalls = stage.equals("tool") ? 1 : 2;
        if (streaming) verify(runner, times(expectedCalls)).run(any(), anyList(), any());
        else verify(runner, times(expectedCalls)).run(any(), anyList());
        // A provider round has begun: existing no-refund quota semantics remain intact.
        assertThat(globalCount()).isEqualTo(1);
        assertThatThrownBy(user::id).isInstanceOf(CurrentUser.IdentityUnavailable.class);
    }

    private UploadService.Stored createPhoto(String suffix, int color) throws Exception {
        actor.set("u-" + suffix);
        jdbc.update("INSERT INTO fields (id,name,crop,sow_date,user_id) VALUES (?,?,?,?,?)", "f-" + suffix, "测试田", "测试作物", "2026-09-01", actor.get());
        jdbc.update("INSERT INTO farm_tasks (id,title,task_date,field_id,created_at,user_id) VALUES (?,?,?,?,?,?)", "t-" + suffix, "测试任务", "2026-09-15", "f-" + suffix, "2026-09-15T00:00:00", actor.get());
        var picture = new BufferedImage(240, 240, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 240; y++) for (int x = 0; x < 240; x++) picture.setRGB(x, y, color);
        var buffer = new ByteArrayOutputStream();
        ImageIO.write(picture, "png", buffer);
        return uploads.store(buffer.toByteArray(), "image/png", "f-" + suffix, null, "测试照片", null);
    }

    private ChatController controller(ChatStreams worker) {
        var value = new ChatController(runner, tools, worker, mock(KnowledgeLibrary.class), uploads, new VisionSupport(), user);
        ReflectionTestUtils.setField(value, "apiKeys", keys);
        return value;
    }

    private String body(String image) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("messages", List.of(Map.of("role", "user", "content", "请帮我核对记录")));
        body.put("imageInput", "on");
        if (image != null) body.put("imageIds", List.of(image));
        return json.writeValueAsString(body);
    }

    private MvcResult start(boolean streaming, String owner, String body) throws Exception {
        actor.set(owner);
        try {
            return mvc.perform(post(streaming ? "/api/chat/stream" : "/api/chat")
                    .accept(streaming ? MediaType.TEXT_EVENT_STREAM : MediaType.APPLICATION_JSON)
                    .contentType(MediaType.APPLICATION_JSON).content(body)
                    .header("X-User-Id", "u-b").param("userId", "u-b")
                    .with(request -> { request.setRemoteAddr("192.0.2.40"); return request; })).andReturn();
        } finally { actor.remove(); }
    }

    private MvcResult finish(MvcResult pending) throws Exception {
        if (!pending.getRequest().isAsyncStarted()) return pending;
        pending.getAsyncResult(5000);
        return mvc.perform(asyncDispatch(pending)).andReturn();
    }

    private static String text(MvcResult result) throws Exception { return result.getResponse().getContentAsString(StandardCharsets.UTF_8); }
    private int globalCount() { return jdbc.queryForObject("SELECT COALESCE(SUM(count),0) FROM api_usage WHERE scope='global'", Integer.class); }
    private static StreamObserver observer() {
        return new StreamObserver() {
            public boolean cancelled() { return false; }
            public void event(String name, Object data) { }
        };
    }
}
