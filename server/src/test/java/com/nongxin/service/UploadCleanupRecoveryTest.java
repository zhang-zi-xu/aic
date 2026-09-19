package com.nongxin.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import javax.sql.DataSource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Automatic cleanup is invoked only against newly created SQLite/JPEG fixtures, never a running app. */
class UploadCleanupRecoveryTest {
    private static final String ID = "img-target";
    private static final String PRIVATE_ERROR = "synthetic-private-cleanup-failure";
    @TempDir Path directory;
    private Path images;
    private Path original;
    private DataSource source;
    private JdbcTemplate jdbc;
    private CurrentUser user;
    private UploadService uploads;
    private byte[] bytes;

    @BeforeEach
    void setUp() throws Exception {
        images = directory.resolve("images");
        Files.createDirectory(images);
        source = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("cleanup-recovery.db"));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        user = new CurrentUser();
        user.setResolver(() -> { throw new AssertionError("Cleanup must remain independent of CurrentUser"); });
        uploads = service(jdbc);
        var buffer = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(240, 240, BufferedImage.TYPE_INT_RGB), "jpeg", buffer);
        bytes = buffer.toByteArray();
        original = seed(ID, "u-a");
    }

    @ParameterizedTest
    @ValueSource(strings = {"before-delete", "after-delete", "zero", "zero-after-delete", "two-after-delete"})
    void knownRollbackRestoresTheImageAndRowWithoutCountingFailureAsSuccess(String mode) throws Exception {
        var row = jdbc.queryForMap("SELECT * FROM uploads WHERE id=?", ID);
        assertThat(service(failingDelete(source, mode)).cleanupUnreferenced()).isZero();
        assertThat(jdbc.queryForMap("SELECT * FROM uploads WHERE id=?", ID)).isEqualTo(row);
        assertThat(Files.readAllBytes(original)).containsExactly(bytes);
        assertThat(recoveryFiles()).isEmpty();
        assertThat(TransactionSynchronizationManager.hasResource(source)).isFalse();
        assertThat(uploads.cleanupUnreferenced()).isEqualTo(1);
        assertThat(rows()).isZero();
        assertThat(Files.exists(original)).isFalse();
        assertThat(uploads.cleanupUnreferenced()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ABORT", "IGNORE"})
    void actualSqlFailureRetainsItsPhotoWhileAnotherOwnerCanCompleteAndTheFailedItemCanRetry(String mode) throws Exception {
        Path other = seed("img-other", "u-b");
        jdbc.execute("CREATE TRIGGER fail_cleanup BEFORE DELETE ON uploads WHEN OLD.id='img-target' "
                + "BEGIN SELECT RAISE(" + (mode.equals("ABORT") ? "ABORT,'synthetic failure'" : "IGNORE") + "); END");
        assertThat(uploads.cleanupUnreferenced()).isEqualTo(1);
        assertThat(rows()).isEqualTo(1);
        assertThat(Files.readAllBytes(original)).containsExactly(bytes);
        assertThat(Files.exists(other)).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uploads WHERE id='img-other'", Integer.class)).isZero();
        assertThat(recoveryFiles()).isEmpty();
        jdbc.execute("DROP TRIGGER fail_cleanup"); // This test's disposable database only.
        assertThat(uploads.cleanupUnreferenced()).isEqualTo(1);
        assertThat(rows()).isZero();
        assertThat(recoveryFiles()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"before-commit", "after-commit", "rollback"})
    void uncertainTransactionKeepsRecoverableBytesWithoutClaimingTheRowWasDefinitelyPreserved(String mode) throws Exception {
        DataSource faulty = faultyConnections(mode);
        JdbcTemplate template = mode.equals("rollback") ? failingDelete(faulty, "after-delete") : new JdbcTemplate(faulty);
        assertThat(service(template).cleanupUnreferenced()).isZero();
        assertThat(recoveryFiles()).hasSize(1);
        assertThat(Files.readAllBytes(recoveryFiles().getFirst())).containsExactly(bytes);
        assertThat(rows()).isEqualTo(mode.equals("before-commit") ? 1 : 0);
        assertThat(TransactionSynchronizationManager.hasResource(faulty)).isFalse();
    }

    @Test
    void callerTransactionIsSkippedBeforeCreatingAnyRecoveryCopyOrUnlinking() throws Exception {
        var outer = new TransactionTemplate(new DataSourceTransactionManager(source));
        outer.executeWithoutResult(status -> {
            assertThat(uploads.cleanupUnreferenced()).isZero();
            status.setRollbackOnly();
        });
        assertIntactWithoutCopy();
    }

    @Test
    void boundConnectionWithoutAnActiveTransactionAlsoPreventsFilesystemChanges() throws Exception {
        try (Connection connection = source.getConnection()) {
            TransactionSynchronizationManager.bindResource(source, new ConnectionHolder(connection));
            try { assertThat(uploads.cleanupUnreferenced()).isZero(); }
            finally { TransactionSynchronizationManager.unbindResource(source); }
        }
        assertIntactWithoutCopy();
    }

    @Test
    void transactionBeginFailureLeavesOriginalAndRegistrationUntouched() throws Exception {
        assertThat(service(new JdbcTemplate(faultyConnections("begin"))).cleanupUnreferenced()).isZero();
        assertIntactWithoutCopy();
    }

    @Test
    void partialBackupFailureNeverUnlinksTheOriginal() throws Exception {
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.copy(eq(original), any(Path.class), any(CopyOption[].class)))
                    .thenAnswer(call -> { call.callRealMethod(); throw new IOException(PRIVATE_ERROR); });
            assertThat(Files.readAllBytes(original)).containsExactly(bytes);
            assertThat(uploads.cleanupUnreferenced()).isZero();
        }
        assertIntactWithoutCopy();
    }

    @Test
    void restorationFailureRetainsItsPrivateCopyForInspection() throws Exception {
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.copy(any(Path.class), eq(original), any(CopyOption[].class)))
                    .thenThrow(new IOException(PRIVATE_ERROR));
            assertThat(service(failingDelete(source, "before-delete")).cleanupUnreferenced()).isZero();
        }
        assertThat(rows()).isEqualTo(1);
        assertThat(recoveryFiles()).hasSize(1);
        assertThat(Files.readAllBytes(recoveryFiles().getFirst())).containsExactly(bytes);
    }

    @Test
    void restorationDoesNotOverwriteDifferentBytesCreatedAtTheOriginalPath() throws Exception {
        byte[] replacement = {7, 3, 1};
        var replacing = new JdbcTemplate(source) {
            @Override public int update(String sql, Object... args) {
                if (sql.startsWith("DELETE FROM uploads")) {
                    try { Files.write(original, replacement); } catch (IOException failure) { throw new AssertionError(failure); }
                    throw new DataAccessResourceFailureException(PRIVATE_ERROR);
                }
                return super.update(sql, args);
            }
        };
        assertThat(service(replacing).cleanupUnreferenced()).isZero();
        assertThat(rows()).isEqualTo(1);
        assertThat(Files.readAllBytes(original)).containsExactly(replacement);
        assertThat(recoveryFiles()).hasSize(1);
        assertThat(Files.readAllBytes(recoveryFiles().getFirst())).containsExactly(bytes);
    }

    @Test
    void committedRowWithFailedCopyPurgeIsNotCountedAsCompleteCleanup() throws Exception {
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.deleteIfExists(argThat(path -> path != null && path.getFileName().toString().equals("image.backup"))))
                    .thenThrow(new IOException(PRIVATE_ERROR));
            assertThat(uploads.cleanupUnreferenced()).isZero();
        }
        assertThat(rows()).isZero();
        assertThat(Files.exists(original)).isFalse();
        assertThat(recoveryFiles()).hasSize(1);
        assertThat(Files.readAllBytes(recoveryFiles().getFirst())).containsExactly(bytes);
        assertThat(uploads.cleanupUnreferenced()).isZero();
        assertThat(recoveryFiles()).hasSize(1); // A later scan must not sweep an old recovery directory.
    }

    @Test
    void databaseFailureForAnAlreadyMissingFileKeepsTheRowWithoutInventingARecoveryFile() throws Exception {
        Files.delete(original);
        assertThat(service(failingDelete(source, "before-delete")).cleanupUnreferenced()).isZero();
        assertThat(rows()).isEqualTo(1);
        assertThat(Files.exists(original)).isFalse();
        assertThat(recoveryFiles()).isEmpty();
        assertThat(uploads.cleanupUnreferenced()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"../outside", "jpg/../../outside", "jpg:stream"})
    void invalidLegacyExtensionIsSkippedWithoutChangingFilesOrAbortingOtherCandidates(String extension) throws Exception {
        Path sentinel = directory.resolve("outside");
        Files.writeString(sentinel, "untouched synthetic fixture");
        jdbc.update("UPDATE uploads SET ext=? WHERE id=?", extension, ID);
        Path other = seed("img-other", "u-b");
        assertThat(uploads.cleanupUnreferenced()).isEqualTo(1);
        assertIntactWithoutCopy();
        assertThat(Files.readString(sentinel)).isEqualTo("untouched synthetic fixture");
        assertThat(Files.exists(other)).isFalse();
    }

    @Test
    void manualDeletionWaitsUntilAutomaticCleanupFinishesItsRollbackRestoration() throws Exception {
        var cleanupInside = new CountDownLatch(1);
        var releaseCleanup = new CountDownLatch(1);
        var manualStarted = new CountDownLatch(1);
        var manualRead = new CountDownLatch(1);
        var cleanup = service(new JdbcTemplate(source) {
            @Override public int update(String sql, Object... args) {
                if (sql.startsWith("DELETE FROM uploads")) {
                    cleanupInside.countDown();
                    try { assertThat(releaseCleanup.await(5, TimeUnit.SECONDS)).isTrue(); }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
                    throw new DataAccessResourceFailureException(PRIVATE_ERROR);
                }
                return super.update(sql, args);
            }
        });
        var owner = new CurrentUser();
        owner.setResolver(() -> "u-a");
        var manual = new UploadService(new JdbcTemplate(source) {
            @Override public <T> List<T> query(String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
                if (sql.startsWith("SELECT * FROM uploads")) manualRead.countDown();
                return super.query(sql, mapper, args);
            }
        }, images.toString(), 8_388_608, 7, owner);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var cleaned = executor.submit(cleanup::cleanupUnreferenced);
            assertThat(cleanupInside.await(5, TimeUnit.SECONDS)).isTrue();
            var deleted = executor.submit(() -> { manualStarted.countDown(); return manual.delete(ID); });
            assertThat(manualStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(manualRead.await(150, TimeUnit.MILLISECONDS)).isFalse();
            releaseCleanup.countDown();
            assertThat(cleaned.get(5, TimeUnit.SECONDS)).isZero();
            assertThat(deleted.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(rows()).isZero();
            assertThat(Files.exists(original)).isFalse();
            assertThat(recoveryFiles()).isEmpty();
        } finally {
            releaseCleanup.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void assertIntactWithoutCopy() throws IOException {
        assertThat(rows()).isEqualTo(1);
        assertThat(Files.readAllBytes(original)).containsExactly(bytes);
        assertThat(recoveryFiles()).isEmpty();
    }

    private Path seed(String id, String owner) throws IOException {
        Path path = images.resolve(id + ".jpg").toAbsolutePath().normalize();
        assertThat(path.startsWith(directory.toAbsolutePath().normalize())).isTrue();
        Files.write(path, bytes);
        jdbc.update("INSERT INTO uploads (id,mime,ext,bytes,width,height,created_at,user_id)"
                + " VALUES (?,'image/jpeg','jpg',?,240,240,?,?)", id, bytes.length, LocalDateTime.now().minusDays(30).toString(), owner);
        return path;
    }

    private UploadService service(JdbcTemplate template) {
        return new UploadService(template, images.toString(), 8_388_608, 7, user);
    }

    private int rows() { return jdbc.queryForObject("SELECT COUNT(*) FROM uploads WHERE id=?", Integer.class, ID); }

    private List<Path> recoveryFiles() throws IOException {
        try (var paths = Files.walk(images)) {
            return paths.filter(Files::isRegularFile).filter(path -> !path.getParent().equals(images)).sorted().toList();
        }
    }

    private JdbcTemplate failingDelete(DataSource dataSource, String mode) {
        return new JdbcTemplate(dataSource) {
            @Override public int update(String sql, Object... args) {
                if (!sql.startsWith("DELETE FROM uploads")) return super.update(sql, args);
                if (mode.contains("after-delete")) super.update(sql, args);
                if (mode.startsWith("zero")) return 0;
                if (mode.startsWith("two")) return 2;
                throw new DataAccessResourceFailureException(PRIVATE_ERROR);
            }
        };
    }

    private DataSource faultyConnections(String mode) {
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                Connection delegate = source.getConnection();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("setAutoCommit") && mode.equals("begin") && Boolean.FALSE.equals(args[0])) throw new SQLException(PRIVATE_ERROR);
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
