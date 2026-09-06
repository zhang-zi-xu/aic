package com.nongxin.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nongxin.model.Conversation;
import com.nongxin.model.SavedChatMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Service
public class ConversationService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ConversationService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<Conversation> list() {
        return jdbc.query("SELECT * FROM conversations ORDER BY created_at DESC, id ASC", (rs, row) -> read(rs));
    }

    public Conversation get(String id) {
        List<Conversation> rows = jdbc.query("SELECT * FROM conversations WHERE id=?", (rs, row) -> read(rs), id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Conversation save(Conversation conversation) {
        String messages;
        try {
            messages = json.writeValueAsString(conversation.messages());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("对话消息格式不正确");
        }
        if (messages.length() > 2_000_000) throw new IllegalArgumentException("对话内容过长，请新建对话");
        jdbc.update("INSERT INTO conversations (id,title,field_id,messages_json,created_at) VALUES (?,?,?,?,?) "
                        + "ON CONFLICT(id) DO UPDATE SET title=excluded.title,field_id=excluded.field_id,messages_json=excluded.messages_json",
                conversation.id(), conversation.title(), conversation.fieldId(), messages, conversation.createdAt());
        return get(conversation.id());
    }

    public boolean delete(String id) {
        return jdbc.update("DELETE FROM conversations WHERE id=?", id) > 0;
    }

    private Conversation read(ResultSet rs) throws SQLException {
        try {
            List<SavedChatMessage> messages = json.readValue(rs.getString("messages_json"), new TypeReference<>() {});
            return new Conversation(rs.getString("id"), rs.getString("title"), rs.getString("field_id"),
                    messages, rs.getString("created_at"));
        } catch (JsonProcessingException e) {
            throw new SQLException("保存的对话内容无法读取", e);
        }
    }
}
