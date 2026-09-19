package com.nongxin.controller;

import com.nongxin.service.UploadService;
import com.nongxin.service.CurrentUser;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
/**
 * 图片附件接口。
 *
 * <p>上传后只返回 id 与元数据；聊天请求里传 id，原图由服务端读取后转发给供应商，
 * 会话记录中永远不出现 base64——避免大图污染文本字段、也避免数据库膨胀。
 * 查询按 CurrentUser 的数据归属过滤，但默认本机共享身份不是登录认证。
 */
@RestController
@RequestMapping("/api/uploads")
public class UploadController implements ApplicationRunner {

    private final UploadService uploads;
    private final com.nongxin.service.FieldService fields;
    private final CurrentUser currentUser;

    public UploadController(UploadService uploads, com.nongxin.service.FieldService fields, CurrentUser currentUser) {
        this.uploads = uploads;
        this.fields = fields;
        this.currentUser = currentUser;
    }

    /** 启动时仅清理已核实未归档、未引用的过期附件；核验失败时保留。 */
    @Override
    public void run(ApplicationArguments args) { uploads.cleanupUnreferenced(); }

    @PostMapping
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file,
                                    @RequestParam(value = "fieldId", required = false) String fieldId,
                                    @RequestParam(value = "observedAt", required = false) String observedAt,
                                    @RequestParam(value = "note", required = false) String note,
                                    @RequestParam(value = "taskId", required = false) String taskId) throws Exception {
        return currentUser.withSnapshot(currentUser.capture(), () -> uploadOwned(file, fieldId, observedAt, note, taskId));
    }

    private ResponseEntity<?> uploadOwned(MultipartFile file, String fieldId, String observedAt, String note, String taskId) throws Exception {
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
        return currentUser.withSnapshot(currentUser.capture(), () -> {
            RequestValidation.id(fieldId);
            if (fields.get(fieldId) == null) throw new IllegalArgumentException("关联田块不存在");
            List<Map<String, Object>> photos = new ArrayList<>();
            for (UploadService.Stored stored : uploads.byField(fieldId, 500)) photos.add(card(stored));
            return Map.of("fieldId", fieldId, "photos", photos, "usage", uploads.usage(fieldId));
        });
    }

    /** 归档信息编辑：备注、拍摄日期、改挂到别的田块（或从档案里撤下）。只更新传了的字段。 */
    @PatchMapping("/{id}")
    public Map<String, Object> edit(@PathVariable String id, @RequestBody Map<String, String> body) {
        return currentUser.withSnapshot(currentUser.capture(), () -> editOwned(id, body));
    }

    private Map<String, Object> editOwned(String id, Map<String, String> body) {
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

    /** 用户照片不能由公共缓存复用；删除或身份变化后必须重新经过服务端检查。 */
    @GetMapping("/{id}")
    public ResponseEntity<byte[]> read(@PathVariable String id) {
        return currentUser.withSnapshot(currentUser.capture(), () -> {
            RequestValidation.id(id);
            byte[] data = uploads.read(id);
            if (data == null) return ResponseEntity.status(404).cacheControl(CacheControl.noStore()).build();
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .cacheControl(CacheControl.noStore())
                    .header("X-Content-Type-Options", "nosniff")
                    .body(data);
        });
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return currentUser.withSnapshot(currentUser.capture(), () -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (UploadService.Stored stored : uploads.list()) out.add(card(stored));
            return out;
        });
    }

    /** 用户可删除自己的照片（含已引用照片）；其他归属或不存在的 ID 均按未找到处理。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        return currentUser.withSnapshot(currentUser.capture(), () -> {
            RequestValidation.id(id);
            if (!uploads.delete(id)) throw new UploadService.DeleteUnavailable();
            return ResponseEntity.ok(Map.of("deleted", true));
        });
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
