package com.nongxin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nongxin.service.ConversationService;
import com.nongxin.service.CurrentUser;
import com.nongxin.service.KnowledgeLibrary;
import com.nongxin.service.TaskService;
import com.nongxin.service.UploadService;
import com.nongxin.service.impl.FieldServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Synthetic local-owner records only; this does not test a login system. */
class DataIdentityFailureTest {
    @TempDir Path directory;
    private final CurrentUser user = new CurrentUser();
    private JdbcTemplate jdbc;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var source = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("identity.db"));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        var fields = new FieldServiceImpl(jdbc, user);
        var json = new ObjectMapper();
        var library = mock(KnowledgeLibrary.class);
        mvc = MockMvcBuilders.standaloneSetup(new FieldController(fields, user),
                new ConversationController(new ConversationService(jdbc, json, user), fields, library, mock(UploadService.class), user),
                new TaskController(new TaskService(jdbc, json, user), fields, library, user))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        jdbc.update("INSERT INTO fields (id,name,crop,sow_date,user_id) VALUES ('f-local','测试田','测试作物','2026-09-01',?)", CurrentUser.LOCAL_OWNER);
        jdbc.update("INSERT INTO field_records (field_id,record_date,note) VALUES ('f-local','2026-09-01','原始记录')");
        jdbc.update("INSERT INTO conversations (id,title,field_id,messages_json,created_at,user_id) VALUES ('c-local','测试对话','f-local','[]','2026-09-01T00:00:00',?)", CurrentUser.LOCAL_OWNER);
        jdbc.update("INSERT INTO farm_tasks (id,title,task_date,field_id,status,created_at,user_id) VALUES ('t-local','测试任务','2026-09-01','f-local','pending','2026-09-01T00:00:00',?)", CurrentUser.LOCAL_OWNER);
        jdbc.update("INSERT INTO task_records (id,task_id,kind,record_date,note,created_at) VALUES ('r-local','t-local','execution','2026-09-01','原始记录','2026-09-01T00:00:00')");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "blank", "exception"})
    void failedIdentityCannotReadOrChangeLocalFieldsConversationsOrTasks(String failure) throws Exception {
        var original = snapshot();
        // The default remains usable without login before and after a failed configured resolver.
        for (String endpoint : List.of("fields", "conversations", "tasks")) {
            mvc.perform(get("/api/" + endpoint)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        }
        user.setResolver(() -> switch (failure) {
            case "null" -> null;
            case "blank" -> " \t";
            default -> throw new IllegalStateException("private resolver detail");
        });
        String field = "{\"name\":\"changed\",\"crop\":\"测试作物\",\"sowDate\":\"2026-09-01\"}";
        String conversation = "{\"title\":\"changed\",\"messages\":[],\"createdAt\":\"2026-09-01T00:00:00Z\"}";
        var requests = List.of(
                get("/api/fields"), get("/api/fields/f-local"),
                post("/api/fields").contentType(MediaType.APPLICATION_JSON).content(field),
                put("/api/fields/f-local").contentType(MediaType.APPLICATION_JSON).content(field),
                post("/api/fields/f-local/records").contentType(MediaType.APPLICATION_JSON).content("{\"date\":\"2026-09-01\",\"note\":\"changed\"}"),
                delete("/api/fields/f-local"), get("/api/conversations"),
                put("/api/conversations/c-local").contentType(MediaType.APPLICATION_JSON).content(conversation),
                put("/api/conversations/c-new").contentType(MediaType.APPLICATION_JSON).content(conversation),
                patch("/api/conversations/c-local").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"changed\"}"),
                delete("/api/conversations/c-local"), get("/api/tasks"), get("/api/tasks/t-local"), get("/api/tasks/t-local/records"),
                post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"new task\"}"),
                post("/api/tasks/t-local/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"cancelled\"}"),
                post("/api/tasks/t-local/records").contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"execution\",\"note\":\"changed\"}"),
                delete("/api/tasks/t-local"));
        for (var request : requests) {
            mvc.perform(request.header("X-User-Id", CurrentUser.LOCAL_OWNER).param("userId", CurrentUser.LOCAL_OWNER))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.code").value("IDENTITY_UNAVAILABLE"))
                    .andExpect(jsonPath("$.error").value("当前用户身份暂时无法确认，请稍后重试"));
            assertThat(snapshot()).isEqualTo(original);
        }
        user.reset();
        for (String endpoint : List.of("fields", "conversations", "tasks")) {
            mvc.perform(get("/api/" + endpoint)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        }
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (String table : List.of("fields", "field_records", "conversations", "farm_tasks", "task_records")) {
            result.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
        }
        return result;
    }
}
