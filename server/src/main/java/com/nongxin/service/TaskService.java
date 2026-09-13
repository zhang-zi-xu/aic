package com.nongxin.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nongxin.model.FarmTask;
import com.nongxin.model.TaskRecord;
import com.nongxin.model.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 农事任务与执行/复查记录。
 *
 * <p>状态推进规则见 {@link TaskStatus}：进入「已执行待复查」必须提交执行记录，
 * 进入「已完成」必须提交复查记录——AI 不能替用户宣告任务完成。
 * 同一方案项（来源消息 + 方案项 ID）重复登记时返回已存在的任务，保证幂等。
 */
@Service
public class TaskService {
    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CurrentUser currentUser;

    private final RowMapper<FarmTask> taskMapper = (rs, row) -> new FarmTask(
            rs.getString("id"), rs.getString("title"), rs.getString("task_date"),
            rs.getString("field_id"), rs.getString("field_name"), rs.getString("condition_text"),
            rs.getString("method"), rs.getString("review"), rs.getString("note"),
            rs.getString("status"), null, rs.getString("time_window"), rs.getString("materials"),
            rs.getString("risk"), readEvidence(rs.getString("evidence")), List.of(),
            rs.getString("plan_item_id"), rs.getString("source_message_id"),
            rs.getString("created_at"), rs.getString("updated_at"), rs.getString("confirmed_at"),
            rs.getString("executed_at"), rs.getString("completed_at"), List.of());

    private static final RowMapper<TaskRecord> RECORD_MAPPER = (rs, row) -> new TaskRecord(
            rs.getString("id"), rs.getString("task_id"), rs.getString("field_id"), rs.getString("kind"),
            rs.getString("record_date"), rs.getString("note"), rs.getString("outcome"),
            rs.getString("source_message_id"), rs.getString("created_at"));

    public TaskService(JdbcTemplate jdbc, ObjectMapper json, CurrentUser currentUser) {
        this.jdbc = jdbc;
        this.json = json;
        this.currentUser = currentUser;
    }

    // ---- 查询 ----

    public List<FarmTask> list() {
        Map<String, List<TaskRecord>> records = recordsByTask(null);
        return jdbc.query("SELECT * FROM farm_tasks WHERE user_id=? ORDER BY task_date ASC, created_at ASC, id ASC",
                        taskMapper, currentUser.id())
                .stream().map(task -> task.withRecords(records.getOrDefault(task.id(), List.of()))).toList();
    }

    public FarmTask get(String id) {
        List<FarmTask> rows = jdbc.query("SELECT * FROM farm_tasks WHERE id=? AND user_id=?", taskMapper, id, currentUser.id());
        return rows.isEmpty() ? null : rows.getFirst().withRecords(records(id));
    }

    public List<TaskRecord> records(String taskId) {
        // 记录表本身没有归属字段；通过"这条记录所属任务是不是当前用户的"来过滤
        return jdbc.query("SELECT * FROM task_records WHERE task_id=?"
                        + " AND EXISTS (SELECT 1 FROM farm_tasks t WHERE t.id=task_records.task_id AND t.user_id=?)"
                        + " ORDER BY record_date ASC, created_at ASC, id ASC",
                RECORD_MAPPER, taskId, currentUser.id());
    }

    /** 某田块的任务（供对话上下文使用），按日期倒序取最近 limit 条。 */
    public List<FarmTask> forField(String fieldId, int limit) {
        if (fieldId == null || fieldId.isBlank()) return List.of();
        Map<String, List<TaskRecord>> records = recordsByTask(fieldId);
        return jdbc.query("SELECT * FROM farm_tasks WHERE field_id=? AND user_id=?"
                                + " ORDER BY task_date DESC, created_at DESC, id DESC LIMIT ?",
                        taskMapper, fieldId, currentUser.id(), limit)
                .stream().map(task -> task.withRecords(records.getOrDefault(task.id(), List.of()))).toList();
    }

    private Map<String, List<TaskRecord>> recordsByTask(String fieldId) {
        String guard = " AND EXISTS (SELECT 1 FROM farm_tasks t WHERE t.id=task_records.task_id AND t.user_id=?)";
        List<TaskRecord> rows = fieldId == null
                ? jdbc.query("SELECT * FROM task_records WHERE 1=1" + guard
                        + " ORDER BY record_date ASC, created_at ASC, id ASC", RECORD_MAPPER, currentUser.id())
                : jdbc.query("SELECT * FROM task_records WHERE field_id=?" + guard
                        + " ORDER BY record_date ASC, created_at ASC, id ASC", RECORD_MAPPER, fieldId, currentUser.id());
        Map<String, List<TaskRecord>> grouped = new LinkedHashMap<>();
        for (TaskRecord record : rows) grouped.computeIfAbsent(record.taskId(), key -> new ArrayList<>()).add(record);
        return grouped;
    }

    // ---- 写入 ----

