package com.nongxin.controller;

import com.nongxin.model.FarmTask;
import com.nongxin.model.TaskRecord;
import com.nongxin.model.TaskStatus;
import com.nongxin.service.FieldService;
import com.nongxin.service.KnowledgeLibrary;
import com.nongxin.service.TaskService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 农事任务 API：登记（幂等）、字段编辑、状态流转、执行/复查记录。
 *
 * <p>任务状态不由前端随意改写：进入「已执行待复查」必须带执行记录，
 * 进入「已完成」必须带复查记录，由 {@link TaskService} 统一校验。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController implements org.springframework.boot.ApplicationRunner {
    private final TaskService tasks;
    private final FieldService fields;
    private final KnowledgeLibrary knowledge;

    public TaskController(TaskService tasks, FieldService fields, KnowledgeLibrary knowledge) {
        this.tasks = tasks;
        this.fields = fields;
        this.knowledge = knowledge;
    }

    /** 启动时自动合并历史遗留的重复任务（保守规则见 TaskService.mergeDuplicates）。 */
    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        int merged = tasks.mergeDuplicates();
        if (merged > 0) org.slf4j.LoggerFactory.getLogger(TaskController.class)
                .info("[task] 启动自动合并重复任务：清理 {} 条", merged);
    }

    @GetMapping
    public List<FarmTask> list() {
        return tasks.list().stream().map(this::withEvidenceCards).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        FarmTask task = tasks.get(id);
        return task == null ? notFound() : ResponseEntity.ok(withEvidenceCards(task));
    }

    @GetMapping("/{id}/records")
    public ResponseEntity<?> records(@PathVariable String id) {
        return tasks.get(id) == null ? notFound() : ResponseEntity.ok(tasks.records(id));
    }

    /** 登记任务：201 = 新建，200 = 该方案项已登记过（幂等，不产生重复任务）。 */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody FarmTask task) {
        String id = task.id() == null || task.id().isBlank() ? TaskService.newId() : RequestValidation.id(task.id());
        FarmTask candidate = validate(id, task, true);
        FarmTask existing = tasks.findByPlanItem(candidate.sourceMessageId(), candidate.planItemId());
        FarmTask saved = tasks.create(candidate);
        return ResponseEntity.status(existing == null ? HttpStatus.CREATED : HttpStatus.OK).body(withEvidenceCards(saved));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody FarmTask task) {
        RequestValidation.matchingId(id, task.id());
        FarmTask updated = tasks.update(id, validate(id, task, false));
        return updated == null ? notFound() : ResponseEntity.ok(withEvidenceCards(updated));
    }

    /** 状态操作：确认安排、取消、恢复、重新打开。需要记录的推进会被拒绝。 */
    @PostMapping("/{id}/status")
    public ResponseEntity<?> changeStatus(@PathVariable String id, @RequestBody Map<String, String> body) {
        TaskStatus target = TaskStatus.of(body == null ? null : body.get("status"));
        FarmTask updated = tasks.changeStatus(id, target);
        return updated == null ? notFound() : ResponseEntity.ok(withEvidenceCards(updated));
    }

    /** 提交执行/复查记录，并推进状态；记录与任务、田块、来源消息一起保存。 */
    @PostMapping("/{id}/records")
    public ResponseEntity<?> addRecord(@PathVariable String id, @RequestBody TaskRecord record) {
        FarmTask task = tasks.get(id);
        if (task == null) return notFound();
        TaskRecord saved = validateRecord(task, record);
        return ResponseEntity.ok(withEvidenceCards(tasks.addRecord(id, saved)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        return tasks.delete(id) ? ResponseEntity.ok(Map.of("deleted", true)) : notFound();
    }

    private ResponseEntity<?> notFound() {
        return ResponseEntity.status(404).body(Map.of("error", "任务不存在"));
    }

    // ---- 校验 ----

    private FarmTask validate(String id, FarmTask task, boolean creating) {
        RequestValidation.id(id);
        String fieldId = task.fieldId() == null || task.fieldId().isBlank() ? null : task.fieldId();
        String fieldName = RequestValidation.optionalText(task.fieldName(), "田块名称", 120);
        if (fieldId != null) {
            var field = fields.get(fieldId);
            if (field == null) throw new IllegalArgumentException("关联田块不存在");
            fieldName = field.name();
        }
        TaskStatus status = TaskStatus.of(task.status());
        if (creating && status != TaskStatus.PENDING && status != TaskStatus.PENDING_CONFIRMATION) {
            throw new IllegalArgumentException("新任务只能从「待确认」或「待执行」开始");
        }
        return new FarmTask(id,
                RequestValidation.requiredText(task.title(), "任务名称", 200),
                task.date() == null || task.date().isBlank() ? "" : RequestValidation.date(task.date(), "任务日期"),
                fieldId, fieldName,
                RequestValidation.optionalText(task.condition(), "执行条件", 8000),
                RequestValidation.optionalText(task.method(), "执行方法", 8000),
                RequestValidation.optionalText(task.review(), "复查要求", 8000),
                RequestValidation.optionalText(task.note(), "任务备注", 8000),
                status.code(), null,
                RequestValidation.optionalText(task.timeWindow(), "时间窗口", 200),
                RequestValidation.optionalText(task.materials(), "所需物料", 2000),
                RequestValidation.optionalText(task.risk(), "风险与禁忌", 4000),
                knownEvidence(task.evidence()), List.of(),
                task.planItemId() == null || task.planItemId().isBlank()
                        ? null : RequestValidation.optionalText(task.planItemId(), "方案项", 80),
                task.sourceMessageId() == null || task.sourceMessageId().isBlank()
                        ? null : RequestValidation.optionalText(task.sourceMessageId(), "来源消息", 200),
                RequestValidation.createdAt(task.createdAt()), null, null, null, null, List.of());
    }

    private TaskRecord validateRecord(FarmTask task, TaskRecord record) {
        String kind = record.kind() == null ? "" : record.kind().trim();
        if (!TaskRecord.KIND_EXECUTION.equals(kind) && !TaskRecord.KIND_REVIEW.equals(kind)) {
            throw new IllegalArgumentException("记录类型只能是执行记录（execution）或复查记录（review）");
        }
        String date = record.date() == null || record.date().isBlank()
                ? LocalDate.now().toString() : RequestValidation.date(record.date(), "记录日期");
        String outcome = record.outcome() == null ? "" : record.outcome().trim();
        if (!outcome.isEmpty() && TaskRecord.outcomeLabel(outcome).isEmpty()) {
            throw new IllegalArgumentException("复查结论取值不合法：" + outcome);
        }
        return new TaskRecord(
                record.id() == null || record.id().isBlank() ? "r-" + java.util.UUID.randomUUID() : RequestValidation.id(record.id()),
                task.id(),
                record.fieldId() == null || record.fieldId().isBlank() ? task.fieldId() : record.fieldId(),
                kind,
                date,
                RequestValidation.requiredText(record.note(), "记录内容", 8000),
                TaskRecord.KIND_REVIEW.equals(kind) ? outcome : "",
                record.sourceMessageId() == null || record.sourceMessageId().isBlank()
                        ? task.sourceMessageId() : RequestValidation.optionalText(record.sourceMessageId(), "来源消息", 200),
                RequestValidation.createdAt(record.createdAt()));
    }

    /** 依据只保留资料库里真实存在的片段 ID，客户端无法伪造。 */
    private List<String> knownEvidence(List<String> evidence) {
        if (evidence == null || evidence.isEmpty()) return List.of();
        List<String> ids = new ArrayList<>();
        for (KnowledgeLibrary.SourcedHit hit : knowledge.resolve(evidence)) ids.add(hit.chunk().id());
        return ids;
    }

    private FarmTask withEvidenceCards(FarmTask task) {
        if (task.evidence().isEmpty()) return task;
        return task.withEvidenceCards(knowledge.cards(task.evidence()));
    }
}
