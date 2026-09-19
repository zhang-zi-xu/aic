package com.nongxin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nongxin.model.Conversation;
import com.nongxin.model.SavedChatMessage;
import com.nongxin.service.*;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import javax.sql.DataSource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real controllers/services and disposable SQLite/JPEG fixtures. No running server or model calls. */
class ConversationSaveSafetyTest {
    private static final String IMAGE = "img-active";
    private static final String CONVERSATION = "c-save";
    private static final String PRIVATE_ERROR = "synthetic-private-sql-or-path";
    @TempDir Path directory;
    private DriverManagerDataSource source;
    private JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();
    private CurrentUser user;
    private Path images;
    private Path original;
    private byte[] bytes;

    @BeforeEach
    void setUp() throws Exception {
        source = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("save.db"));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        user = new CurrentUser();
        user.setResolver(() -> "u-a");
        images = Files.createDirectory(directory.resolve("images"));
        original = images.resolve(IMAGE + ".jpg");
        assertThat(original.toAbsolutePath().normalize().startsWith(directory.toAbsolutePath().normalize())).isTrue();
        var buffer = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(240, 240, BufferedImage.TYPE_INT_RGB), "jpeg", buffer);
        bytes = buffer.toByteArray();
        Files.write(original, bytes);
        jdbc.update("INSERT INTO uploads (id,mime,ext,bytes,width,height,created_at,user_id)"
                + " VALUES (?,'image/jpeg','jpg',?,240,240,?,'u-a')", IMAGE, bytes.length, LocalDateTime.now().minusDays(30).toString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cleanupCommittedAfterImageValidationCannotCreateOrOverwriteConversationWithAStaleImage(boolean existing) throws Exception {
        assertThat(jdbc.queryForObject("PRAGMA journal_mode=WAL", String.class)).isEqualTo("wal");
        if (existing) oldConversation();
        var before = conversationRows();
        var validated = new CountDownLatch(1);
        var resumeSave = new CountDownLatch(1);
        var paused = new AtomicBoolean();
        var checking = new UploadService(jdbc, images.toString(), 8_388_608, 7, user) {
            @Override public Stored get(String id) {
                Stored photo = super.get(id);
                if (IMAGE.equals(id) && photo != null && paused.compareAndSet(false, true)) {
                    validated.countDown();
                    await(resumeSave);
                }
                return photo;
            }
        };
        var mvc = mvc(jdbc, checking);
        var maintenance = new CurrentUser();
        maintenance.setResolver(() -> { throw new AssertionError("System cleanup does not use the request owner"); });
        var cleanerSource = new DriverManagerDataSource(source.getUrl());
        var cleaner = new UploadService(new JdbcTemplate(cleanerSource), images.toString(), 8_388_608, 7, maintenance);
        var executor = Executors.newFixedThreadPool(2);
        try {
            String payload = json.writeValueAsString(conversation("new answer", List.of(Map.of("id", IMAGE))));
            var saving = executor.submit(() -> {
                try { return mvc.perform(put("/api/conversations/" + CONVERSATION).contentType(MediaType.APPLICATION_JSON)
                        .content(payload)).andReturn().getResponse(); }
                finally { assertThat(TransactionSynchronizationManager.hasResource(source)).isFalse(); }
            });
            await(validated);
            assertThat(executor.submit(cleaner::cleanupUnreferenced).get(8, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(Files.exists(original)).isFalse();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uploads", Integer.class)).isZero();
            resumeSave.countDown();
            var response = saving.get(8, TimeUnit.SECONDS);
            assertThat(conversationRows()).isEqualTo(before);
            assertThat(response.getStatus()).isEqualTo(503);
            assertThat(json.readTree(response.getContentAsByteArray()).path("code").asText()).isEqualTo("CONVERSATION_SAVE_UNAVAILABLE");
            assertThat(response.getHeader("Cache-Control")).contains("no-store");
            assertThat(response.getContentAsString()).doesNotContain("SQLITE", directory.toString(), "INSERT INTO");
            // A fresh request keeps historical missing-image compatibility and can save the text without the gone image.
            mvc.perform(put("/api/conversations/" + CONVERSATION).contentType(MediaType.APPLICATION_JSON).content(payload))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.messages[0].content").value("new answer"))
                    .andExpect(jsonPath("$.messages[0].images").doesNotExist());
        } finally {
            resumeSave.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(8, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void referenceWriteFailureRollsBackTheNewOrUpdatedConversation(boolean existing) throws Exception {
        if (existing) oldConversation();
        var before = conversationRows();
        var failing = new JdbcTemplate(source) {
            @Override public int update(String sql, Object... args) {
                if (sql.startsWith("UPDATE uploads SET referenced_at")) throw new DataAccessResourceFailureException(PRIVATE_ERROR);
                return super.update(sql, args);
            }
        };
        var response = mvc(failing, uploads(failing)).perform(put("/api/conversations/" + CONVERSATION)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(conversation("new answer", List.of(Map.of("id", IMAGE))))))
                .andReturn().getResponse();
        assertThat(conversationRows()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT referenced_at FROM uploads WHERE id=?", String.class, IMAGE)).isNull();
        assertThat(Files.readAllBytes(original)).containsExactly(bytes);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(json.readTree(response.getContentAsByteArray()).path("code").asText()).isEqualTo("CONVERSATION_SAVE_UNAVAILABLE");
        assertThat(response.getContentAsString()).doesNotContain(PRIVATE_ERROR);
        assertThat(TransactionSynchronizationManager.hasResource(source)).isFalse();
    }

    @Test
    void ordinarySaveKeepsOwnershipSanitizationAndAlreadyReferencedImagesCompatible() throws Exception {
        jdbc.update("INSERT INTO uploads (id,mime,ext,bytes,width,height,created_at,user_id)"
                + " VALUES ('img-other','image/jpeg','jpg',1,240,240,?,'u-b')", LocalDateTime.now().toString());
        String payload = json.writeValueAsString(conversation("normal answer", List.of(
                Map.of("id", IMAGE, "url", "https://invalid.test/forged"), Map.of("id", "img-other"), Map.of("id", "img-missing"))));
        var mvc = mvc(jdbc, uploads(jdbc));
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(put("/api/conversations/" + CONVERSATION).contentType(MediaType.APPLICATION_JSON).content(payload))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.messages[0].images.length()").value(1))
                    .andExpect(jsonPath("$.messages[0].images[0].url").value("/api/uploads/" + IMAGE));
        }
        assertThat(jdbc.queryForObject("SELECT referenced_at FROM uploads WHERE id=?", String.class, IMAGE)).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT referenced_at FROM uploads WHERE id='img-other'", String.class)).isNull();
        assertThat(Files.readAllBytes(original)).containsExactly(bytes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"delete", "wal"})
    void savePinsBeforeConversationInsertAndConcurrentCleanupCannotCommitDeletion(String journal) throws Exception {
        assertThat(jdbc.queryForObject("PRAGMA journal_mode=" + journal, String.class)).isEqualTo(journal);
        var pinned = new CountDownLatch(1);
        var resumeSave = new CountDownLatch(1);
        var template = new JdbcTemplate(source) {
            @Override public int update(String sql, Object... args) {
                int affected = super.update(sql, args);
                if (sql.startsWith("UPDATE uploads SET referenced_at=COALESCE")) {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    pinned.countDown();
                    await(resumeSave);
                }
                return affected;
            }
        };
        var mvc = mvc(template, uploads(template));
        var maintenance = new CurrentUser();
        maintenance.setResolver(() -> { throw new AssertionError("Maintenance is independent of the saving user"); });
        var cleaner = new UploadService(new JdbcTemplate(new DriverManagerDataSource(source.getUrl())),
                images.toString(), 8_388_608, 7, maintenance);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var saving = executor.submit(() -> {
                try { return save(mvc).andReturn().getResponse(); }
                finally { assertThat(TransactionSynchronizationManager.hasResource(source)).isFalse(); }
            });
            await(pinned);
            assertThat(executor.submit(cleaner::cleanupUnreferenced).get(8, TimeUnit.SECONDS)).isZero();
            assertThat(Files.readAllBytes(original)).containsExactly(bytes);
            resumeSave.countDown();
            assertThat(saving.get(8, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
            assertThat(conversationRows()).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT referenced_at FROM uploads WHERE id=?", String.class, IMAGE)).isNotBlank();
            assertThat(cleaner.cleanupUnreferenced()).isZero();
            try (var paths = Files.list(images)) { assertThat(paths.toList()).containsExactly(original); }
        } finally {
            resumeSave.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(8, TimeUnit.SECONDS)).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"insert-before", "insert-after", "pin-after", "pin-zero", "pin-zero-after"})
    void failuresAfterPartialWritesRollBackBothConversationAndReferencesAndReleaseTheConnection(String mode) throws Exception {
        oldConversation();
        var before = conversationRows();
        var failing = new JdbcTemplate(source) {
            @Override public int update(String sql, Object... args) {
                boolean pin = sql.startsWith("UPDATE uploads SET referenced_at") && mode.startsWith("pin");
                boolean insert = sql.startsWith("INSERT INTO conversations") && mode.startsWith("insert");
                if (pin || insert) {
                    if (mode.endsWith("after")) super.update(sql, args);
                    if (mode.contains("zero")) return 0;
                    throw new DataAccessResourceFailureException(PRIVATE_ERROR);
                }
                return super.update(sql, args);
            }
        };
        save(mvc(failing, uploads(failing))).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CONVERSATION_SAVE_UNAVAILABLE"));
        assertThat(conversationRows()).isEqualTo(before);
        assertUnreferencedAndIntact();
        assertThat(TransactionSynchronizationManager.hasResource(source)).isFalse();
        save(mvc(jdbc, uploads(jdbc))).andExpect(status().isOk());
    }

    @Test
    void realSqlRejectionAfterPinningRollsBackTheReferenceMarker() throws Exception {
        jdbc.execute("CREATE TRIGGER reject_save BEFORE INSERT ON conversations BEGIN SELECT RAISE(ABORT,'synthetic failure'); END");
        save(mvc(jdbc, uploads(jdbc))).andExpect(status().isServiceUnavailable());
        assertThat(conversationRows()).isEmpty();
        assertUnreferencedAndIntact();
        jdbc.execute("DROP TRIGGER reject_save");
        save(mvc(jdbc, uploads(jdbc))).andExpect(status().isOk());
    }

    @Test
    void missingSecondImageRollsBackTheFirstPinInsteadOfSavingPartialReferences() throws Exception {
        Path second = images.resolve("img-second.jpg");
        Files.write(second, bytes);
        jdbc.update("INSERT INTO uploads (id,mime,ext,bytes,width,height,created_at,user_id)"
                + " VALUES ('img-second','image/jpeg','jpg',?,240,240,?,'u-a')", bytes.length, LocalDateTime.now().toString());
        var stale = new UploadService(jdbc, images.toString(), 8_388_608, 7, user) {
            @Override public Stored get(String id) {
                Stored photo = super.get(id);
                if (id.equals("img-second")) jdbc.update("DELETE FROM uploads WHERE id=?", id); // Disposable fixture only, before save transaction.
                return photo;
            }
        };
        mvc(jdbc, stale).perform(put("/api/conversations/" + CONVERSATION).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(conversation("new answer", List.of(Map.of("id", IMAGE), Map.of("id", "img-second"))))))
                .andExpect(status().isServiceUnavailable());
        assertThat(conversationRows()).isEmpty();
        assertUnreferencedAndIntact();
        assertThat(Files.readAllBytes(second)).containsExactly(bytes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"active", "bound"})
    void saveDoesNotJoinAnUnfinishedCallerTransactionOrBorrowAnotherConnection(String mode) throws Exception {
        var mvc = mvc(jdbc, uploads(jdbc));
        if (mode.equals("active")) {
            new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status -> {
                try { save(mvc).andExpect(status().isServiceUnavailable()); }
                catch (Exception failure) { throw new AssertionError(failure); }
                assertThat(TransactionSynchronizationManager.hasResource(source)).isTrue();
                status.setRollbackOnly();
            });
        } else {
            try (Connection connection = source.getConnection()) {
                TransactionSynchronizationManager.bindResource(source, new ConnectionHolder(connection));
                try { save(mvc).andExpect(status().isServiceUnavailable()); }
                finally { TransactionSynchronizationManager.unbindResource(source); }
            }
        }
        assertThat(conversationRows()).isEmpty();
        assertUnreferencedAndIntact();
    }

    @ParameterizedTest
    @ValueSource(strings = {"begin", "before-commit", "after-commit"})
    void transactionFaultReturnsAnUnconfirmedResponseWithoutPretendingTheWriteCannotHaveCommitted(String mode) throws Exception {
        var faulty = faultyConnections(mode);
        var template = new JdbcTemplate(faulty);
        var response = save(mvc(template, uploads(template))).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("CONVERSATION_SAVE_UNAVAILABLE")).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain(PRIVATE_ERROR, "SQLException", directory.toString());
        assertThat(TransactionSynchronizationManager.hasResource(faulty)).isFalse();
        if (mode.equals("after-commit")) {
            assertThat(conversationRows()).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT referenced_at FROM uploads WHERE id=?", String.class, IMAGE)).isNotBlank();
        } else {
            assertThat(conversationRows()).isEmpty();
            assertUnreferencedAndIntact();
        }
    }

    @Test
    void savingAndRetryingReferencedImagesUseOnePooledConnectionWithoutDeadlock() throws Exception {
        try (var pool = new HikariDataSource()) {
            pool.setDataSource(source);
            pool.setMaximumPoolSize(1);
            pool.setMinimumIdle(1);
            pool.setConnectionTimeout(500);
            var template = new JdbcTemplate(pool);
            var mvc = mvc(template, uploads(template));
            save(mvc).andExpect(status().isOk());
            save(mvc).andExpect(status().isOk());
            assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
            assertThat(TransactionSynchronizationManager.hasResource(pool)).isFalse();
        }
        assertThat(conversationRows()).hasSize(1);
        assertThat(Files.readAllBytes(original)).containsExactly(bytes);
    }

    @Test
    void referenceConfirmationOutsideASaveTransactionCannotPartiallyPinAnything() throws Exception {
        assertThat(uploads(jdbc).confirmConversationReferences(List.of(IMAGE))).isFalse();
        assertUnreferencedAndIntact();
    }

    @Test
    void anotherOwnersConversationCannotBeOverwrittenOrLeaveTheCallersPhotoPinned() throws Exception {
        jdbc.update("INSERT INTO conversations (id,title,messages_json,user_id) VALUES (?,'other owner','[]','u-b')", CONVERSATION);
        var before = conversationRows();
        save(mvc(jdbc, uploads(jdbc))).andExpect(status().isServiceUnavailable());
        assertThat(conversationRows()).isEqualTo(before);
        assertUnreferencedAndIntact();
    }

    private org.springframework.test.web.servlet.ResultActions save(MockMvc mvc) throws Exception {
        return mvc.perform(put("/api/conversations/" + CONVERSATION).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(conversation("new answer", List.of(Map.of("id", IMAGE))))));
    }

    private void assertUnreferencedAndIntact() throws Exception {
        assertThat(jdbc.queryForObject("SELECT referenced_at FROM uploads WHERE id=?", String.class, IMAGE)).isNull();
        assertThat(Files.readAllBytes(original)).containsExactly(bytes);
    }

    private DataSource faultyConnections(String mode) {
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                Connection delegate = source.getConnection();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("setAutoCommit") && mode.equals("begin") && Boolean.FALSE.equals(args[0])) throw new SQLException(PRIVATE_ERROR);
                    if (method.getName().equals("commit") && mode.equals("before-commit")) throw new SQLException(PRIVATE_ERROR);
                    try {
                        Object result = method.invoke(delegate, args);
                        if (method.getName().equals("commit") && mode.equals("after-commit")) throw new SQLException(PRIVATE_ERROR);
                        return result;
                    } catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
            }
            @Override public Connection getConnection(String username, String password) throws SQLException { return getConnection(); }
        };
    }

    private MockMvc mvc(JdbcTemplate template, UploadService uploads) {
        return MockMvcBuilders.standaloneSetup(new ConversationController(new ConversationService(template, json, user),
                mock(FieldService.class), mock(KnowledgeLibrary.class), uploads, user))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private UploadService uploads(JdbcTemplate template) {
        return new UploadService(template, images.toString(), 8_388_608, 7, user);
    }

    private Conversation conversation(String text, List<Map<String, Object>> attachments) {
        var message = new SavedChatMessage("m-photo", "user", text, null, null, null, null, null,
                null, null, null, null, null, attachments);
        return new Conversation(CONVERSATION, "fixture", null, List.of(message), Instant.now().toString());
    }

    private void oldConversation() { new ConversationService(jdbc, json, user).save(conversation("old answer", null)); }
    private List<Map<String, Object>> conversationRows() { return jdbc.queryForList("SELECT * FROM conversations ORDER BY id"); }

    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(8, TimeUnit.SECONDS)).as("bounded save checkpoint").isTrue(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
}