    /** 登记任务；同一来源消息的同一方案项、或同一田块里同名未完成的任务，都复用已有记录（幂等）。 */
    public FarmTask create(FarmTask task) {
        FarmTask existing = findByPlanItem(task.sourceMessageId(), task.planItemId());
        if (existing != null) {
            log.info("[task] 方案项已登记，复用任务 {}（source={} item={}）", existing.id(), task.sourceMessageId(), task.planItemId());
            return existing;
        }
        // 跨轮去重：模型每轮都会重新生成方案卡，方案项 ID 只在单条消息内唯一，
        // 所以再按"同一田块 + 同名 + 还没做完"兜一层，避免待办里堆出孪生任务。
        FarmTask duplicate = findOpenByTitle(task.fieldId(), task.title());
        if (duplicate != null) {
            log.info("[task] 同名未完成任务已存在，复用 {}：{}", duplicate.id(), duplicate.title());
            return duplicate;
        }
        String now = now();
        TaskStatus status = TaskStatus.of(task.status());
        jdbc.update("INSERT INTO farm_tasks (id,title,task_date,field_id,field_name,condition_text,method,review,note,"
                        + "status,time_window,materials,risk,evidence,plan_item_id,source_message_id,"
                        + "created_at,updated_at,confirmed_at,executed_at,completed_at,user_id) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                task.id(), task.title(), task.date(), task.fieldId(), task.fieldName(), task.condition(),
                task.method(), task.review(), task.note(), status.code(), task.timeWindow(), task.materials(),
                task.risk(), writeEvidence(task.evidence()), task.planItemId(), task.sourceMessageId(),
                task.createdAt(), now, status == TaskStatus.PENDING ? now : null, null, null, currentUser.id());
        return get(task.id());
    }

    /** 同一田块里还没做完的同名任务（忽略空格差异）；用于跨轮去重。 */
    public FarmTask findOpenByTitle(String fieldId, String title) {
        if (title == null || title.isBlank()) return null;
        String wanted = title.replaceAll("[\\s　]+", "");
        if (wanted.isEmpty()) return null;
        for (FarmTask task : list()) {
            TaskStatus status = TaskStatus.of(task.status());
            if (status == TaskStatus.COMPLETED || status == TaskStatus.CANCELLED) continue;
            boolean sameField = fieldId == null || fieldId.isBlank()
                    ? task.fieldId() == null || task.fieldId().isBlank()
                    : fieldId.equals(task.fieldId());
            if (!sameField) continue;
            if (task.title().replaceAll("[\\s　]+", "").equals(wanted)) return task;
        }
        return null;
    }

    public FarmTask findByPlanItem(String sourceMessageId, String planItemId) {        if (sourceMessageId == null || sourceMessageId.isBlank() || planItemId == null || planItemId.isBlank()) return null;
        List<FarmTask> rows = jdbc.query("SELECT * FROM farm_tasks WHERE source_message_id=? AND plan_item_id=? AND user_id=?",
                taskMapper, sourceMessageId, planItemId, currentUser.id());
        return rows.isEmpty() ? null : get(rows.getFirst().id());
    }

    /** 字段编辑；状态变化交给状态机校验（需要记录的推进会被拒绝，请走 addRecord）。 */
    public FarmTask update(String id, FarmTask task) {
        FarmTask current = get(id);
        if (current == null) return null;
        TaskStatus target = TaskStatus.of(task.status());
        if (!target.code().equals(current.status())) return changeStatus(id, target);
        jdbc.update("UPDATE farm_tasks SET title=?,task_date=?,field_id=?,field_name=?,condition_text=?,method=?,"
                        + "review=?,note=?,time_window=?,materials=?,risk=?,evidence=?,plan_item_id=?,source_message_id=?,updated_at=?"
                        + " WHERE id=? AND user_id=?",
                task.title(), task.date(), task.fieldId(), task.fieldName(), task.condition(), task.method(),
                task.review(), task.note(), task.timeWindow(), task.materials(), task.risk(),
                writeEvidence(task.evidence()), task.planItemId(), task.sourceMessageId(), now(), id, currentUser.id());
        return get(id);
    }

    /** 直接切换状态（确认安排、取消、重新打开、退回重做）。 */
    public FarmTask changeStatus(String id, TaskStatus target) {
        FarmTask current = get(id);
        if (current == null) return null;
        TaskStatus from = TaskStatus.of(current.status());
        if (from == target) return current;
        if (TaskStatus.requiresRecord(target)) {
            throw new IllegalStateException("「" + target.label() + "」必须由提交"
                    + (target == TaskStatus.AWAITING_REVIEW ? "执行" : "复查") + "记录进入，不能直接改状态");
        }
        if (!from.allows(target)) {
            throw new IllegalStateException("不能从「" + from.label() + "」直接改为「" + target.label() + "」");
        }
        String now = now();
        String confirmedAt = target == TaskStatus.PENDING && from == TaskStatus.PENDING_CONFIRMATION ? now : null;
        jdbc.update("UPDATE farm_tasks SET status=?, updated_at=?, confirmed_at=COALESCE(?, confirmed_at)"
                        + " WHERE id=? AND user_id=?",
                target.code(), now, confirmedAt, id, currentUser.id());
        log.info("[task] {} 状态 {} → {}", id, from.code(), target.code());
        return get(id);
    }

