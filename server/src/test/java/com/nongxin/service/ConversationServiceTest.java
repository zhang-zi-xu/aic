package com.nongxin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nongxin.model.Conversation;
import com.nongxin.model.SavedChatMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationServiceTest {
    private static final Path DATABASE = temporaryDatabase();
    private final ObjectMapper json = new ObjectMapper();
    private final JdbcTemplate jdbc = freshConnection();
    private final ConversationService service = new ConversationService(jdbc, json, new CurrentUser());

    private static Path temporaryDatabase() {
        try {
            return Files.createTempDirectory("nongxin-conversation-test-").resolve("test.db");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static JdbcTemplate freshConnection() {
        DriverManagerDataSource source = new DriverManagerDataSource("jdbc:sqlite:" + DATABASE);
        source.setDriverClassName("org.sqlite.JDBC");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.update("""
                CREATE TABLE IF NOT EXISTS conversations (
                  id TEXT PRIMARY KEY,
                  title TEXT NOT NULL,
                  field_id TEXT,
                  messages_json TEXT NOT NULL,
                  created_at TEXT NOT NULL,
                  user_id TEXT NOT NULL DEFAULT 'local-owner'
                )""");
        return jdbc;
    }

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM conversations");
    }

    @AfterAll
    static void removeDatabase() throws IOException {
        Files.deleteIfExists(DATABASE);
    }

    private Conversation conversation(List<SavedChatMessage> messages) {
        return new Conversation("c-1", "田间对话", null, messages, "2026-09-06T00:00:00Z");
    }

    private SavedChatMessage message(String id, String role, String content) {
        return new SavedChatMessage(id, role, content, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void roundTripsInterruptedDegradedAndSourceCards() {
        SavedChatMessage interrupted = new SavedChatMessage("m-1", "assistant", "半截", null,
                null, null, null, null, "interrupted", "连接中断", null, null, null, null);
        SavedChatMessage degraded = new SavedChatMessage("m-2", "assistant", "工具结果保留在下方", null,
                Map.of("items", List.of(Map.of("task", "检查排水沟"))), null, null, null, null, null,
                Map.of("field", Map.of("name", "试验田")), true,
                List.of(Map.of("id", "chunk-pest-rice-blast", "title", "2025年粮食作物重大病虫害防控技术方案",
                        "status", "verified", "url", "https://example.org/a")), null);

        service.save(conversation(List.of(interrupted, degraded)));

        Conversation loaded = service.get("c-1");
        assertThat(loaded.messages()).hasSize(2);
        assertThat(loaded.messages().get(0).status()).isEqualTo("interrupted");
        assertThat(loaded.messages().get(0).error()).isEqualTo("连接中断");
        assertThat(loaded.messages().get(1).degraded()).isTrue();
        assertThat(loaded.messages().get(1).plan()).containsKey("items");
        assertThat(loaded.messages().get(1).requestContext()).containsKey("field");
        assertThat(loaded.messages().get(1).sources()).hasSize(1);
        assertThat(loaded.messages().get(1).sources().getFirst())
                .containsEntry("id", "chunk-pest-rice-blast").containsEntry("status", "verified");
    }

    @Test
    void saveUpsertsMessagesAndTitleButNeverRewritesCreatedAt() {
        service.save(conversation(List.of(message("m-1", "user", "问题"))));
        service.save(new Conversation("c-1", "新标题", null,
                List.of(message("m-2", "user", "新问题")), "2030-01-01T00:00:00Z"));

        Conversation loaded = service.get("c-1");
        assertThat(loaded.title()).isEqualTo("新标题");
        assertThat(loaded.messages()).extracting(SavedChatMessage::id).containsExactly("m-2");
        assertThat(loaded.createdAt()).isEqualTo("2026-09-06T00:00:00Z");
    }

    @Test
    void renameOnlyChangesTheTitle() {
        service.save(conversation(List.of(message("m-1", "user", "问题"))));

        assertThat(service.rename("c-1", "仅改标题")).isTrue();
        assertThat(service.rename("missing", "不存在")).isFalse();

        Conversation loaded = service.get("c-1");
        assertThat(loaded.title()).isEqualTo("仅改标题");
        assertThat(loaded.messages()).hasSize(1);
    }

    @Test
    void deleteRemovesOnlyTheTargetConversation() {
        service.save(conversation(List.of(message("m-1", "user", "问题"))));
        service.save(new Conversation("c-2", "另一段", null,
                List.of(message("m-2", "user", "别删我")), "2026-09-06T00:00:00Z"));

        assertThat(service.delete("c-1")).isTrue();
        assertThat(service.delete("c-1")).isFalse();
        assertThat(service.get("c-1")).isNull();
        assertThat(service.get("c-2").title()).isEqualTo("另一段");
        assertThat(service.list()).hasSize(1);
    }

    @Test
    void listIsOrderedByNewestFirst() {
        service.save(conversation(List.of(message("m-1", "user", "a"))));
        service.save(new Conversation("c-2", "较新", null,
                List.of(message("m-2", "user", "b")), "2026-09-07T00:00:00Z"));

        assertThat(service.list()).extracting(Conversation::id).containsExactly("c-2", "c-1");
    }

    @Test
    void rejectsMessagesLargerThanTwoMegabytes() {
        String huge = "字".repeat(2_000_001);
        assertThatThrownBy(() -> service.save(conversation(List.of(message("m-1", "user", huge)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("过长");
    }
}
