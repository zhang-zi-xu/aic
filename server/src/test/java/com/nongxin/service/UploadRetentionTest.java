package com.nongxin.service;

import com.nongxin.controller.UploadController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** All cleanup targets are generated JPEG fixtures inside this case's @TempDir. No app is started. */
class UploadRetentionTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 15, 12, 0, 0);
    private static final String OLD = NOW.minusDays(8).toString();
    @TempDir Path directory;
    private JdbcTemplate jdbc;
    private CurrentUser user;
    private UploadService uploads;
    private byte[] image;
    private MockedStatic<LocalDateTime> clock;

    @BeforeEach
    void setUp() throws Exception {
        var source = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("retention.db"));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        user = new CurrentUser();
        uploads = service(7);
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(240, 240, BufferedImage.TYPE_INT_RGB), "jpeg", bytes);
        image = bytes.toByteArray();
        clock = mockStatic(LocalDateTime.class, CALLS_REAL_METHODS);
        clock.when(LocalDateTime::now).thenReturn(NOW);
    }

    @AfterEach void close() { if (clock != null) clock.close(); }

    @ParameterizedTest
    @ValueSource(strings = {"local-owner", "u-a", "u-b"})
    void archivedAndReferencedPhotosSurviveWhileOnlyOldUnusedAttachmentsAreRemoved(String owner) throws Exception {
        jdbc.update("INSERT INTO fields (id,name,crop,sow_date,user_id) VALUES ('f-archive','测试田','测试作物','2026-09-01',?)", owner);
        jdbc.update("INSERT INTO farm_tasks (id,title,task_date,created_at,user_id) VALUES ('t-archive','测试任务','2026-09-15',?,?)", NOW.toString(), owner);
        seed("img-field", owner, OLD, "f-archive", null, null);
        seed("img-task", owner, OLD, null, "t-archive", null);
        seed("img-both", owner, OLD, "f-archive", "t-archive", null);
        seed("img-chat", owner, OLD, null, null, NOW.minusDays(7).toString());
        seed("img-unused", owner, OLD, null, null, null);
        seed("img-fresh", owner, NOW.minusDays(1).toString(), null, null, null);
        // Startup maintenance must not be scoped to the current local owner.
        var controller = new UploadController(uploads, mock(FieldService.class), user);
        controller.run(new DefaultApplicationArguments());
        for (String id : List.of("img-field", "img-task", "img-both", "img-chat", "img-fresh")) assertKept(id);
        assertRemoved("img-unused");
        assertThat(uploads.cleanupUnreferenced()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"dangling-field", "dangling-task", "blank-field", "blank-task", "blank-reference"})
    void nonNullArchiveOrReferenceMarkersAreKeptEvenWhenTheyAreUncertain(String kind) throws Exception {
        String field = kind.equals("dangling-field") ? "f-missing" : kind.equals("blank-field") ? "" : null;
        String task = kind.equals("dangling-task") ? "t-missing" : kind.equals("blank-task") ? " " : null;
        String reference = kind.equals("blank-reference") ? "" : null;
        seed("img-uncertain", "u-a", OLD, field, task, reference);
        var original = jdbc.queryForMap("SELECT * FROM uploads WHERE id='img-uncertain'");
        assertThat(uploads.cleanupUnreferenced()).isZero();
        assertKept("img-uncertain");
        assertThat(jdbc.queryForMap("SELECT * FROM uploads WHERE id='img-uncertain'")).isEqualTo(original);
    }

    @Test
    void cutoffIsStrictAndBasedOnUploadTimeNotObservationDate() throws Exception {
        seed("img-before", "u-a", NOW.minusDays(7).minusSeconds(1).toString(), null, null, null);
        seed("img-at", "u-a", NOW.minusDays(7).toString(), null, null, null);
        seed("img-after", "u-a", NOW.minusDays(7).plusSeconds(1).toString(), null, null, null);
        jdbc.update("UPDATE uploads SET observed_at='2020-01-01' WHERE id='img-after'");
        assertThat(uploads.cleanupUnreferenced()).isEqualTo(1);
        assertRemoved("img-before");
        assertKept("img-at");
        assertKept("img-after");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "0000-invalid", "2026-02-30T00:00:00"})
    void invalidUploadTimesAreNotEvidenceThatAFileIsExpired(String createdAt) throws Exception {
        seed("img-unknown-age", "u-a", createdAt, null, null, null);
        assertThat(uploads.cleanupUnreferenced()).isZero();
        assertKept("img-unknown-age");
    }

    @ParameterizedTest
    @ValueSource(strings = {"u-a", "u-b"})
    void persistedConversationReferencesProtectImagesEvenWithoutTheTimestampMarker(String conversationOwner) throws Exception {
        seed("img-saved", "u-a", OLD, null, null, null);
        seed("img-unused", "u-b", OLD, null, null, null);
        conversation("[{\"role\":\"user\",\"images\":[{\"id\":\"img-saved\"}]}]", conversationOwner);
        assertThat(uploads.cleanupUnreferenced()).isEqualTo(1);
        assertKept("img-saved");
        assertRemoved("img-unused");
        assertThat(jdbc.queryForObject("SELECT referenced_at FROM uploads WHERE id='img-saved'", String.class)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"broken json", "null", "{}", "[null]", "[42]", "[] []",
            "[{\"images\":{}}]", "[{\"images\":[null]}]", "[{\"images\":[{\"id\":\"\"}]}]",
            "[{\"images\":[{\"id\":\"img-maybe-used\"}],\"images\":[]}]"})
    void uncertainConversationDataSkipsCleanupBeforeAnyDeletion(String messages) throws Exception {
        seed("img-maybe-used", "u-a", OLD, null, null, null);
        seed("img-also-unknown", "u-b", OLD, null, null, null);
        conversation(messages, "u-a");
        assertThat(uploads.cleanupUnreferenced()).isZero();
        assertKept("img-maybe-used");
        assertKept("img-also-unknown");
    }

    @Test
    void unavailableConversationStoreDoesNotCauseUnverifiedDeletion() throws Exception {
        seed("img-maybe-used", "u-a", OLD, null, null, null);
        jdbc.execute("DROP TABLE conversations"); // Disposable fault fixture only.
        assertThat(uploads.cleanupUnreferenced()).isZero();
        assertKept("img-maybe-used");
    }

    @Test
    void cleanupDoesNotAskForAnInteractiveUserIdentity() throws Exception {
        seed("img-unused-a", "u-a", OLD, null, null, null);
        seed("img-unused-b", "u-b", OLD, null, null, null);
        user.setResolver(() -> { throw new AssertionError("System maintenance cannot depend on an interactive user"); });
        assertThat(uploads.cleanupUnreferenced()).isEqualTo(2);
        assertRemoved("img-unused-a");
        assertRemoved("img-unused-b");
    }

    @ParameterizedTest
    @ValueSource(strings = {"snapshot", "escaped-id", "inline-link"})
    void alternateSavedReferenceFormsAreRetained(String form) throws Exception {
        seed("img-saved", "u-a", OLD, null, null, null);
        seed("img-save", "u-a", OLD, null, null, null); // A prefix is not the same image ID.
        String messages = switch (form) {
            case "snapshot" -> "[{\"requestContext\":{\"imageIds\":[\"img-saved\"]}}]";
            case "escaped-id" -> "[{\"images\":[{\"id\":\"img-" + "\\" + "u0073aved\"}]}]";
            case "inline-link" -> "[{\"content\":\"![test](/api/uploads/img-saved)\"}]";
            default -> throw new AssertionError(form);
        };
        conversation(messages, "u-a");
        assertThat(uploads.cleanupUnreferenced()).isEqualTo(1);
        assertKept("img-saved");
        assertRemoved("img-save");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -7})
    void invalidRetentionSettingsDoNotTurnIntoImmediateDeletion(int days) throws Exception {
        seed("img-unused", "u-a", OLD, null, null, null);
        assertThat(service(days).cleanupUnreferenced()).isZero();
        assertKept("img-unused");
    }

    @Test
    void emptyCandidateSetNeedsNoConversationScan() {
        jdbc.execute("DROP TABLE conversations");
        assertThat(uploads.cleanupUnreferenced()).isZero();
    }

    private UploadService service(int retentionDays) {
        return new UploadService(jdbc, directory.resolve("images").toString(), 8_388_608, retentionDays, user);
    }

    private void seed(String id, String owner, String createdAt, String field, String task, String referenced) throws Exception {
        Path target = uploads.fileFor(id, "jpg").toAbsolutePath().normalize();
        assertThat(target.startsWith(directory.toAbsolutePath().normalize())).isTrue();
        Files.createDirectories(target.getParent());
        Files.write(target, image);
        jdbc.update("INSERT INTO uploads (id,mime,ext,bytes,width,height,created_at,field_id,task_id,referenced_at,user_id)"
                        + " VALUES (?,'image/jpeg','jpg',?,240,240,?,?,?,?,?)",
                id, image.length, createdAt, field, task, referenced, owner);
    }

    private void conversation(String messages, String owner) {
        jdbc.update("INSERT INTO conversations (id,title,messages_json,user_id) VALUES ('c-test','测试引用',?,?)", messages, owner);
    }

    private void assertKept(String id) throws Exception {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uploads WHERE id=?", Integer.class, id)).as(id).isEqualTo(1);
        assertThat(Files.readAllBytes(uploads.fileFor(id, "jpg"))).as(id).containsExactly(image);
    }

    private void assertRemoved(String id) {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uploads WHERE id=?", Integer.class, id)).as(id).isZero();
        assertThat(Files.exists(uploads.fileFor(id, "jpg"))).as(id).isFalse();
    }
}
