package com.nongxin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nongxin.model.FarmTask;
import com.nongxin.service.ConversationService;
import com.nongxin.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class WorkspaceApiIntegrationTest {
    // The application database is never used by this test suite.
    private static final Path DATABASE = temporaryDatabase();
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
    }

    private static Path temporaryDatabase() {
        try {
            return Files.createTempDirectory("nongxin-api-test-").resolve("test.db");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @BeforeEach
    void clearTestData() {
        jdbc.update("DELETE FROM task_records");
        jdbc.update("DELETE FROM farm_tasks");
        jdbc.update("DELETE FROM conversations");
        jdbc.update("DELETE FROM field_records");
        jdbc.update("DELETE FROM fields");
    }

    @Test
    void reportsHealthCatalogAndEmptyUserCollectionsWithoutSeedRows() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.service").value("nongxin-api"));
        mvc.perform(get("/api/knowledge")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").isString()).andExpect(jsonPath("$[0].title").isString())
                .andExpect(jsonPath("$[0].reviewStatus").value("verified"))
                .andExpect(jsonPath("$[0].institution").isString())
                .andExpect(jsonPath("$[0].url").isString())
                .andExpect(jsonPath("$[0].publishedAt").isString())
                .andExpect(jsonPath("$[0].crops[0]").isString())
                .andExpect(jsonPath("$[5].reviewStatus").value("unverified"))
                .andExpect(jsonPath("$[5].url").doesNotExist());
        for (String path : List.of("fields", "tasks", "conversations")) {
            mvc.perform(get("/api/" + path)).andExpect(status().isOk()).andExpect(content().json("[]"));
        }
    }

    @Test
    void fieldUpdatePreservesHistoryAndRejectsImpossibleDateOrArea() throws Exception {
        createField("f-preserved");
        mvc.perform(post("/api/fields/f-preserved/records").contentType("application/json")
                        .content(json.writeValueAsString(Map.of("date", "2026-09-06", "note", "检查田间排水"))))
                .andExpect(status().isOk());
        Map<String, Object> changed = field("f-preserved");
        changed.put("name", "更新后的田块");
        changed.put("records", List.of());
        mvc.perform(put("/api/fields/f-preserved").contentType("application/json").content(json.writeValueAsString(changed)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("更新后的田块"))
                .andExpect(jsonPath("$.records[0].note").value("检查田间排水"));
        for (Object invalidArea : List.of(0, -1)) {
            changed.put("areaMu", invalidArea);
            mvc.perform(put("/api/fields/f-preserved").contentType("application/json").content(json.writeValueAsString(changed)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").isString());
        }
        changed.put("areaMu", 12);
        changed.put("sowDate", "2026-02-30");
        mvc.perform(post("/api/fields").contentType("application/json").content(json.writeValueAsString(changed)))
                .andExpect(status().isBadRequest());
        changed.put("sowDate", "2026-9-1");
        mvc.perform(post("/api/fields").contentType("application/json").content(json.writeValueAsString(changed)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/fields/f-preserved")).andExpect(status().isOk())
                .andExpect(jsonPath("$.areaMu").value(12)).andExpect(jsonPath("$.records.length()").value(1));
        mvc.perform(post("/api/fields/f-preserved/records").contentType("application/json")
                        .content("{\"date\":\"bad\",\"note\":\"检查\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tasksSurviveNewDatabaseConnectionAndAcceptUnknownDate() throws Exception {
        createField("f-tasks");
        Map<String, Object> task = task("t-persisted", "f-tasks");
        mvc.perform(post("/api/tasks").contentType("application/json").content(json.writeValueAsString(task)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.statusLabel").value("待执行"))
                .andExpect(jsonPath("$.date").value(""));
        task.put("note", "已检查并记录");
        task.put("createdAt", "2030-01-01T00:00:00Z");
        mvc.perform(put("/api/tasks/t-persisted").contentType("application/json").content(json.writeValueAsString(task)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.note").value("已检查并记录"))
                .andExpect(jsonPath("$.createdAt").value("2026-09-06T00:00:00Z"));
        TaskService reopened = new TaskService(freshDatabaseConnection(), json, new com.nongxin.service.CurrentUser());
        FarmTask persisted = reopened.get("t-persisted");
        assertThat(persisted.status()).isEqualTo("pending");
        assertThat(persisted.note()).isEqualTo("已检查并记录");
        assertThat(persisted.date()).isEmpty();
        assertThat(persisted.sourceMessageId()).isEqualTo("m-plan");
    }

    @Test
    void invalidTasksAndUnknownUpdatesDoNotCreateRecords() throws Exception {
        Map<String, Object> task = task("t-invalid", null);
        task.put("date", "2026-02-30");
        mvc.perform(post("/api/tasks").contentType("application/json").content(json.writeValueAsString(task)))
                .andExpect(status().isBadRequest());
        task.put("date", "2026-09-06");
        task.put("fieldId", "missing-field");
        mvc.perform(post("/api/tasks").contentType("application/json").content(json.writeValueAsString(task)))
                .andExpect(status().isBadRequest());
        task.put("fieldId", null);
        mvc.perform(put("/api/tasks/t-invalid").contentType("application/json").content(json.writeValueAsString(task)))
                .andExpect(status().isNotFound());
        task.put("title", "  ");
        mvc.perform(post("/api/tasks").contentType("application/json").content(json.writeValueAsString(task)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/tasks")).andExpect(content().json("[]"));
    }

    @Test
    void duplicateIdsReturnConflictAndKeepOriginalRecord() throws Exception {
        createField("f-duplicate");
        Map<String, Object> duplicate = field("f-duplicate");
        duplicate.put("name", "不得覆盖原记录");
        mvc.perform(post("/api/fields").contentType("application/json").content(json.writeValueAsString(duplicate)))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/fields/f-duplicate")).andExpect(jsonPath("$.name").value("试验田"));
        Map<String, Object> task = task("t-duplicate", null);
        mvc.perform(post("/api/tasks").contentType("application/json").content(json.writeValueAsString(task)))
                .andExpect(status().isCreated());
        task.put("title", "不得覆盖原任务");
        mvc.perform(post("/api/tasks").contentType("application/json").content(json.writeValueAsString(task)))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/tasks")).andExpect(jsonPath("$[0].title").value("检查排水沟"));
    }

    @Test
    void conversationsPersistCardsButExcludeProviderSettings() throws Exception {
        Map<String, Object> body = conversation("c-saved", null);
        body.put("apiKey", "fake-do-not-store");
        body.put("settings", Map.of("apiKey", "fake-do-not-store"));
        mvc.perform(put("/api/conversations/c-saved").contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.messages[0].plan.items[0].task").value("检查排水沟"))
                .andExpect(jsonPath("$.apiKey").doesNotExist()).andExpect(jsonPath("$.settings").doesNotExist());
        String stored = jdbc.queryForObject("SELECT messages_json FROM conversations WHERE id='c-saved'", String.class);
        assertThat(stored).doesNotContain("fake-do-not-store", "apiKey", "settings");
        ConversationService reopened = new ConversationService(freshDatabaseConnection(), json, new com.nongxin.service.CurrentUser());
        assertThat(reopened.get("c-saved").messages().getFirst().plan()).containsKey("items");
        body.put("title", "更新标题");
        body.put("createdAt", "2030-01-01T00:00:00Z");
        mvc.perform(put("/api/conversations/c-saved").contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("更新标题"))
                .andExpect(jsonPath("$.createdAt").value("2026-09-06T00:00:00Z"));
        mvc.perform(get("/api/conversations")).andExpect(jsonPath("$.length()").value(1));
        body.put("messages", List.of(Map.of("id", "m-bad", "role", "system", "content", "invalid")));
        mvc.perform(put("/api/conversations/c-saved").contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
        body.put("messages", List.of(Map.of("id", "m-bad", "role", "assistant", "content", "x", "plan", Map.of("api_key", "fake"))));
        mvc.perform(put("/api/conversations/c-saved").contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void savedSourceCardsAreRebuiltFromTheLibraryAndCannotBeForged() throws Exception {
        Map<String, Object> body = conversation("c-sources", null);
        body.put("messages", List.of(Map.of("id", "m-1", "role", "assistant", "content", "回答内容",
                "sources", List.of(
                        Map.of("id", "chunk-pest-rice-blast", "status", "unverified", "title", "伪造标题",
                                "url", "javascript:alert(1)"),
                        Map.of("id", "chunk-not-in-library", "status", "verified", "url", "https://evil.example")))));

        mvc.perform(put("/api/conversations/c-sources").contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].sources.length()").value(1))
                .andExpect(jsonPath("$.messages[0].sources[0].id").value("chunk-pest-rice-blast"))
                .andExpect(jsonPath("$.messages[0].sources[0].status").value("verified"))
                .andExpect(jsonPath("$.messages[0].sources[0].title").value("2025年粮食作物重大病虫害防控技术方案"))
                .andExpect(jsonPath("$.messages[0].sources[0].url").value("https://www.moa.gov.cn/xw/zxfb/202502/t20250228_6470753.htm"));
    }

    @Test
    void fieldDeletionRetainsTaskAndConversationContentWithNoDanglingFieldLink() throws Exception {
        createField("f-delete");
        mvc.perform(post("/api/tasks").contentType("application/json").content(json.writeValueAsString(task("t-kept", "f-delete"))))
                .andExpect(status().isCreated());
        mvc.perform(put("/api/conversations/c-kept").contentType("application/json")
                .content(json.writeValueAsString(conversation("c-kept", "f-delete")))).andExpect(status().isOk());
        mvc.perform(delete("/api/fields/f-delete")).andExpect(status().isOk());
        mvc.perform(get("/api/tasks")).andExpect(jsonPath("$[0].id").value("t-kept"))
                .andExpect(jsonPath("$[0].fieldId").doesNotExist()).andExpect(jsonPath("$[0].fieldName").value("试验田"));
        mvc.perform(get("/api/conversations")).andExpect(jsonPath("$[0].id").value("c-kept"))
                .andExpect(jsonPath("$[0].fieldId").doesNotExist()).andExpect(jsonPath("$[0].messages.length()").value(1));
        mvc.perform(delete("/api/tasks/t-kept")).andExpect(status().isOk());
        mvc.perform(delete("/api/conversations/c-kept")).andExpect(status().isOk());
        mvc.perform(delete("/api/fields/f-delete")).andExpect(status().isNotFound());
    }

    private JdbcTemplate freshDatabaseConnection() {
        DriverManagerDataSource source = new DriverManagerDataSource("jdbc:sqlite:" + DATABASE);
        source.setDriverClassName("org.sqlite.JDBC");
        return new JdbcTemplate(source);
    }

    private void createField(String id) throws Exception {
        mvc.perform(post("/api/fields").contentType("application/json").content(json.writeValueAsString(field(id))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
    }

    private Map<String, Object> field(String id) {
        return new LinkedHashMap<>(Map.of("id", id, "name", "试验田", "crop", "玉米", "sowDate", "2026-06-15", "areaMu", 12));
    }

    private Map<String, Object> task(String id, String fieldId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("title", "检查排水沟");
        result.put("date", "");
        result.put("fieldId", fieldId);
        result.put("fieldName", "试验田");
        result.put("condition", "观察田间积水情况后决定");
        result.put("method", "现场巡查");
        result.put("review", "记录检查结果");
        result.put("status", "pending");
        result.put("createdAt", "2026-09-06T00:00:00Z");
        result.put("sourceMessageId", "m-plan");
        return result;
    }

    private Map<String, Object> conversation(String id, String fieldId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("title", "田间巡查");
        result.put("fieldId", fieldId);
        result.put("createdAt", "2026-09-06T00:00:00Z");
        result.put("messages", List.of(Map.of("id", "m-plan", "role", "assistant", "content", "先检查排水情况。",
                "plan", Map.of("items", List.of(Map.of("task", "检查排水沟", "condition", "现场确认"))))));
        return result;
    }
}
