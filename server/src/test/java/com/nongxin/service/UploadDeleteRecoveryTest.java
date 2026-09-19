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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** All photos, recovery copies, SQL triggers and connection faults are confined to @TempDir fixtures. */
class UploadDeleteRecoveryTest {
    private static final String PRIVATE_ERROR = "synthetic-private-path-and-sql";
    @TempDir Path directory;
    private Path images;
    private DataSource source;
    private JdbcTemplate jdbc;
    private CurrentUser user;
    private UploadService uploads;
    private UploadService.Stored photo;
    private Path original;
    private byte[] bytes;

    @BeforeEach
    void setUp() throws Exception {
        images = directory.resolve("images");
        source = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("recovery.db"));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        user = new CurrentUser();
        uploads = service(jdbc);
        var buffer = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(240, 240, BufferedImage.TYPE_INT_RGB), "png", buffer);
        photo = uploads.store(buffer.toByteArray(), "image/png", null, null, "synthetic fixture", null);
        original = uploads.fileFor(photo.id(), "jpg");
        bytes = Files.readAllBytes(original);
    }

    @ParameterizedTest
    @ValueSource(strings = {"before-delete", "after-delete", "zero", "zero-after-delete"})
    void knownRollbackRestoresOriginalBytesAndMetadata(String mode) throws Exception {
        var originalRow = jdbc.queryForMap("SELECT * FROM uploads WHERE id=?", photo.id());
        var failing = failingDelete(source, mode);
        if (mode.startsWith("zero")) assertThat(service(failing).delete(photo.id())).isFalse();
        else assertThatThrownBy(() -> service(failing).delete(photo.id())).isInstanceOf(UploadService.DeleteUnavailable.class)
                .hasNoCause().hasMessageNotContaining(PRIVATE_ERROR);
        assertThat(jdbc.queryForMap("SELECT * FROM uploads WHERE id=?", photo.id())).isEqualTo(originalRow);
        assertThat(Files.readAllBytes(original)).containsExactly(bytes);
        assertThat(recoveryFiles()).isEmpty();
        assertThat(TransactionSynchronizationManager.hasResource(source)).isFalse();
        assertThat(uploads.delete(photo.id())).isTrue();
        assertThat(rows()).isZero();
        assertThat(allFiles()).isEmpty();
    }

    @Test
    void actualSqlAbortRestoresThePhotoRatherThanOnlyReturningAnError() throws Exception {
        jdbc.execute("CREATE TRIGGER fail_delete BEFORE DELETE ON uploads BEGIN SELECT RAISE(ABORT,'synthetic failure'); END");
        assertThatThrownBy(() -> uploads.delete(photo.id())).isInstanceOf(RuntimeException.class);
        assertThat(rows()).isEqualTo(1);
        assertThat(Files.readAllBytes(original)).containsExactly(bytes);
        assertThat(recoveryFiles()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"before-commit", "after-commit", "rollback"})
    void uncertainTransactionRetainsARecoverableCopyWithoutReportingSuccess(String mode) throws Exception {
        DataSource faulty = faultyConnections(mode);
        JdbcTemplate template = mode.equals("rollback") ? failingDelete(faulty, "after-delete") : new JdbcTemplate(faulty);
        assertThatThrownBy(() -> service(template).delete(photo.id())).isInstanceOf(RuntimeException.class);
        assertThat(recoveryFiles()).hasSize(1);
        assertThat(Files.readAllBytes(recoveryFiles().getFirst())).containsExactly(bytes);
        assertThat(rows()).isEqualTo(mode.equals("before-commit") ? 1 : 0);
    }

    @Test
    void outerTransactionIsRejectedBeforeChangingThePhoto() throws Exception {
        var outer = new TransactionTemplate(new DataSourceTransactionManager(source));
        outer.executeWithoutResult(status -> {
            assertThatThrownBy(() -> uploads.delete(photo.id())).isInstanceOf(RuntimeException.class);
            status.setRollbackOnly();
        });
        assertThat(rows()).isEqualTo(1);
        assertThat(Files.readAllBytes(original)).containsExactly(bytes);
        assertThat(recoveryFiles()).isEmpty();
    }

    @Test
    void backupFailureNeverUnlinksTheOriginalOrDeletesItsRow() throws Exception {
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.copy(eq(original), any(Path.class), any(CopyOption[].class)))
                    .thenThrow(new IOException(PRIVATE_ERROR));
            assertThatThrownBy(() -> uploads.delete(photo.id())).isInstanceOf(RuntimeException.class);
        }
        assertThat(rows()).isEqualTo(1);
        assertThat(Files.readAllBytes(original)).containsExactly(bytes);
        assertThat(recoveryFiles()).isEmpty();
    }

    @Test
    void restoreFailureKeepsTheOnlyRecoveryCopy() throws Exception {
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.copy(any(Path.class), eq(original), any(CopyOption[].class)))
                    .thenThrow(new IOException(PRIVATE_ERROR));
            assertThatThrownBy(() -> service(failingDelete(source, "before-delete")).delete(photo.id()))
                    .isInstanceOf(RuntimeException.class);
        }
        assertThat(rows()).isEqualTo(1);
        assertThat(recoveryFiles()).hasSize(1);
        assertThat(Files.readAllBytes(recoveryFiles().getFirst())).containsExactly(bytes);
    }

    @Test
    void restorationNeverOverwritesANewFileAtTheOriginalPath() throws Exception {
        byte[] replacement = {9, 8, 7, 6};
        var replacing = new JdbcTemplate(source) {
            @Override public int update(String sql, Object... args) {
                if (sql.startsWith("DELETE FROM uploads")) {
                    try { Files.write(original, replacement); } catch (IOException e) { throw new AssertionError(e); }
                    throw new DataAccessResourceFailureException(PRIVATE_ERROR);
                }
                return super.update(sql, args);
            }
        };
        assertThatThrownBy(() -> service(replacing).delete(photo.id())).isInstanceOf(RuntimeException.class);
        assertThat(Files.readAllBytes(original)).containsExactly(replacement);
        assertThat(recoveryFiles()).hasSize(1);
        assertThat(Files.readAllBytes(recoveryFiles().getFirst())).containsExactly(bytes);
        assertThat(rows()).isEqualTo(1);
    }

    @Test
    void successfulDeletePurgesItsCopyButLeavesOlderRecoveryFilesAlone() throws Exception {
        Path old = Files.createDirectory(images.resolve(".delete-old-fixture"));
        Path oldPhoto = Files.write(old.resolve("image.jpg"), new byte[]{1, 3, 5});
        assertThat(uploads.delete(photo.id())).isTrue();
        assertThat(rows()).isZero();
        assertThat(Files.exists(original)).isFalse();
        assertThat(recoveryFiles()).containsExactly(oldPhoto);
        assertThat(Files.readAllBytes(oldPhoto)).containsExactly((byte) 1, (byte) 3, (byte) 5);
    }

    @Test
    void aPartialBackupFailureIsCleanedWithoutTouchingTheOriginal() throws Exception {
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.copy(eq(original), any(Path.class), any(CopyOption[].class)))
                    .thenAnswer(invocation -> { invocation.callRealMethod(); throw new IOException(PRIVATE_ERROR); });
            assertThatThrownBy(() -> uploads.delete(photo.id())).isInstanceOf(UploadService.DeleteUnavailable.class);
        }
        assertThat(rows()).isEqualTo(1);
        assertThat(Files.readAllBytes(original)).containsExactly(bytes);
        assertThat(recoveryFiles()).isEmpty();
    }

    @Test
    void inaccessibleFileMetadataIsNotTreatedAsAMissingPhoto() throws Exception {
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.readAttributes(eq(original), eq(BasicFileAttributes.class), any(LinkOption[].class)))
                    .thenThrow(new IOException(PRIVATE_ERROR));
            assertThatThrownBy(() -> uploads.delete(photo.id())).isInstanceOf(UploadService.DeleteUnavailable.class);
        }
        assertThat(rows()).isEqualTo(1);
        assertThat(Files.readAllBytes(original)).containsExactly(bytes);
        assertThat(recoveryFiles()).isEmpty();
    }

    @Test
    void finalCopyPurgeFailureDoesNotClaimCompleteDeletionOrRestoreCommittedMetadata() throws Exception {
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.deleteIfExists(argThat(path -> path != null && path.getFileName().toString().equals("image.backup"))))
                    .thenThrow(new IOException(PRIVATE_ERROR));
            assertThatThrownBy(() -> uploads.delete(photo.id())).isInstanceOf(UploadService.DeleteUnavailable.class);
        }
        assertThat(rows()).isZero();
        assertThat(Files.exists(original)).isFalse();
        assertThat(recoveryFiles()).hasSize(1);
        assertThat(Files.readAllBytes(recoveryFiles().getFirst())).containsExactly(bytes);
    }

    @Test
    void transactionBeginFailureLeavesTheOriginalAndRemovesOnlyItsUnusedCopy() throws Exception {
        assertThatThrownBy(() -> service(new JdbcTemplate(faultyConnections("begin"))).delete(photo.id()))
                .isInstanceOf(UploadService.DeleteUnavailable.class);
        assertThat(rows()).isEqualTo(1);
        assertThat(Files.readAllBytes(original)).containsExactly(bytes);
        assertThat(recoveryFiles()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"../outside", "jpg/../../outside", "jpg:stream"})
    void invalidLegacyExtensionsCannotRedirectDeleteOrRecoveryOutsideTheUploadDirectory(String extension) throws Exception {
        Path sentinel = directory.resolve("outside");
        Files.writeString(sentinel, "untouched fixture outside images");
        jdbc.update("UPDATE uploads SET ext=? WHERE id=?", extension, photo.id());
        assertThatThrownBy(() -> uploads.delete(photo.id())).isInstanceOf(UploadService.DeleteUnavailable.class);
        assertThat(rows()).isEqualTo(1);
        assertThat(Files.readAllBytes(original)).containsExactly(bytes);
        assertThat(Files.readString(sentinel)).isEqualTo("untouched fixture outside images");
        assertThat(recoveryFiles()).isEmpty();
    }

    @Test
    void symbolicLinkAttributesAreRejectedBeforeCopyOrUnlink() throws Exception {
        var attributes = mock(BasicFileAttributes.class);
        when(attributes.isRegularFile()).thenReturn(true);
        when(attributes.isSymbolicLink()).thenReturn(true);
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.readAttributes(eq(original), eq(BasicFileAttributes.class), any(LinkOption[].class)))
                    .thenReturn(attributes);
            assertThatThrownBy(() -> uploads.delete(photo.id())).isInstanceOf(UploadService.DeleteUnavailable.class);
            files.verify(() -> Files.deleteIfExists(any(Path.class)), never());
        }
        assertThat(rows()).isEqualTo(1);
        assertThat(Files.readAllBytes(original)).containsExactly(bytes);
    }

    @Test
    void concurrentServiceInstancesCannotRaceADeletionAgainstRollbackRestoration() throws Exception {
        var firstInside = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var secondRead = new CountDownLatch(1);
        var first = service(new JdbcTemplate(source) {
            @Override public int update(String sql, Object... args) {
                if (sql.startsWith("DELETE FROM uploads")) {
                    firstInside.countDown();
                    try { assertThat(releaseFirst.await(5, TimeUnit.SECONDS)).isTrue(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                    throw new DataAccessResourceFailureException(PRIVATE_ERROR);
                }
                return super.update(sql, args);
            }
        });
        var second = service(new JdbcTemplate(source) {
            @Override public <T> List<T> query(String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
                if (sql.startsWith("SELECT * FROM uploads")) secondRead.countDown();
                return super.query(sql, mapper, args);
            }
        });
        var executor = Executors.newFixedThreadPool(2);
        try {
            var failed = executor.submit(() -> assertThatThrownBy(() -> first.delete(photo.id())).isInstanceOf(UploadService.DeleteUnavailable.class));
            assertThat(firstInside.await(5, TimeUnit.SECONDS)).isTrue();
            var succeeded = executor.submit(() -> { secondStarted.countDown(); return second.delete(photo.id()); });
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(secondRead.await(150, TimeUnit.MILLISECONDS)).isFalse();
            releaseFirst.countDown();
            failed.get(5, TimeUnit.SECONDS);
            assertThat(succeeded.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(rows()).isZero();
            assertThat(allFiles()).isEmpty();
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private JdbcTemplate failingDelete(DataSource dataSource, String mode) {
        return new JdbcTemplate(dataSource) {
            @Override public int update(String sql, Object... args) {
                if (!sql.startsWith("DELETE FROM uploads")) return super.update(sql, args);
                if (mode.equals("after-delete") || mode.equals("zero-after-delete")) super.update(sql, args);
                if (mode.startsWith("zero")) return 0;
                throw new DataAccessResourceFailureException(PRIVATE_ERROR);
            }
        };
    }

    private UploadService service(JdbcTemplate template) {
        return new UploadService(template, images.toString(), 8_388_608, 7, user);
    }

    private int rows() { return jdbc.queryForObject("SELECT COUNT(*) FROM uploads", Integer.class); }

    private List<Path> allFiles() throws IOException {
        try (var paths = Files.walk(images)) { return paths.filter(Files::isRegularFile).sorted().toList(); }
    }

    private List<Path> recoveryFiles() throws IOException {
        return allFiles().stream().filter(path -> !path.getParent().equals(images)).toList();
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
