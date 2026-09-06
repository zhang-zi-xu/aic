package com.nongxin.controller;

import com.nongxin.model.FieldProfile;
import com.nongxin.model.FieldRecord;
import com.nongxin.service.FieldService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 田块档案 API（SQLite 持久化）。
 */
@RestController
@RequestMapping("/api/fields")
public class FieldController {

    private final FieldService fieldService;

    public FieldController(FieldService fieldService) {
        this.fieldService = fieldService;
    }

    @GetMapping
    public List<FieldProfile> list() {
        return fieldService.list();
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody FieldProfile field) {
        validate(field);
        if (field.id() != null && !field.id().isBlank()) RequestValidation.id(field.id());
        return ResponseEntity.ok(fieldService.create(field));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        FieldProfile field = fieldService.get(id);
        return field == null ? error(HttpStatus.NOT_FOUND, "田块不存在") : ResponseEntity.ok(field);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id, @RequestBody FieldProfile field) {
        RequestValidation.matchingId(id, field.id());
        validate(field);
        FieldProfile updated = fieldService.update(id, field);
        return updated == null ? error(HttpStatus.NOT_FOUND, "田块不存在") : ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        boolean deleted = fieldService.delete(id);
        if (!deleted) return error(HttpStatus.NOT_FOUND, "田块不存在");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deleted", true);
        return ResponseEntity.ok(out);
    }

    /** 追加田块记录（复查打卡等） */
    @PostMapping("/{id}/records")
    public ResponseEntity<?> addRecord(@PathVariable String id, @RequestBody FieldRecord record) {
        if (fieldService.get(id) == null) return error(HttpStatus.NOT_FOUND, "田块不存在");
        RequestValidation.date(record.date(), "记录日期");
        RequestValidation.requiredText(record.note(), "记录内容", 8000);
        fieldService.addRecord(id, record.date(), record.note());
        return ResponseEntity.ok(fieldService.get(id));
    }

    private void validate(FieldProfile field) {
        RequestValidation.requiredText(field.name(), "田块名称", 120);
        RequestValidation.requiredText(field.crop(), "作物", 80);
        RequestValidation.date(field.sowDate(), "播期");
        if (field.areaMu() != null && (!Double.isFinite(field.areaMu()) || field.areaMu() <= 0)) {
            throw new IllegalArgumentException("面积必须是大于 0 的数字");
        }
        RequestValidation.optionalText(field.variety(), "品种", 120);
        RequestValidation.optionalText(field.notes(), "备注", 8000);
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }
}
