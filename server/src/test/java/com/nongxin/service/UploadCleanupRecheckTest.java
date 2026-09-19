package com.nongxin.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.imageio.ImageIO;
import javax.sql.DataSource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Mutations occur at controlled scan/copy boundaries, only in fresh SQLite and synthetic JPEG fixtures. */
class UploadCleanupRecheckTest {
    private static final String ID = "img-target";
    @TempDir Path directory;
    private DataSource source;
    private JdbcTemplate jdbc;
    private Path images;
    private Path original;
    private byte[] bytes;

    @BeforeEach
    void setUp() throws Exception {
        source = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("recheck.db"));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        images = Files.createDirectory(directory.resolve("images"));
        var buffer = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(240, 240, BufferedImage.TYPE_INT_RGB), "jpeg", buffer);
        bytes = buffer.toByteArray();
        original = seed(ID, "u-a");
    }

    @ParameterizedTest
    @ValueSource(strings = {"field", "task", "reference", "blank-marker", "fresh-time", "extension", "note"})
    void changedCandidateIsRetainedWhileAnUnchangedPhotoFromAnotherOwnerCanBeCleaned(String change) throws Exception {
        Path other = seed("img-other", "u-b");
        var service = service(afterInitialScan(() -> mutate(change)));
        assertThat(service.cleanupUnreferenced()).isEqualTo(1);
        assertKept(ID);
        assertThat(Files.exists(other)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uploads WHERE id='img-other'", Integer.class)).isZero();
        assertNoRecoveryFiles();
    }

    @ParameterizedTest
    @ValueSource(strings = {"image", "snapshot", "inline"})
    void conversationSavedAfterInitialScanProtectsThePhotoWithoutAReferenceTimestamp(String form) throws Exception {
        String messages = switch (form) {
            case "image" -> "[{\"images\":[{\"id\":\"img-target\"}]}]";
            case "snapshot" -> "[{\"requestContext\":{\"imageIds\":[\"img-target\"]}}]";
            case "inline" -> "[{\"content\":\"![fixture](/api/uploads/img-target)\"}]";
            default -> throw new AssertionError(form);
        };
        assertThat(service(afterInitialScan(() -> conversation(messages))).cleanupUnreferenced()).isZero();
        assertKept(ID);
        assertThat(jdbc.queryForObject("SELECT referenced_at FROM uploads WHERE id=?", String.class, ID)).isNull();
        assertNoRecoveryFiles();
    }

    @ParameterizedTest
    @ValueSource(strings = {"field", "conversation"})
    void changeDuringBackupCopyIsCheckedInsideTheTransactionBeforeAnyUnlink(String change) throws Exception {
        var recheckedInTransaction = new AtomicBoolean();
        var checking = new JdbcTemplate(source) {
            @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
                if (sql.startsWith("SELECT * FROM uploads WHERE id=")) {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    recheckedInTransaction.set(true);
                }
                return super.query(sql, mapper, args);
            }
        };
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.copy(eq(original), any(Path.class), any(CopyOption[].class))).thenAnswer(call -> {
                Object result = call.callRealMethod();
                if (change.equals("field")) mutate("field");
                else conversation("[{\"images\":[{\"id\":\"img-target\"}]}]");
                return result;
            });
            assertThat(Files.readAllBytes(original)).containsExactly(bytes);
            assertThat(service(checking).cleanupUnreferenced()).isZero();
            files.verify(() -> Files.deleteIfExists(original), never());
        }
        assertThat(recheckedInTransaction).isTrue();
        assertKept(ID);
        assertNoRecoveryFiles();
    }

    @ParameterizedTest
    @ValueSource(strings = {"upload-query", "conversation-query"})
    void recheckFailureStopsRemainingCandidatesWithoutChangingThemAndCanBeRetried(String fault) throws Exception {
        seed("img-other", "u-b");
        var failing = new JdbcTemplate(source) {
            private int scans;
            @Override public void query(String sql, RowCallbackHandler callback) {
                if (sql.equals("SELECT messages_json FROM conversations") && ++scans > 1 && fault.equals("conversation-query")) {
                    throw new DataAccessResourceFailureException("synthetic private query detail");
                }
                super.query(sql, callback);
            }
            @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
                if (sql.startsWith("SELECT * FROM uploads WHERE id=") && fault.equals("upload-query")) {
                    throw new DataAccessResourceFailureException("synthetic private query detail");
                }
                return super.query(sql, mapper, args);
            }
        };
        assertThat(service(failing).cleanupUnreferenced()).isZero();
        assertKept(ID);
        assertKept("img-other");
        assertNoRecoveryFiles();
        assertThat(TransactionSynchronizationManager.hasResource(source)).isFalse();
        assertThat(service(jdbc).cleanupUnreferenced()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"broken json", "[{\"images\":{}}]", "[{\"images\":[],\"images\":[]}]"})
    void newlyMalformedConversationDataStopsBeforeAnyCandidateIsUnlinked(String messages) throws Exception {
        seed("img-other", "u-b");
        assertThat(service(afterInitialScan(() -> conversation(messages))).cleanupUnreferenced()).isZero();
        assertKept(ID);
        assertKept("img-other");
        assertNoRecoveryFiles();
    }

    @Test
    void vanishedRegistrationDoesNotAuthorizeUnlinkingTheOldPath() throws Exception {
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            assertThat(service(afterInitialScan(() -> jdbc.update("DELETE FROM uploads WHERE id=?", ID)))
                    .cleanupUnreferenced()).isZero();
            files.verify(() -> Files.deleteIfExists(original), never());
        }
        assertThat(Files.readAllBytes(original)).containsExactly(bytes);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uploads WHERE id=?", Integer.class, ID)).isZero();
        assertNoRecoveryFiles();
    }

    @Test
    void laterVerificationFailureStopsTheRestButDoesNotEraseAnAlreadyConfirmedCount() throws Exception {
        seed("img-other", "u-b");
        seed("img-third", "u-c");
        var changing = new JdbcTemplate(source) {
            private boolean changed;
            @Override public int update(String sql, Object... args) {
                int affected = super.update(sql, args);
                if (sql.startsWith("DELETE FROM uploads") && !changed) {
                    changed = true;
                    conversation("broken json"); // Joins this test's deletion transaction on the same data source.
                }
                return affected;
            }
        };
        assertThat(service(changing).cleanupUnreferenced()).isEqualTo(1);
        List<String> remaining = jdbc.queryForList("SELECT id FROM uploads", String.class);
        assertThat(remaining).hasSize(2);
        for (String id : remaining) assertKept(id);
        assertNoRecoveryFiles();
    }

    private JdbcTemplate afterInitialScan(Runnable mutation) {
        return new JdbcTemplate(source) {
            private boolean changed;
            @Override public void query(String sql, RowCallbackHandler callback) {
                super.query(sql, callback);
                if (sql.equals("SELECT messages_json FROM conversations") && !changed) {
                    changed = true;
                    mutation.run(); // Query resources are closed; subsequent reads must see the new state.
                }
            }
        };
    }

    private void mutate(String change) {
        var owner = new CurrentUser();
        owner.setResolver(() -> "u-a");
        var writer = new UploadService(jdbc, images.toString(), 8_388_608, 7, owner);
        switch (change) {
            case "field" -> {
                jdbc.update("INSERT INTO fields (id,name,crop,sow_date,user_id) VALUES ('f-new','fixture','fixture','2026-01-01','u-a')");
                writer.updateArchive(ID, "new archive", null, "f-new");
            }
            case "reference" -> writer.markReferenced(List.of(ID));
            case "task" -> jdbc.update("UPDATE uploads SET task_id='t-new' WHERE id=?", ID);
            case "blank-marker" -> jdbc.update("UPDATE uploads SET field_id='' WHERE id=?", ID);
            case "fresh-time" -> jdbc.update("UPDATE uploads SET created_at=? WHERE id=?", LocalDateTime.now().toString(), ID);
            case "extension" -> jdbc.update("UPDATE uploads SET ext='png' WHERE id=?", ID);
            case "note" -> writer.updateArchive(ID, "new observation", null, null);
            default -> throw new AssertionError(change);
        }
    }

    private void conversation(String messages) {
        jdbc.update("INSERT INTO conversations (id,title,messages_json,user_id) VALUES ('c-new','fixture',?,'u-other')", messages);
    }

    private UploadService service(JdbcTemplate template) {
        var maintenance = new CurrentUser();
        maintenance.setResolver(() -> { throw new AssertionError("Maintenance must not resolve a user"); });
        return new UploadService(template, images.toString(), 8_388_608, 7, maintenance);
    }

    private Path seed(String id, String owner) throws IOException {
        Path path = images.resolve(id + ".jpg").toAbsolutePath().normalize();
        assertThat(path.startsWith(directory.toAbsolutePath().normalize())).isTrue();
        Files.write(path, bytes);
        jdbc.update("INSERT INTO uploads (id,mime,ext,bytes,width,height,created_at,user_id)"
                + " VALUES (?,'image/jpeg','jpg',?,240,240,?,?)", id, bytes.length, LocalDateTime.now().minusDays(30).toString(), owner);
        return path;
    }

    private void assertKept(String id) throws IOException {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uploads WHERE id=?", Integer.class, id)).isEqualTo(1);
        assertThat(Files.readAllBytes(images.resolve(id + ".jpg"))).containsExactly(bytes);
    }

    private void assertNoRecoveryFiles() throws IOException {
        try (var paths = Files.list(images)) { assertThat(paths.filter(Files::isDirectory).toList()).isEmpty(); }
    }
}
