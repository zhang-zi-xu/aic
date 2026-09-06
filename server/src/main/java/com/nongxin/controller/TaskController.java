package com.nongxin.controller;

import com.nongxin.model.FarmTask;
import com.nongxin.service.FieldService;
import com.nongxin.service.TaskService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private final TaskService tasks;
    private final FieldService fields;

    public TaskController(TaskService tasks, FieldService fields) {
        this.tasks = tasks;
        this.fields = fields;
    }

    @GetMapping
    public List<FarmTask> list() { return tasks.list(); }

    @PostMapping
    public FarmTask create(@RequestBody FarmTask task) {
        String id = task.id() == null || task.id().isBlank() ? "t-" + UUID.randomUUID() : task.id();
        return tasks.create(validate(id, task));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody FarmTask task) {
        RequestValidation.matchingId(id, task.id());
        FarmTask updated = tasks.update(id, validate(id, task));
        return updated == null ? ResponseEntity.status(404).body(Map.of("error", "任务不存在")) : ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        return tasks.delete(id) ? ResponseEntity.ok(Map.of("deleted", true))
                : ResponseEntity.status(404).body(Map.of("error", "任务不存在"));
    }

    private FarmTask validate(String id, FarmTask task) {
        RequestValidation.id(id);
        String fieldId = task.fieldId() == null || task.fieldId().isBlank() ? null : task.fieldId();
        if (fieldId != null && fields.get(fieldId) == null) throw new IllegalArgumentException("关联田块不存在");
        return new FarmTask(id, RequestValidation.requiredText(task.title(), "任务名称", 200),
                task.date() == null || task.date().isBlank() ? "" : RequestValidation.date(task.date(), "任务日期"), fieldId,
                RequestValidation.optionalText(task.fieldName(), "田块名称", 120),
                RequestValidation.optionalText(task.condition(), "执行条件", 8000),
                RequestValidation.optionalText(task.method(), "执行方法", 8000),
                RequestValidation.optionalText(task.review(), "复查要求", 8000),
                RequestValidation.optionalText(task.note(), "任务备注", 8000), task.done(),
                RequestValidation.createdAt(task.createdAt()),
                task.sourceMessageId() == null || task.sourceMessageId().isBlank() ? null : RequestValidation.id(task.sourceMessageId()));
    }
}
