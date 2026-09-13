package com.nongxin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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

/**
 * P2 闭环：方案项幂等登记 → 确认 → 执行记录 → 复查记录 → 完成；
 * 以及"缺记录不许推进状态""依据不可伪造""记录能追到田块与来源消息"。
 * 全程使用临时数据库，不触碰 data/nongxin.db。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TaskFlowIntegrationTest {
    private static final Path DATABASE = temporaryDatabase();
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.nongxin.service.TaskService tasks;
    @Autowired private com.nongxin.service.KnowledgeLibrary knowledge;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
    }

    private static Path temporaryDatabase() {
        try {
            return Files.createTempDirectory("nongxin-task-flow-").resolve("test.db");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @BeforeEach
    void clearTestData() throws Exception {
        jdbc.update("DELETE FROM task_records");
        jdbc.update("DELETE FROM farm_tasks");
        jdbc.update("DELETE FROM fields");
        mvc.perform(post("/api/fields").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(
                Map.of("id", "f-flow", "name", "闭环试验田", "crop", "水稻", "sowDate", "2026-06-01", "areaMu", 8))));
    }

    /** 同一条回复里的同一个方案项重复点「加入任务」，只能有一条任务。 */
    @Test
    void registeringTheSamePlanItemTwiceIsIdempotent() throws Exception {
        Map<String, Object> planTask = planTask("pending_confirmation");
        mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(planTask)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("pending_confirmation"))
                .andExpect(jsonPath("$.statusLabel").value("待确认"))
                .andExpect(jsonPath("$.planItemId").value("p1"));

        mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(planTask)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("t-plan-1"));

        mvc.perform(get("/api/tasks")).andExpect(jsonPath("$.length()").value(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM farm_tasks", Integer.class)).isEqualTo(1);
    }

    /** 确认安排 → 执行记录 → 复查记录，全程由用户提交的事实驱动。 */
    @Test
    void taskMovesThroughConfirmationExecutionAndReview() throws Exception {
        mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(planTask("pending_confirmation"))))
                .andExpect(status().isCreated());

        // 未确认前不能直接跳到"已执行待复查"
        mvc.perform(post("/api/tasks/t-plan-1/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"awaiting_review\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("「已执行待复查」必须由提交执行记录进入，不能直接改状态"));

        mvc.perform(post("/api/tasks/t-plan-1/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"pending\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.confirmedAt").isString());

        // 没有执行记录就想完成 → 拒绝
        mvc.perform(post("/api/tasks/t-plan-1/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"completed\"}"))
                .andExpect(status().isConflict());

        // 复查记录不能先于执行记录
        mvc.perform(post("/api/tasks/t-plan-1/records").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("kind", "review", "note", "复查：未见病斑", "outcome", "resolved"))))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/tasks/t-plan-1/records").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("kind", "execution", "date", "2026-09-12",
                                "note", "上午按方案喷施，风力 2 级，用时 1.5 小时", "sourceMessageId", "m-plan"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("awaiting_review"))
                .andExpect(jsonPath("$.statusLabel").value("已执行待复查"))
                .andExpect(jsonPath("$.executedAt").isString())
                .andExpect(jsonPath("$.records.length()").value(1))
                .andExpect(jsonPath("$.records[0].kind").value("execution"))
                .andExpect(jsonPath("$.records[0].fieldId").value("f-flow"))
                .andExpect(jsonPath("$.records[0].sourceMessageId").value("m-plan"));

        mvc.perform(post("/api/tasks/t-plan-1/records").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("kind", "review", "date", "2026-09-16",
                                "note", "病斑没有扩展，新叶干净", "outcome", "improved"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.completedAt").isString())
                .andExpect(jsonPath("$.records.length()").value(2))
                .andExpect(jsonPath("$.records[1].outcome").value("improved"));

        // 记录持久化并可单独查询
        mvc.perform(get("/api/tasks/t-plan-1/records")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_records WHERE task_id='t-plan-1'", Integer.class)).isEqualTo(2);
    }

    /** 取消 / 恢复 / 重新打开都允许，且历史记录不丢。 */
    @Test
    void cancelledAndCompletedTasksCanBeReopenedWithoutLosingRecords() throws Exception {
        mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(planTask("pending"))))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/tasks/t-plan-1/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"cancelled\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("cancelled"));
        // 取消后不能提交记录
        mvc.perform(post("/api/tasks/t-plan-1/records").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("kind", "execution", "note", "已喷施"))))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/tasks/t-plan-1/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"pending\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/tasks/t-plan-1/records").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("kind", "execution", "note", "补做完成"))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/tasks/t-plan-1/records").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("kind", "review", "note", "复查无异常", "outcome", "resolved"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("completed"));
        // 已完成的任务退回待执行后仍能看到两条记录
        mvc.perform(post("/api/tasks/t-plan-1/status").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"pending\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.records.length()").value(2));
    }

    /** 依据只能来自真实来源库，客户端伪造的 chunkId 会被剔除。 */
    @Test
    void forgedEvidenceIsDroppedAndRealChunkIsKept() throws Exception {
        String realChunk = knowledge.chunks().isEmpty() ? null : knowledge.chunks().getFirst().id();
        Map<String, Object> body = planTask("pending");
        body.put("evidence", realChunk == null ? List.of("chunk-not-real") : List.of("chunk-not-real", realChunk));
        mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.evidence.length()").value(realChunk == null ? 0 : 1));
        if (realChunk != null) {
            mvc.perform(get("/api/tasks/t-plan-1")).andExpect(jsonPath("$.evidence[0]").value(realChunk))
                    .andExpect(jsonPath("$.evidenceCards[0].id").value(realChunk));
        }
    }

    /** 删除任务时执行记录一并清理，不留孤儿。 */
    @Test
    void deletingTaskRemovesItsRecords() throws Exception {
        mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(planTask("pending"))))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/tasks/t-plan-1/records").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("kind", "execution", "note", "已执行"))))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/tasks/t-plan-1")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_records", Integer.class)).isZero();
    }

    /** 记录内容必填、类型受限。 */
    @Test
    void recordsRequireContentAndKnownKind() throws Exception {
        mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(planTask("pending"))))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/tasks/t-plan-1/records").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("kind", "execution", "note", "   "))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/tasks/t-plan-1/records").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("kind", "photo", "note", "看图"))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/tasks/t-plan-1/records").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("kind", "review", "note", "复查", "outcome", "unknown-value"))))
                .andExpect(status().isBadRequest());
    }

    /** 重复任务自动合并：同名同日期同田块只留一条；有记录或日期不同的绝不自动删。 */
    @Test
    void duplicateTasksAreMergedAutomaticallyButRecordsAreNeverDiscarded() throws Exception {
        // 历史遗留数据直接落库（现在 create 已经会按标题去重，重复只能来自旧版本或直接改标题）
        for (int i = 1; i <= 3; i++) {
            jdbc.update("INSERT INTO farm_tasks (id,title,task_date,field_id,status,created_at) VALUES (?,?,?,?,?,?)",
                    "t-dup-" + i, "全田定级复查：数病穗率、拍照留档", "2026-09-11", "f-flow", "pending",
                    "2026-09-11T02:4" + i + ":00");
        }
        // 日期不同 → 视为两次安排，不合并
        jdbc.update("INSERT INTO farm_tasks (id,title,task_date,field_id,status,created_at) VALUES (?,?,?,?,?,?)",
                "t-other-day", "全田定级复查：数病穗率、拍照留档", "2026-09-19", "f-flow", "pending", "2026-09-11T03:00:00");
        // 有执行记录 → 用户写过的事实，绝不自动删
        jdbc.update("INSERT INTO farm_tasks (id,title,task_date,field_id,status,created_at) VALUES (?,?,?,?,?,?)",
                "t-with-record", "全田定级复查：数病穗率、拍照留档", "2026-09-11", "f-flow", "pending", "2026-09-11T02:45:00");
        jdbc.update("INSERT INTO task_records (id,task_id,field_id,kind,record_date,note,outcome,created_at)"
                + " VALUES ('r-dup','t-with-record','f-flow','execution','2026-09-12','已按方案执行','','2026-09-12T09:00:00')");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM farm_tasks", Integer.class)).isEqualTo(5);

        int merged = tasks.mergeDuplicates();

        assertThat(merged).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM farm_tasks", Integer.class)).isEqualTo(3);
        assertThat(tasks.get("t-dup-1")).isNotNull();
        assertThat(tasks.get("t-dup-2")).isNull();
        assertThat(tasks.get("t-other-day")).isNotNull();
        assertThat(tasks.get("t-with-record")).isNotNull();
    }

    private Map<String, Object> planTask(String status) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("id", "t-plan-1");
        task.put("title", "破口期预防施药");
        task.put("date", "2026-09-12");
        task.put("fieldId", "f-flow");
        task.put("condition", "雨停后、田面无积水");
        task.put("method", "按登记标签兑水均匀喷施");
        task.put("review", "施药后 5 天检查病斑扩展");
        task.put("timeWindow", "破口前 3—5 天");
        task.put("materials", "待确认（以当地登记标签为准）");
        task.put("risk", "避开高温时段与蜜蜂活动区");
        task.put("status", status);
        task.put("planItemId", "p1");
        task.put("sourceMessageId", "m-plan");
        task.put("createdAt", "2026-09-11T09:00:00Z");
        return task;
    }
}
