package com.nongxin.controller;

import com.nongxin.model.Conversation;
import com.nongxin.model.SavedChatMessage;
import com.nongxin.service.ConversationService;
import com.nongxin.service.FieldService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {
    private static final Set<String> PRIVATE_KEYS = Set.of("apikey", "authorization", "settings", "password", "secret", "accesstoken");
    private final ConversationService conversations;
    private final FieldService fields;

    public ConversationController(ConversationService conversations, FieldService fields) {
        this.conversations = conversations;
        this.fields = fields;
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
        }
        return conversations.save(new Conversation(id, title, fieldId, messages, RequestValidation.createdAt(conversation.createdAt())));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        return conversations.delete(id) ? ResponseEntity.ok(Map.of("deleted", true))
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
