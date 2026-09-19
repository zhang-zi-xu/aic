package com.nongxin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nongxin.service.*;
import com.nongxin.service.impl.FieldServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real services and synthetic records only: no login, live data or model calls. */
class DataRequestIdentityTest {
    @TempDir Path directory;
    private final CurrentUser user = new CurrentUser();
    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate jdbc;
    private MockMvc mvc;
    private byte[] png;
    private UploadService uploads;
    private UploadService.Stored photoA;
    private UploadService.Stored photoB;
    private FieldServiceImpl fields;

    @BeforeEach
    void setUp() throws Exception {
        var source = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("request-owner.db"));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        fields = spy(new FieldServiceImpl(jdbc, user));
        var conversations = new ConversationService(jdbc, json, user);
        var tasks = new TaskService(jdbc, json, user);
        var library = mock(KnowledgeLibrary.class);
        uploads = new UploadService(jdbc, directory.resolve("images").toString(), 8_388_608, 7, user);
        mvc = MockMvcBuilders.standaloneSetup(new FieldController(fields, user),
                new ConversationController(conversations, fields, library, uploads, user),
                new TaskController(tasks, fields, library, user), new UploadController(uploads, fields, user))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(240, 240, BufferedImage.TYPE_INT_RGB), "png", bytes);
        png = bytes.toByteArray();
        var actor = new AtomicReference<>("u-a");
        user.setResolver(actor::get);
        for (String suffix : List.of("a", "b")) {
            actor.set("u-" + suffix);
            jdbc.update("INSERT INTO fields (id,name,crop,sow_date,user_id) VALUES (?,?,?,?,?)", "f-" + suffix, "测试田-" + suffix, "测试作物", "2026-09-01", actor.get());
            jdbc.update("INSERT INTO field_records (field_id,record_date,note) VALUES (?,?,?)", "f-" + suffix, "2026-09-01", "原始记录");
            jdbc.update("INSERT INTO conversations (id,title,field_id,messages_json,created_at,user_id) VALUES (?,?,?,?,?,?)", "c-" + suffix, "测试对话-" + suffix, "f-" + suffix, "[]", "2026-09-01T00:00:00Z", actor.get());
            jdbc.update("INSERT INTO farm_tasks (id,title,task_date,field_id,status,created_at,user_id) VALUES (?,?,?,?,?,?,?)", "t-" + suffix, "测试任务-" + suffix, "2026-09-01", "f-" + suffix, "pending", "2026-09-01T00:00:00Z", actor.get());
            jdbc.update("INSERT INTO task_records (id,task_id,field_id,kind,record_date,note,created_at) VALUES (?,?,?,?,?,?,?)", "r-" + suffix, "t-" + suffix, "f-" + suffix, "execution", "2026-09-01", "原始记录", "2026-09-01T00:00:00Z");
            var photo = uploads.store(png, "image/png", "f-" + suffix, null, "原始照片", "t-" + suffix);
            if (suffix.equals("a")) photoA = photo;
            else photoB = photo;
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"field-list", "field-get", "field-create", "field-update", "field-delete", "field-record",
            "conversation-list", "conversation-save", "conversation-create", "conversation-delete", "conversation-rename",
            "task-list", "task-get", "task-records", "task-create", "task-update", "task-status", "task-record", "task-delete",
            "upload-list", "upload-get", "upload-field", "upload-create", "upload-edit", "upload-delete"})
    void allDataRoutesKeepOneOwnerForValidationWritesAndResponse(String operation) throws Exception {
        var otherData = otherOwnerSnapshot();
        byte[] otherBytes = Files.readAllBytes(uploads.fileFor(photoB.id(), "jpg"));
        var calls = new AtomicInteger();
        user.setResolver(() -> calls.getAndIncrement() == 0 ? "u-a" : "u-b");
        var result = mvc.perform(request(operation).header("X-User-Id", "u-b").param("userId", "u-b"))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse();
        assertThat(result.getContentAsByteArray()).isNotEmpty();
        assertThat(calls.get()).as(operation + " resolves exactly once").isEqualTo(1);
        assertThat(otherOwnerSnapshot()).isEqualTo(otherData);
        assertThat(Files.readAllBytes(uploads.fileFor(photoB.id(), "jpg"))).containsExactly(otherBytes);
        if (operation.startsWith("conversation-") && (operation.endsWith("save") || operation.endsWith("create"))) {
            var saved = json.readTree(result.getContentAsByteArray());
            assertThat(saved.path("messages").get(0).path("images").get(0).path("id").asText()).isEqualTo(photoA.id());
            assertThat(jdbc.queryForObject("SELECT referenced_at FROM uploads WHERE id=?", String.class, photoA.id())).isNotBlank();
            assertThat(jdbc.queryForObject("SELECT user_id FROM conversations WHERE id=?", String.class, saved.path("id").asText())).isEqualTo("u-a");
        }
        // Same calling thread sees the next identity, never a scope left over from this request.
        assertThat(user.id()).isEqualTo("u-b");
    }

    @ParameterizedTest
    @ValueSource(strings = {"field-create", "conversation-save", "task-record", "upload-create", "upload-field"})
    void laterResolverFailureCannotInterruptAnAlreadyCapturedRequest(String operation) throws Exception {
        var calls = new AtomicInteger();
        user.setResolver(() -> {
            if (calls.getAndIncrement() == 0) return "u-a";
            throw new IllegalStateException("synthetic resolver failure");
        });
        mvc.perform(request(operation)).andExpect(status().is2xxSuccessful());
        assertThat(calls.get()).isEqualTo(1);
        assertThatThrownBy(user::id).isInstanceOf(CurrentUser.IdentityUnavailable.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"fields", "conversations", "tasks", "uploads"})
    void validationFailureReleasesTheScopeBeforeTheNextRequest(String family) throws Exception {
        var original = allDataSnapshot();
        var calls = new AtomicInteger();
        user.setResolver(() -> calls.getAndIncrement() == 0 ? "u-a" : "u-b");
        MockHttpServletRequestBuilder invalid = switch (family) {
            case "conversations" -> put("/api/conversations/c-a").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"\",\"messages\":[]}");
            case "uploads" -> multipart("/api/uploads").file(new MockMultipartFile("file", "test.png", "image/png", png))
                    .param("fieldId", "f-a").param("observedAt", "not-a-date");
            default -> post("/api/" + family).contentType(MediaType.APPLICATION_JSON).content("{}");
        };
        mvc.perform(invalid).andExpect(status().isBadRequest());
        assertThat(calls.get()).isEqualTo(1);
        assertThat(user.id()).isEqualTo("u-b");
        assertThat(allDataSnapshot()).isEqualTo(original);
        user.setResolver(() -> null);
        mvc.perform(get("/api/" + family)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("IDENTITY_UNAVAILABLE"));
        user.setResolver(() -> "u-b");
        mvc.perform(get("/api/fields")).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value("f-b"));
    }

    @Test
    void checkedUploadReadFailureIsNotRewrappedAndStillReleasesIdentity() throws Exception {
        var actor = new AtomicReference<>("u-a");
        user.setResolver(actor::get);
        var file = mock(MultipartFile.class);
        var storage = mock(UploadService.class);
        var fieldService = mock(FieldService.class);
        var controller = new UploadController(storage, fieldService, user);
        var failure = new IOException("synthetic upload read failure");
        when(file.getBytes()).thenAnswer(invocation -> {
            assertThat(user.id()).isEqualTo("u-a");
            actor.set("u-b");
            throw failure;
        });
        assertThatThrownBy(() -> controller.upload(file, null, null, null, null)).isSameAs(failure);
        assertThat(user.id()).isEqualTo("u-b");
        verifyNoInteractions(storage, fieldService);
    }

    @Test
    void missingIdentityCannotInspectOrReadMultipartFileInTheController() {
        user.setResolver(() -> null);
        var file = mock(MultipartFile.class);
        var storage = mock(UploadService.class);
        var fieldService = mock(FieldService.class);
        var controller = new UploadController(storage, fieldService, user);
        assertThatThrownBy(() -> controller.upload(file, "f-a", null, null, null))
                .isInstanceOf(CurrentUser.IdentityUnavailable.class);
        verifyNoInteractions(file, storage, fieldService);
    }

    @Test
    void concurrentDataRequestsKeepIndependentScopes() throws Exception {
        var actor = new ThreadLocal<String>();
        user.setResolver(actor::get);
        var bothInside = new CountDownLatch(2);
        doAnswer(invocation -> {
            String owner = user.id();
            bothInside.countDown();
            assertThat(bothInside.await(5, TimeUnit.SECONDS)).isTrue();
            actor.set("u-outside");
            assertThat(user.id()).isEqualTo(owner);
            return invocation.callRealMethod();
        }).when(fields).list();
        var executor = Executors.newFixedThreadPool(2);
        try {
            var futures = List.of("a", "b").stream().map(suffix -> executor.submit(() -> {
                actor.set("u-" + suffix);
                try {
                    mvc.perform(get("/api/fields").header("X-User-Id", "u-other"))
                            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                            .andExpect(jsonPath("$[0].id").value("f-" + suffix));
                    assertThat(user.id()).isEqualTo("u-outside");
                    return true;
                } finally { actor.remove(); }
            })).toList();
            for (var future : futures) assertThat(future.get(5, TimeUnit.SECONDS)).isTrue();
        } finally { executor.shutdownNow(); assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test
    void controllerStartupHooksAreNotWrappedAsInteractiveRequests() {
        user.setResolver(() -> { throw new AssertionError("No controller-level identity capture in startup hooks"); });
        var storage = mock(UploadService.class);
        var taskService = mock(TaskService.class);
        var fieldService = mock(FieldService.class);
        new UploadController(storage, fieldService, user).run(new org.springframework.boot.DefaultApplicationArguments());
        new TaskController(taskService, fieldService, mock(KnowledgeLibrary.class), user)
                .run(new org.springframework.boot.DefaultApplicationArguments());
        verify(storage).cleanupUnreferenced();
        verify(taskService).mergeDuplicates();
        // TaskService's own legacy maintenance policy is not changed or authenticated by this test.
    }

    private Map<String, List<Map<String, Object>>> allDataSnapshot() {
        var result = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (String table : List.of("fields", "field_records", "conversations", "farm_tasks", "task_records", "uploads"))
            result.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
        return result;
    }

    private MockHttpServletRequestBuilder request(String operation) throws Exception {
        String field = json.writeValueAsString(Map.of("name", "更新后的测试田", "crop", "测试作物", "sowDate", "2026-09-01"));
        String conversation = json.writeValueAsString(Map.of("title", "更新后的测试对话", "fieldId", "f-a",
                "messages", List.of(Map.of("id", "m-a", "role", "user", "content", "测试消息", "images", List.of(Map.of("id", photoA.id()))))));
        String task = json.writeValueAsString(Map.of("title", "更新后的测试任务", "fieldId", "f-a", "date", "2026-09-01", "status", "pending"));
        return switch (operation) {
            case "field-list" -> get("/api/fields");
            case "field-get" -> get("/api/fields/f-a");
            case "field-create" -> post("/api/fields").contentType(MediaType.APPLICATION_JSON).content(field);
            case "field-update" -> put("/api/fields/f-a").contentType(MediaType.APPLICATION_JSON).content(field);
            case "field-delete" -> delete("/api/fields/f-a");
            case "field-record" -> post("/api/fields/f-a/records").contentType(MediaType.APPLICATION_JSON).content("{\"date\":\"2026-09-16\",\"note\":\"更新后的记录\"}");
            case "conversation-list" -> get("/api/conversations");
            case "conversation-save" -> put("/api/conversations/c-a").contentType(MediaType.APPLICATION_JSON).content(conversation);
            case "conversation-create" -> put("/api/conversations/c-new").contentType(MediaType.APPLICATION_JSON).content(conversation);
            case "conversation-delete" -> delete("/api/conversations/c-a");
            case "conversation-rename" -> patch("/api/conversations/c-a").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"更新后的测试对话\"}");
            case "task-list" -> get("/api/tasks");
            case "task-get" -> get("/api/tasks/t-a");
            case "task-records" -> get("/api/tasks/t-a/records");
            case "task-create" -> post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content(task);
            case "task-update" -> put("/api/tasks/t-a").contentType(MediaType.APPLICATION_JSON).content(task);
            case "task-status" -> post("/api/tasks/t-a/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"cancelled\"}");
            case "task-record" -> post("/api/tasks/t-a/records").contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"execution\",\"note\":\"更新后的记录\"}");
            case "task-delete" -> delete("/api/tasks/t-a");
            case "upload-list" -> get("/api/uploads");
            case "upload-get" -> get("/api/uploads/" + photoA.id());
            case "upload-field" -> get("/api/uploads/field/f-a");
            case "upload-create" -> multipart("/api/uploads").file(new MockMultipartFile("file", "test.png", "image/png", png)).param("fieldId", "f-a").param("taskId", "t-a");
            case "upload-edit" -> patch("/api/uploads/" + photoA.id()).contentType(MediaType.APPLICATION_JSON).content("{\"fieldId\":\"f-a\",\"note\":\"updated\"}");
            case "upload-delete" -> delete("/api/uploads/" + photoA.id());
            default -> throw new AssertionError(operation);
        };
    }

    private Map<String, List<Map<String, Object>>> otherOwnerSnapshot() {
        var result = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (String table : List.of("fields", "conversations", "farm_tasks", "uploads"))
            result.put(table, jdbc.queryForList("SELECT * FROM " + table + " WHERE user_id='u-b' ORDER BY id"));
        result.put("field_records", jdbc.queryForList("SELECT * FROM field_records WHERE field_id='f-b' ORDER BY id"));
        result.put("task_records", jdbc.queryForList("SELECT * FROM task_records WHERE task_id='t-b' ORDER BY id"));
        return result;
    }
}