    /**
     * 提交执行/复查记录，并推进状态：
     * 执行记录 → 已执行待复查；复查记录 → 已完成。记录本身永远保留。
     */
    public FarmTask addRecord(String taskId, TaskRecord record) {
        FarmTask task = get(taskId);
        if (task == null) return null;
        TaskStatus from = TaskStatus.of(task.status());
        boolean execution = TaskRecord.KIND_EXECUTION.equals(record.kind());
        TaskStatus target = execution ? TaskStatus.AWAITING_REVIEW : TaskStatus.COMPLETED;

        if (from == TaskStatus.CANCELLED) {
            throw new IllegalStateException("任务已取消，如需继续请先恢复为待执行");
        }
        if (!execution && (from == TaskStatus.PENDING_CONFIRMATION || from == TaskStatus.PENDING)) {
            throw new IllegalStateException("还没有执行记录：复查记录需要在执行之后再提交");
        }
        if (from == TaskStatus.COMPLETED && execution) {
            throw new IllegalStateException("任务已完成，如需再执行一次请先把状态改回待执行");
        }
        jdbc.update("INSERT INTO task_records (id,task_id,field_id,kind,record_date,note,outcome,source_message_id,created_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?)",
                record.id(), taskId, record.fieldId(), record.kind(), record.date(), record.note(),
                record.outcome(), record.sourceMessageId(), record.createdAt());

        String now = now();
        if (from != target) {
            jdbc.update("UPDATE farm_tasks SET status=?, updated_at=?, confirmed_at=COALESCE(confirmed_at,?),"
                            + " executed_at=CASE WHEN ?='awaiting_review' THEN ? ELSE executed_at END,"
                            + " completed_at=CASE WHEN ?='completed' THEN ? ELSE completed_at END"
                            + " WHERE id=? AND user_id=?",
                    target.code(), now, now, target.code(), now, target.code(), now, taskId, currentUser.id());
            log.info("[task] {} 状态 {} → {}（{}：{}）", taskId, from.code(), target.code(),
                    TaskRecord.kindLabel(record.kind()), record.date());
        } else {
            jdbc.update("UPDATE farm_tasks SET updated_at=? WHERE id=? AND user_id=?", now, taskId, currentUser.id());
        }
        return get(taskId);
    }

    /**
     * 自动合并重复任务：同一田块 + 同名 + 同日期 + 都还没做完 + 没有任何执行/复查记录 → 只保留最早创建的一条。
     * 保守边界（宁可留着让用户自己决定）：
     * <ul>
     *   <li>已完成、已取消的任务不参与合并；</li>
     *   <li>提交过执行或复查记录的任务不自动删除——那是用户写过的事实，删了就丢了；</li>
     *   <li>日期不同的同名任务视为两次安排，不合并。</li>
     * </ul>
     * 每次启动执行一次，用于清理历史遗留的重复（例如模型连着给多张方案卡、用户逐张点加入）。
     */
    public int mergeDuplicates() {
        Map<String, FarmTask> keep = new LinkedHashMap<>();
        List<FarmTask> remove = new ArrayList<>();
        for (FarmTask task : list()) {   // list() 按 task_date、created_at、id 排序，最早的在前面
            TaskStatus status = TaskStatus.of(task.status());
            if (status == TaskStatus.COMPLETED || status == TaskStatus.CANCELLED) continue;
            if (!task.records().isEmpty()) continue;
            String key = (task.fieldId() == null ? "-" : task.fieldId()) + "|"
                    + task.title().replaceAll("[\\s　]+", "") + "|" + task.date();
            FarmTask first = keep.get(key);
            if (first == null) { keep.put(key, task); continue; }
            remove.add(task);
            log.info("[task] 自动合并重复任务：删除 {}「{}」（保留 {}）", task.id(), task.title(), first.id());
        }
        for (FarmTask task : remove) delete(task.id());
        return remove.size();
    }

    public boolean delete(String id) {
        // 别人的任务一律不动
        if (get(id) == null) return false;
        jdbc.update("DELETE FROM task_records WHERE task_id=?", id);
        return jdbc.update("DELETE FROM farm_tasks WHERE id=? AND user_id=?", id, currentUser.id()) > 0;
    }

    // ---- 辅助 ----

    private List<String> readEvidence(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            List<String> ids = json.readValue(raw, new TypeReference<List<String>>() {});
            return ids == null ? List.of() : ids;
        } catch (Exception e) {
            log.warn("[task] 依据字段解析失败，按空处理：{}", e.getMessage());
            return List.of();
        }
    }

    private String writeEvidence(List<String> evidence) {
        try {
            return json.writeValueAsString(evidence == null ? List.of() : evidence);
        } catch (Exception e) {
            return "[]";
        }
    }

    private static String now() { return LocalDateTime.now().withNano(0).toString(); }

    /** 供控制器构造新任务时复用。 */
    public static String newId() { return "t-" + UUID.randomUUID(); }
}
