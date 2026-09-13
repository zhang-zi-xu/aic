package com.nongxin.controller;

import com.nongxin.model.Conversation;
import com.nongxin.model.SavedChatMessage;
import com.nongxin.service.ConversationService;
import com.nongxin.service.FieldService;
import com.nongxin.service.KnowledgeLibrary;
import com.nongxin.service.UploadService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {
    private static final Set<String> PRIVATE_KEYS = Set.of("apikey", "authorization", "settings", "password", "secret", "accesstoken");
    private final ConversationService conversations;
    private final FieldService fields;
    private final KnowledgeLibrary library;
    private final UploadService uploads;

    public ConversationController(ConversationService conversations, FieldService fields, KnowledgeLibrary library,
                                  UploadService uploads) {
        this.conversations = conversations;
        this.fields = fields;
        this.library = library;
        this.uploads = uploads;
    }

    @GetMapping
    public List<Conversation> list() { return conversations.list(); }

    @PutMapping("/{id}")
    public Conversation save(@PathVariable String id, @RequestBody Conversation conversation) {
        RequestValidation.matchingId(id, conversation.id());
        String title = RequestValidation.requiredText(conversation.title(), "对话标题", 200);
        String fieldId = conversation.fieldId() == null || conversation.fieldId().isBlank() ? null : conversation.fieldId();
        if (fieldId != null && fields.get(fieldId) == null) throw new IllegalArgumentException("关联田块不存在");
        List<SavedChatMessage> messages = conversation.messages();
        if (messages == null || messages.size() > 500) throw new IllegalArgumentException("对话消息需为列表，且不能超过 500 条");
        List<SavedChatMessage> sanitized = new ArrayList<>();
        List<String> referencedImages = new ArrayList<>();
        for (SavedChatMessage message : messages) {
            if (message == null) throw new IllegalArgumentException("对话消息不能为 null");
            RequestValidation.id(message.id());
            if (!"user".equals(message.role()) && !"assistant".equals(message.role())) {
                throw new IllegalArgumentException("仅能保存用户与助手的对话消息");
            }
            RequestValidation.optionalText(message.content(), "消息内容", 20000);
            RequestValidation.optionalText(message.attachedData(), "附件内容", 200000);
            rejectPrivateKeys(message.plan());
            rejectPrivateKeys(message.risk());
            rejectPrivateKeys(message.clarify());
            rejectPrivateKeys(message.evidence());
            rejectPrivateKeys(message.requestContext());
            RequestValidation.optionalText(message.error(), "回答状态说明", 1000);
            if (message.status() != null && !"interrupted".equals(message.status()))
                throw new IllegalArgumentException("回答状态无效");
            // 来源卡只接受 ID，内容由服务端从资料库重建：客户端无法伪造「已核验」状态或外链。
            List<Map<String, Object>> images = sanitizeImages(message.images(), referencedImages);
            sanitized.add(new SavedChatMessage(message.id(), message.role(), message.content(), message.attachedData(),
                    message.plan(), message.risk(), message.clarify(), message.evidence(), message.status(),
                    message.error(), message.requestContext(), message.degraded(), rebuildSources(message.sources()), images));
        }
        Conversation saved = conversations.save(new Conversation(id, title, fieldId, sanitized, RequestValidation.createdAt(conversation.createdAt())));
        // 被会话引用的图片不再参与过期清理（生命周期管理）
        uploads.markReferenced(referencedImages);
        return saved;
    }

    /**
     * 只接受"确实存在的附件 id"，元数据一律以服务端记录为准：
     * 客户端既不能伪造图片，也不能把 base64 塞进对话记录。
     */
    private List<Map<String, Object>> sanitizeImages(List<Map<String, Object>> images, List<String> referenced) {
        if (images == null || images.isEmpty()) return null;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> image : images) {
            Object rawId = image == null ? null : image.get("id");
            if (!(rawId instanceof String id) || id.isBlank()) continue;
            RequestValidation.id(id);
            UploadService.Stored stored = uploads.get(id);
            if (stored == null) continue;
            if (!referenced.contains(id)) referenced.add(id);
            out.add(Map.of("id", stored.id(), "url", "/api/uploads/" + stored.id(), "mime", stored.mime(),
                    "width", stored.width(), "height", stored.height(), "bytes", stored.bytes()));
        }
        return out.isEmpty() ? null : out;
    }

    private List<Map<String, Object>> rebuildSources(List<Map<String, Object>> sources) {
        if (sources == null || sources.isEmpty()) return null;
        List<String> ids = sources.stream()
                .map(source -> source == null ? null : source.get("id"))
                .filter(String.class::isInstance).map(String.class::cast).toList();
        List<Map<String, Object>> cards = library.cards(ids);
        return cards.isEmpty() ? null : cards;
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        return conversations.delete(id) ? ResponseEntity.ok(Map.of("deleted", true))
                : ResponseEntity.status(404).body(Map.of("error", "对话不存在"));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> rename(@PathVariable String id, @RequestBody Map<String, String> body) {
        RequestValidation.id(id);
        String title = RequestValidation.requiredText(body.get("title"), "对话标题", 200);
        return conversations.rename(id, title) ? ResponseEntity.ok(conversations.get(id))
                : ResponseEntity.status(404).body(Map.of("error", "对话不存在"));
    }

    private void rejectPrivateKeys(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey()).replace("_", "").replace("-", "").toLowerCase(java.util.Locale.ROOT);
                if (PRIVATE_KEYS.contains(key)) throw new IllegalArgumentException("对话不能包含模型密钥或设置");
                rejectPrivateKeys(entry.getValue());
            }
        } else if (value instanceof List<?> list) {
            list.forEach(this::rejectPrivateKeys);
        }
    }
}
