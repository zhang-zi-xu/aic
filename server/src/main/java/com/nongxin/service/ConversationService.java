package com.nongxin.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nongxin.model.Conversation;
import com.nongxin.model.SavedChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

@Service
public class ConversationService {
    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CurrentUser currentUser;
    private final TransactionTemplate saveTransaction;

    public ConversationService(JdbcTemplate jdbc, ObjectMapper json, CurrentUser currentUser) {
        this.jdbc = jdbc;
        this.json = json;
        this.currentUser = currentUser;
        var manager = new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource()));
        manager.setRollbackOnCommitFailure(true);
        saveTransaction = new TransactionTemplate(manager);
    }

    /** Commit may be uncertain: keep the user's draft and check saved history before retrying. */
    public static final class SaveUnavailable extends RuntimeException {
        public SaveUnavailable() {
            super("对话保存暂时无法确认，请保留当前内容，刷新记录核对后再重试");
        }
    }

    /** The HTTP save path confirms live image references and writes the conversation on one bound connection. */
    public Conversation inSaveTransaction(Supplier<Conversation> operation) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.hasResource(jdbc.getDataSource())) throw new SaveUnavailable();
        try {
            return saveTransaction.execute(status -> {
                Conversation saved = operation.get();
                if (saved == null) throw new SaveUnavailable();
                return saved;
            });
        } catch (DataAccessException | TransactionException failure) {
            log.warn("[conversation] 保存结果未确认（{}），需核对已保存记录", failure.getClass().getSimpleName());
            throw new SaveUnavailable();
        }
    }

    /** 只列当前用户自己的对话（归属过滤在 SQL 里做，不靠前端）。 */
    public List<Conversation> list() {
        return jdbc.query("SELECT * FROM conversations WHERE user_id=? ORDER BY created_at DESC, id ASC",
                (rs, row) -> read(rs), currentUser.id());
    }

    /** 取对话：不是自己的返回 null（调用方按 404 处理，不泄露"存在但不属于你"）。 */
    public Conversation get(String id) {
        List<Conversation> rows = jdbc.query("SELECT * FROM conversations WHERE id=? AND user_id=?",
                (rs, row) -> read(rs), id, currentUser.id());
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
        // 覆盖已有对话时必须仍是自己的：WHERE 带上 user_id，别人的 id 改不动
        jdbc.update("INSERT INTO conversations (id,title,field_id,messages_json,created_at,user_id) VALUES (?,?,?,?,?,?) "
                        + "ON CONFLICT(id) DO UPDATE SET title=excluded.title,field_id=excluded.field_id,"
                        + "messages_json=excluded.messages_json WHERE conversations.user_id=excluded.user_id",
                conversation.id(), conversation.title(), conversation.fieldId(), messages, conversation.createdAt(),
                currentUser.id());
        return get(conversation.id());
    }

    public boolean delete(String id) {
        return jdbc.update("DELETE FROM conversations WHERE id=? AND user_id=?", id, currentUser.id()) > 0;
    }

    public boolean rename(String id, String title) {
        return jdbc.update("UPDATE conversations SET title=? WHERE id=? AND user_id=?", title, id, currentUser.id()) > 0;
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
