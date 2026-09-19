package com.nongxin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nongxin.model.Conversation;
import com.nongxin.model.SavedChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteConnection;
import org.sqlite.SQLiteException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/** Real SQLite contention on independent connections/threads; no injected SQL exceptions or running application. */
class UploadCleanupConcurrencyTest {
    private static final String ID = "img-concurrent";
    @TempDir Path directory;
    private DriverManagerDataSource cleanupSource;
    private DriverManagerDataSource writerSource;
    private JdbcTemplate jdbc;
    private Path images;
    private Path original;
    private byte[] bytes;

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("concurrent.db");
        cleanupSource = new DriverManagerDataSource(url);
        writerSource = new DriverManagerDataSource(url); // Different connection factory, same disposable file.
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(cleanupSource);
        jdbc = new JdbcTemplate(cleanupSource);
        images = Files.createDirectory(directory.resolve("images"));
        original = images.resolve(ID + ".jpg").toAbsolutePath().normalize();
        assertThat(original.startsWith(directory.toAbsolutePath().normalize())).isTrue();
        var buffer = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(240, 240, BufferedImage.TYPE_INT_RGB), "jpeg", buffer);
        bytes = buffer.toByteArray();
        Files.write(original, bytes);
        jdbc.update("INSERT INTO uploads (id,mime,ext,bytes,width,height,created_at,user_id)"
                + " VALUES (?,'image/jpeg','jpg',?,240,240,?,'u-a')", ID, bytes.length, LocalDateTime.now().minusDays(30).toString());
        jdbc.update("INSERT INTO fields (id,name,crop,sow_date,user_id) VALUES ('f-concurrent','fixture','fixture','2026-01-01','u-a')");
    }

    @Test
    void freshDatabaseAndPinnedDriverDefaultsAreVerifiedWithoutInspectingTheUsersDatabase() throws Exception {
        assertThat(jdbc.queryForObject("PRAGMA journal_mode", String.class)).isEqualTo("delete");
        assertThat(jdbc.queryForObject("PRAGMA busy_timeout", Integer.class)).isEqualTo(3000);
        assertThat(jdbc.queryForObject("PRAGMA read_uncommitted", Integer.class)).isZero();
        try (Connection connection = cleanupSource.getConnection()) {
            assertThat(connection.getAutoCommit()).isTrue();
            assertThat(connection.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_SERIALIZABLE);
            assertThat(connection.unwrap(SQLiteConnection.class).getConnectionConfig().getTransactionMode().name()).isEqualTo("DEFERRED");
            assertThat(connection.getMetaData().getDriverVersion()).isEqualTo("3.45.3.0");
        }
    }

    @ParameterizedTest
    @CsvSource({"delete,archive", "delete,reference", "delete,conversation", "wal,archive", "wal,reference", "wal,conversation"})
    void realWriteAfterFinalReferenceReadPreventsStaleDeletionAndRestoresOriginalBytes(String journal, String operation) throws Exception {
        // WAL is an alternate test fixture, not a change to the user's database or application configuration.
        assertThat(jdbc.queryForObject("PRAGMA journal_mode=" + journal, String.class)).isEqualTo(journal);
        var rechecked = new CountDownLatch(1);
        var allowCleanup = new CountDownLatch(1);
        var writerUpdated = new CountDownLatch(1);
        var allowWriterCommit = new CountDownLatch(1);
        var deleteAttempts = new AtomicInteger();
        var sqliteFailure = new AtomicReference<DataAccessException>();
        var cleanupTemplate = new JdbcTemplate(cleanupSource) {
            private int scans;
            @Override public void query(String sql, RowCallbackHandler callback) {
                super.query(sql, callback);
                if (sql.equals("SELECT messages_json FROM conversations") && ++scans == 2) {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    rechecked.countDown(); // Both metadata and conversation reads have completed, but no unlink yet.
                    await(allowCleanup);
                }
            }
            @Override public int update(String sql, Object... args) {
                if (!sql.startsWith("DELETE FROM uploads")) return super.update(sql, args);
                deleteAttempts.incrementAndGet();
                assertThat(Files.exists(original)).isFalse(); // Real unlink happened; restoration must be observed later.
                try { return super.update(sql, args); }
                catch (DataAccessException failure) { sqliteFailure.set(failure); throw failure; }
            }
        };
        var maintenance = new CurrentUser();
        maintenance.setResolver(() -> { throw new AssertionError("Maintenance must not resolve an interactive user"); });
        var cleanup = new UploadService(cleanupTemplate, images.toString(), 8_388_608, 7, maintenance);
        var writerTemplate = new JdbcTemplate(writerSource);
        var owner = new CurrentUser();
        owner.setResolver(() -> "u-a");
        var writerUploads = new UploadService(writerTemplate, images.toString(), 8_388_608, 7, owner);
        var conversations = new ConversationService(writerTemplate, new ObjectMapper(), owner);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var cleaned = executor.submit(() -> {
                try { return cleanup.cleanupUnreferenced(); }
                finally { assertThat(TransactionSynchronizationManager.hasResource(cleanupSource)).isFalse(); }
            });
            await(rechecked);
            assertThat(Files.readAllBytes(original)).containsExactly(bytes);
            var written = executor.submit(() -> {
                try {
                    if (journal.equals("delete")) {
                        // Hold a real write transaction after UPDATE/INSERT to make the rollback-journal race deterministic.
                        new TransactionTemplate(new DataSourceTransactionManager(writerSource)).executeWithoutResult(status -> {
                            write(operation, writerUploads, conversations);
                            writerUpdated.countDown();
                            await(allowWriterCommit);
                        });
                    } else {
                        // WAL permits this real autocommit service write while the cleanup read transaction is open.
                        write(operation, writerUploads, conversations);
                        writerUpdated.countDown();
                    }
                } finally { assertThat(TransactionSynchronizationManager.hasResource(writerSource)).isFalse(); }
            });
            await(writerUpdated);
            if (journal.equals("wal")) {
                written.get(8, TimeUnit.SECONDS);
                assertWriteVisible(operation); // The other connection committed before cleanup tries DELETE.
            }
            allowCleanup.countDown();
            assertThat(cleaned.get(8, TimeUnit.SECONDS)).isZero();
            allowWriterCommit.countDown();
            written.get(8, TimeUnit.SECONDS);
            assertThat(deleteAttempts).hasValue(1);
            assertThat(sqliteFailure.get()).isNotNull();
            assertThat(sqliteFailure.get().getMostSpecificCause()).isInstanceOf(SQLiteException.class);
            var failure = (SQLiteException) sqliteFailure.get().getMostSpecificCause();
            assertThat(failure.getResultCode().name()).contains("BUSY");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uploads WHERE id=?", Integer.class, ID)).isEqualTo(1);
            assertThat(Files.readAllBytes(original)).containsExactly(bytes);
            assertWriteVisible(operation);
            try (var paths = Files.list(images)) { assertThat(paths.toList()).containsExactly(original); }
            // A subsequent normal maintenance run observes the new archive/reference, without relying on the old snapshot.
            assertThat(cleanup.cleanupUnreferenced()).isZero();
            assertThat(Files.readAllBytes(original)).containsExactly(bytes);
        } finally {
            allowCleanup.countDown();
            allowWriterCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(8, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void write(String operation, UploadService uploads, ConversationService conversations) {
        switch (operation) {
            case "archive" -> assertThat(uploads.updateArchive(ID, "new archive", null, "f-concurrent").fieldId()).isEqualTo("f-concurrent");
            case "reference" -> assertThat(uploads.markReferenced(List.of(ID))).isEqualTo(1);
            case "conversation" -> {
                var message = new SavedChatMessage("m-photo", "user", "fixture", null, null, null, null, null,
                        null, null, null, null, null, List.of(Map.of("id", ID)));
                assertThat(conversations.save(new Conversation("c-photo", "fixture", null, List.of(message), Instant.now().toString()))).isNotNull();
            }
            default -> throw new AssertionError(operation);
        }
    }

    private void assertWriteVisible(String operation) {
        switch (operation) {
            case "archive" -> assertThat(jdbc.queryForObject("SELECT field_id FROM uploads WHERE id=?", String.class, ID)).isEqualTo("f-concurrent");
            case "reference" -> assertThat(jdbc.queryForObject("SELECT referenced_at FROM uploads WHERE id=?", String.class, ID)).isNotBlank();
            case "conversation" -> {
                assertThat(jdbc.queryForObject("SELECT messages_json FROM conversations WHERE id='c-photo'", String.class)).contains(ID);
                assertThat(jdbc.queryForObject("SELECT referenced_at FROM uploads WHERE id=?", String.class, ID)).isNull();
            }
            default -> throw new AssertionError(operation);
        }
    }

    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(8, TimeUnit.SECONDS)).as("bounded concurrency checkpoint").isTrue(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
}
