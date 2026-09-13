package com.nongxin.controller;

import com.nongxin.service.UploadService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
/**
 * 图片附件接口。
 *
 * <p>上传后只返回 id 与元数据；聊天请求里传 id，原图由服务端读取后转发给供应商，
 * 会话记录中永远不出现 base64——避免大图污染文本字段、也避免数据库膨胀。
 * 目前没有账号体系（P4 才做隔离），接口仅监听本机回环地址。
 */
@RestController
@RequestMapping("/api/uploads")
public class UploadController implements ApplicationRunner {

    private final UploadService uploads;
    private final com.nongxin.service.FieldService fields;

    public UploadController(UploadService uploads, com.nongxin.service.FieldService fields) {
        this.uploads = uploads;
        this.fields = fields;
    }

    /** 启动时清理过期且未被引用的图片（生命周期管理，不做定时推送那类假机制）。 */
    @Override
    public void run(ApplicationArguments args) { uploads.cleanupUnreferenced(); }

    @PostMapping
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file,
                                    @RequestParam(value = "fieldId", required = false) String fieldId,
                                    @RequestParam(value = "observedAt", required = false) String observedAt,
                                    @RequestParam(value = "note", required = false) String note,
                                    @RequestParam(value = "taskId", required = false) String taskId) throws Exception {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "没有收到图片文件"));
        }
        String field = fieldId == null || fieldId.isBlank() ? null : fieldId;
        if (field != null && fields.get(field) == null) throw new IllegalArgumentException("关联田块不存在");
        String observed = observedAt == null || observedAt.isBlank() ? null : RequestValidation.date(observedAt, "拍摄日期");
        UploadService.Stored stored = uploads.store(file.getBytes(), file.getContentType(), field, observed,
                RequestValidation.optionalText(note, "照片备注", 500), taskId);
        return ResponseEntity.ok(card(stored));
    }

    /** 田块影像档案：按时间倒序，附带占用统计。 */
    @GetMapping("/field/{fieldId}")
    public Map<String, Object> byField(@PathVariable String fieldId) {
        RequestValidation.id(fieldId);
        if (fields.get(fieldId) == null) throw new IllegalArgumentException("关联田块不存在");
        List<Map<String, Object>> photos = new ArrayList<>();
        for (UploadService.Stored stored : uploads.byField(fieldId, 500)) photos.add(card(stored));
        return Map.of("fieldId", fieldId, "photos", photos, "usage", uploads.usage(fieldId));
    }

    /** 归档信息编辑：备注、拍摄日期、改挂到别的田块（或从档案里撤下）。只更新传了的字段。 */
    @PatchMapping("/{id}")
    public Map<String, Object> edit(@PathVariable String id, @RequestBody Map<String, String> body) {
        RequestValidation.id(id);
        UploadService.Stored existing = uploads.get(id);
        if (existing == null) throw new java.util.NoSuchElementException("图片不存在或已被清理");
        Map<String, String> request = body == null ? Map.of() : body;
        String fieldId = request.containsKey("fieldId") ? request.get("fieldId") : existing.fieldId();
        if (fieldId != null && !fieldId.isBlank() && fields.get(fieldId) == null) {
            throw new IllegalArgumentException("关联田块不存在");
        }
        String observedAt = request.containsKey("observedAt")
                ? (request.get("observedAt") == null || request.get("observedAt").isBlank() ? null : RequestValidation.date(request.get("observedAt"), "拍摄日期"))
                : existing.observedAt();
        String note = request.containsKey("note") ? request.get("note") : existing.note();
        return card(uploads.updateArchive(id, note, observedAt, fieldId));
    }

    /** 原图（重编码后的 JPEG）。id 不可变，因此可以长期缓存。 */
    @GetMapping("/{id}")
    public ResponseEntity<byte[]> read(@PathVariable String id) {
        RequestValidation.id(id);
        byte[] data = uploads.read(id);
        if (data == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .header("X-Content-Type-Options", "nosniff")
                .body(data);
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (UploadService.Stored stored : uploads.list()) out.add(card(stored));
        return out;
    }

    /** 删除还没随对话保存的照片；已被引用时明确拒绝，而不是让历史记录缺图。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        RequestValidation.id(id);
        uploads.delete(id);
        return ResponseEntity.ok(Map.of("deleted", true));
    }

    private Map<String, Object> card(UploadService.Stored stored) {
        Map<String, Object> card = new java.util.LinkedHashMap<>();
        card.put("id", stored.id());
        card.put("url", "/api/uploads/" + stored.id());
        card.put("mime", stored.mime());
        card.put("width", stored.width());
        card.put("height", stored.height());
        card.put("bytes", stored.bytes());
        card.put("createdAt", stored.createdAt());
        card.put("referenced", stored.referencedAt() != null);
        card.put("fieldId", stored.fieldId() == null ? "" : stored.fieldId());
        card.put("observedAt", stored.observedAt() == null ? "" : stored.observedAt());
        card.put("note", stored.note() == null ? "" : stored.note());
        card.put("taskId", stored.taskId() == null ? "" : stored.taskId());
        return card;
    }
}
