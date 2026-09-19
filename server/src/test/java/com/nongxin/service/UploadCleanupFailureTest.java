package com.nongxin.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** No application startup: cleanup only sees this test's fresh SQLite database and synthetic files. */
class UploadCleanupFailureTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 16, 12, 0);
    private static final String OLD = NOW.minusDays(8).toString();
    private static final String PRIVATE_ERROR = "synthetic-private-filesystem-detail";
    @TempDir Path directory;
    private JdbcTemplate jdbc;
    private UploadService uploads;
    private byte[] image;
    private MockedStatic<LocalDateTime> clock;

    @BeforeEach
    void setUp() throws Exception {
        var source = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("cleanup.db"));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        jdbc = spy(new JdbcTemplate(source));
        var user = new CurrentUser();
        user.setResolver(() -> { throw new AssertionError("Maintenance must not resolve an interactive user"); });
        uploads = new UploadService(jdbc, directory.resolve("images").toString(), 8_388_608, 7, user);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(240, 240, BufferedImage.TYPE_INT_RGB), "jpeg", bytes);
        image = bytes.toByteArray();
        clock = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS);
        clock.when(LocalDateTime::now).thenReturn(NOW);
    }

    @AfterEach void close() { if (clock != null) clock.close(); }

    @ParameterizedTest
    @ValueSource(strings = {"io", "access-denied", "security"})
    void failureKeepsItsRowAndBytesWhileOtherEligibleOwnersCanBeCleanedAndRetried(String mode) throws Exception {
        seed("img-locked", "u-a", OLD);
        seed("img-ready", "u-b", OLD);
        seed("img-archive", "u-c", OLD);
        seed("img-reference", "u-c", OLD);
        seed("img-fresh", "u-c", NOW.minusDays(1).toString());
        jdbc.update("UPDATE uploads SET field_id='f-existing-marker' WHERE id='img-archive'");
        jdbc.update("INSERT INTO conversations (id,title,messages_json,user_id) VALUES ('c-ref','fixture',?, 'u-other')",
                "[{\"images\":[{\"id\":\"img-reference\"}]}]");
        var originalRow = jdbc.queryForMap("SELECT * FROM uploads WHERE id='img-locked'");
        Path target = path("img-locked");
        Throwable failure = switch (mode) {
            case "io" -> new IOException(PRIVATE_ERROR + target);
            case "access-denied" -> new AccessDeniedException(target.toString(), null, PRIVATE_ERROR);
            case "security" -> new SecurityException(PRIVATE_ERROR + target);
            default -> throw new AssertionError(mode);
        };
        Logger logger = (Logger) LoggerFactory.getLogger(UploadService.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            // A matcher avoids executing a real deletion while registering a CALLS_REAL_METHODS stub.
            files.when(() -> Files.deleteIfExists(eq(target))).thenThrow(failure);
            assertKept("img-locked");
            assertThat(uploads.cleanupUnreferenced()).isEqualTo(1);
            assertKept("img-locked");
            assertThat(jdbc.queryForMap("SELECT * FROM uploads WHERE id='img-locked'")).isEqualTo(originalRow);
            verify(jdbc, never()).update(eq("DELETE FROM uploads WHERE id=?"), eq("img-locked"));
            assertRemoved("img-ready");
            for (String id : List.of("img-archive", "img-reference", "img-fresh")) assertKept(id);
            files.verify(() -> Files.deleteIfExists(target), times(1));
            assertThat(logs.list).anyMatch(event -> event.getFormattedMessage().contains("img-locked"));
            assertThat(logs.list).noneMatch(event -> event.getFormattedMessage().contains(PRIVATE_ERROR)
                    || event.getFormattedMessage().contains(directory.toString()) || event.getThrowableProxy() != null);
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
        assertThat(uploads.cleanupUnreferenced()).isEqualTo(1);
        assertRemoved("img-locked");
        for (String id : List.of("img-archive", "img-reference", "img-fresh")) assertKept(id);
        assertThat(uploads.cleanupUnreferenced()).isZero();
    }

    @Test
    void realNonEmptyDirectoryFailureKeepsTheRowAndNeverRecursivelyDeletesItsContents() throws Exception {
        seed("img-directory", "u-a", OLD);
        Path target = path("img-directory");
        Files.delete(target); // Only this case's generated fixture is replaced.
        Files.createDirectory(target);
        Path child = target.resolve("keep.jpg");
        Files.write(child, image);
        assertThat(uploads.cleanupUnreferenced()).isZero();
        assertRegistered("img-directory");
        assertThat(Files.isDirectory(target)).isTrue();
        assertThat(Files.readAllBytes(child)).containsExactly(image);
        verify(jdbc, never()).update(eq("DELETE FROM uploads WHERE id=?"), eq("img-directory"));
    }

    @Test
    void alreadyMissingFileCanRemoveItsStaleRowWithoutScanningRecoveryOrOrphanFiles() throws Exception {
        seed("img-missing", "u-a", OLD);
        Files.delete(path("img-missing"));
        Path recovery = directory.resolve("images/.delete-img-missing-fixture");
        Files.createDirectory(recovery);
        Path backup = recovery.resolve("image.backup");
        Files.write(backup, image);
        Path orphan = directory.resolve("images/img-unregistered.jpg");
        Files.write(orphan, image);
        assertThat(uploads.cleanupUnreferenced()).isEqualTo(1);
        assertRemoved("img-missing");
        assertThat(uploads.cleanupUnreferenced()).isZero();
        assertThat(Files.readAllBytes(backup)).containsExactly(image);
        assertThat(Files.readAllBytes(orphan)).containsExactly(image);
    }

    @Test
    void failureReportedAfterUnlinkKeepsRegistrationAndRestoresBytesOnKnownRollback() throws Exception {
        seed("img-uncertain", "u-a", OLD);
        Path target = path("img-uncertain");
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.deleteIfExists(eq(target))).thenAnswer(call -> {
                call.callRealMethod();
                throw new IOException(PRIVATE_ERROR);
            });
            assertKept("img-uncertain");
            assertThat(uploads.cleanupUnreferenced()).isZero();
            assertRegistered("img-uncertain");
            assertThat(Files.readAllBytes(target)).containsExactly(image);
            verify(jdbc, never()).update(eq("DELETE FROM uploads WHERE id=?"), eq("img-uncertain"));
        }
    }

    @Test
    void batchOfOnlyFailuresReportsZeroAndKeepsEveryRegistration() throws Exception {
        seed("img-failed-a", "u-a", OLD);
        seed("img-failed-b", "u-b", OLD);
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.deleteIfExists(any(Path.class))).thenThrow(new IOException(PRIVATE_ERROR));
            assertKept("img-failed-a");
            assertKept("img-failed-b");
            assertThat(uploads.cleanupUnreferenced()).isZero();
            assertKept("img-failed-a");
            assertKept("img-failed-b");
            files.verify(() -> Files.deleteIfExists(path("img-failed-a")), times(1));
            files.verify(() -> Files.deleteIfExists(path("img-failed-b")), times(1));
            verify(jdbc, never()).update(eq("DELETE FROM uploads WHERE id=?"), any(Object.class));
        }
    }

    private Path path(String id) {
        Path target = uploads.fileFor(id, "jpg");
        assertThat(target.toAbsolutePath().normalize().startsWith(directory.toAbsolutePath().normalize())).isTrue();
        return target;
    }

    private void seed(String id, String owner, String createdAt) throws Exception {
        Path target = path(id);
        Files.createDirectories(target.getParent());
        Files.write(target, image);
        jdbc.update("INSERT INTO uploads (id,mime,ext,bytes,width,height,created_at,user_id)"
                + " VALUES (?,'image/jpeg','jpg',?,240,240,?,?)", id, image.length, createdAt, owner);
    }

    private void assertRegistered(String id) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uploads WHERE id=?", Integer.class, id)).as(id).isEqualTo(1);
    }

    private void assertKept(String id) throws Exception {
        assertRegistered(id);
        assertThat(Files.readAllBytes(path(id))).as(id).containsExactly(image);
    }

    private void assertRemoved(String id) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uploads WHERE id=?", Integer.class, id)).as(id).isZero();
        assertThat(Files.exists(path(id))).as(id).isFalse();
    }
}
