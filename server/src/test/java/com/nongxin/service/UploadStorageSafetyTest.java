package com.nongxin.service;

import com.nongxin.controller.ApiExceptionHandler;
import com.nongxin.controller.UploadController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.imageio.ImageIO;
import javax.sql.DataSource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Generated fixtures under @TempDir only; failure injection never reaches real photos or databases. */
class UploadStorageSafetyTest {
    private static final UUID FIXED_UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String FIXED_ID = "img-aaaaaaaabbbbccccdddd";
    private static final String PRIVATE_ERROR = "synthetic-private-path-and-sql";
    @TempDir Path directory;
    private Path images;
    private DataSource source;
    private JdbcTemplate jdbc;
    private CurrentUser user;
    private byte[] png;

    @BeforeEach
    void setUp() throws Exception {
        images = directory.resolve("images");
        source = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("storage.db"));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        user = new CurrentUser();
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(240, 240, BufferedImage.TYPE_INT_RGB), "png", bytes);
        png = bytes.toByteArray();
    }

    @Test
    void successfulUploadHasOneCommittedRowOneJpegAndNoTemporaryFile() throws Exception {
        var uploads = service(jdbc);
        var saved = store(uploads);
        assertThat(saved).isNotNull();
        assertThat(uploads.get(saved.id())).isEqualTo(saved);
        assertThat(rows()).isEqualTo(1);
        assertThat(fileNames()).containsExactly(saved.id() + ".jpg");
        byte[] jpeg = Files.readAllBytes(images.resolve(saved.id() + ".jpg"));
        assertThat(jpeg.length).isEqualTo(saved.bytes());
        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(jpeg)).getWidth()).isEqualTo(240);
    }

    @ParameterizedTest
    @ValueSource(strings = {"before-insert", "after-insert", "zero-count", "zero-after-insert"})
    void metadataWriteFailureLeavesNeitherNewFilesNorRows(String mode) throws Exception {
        var failingJdbc = new JdbcTemplate(source) {
            @Override public int update(String sql, Object... args) {
                if (!sql.startsWith("INSERT INTO uploads")) return super.update(sql, args);
                if (mode.equals("after-insert") || mode.equals("zero-after-insert")) super.update(sql, args);
                if (mode.startsWith("zero-")) return 0;
                throw new DataAccessResourceFailureException(PRIVATE_ERROR);
            }
        };
        assertThatThrownBy(() -> store(service(failingJdbc))).isInstanceOf(UploadService.SaveUnavailable.class)
                .hasMessageNotContaining(PRIVATE_ERROR).hasNoCause();
        assertThat(rows()).isZero();
        assertThat(fileNames()).isEmpty();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.hasResource(source)).isFalse();
        // The same thread can retry normally; no transaction state leaks from the failed operation.
        var retry = store(service(jdbc));
        assertThat(rows()).isEqualTo(1);
        assertThat(fileNames()).containsExactly(retry.id() + ".jpg");
    }

    @Test
    void realSqlConstraintFailurePreservesExistingRowsAndFiles() throws Exception {
        var saved = store(service(jdbc));
        byte[] original = Files.readAllBytes(images.resolve(saved.id() + ".jpg"));
        jdbc.execute("CREATE TRIGGER fail_upload BEFORE INSERT ON uploads BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END");
        assertThatThrownBy(() -> store(service(jdbc))).isInstanceOf(RuntimeException.class);
        assertThat(rows()).isEqualTo(1);
        assertThat(fileNames()).containsExactly(saved.id() + ".jpg");
        assertThat(Files.readAllBytes(images.resolve(saved.id() + ".jpg"))).containsExactly(original);
    }

    @Test
    void partialTemporaryWriteIsRemovedWithoutStartingMetadataWrite() throws Exception {
        var writes = new AtomicBoolean();
        var observedJdbc = new JdbcTemplate(source) {
            @Override public int update(String sql, Object... args) {
                if (sql.startsWith("INSERT INTO uploads")) writes.set(true);
                return super.update(sql, args);
            }
        };
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.write(any(Path.class), any(byte[].class), any(OpenOption[].class)))
                    .thenAnswer(invocation -> {
                        invocation.callRealMethod(); // Simulate a failure reported after bytes reached disk.
                        throw new IOException(PRIVATE_ERROR);
                    });
            assertThatThrownBy(() -> store(service(observedJdbc))).isInstanceOf(RuntimeException.class);
        }
        assertThat(writes.get()).isFalse();
        assertThat(rows()).isZero();
        assertThat(fileNames()).isEmpty();
    }

    @Test
    void moveFailureRollsBackMetadataAndRemovesOnlyTheStagedFile() throws Exception {
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.move(any(Path.class), any(Path.class), any(CopyOption[].class)))
                    .thenThrow(new IOException(PRIVATE_ERROR));
            assertThatThrownBy(() -> store(service(jdbc))).isInstanceOf(RuntimeException.class);
        }
        assertThat(rows()).isZero();
        assertThat(fileNames()).isEmpty();
    }

    @Test
    void existingDestinationIsNeverOverwrittenOrDeleted() throws Exception {
        Files.createDirectories(images);
        byte[] original = {1, 2, 3, 4};
        Files.write(images.resolve(FIXED_ID + ".jpg"), original);
        try (var ids = mockStatic(UUID.class, CALLS_REAL_METHODS)) {
            ids.when(UUID::randomUUID).thenReturn(FIXED_UUID);
            assertThatThrownBy(() -> store(service(jdbc))).isInstanceOf(RuntimeException.class);
        }
        assertThat(rows()).isZero();
        assertThat(fileNames()).containsExactly(FIXED_ID + ".jpg");
        assertThat(Files.readAllBytes(images.resolve(FIXED_ID + ".jpg"))).containsExactly(original);
    }

    @Test
    void existingMetadataWithMissingFileIsNotSilentlyReplaced() throws Exception {
        jdbc.update("INSERT INTO uploads (id,mime,ext,bytes,width,height,created_at,user_id) VALUES (?,'image/jpeg','jpg',4,240,240,'2026-09-01','other-owner')", FIXED_ID);
        var original = jdbc.queryForMap("SELECT * FROM uploads WHERE id=?", FIXED_ID);
        try (var ids = mockStatic(UUID.class, CALLS_REAL_METHODS)) {
            ids.when(UUID::randomUUID).thenReturn(FIXED_UUID);
            assertThatThrownBy(() -> store(service(jdbc))).isInstanceOf(RuntimeException.class);
        }
        assertThat(jdbc.queryForMap("SELECT * FROM uploads WHERE id=?", FIXED_ID)).isEqualTo(original);
        assertThat(fileNames()).isEmpty();
    }

    @Test
    void oldTemporaryFilesAreNeitherReusedNorSwept() throws Exception {
        Files.createDirectories(images);
        Path oldTemp = images.resolve(FIXED_ID + ".tmp");
        Files.writeString(oldTemp, "old fixture from another attempt");
        try (var ids = mockStatic(UUID.class, CALLS_REAL_METHODS)) {
            ids.when(UUID::randomUUID).thenReturn(FIXED_UUID);
            assertThat(store(service(jdbc)).id()).isEqualTo(FIXED_ID);
        }
        assertThat(fileNames()).containsExactly(FIXED_ID + ".jpg", FIXED_ID + ".tmp");
        assertThat(Files.readString(oldTemp)).isEqualTo("old fixture from another attempt");
        assertThat(rows()).isEqualTo(1);
    }

    @Test
    void zeroRowsFromARealIgnoreTriggerIsNotReportedAsSuccess() throws Exception {
        jdbc.execute("CREATE TRIGGER ignore_upload BEFORE INSERT ON uploads BEGIN SELECT RAISE(IGNORE); END");
        assertThatThrownBy(() -> store(service(jdbc))).isInstanceOf(UploadService.SaveUnavailable.class);
        assertThat(rows()).isZero();
        assertThat(fileNames()).isEmpty();
    }

    @Test
    void moveThatSucceedsThenThrowsDoesNotDeleteAnUnconfirmedDestination() throws Exception {
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.move(any(Path.class), any(Path.class), any(CopyOption[].class)))
                    .thenAnswer(invocation -> { invocation.callRealMethod(); throw new IOException(PRIVATE_ERROR); });
            assertThatThrownBy(() -> store(service(jdbc))).isInstanceOf(UploadService.SaveUnavailable.class);
        }
        assertThat(rows()).isZero();
        assertThat(fileNames()).hasSize(1).allMatch(name -> name.endsWith(".jpg"));
        assertThat(ImageIO.read(images.resolve(fileNames().getFirst()).toFile())).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {".tmp", ".jpg"})
    void failedCompensatingDeleteRetainsTheNewFileAndNeverMasksTheFailure(String suffix) throws Exception {
        jdbc.execute("CREATE TRIGGER fail_upload BEFORE INSERT ON uploads BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END");
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            if (suffix.equals(".tmp")) {
                files.when(() -> Files.write(any(Path.class), any(byte[].class), any(OpenOption[].class)))
                        .thenAnswer(invocation -> { invocation.callRealMethod(); throw new IOException(PRIVATE_ERROR); });
            }
            files.when(() -> Files.deleteIfExists(argThat(path -> path != null && path.toString().endsWith(suffix))))
                    .thenThrow(new IOException(PRIVATE_ERROR));
            assertThatThrownBy(() -> store(service(jdbc))).isInstanceOf(UploadService.SaveUnavailable.class)
                    .hasMessageNotContaining(PRIVATE_ERROR).hasNoCause();
        }
        assertThat(rows()).isZero();
        assertThat(fileNames()).hasSize(1).allMatch(name -> name.endsWith(suffix));
    }

    @Test
    void transactionConnectionFailureCleansOnlyItsStagedFile() throws Exception {
        var disconnected = new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException { throw new SQLException(PRIVATE_ERROR); }
            @Override public Connection getConnection(String username, String password) throws SQLException { return getConnection(); }
        };
        assertThatThrownBy(() -> store(service(new JdbcTemplate(disconnected))))
                .isInstanceOf(UploadService.SaveUnavailable.class).hasNoCause();
        assertThat(rows()).isZero();
        assertThat(fileNames()).isEmpty();
    }

    @Test
    void rollbackFailureKeepsPublishedBytesEvenIfConnectionCleanupCommitsTheRow() throws Exception {
        var failingJdbc = new JdbcTemplate(faultyConnections("rollback")) {
            @Override public int update(String sql, Object... args) {
                int count = super.update(sql, args);
                if (sql.startsWith("INSERT INTO uploads")) throw new DataAccessResourceFailureException(PRIVATE_ERROR);
                return count;
            }
        };
        assertThatThrownBy(() -> store(service(failingJdbc))).isInstanceOf(UploadService.SaveUnavailable.class).hasNoCause();
        assertThat(fileNames()).hasSize(1).allMatch(name -> name.endsWith(".jpg"));
        // The test driver commits when cleanup re-enables autoCommit after the injected rollback failure.
        assertThat(rows()).isEqualTo(1);
        String id = jdbc.queryForObject("SELECT id FROM uploads", String.class);
        assertThat(Files.exists(images.resolve(id + ".jpg"))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"before-commit", "after-commit"})
    void uncertainCommitNeverReturnsSuccessOrDeletesAPotentiallyCommittedPhoto(String mode) throws Exception {
        var uploads = service(new JdbcTemplate(faultyConnections(mode)));
        assertThatThrownBy(() -> store(uploads)).isInstanceOf(UploadService.SaveUnavailable.class)
                .hasMessageNotContaining(PRIVATE_ERROR).hasNoCause();
        assertThat(fileNames()).hasSize(1).allMatch(name -> name.endsWith(".jpg"));
        assertThat(rows()).isEqualTo(mode.equals("after-commit") ? 1 : 0);
        if (mode.equals("after-commit")) {
            String id = jdbc.queryForObject("SELECT id FROM uploads", String.class);
            assertThat(Files.exists(images.resolve(id + ".jpg"))).isTrue();
        }
    }

    @Test
    void directoryCreationFailureDoesNotExposeItsPathOrWriteMetadata() throws Exception {
        Files.writeString(images, "existing non-directory fixture");
        assertThatThrownBy(() -> store(service(jdbc))).isInstanceOf(RuntimeException.class)
                .hasMessageNotContaining(directory.toString());
        assertThat(rows()).isZero();
        assertThat(Files.readString(images)).isEqualTo("existing non-directory fixture");
    }

    @Test
    void ambientTransactionIsRejectedBeforeAnyUploadFilesAreCreated() throws Exception {
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.executeWithoutResult(status -> {
            assertThatThrownBy(() -> store(service(jdbc))).isInstanceOf(RuntimeException.class);
            status.setRollbackOnly();
        });
        assertThat(rows()).isZero();
        assertThat(fileNames()).isEmpty();
    }

    @Test
    void persistenceFailureReturnsSafe503InsteadOfConflictOrInternalDetails() throws Exception {
        Files.writeString(images, "existing fixture");
        var mvc = MockMvcBuilders.standaloneSetup(new UploadController(service(jdbc), mock(FieldService.class), user))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(multipart("/api/uploads").file(new MockMultipartFile("file", "test.png", "image/png", png)))
                .andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("UPLOAD_SAVE_UNAVAILABLE"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(directory.toString()))));
        assertThat(rows()).isZero();
    }

    private UploadService service(JdbcTemplate template) {
        return new UploadService(template, images.toString(), 8_388_608, 7, user);
    }

    private UploadService.Stored store(UploadService uploads) {
        return uploads.store(png, "image/png", null, null, "test fixture", null);
    }

    private int rows() { return jdbc.queryForObject("SELECT COUNT(*) FROM uploads", Integer.class); }

    private List<String> fileNames() throws IOException {
        if (!Files.exists(images)) return List.of();
        try (var files = Files.list(images)) { return files.map(path -> path.getFileName().toString()).sorted().toList(); }
    }

    private DataSource faultyConnections(String mode) {
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                Connection delegate = source.getConnection();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("rollback") && mode.equals("rollback")) throw new SQLException(PRIVATE_ERROR);
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
}
