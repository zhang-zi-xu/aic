package com.nongxin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nongxin.service.CurrentUser;
import com.nongxin.service.FieldService;
import com.nongxin.service.UploadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Only generated photos and a fresh database in this case's @TempDir are ever deleted. */
class UploadDeleteSafetyTest {
    private static final String PRIVATE_ERROR = "synthetic-private-path-and-error";
    @TempDir Path directory;
    private final CurrentUser user = new CurrentUser();
    private final AtomicReference<String> actor = new AtomicReference<>("u-a");
    private final AtomicInteger deleteCalls = new AtomicInteger();
    private Integer forcedDeleteCount;
    private JdbcTemplate jdbc;
    private UploadService uploads;
    private UploadService.Stored photoA;
    private UploadService.Stored photoB;
    private Path pathA;
    private Path pathB;
    private byte[] bytesA;
    private byte[] bytesB;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        var source = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("delete.db"));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        jdbc = new JdbcTemplate(source) {
            @Override public int update(String sql, Object... args) {
                if (sql.startsWith("DELETE FROM uploads")) {
                    deleteCalls.incrementAndGet();
                    if (forcedDeleteCount != null) return forcedDeleteCount;
                }
                return super.update(sql, args);
            }
        };
        user.setResolver(actor::get);
        uploads = new UploadService(jdbc, directory.resolve("images").toString(), 8_388_608, 7, user);
        mvc = controller(uploads);
        var buffer = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(240, 240, BufferedImage.TYPE_INT_RGB), "png", buffer);
        for (String suffix : List.of("a", "b")) {
            actor.set("u-" + suffix);
            jdbc.update("INSERT INTO fields (id,name,crop,sow_date,user_id) VALUES (?,?,?,?,?)",
                    "f-" + suffix, "合成测试田", "合成作物", "2026-09-01", actor.get());
            jdbc.update("INSERT INTO farm_tasks (id,title,task_date,created_at,user_id) VALUES (?,?,?,?,?)",
                    "t-" + suffix, "合成测试任务", "2026-09-01", "2026-09-01T00:00:00", actor.get());
            var photo = uploads.store(buffer.toByteArray(), "image/png", "f-" + suffix, null, "合成测试照片", "t-" + suffix);
            String messages = new ObjectMapper().writeValueAsString(List.of(Map.of("id", "m-" + suffix,
                    "role", "user", "content", "合成图片引用", "images", List.of(Map.of("id", photo.id())))));
            jdbc.update("INSERT INTO conversations (id,title,messages_json,created_at,user_id) VALUES (?,?,?,?,?)",
                    "c-" + suffix, "合成测试对话", messages, "2026-09-01T00:00:00Z", actor.get());
            if (suffix.equals("a")) photoA = photo;
            else photoB = photo;
        }
        actor.set("u-a");
        pathA = uploads.fileFor(photoA.id(), "jpg");
        pathB = uploads.fileFor(photoB.id(), "jpg");
        bytesA = Files.readAllBytes(pathA);
        bytesB = Files.readAllBytes(pathB);
    }

    @ParameterizedTest
    @ValueSource(strings = {"io", "access-denied", "security"})
    void fileFailureKeepsMetadataAndBytesAndAllowsALaterRetry(String kind) throws Exception {
        var original = snapshot();
        Throwable failure = switch (kind) {
            case "access-denied" -> new AccessDeniedException(PRIVATE_ERROR);
            case "security" -> new SecurityException(PRIVATE_ERROR);
            default -> new IOException(PRIVATE_ERROR);
        };
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            // Use a matcher: CALLS_REAL_METHODS must not unlink the fixture while the stub is being registered.
            files.when(() -> Files.deleteIfExists(eq(pathA))).thenThrow(failure);
            assertThat(Files.readAllBytes(pathA)).containsExactly(bytesA);
            assertDeleteUnavailable(mvc);
            assertThatThrownBy(() -> uploads.delete(photoA.id())).isInstanceOf(UploadService.DeleteUnavailable.class)
                    .hasMessageNotContaining(PRIVATE_ERROR).hasNoCause();
        }
        assertThat(deleteCalls.get()).isZero();
        assertThat(snapshot()).isEqualTo(original);
        assertThat(Files.readAllBytes(pathA)).containsExactly(bytesA);
        assertUnrelatedKept(original);
        mvc.perform(delete("/api/uploads/" + photoA.id())).andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));
        assertThat(deleteCalls.get()).isEqualTo(1);
        assertThat(Files.exists(pathA)).isFalse();
        assertThat(uploads.get(photoA.id())).isNull();
        assertUnrelatedKept(original);
    }

    @Test
    void aRealNonEmptyDirectoryAtTheImagePathIsNotRemovedOrUnregistered() throws Exception {
        var original = snapshot();
        Files.delete(pathA); // Only this case's generated fixture, never user data.
        Files.createDirectory(pathA);
        Path child = pathA.resolve("keep.txt");
        Files.writeString(child, "synthetic content must survive");
        assertDeleteUnavailable(mvc);
        assertThat(deleteCalls.get()).isZero();
        assertThat(snapshot()).isEqualTo(original);
        assertThat(Files.readString(child)).isEqualTo("synthetic content must survive");
        assertUnrelatedKept(original);
    }

    @Test
    void aFailureReportedAfterUnlinkStillStopsBeforeMetadataDeletion() throws Exception {
        var original = snapshot();
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.deleteIfExists(eq(pathA))).thenAnswer(invocation -> {
                invocation.callRealMethod();
                throw new IOException(PRIVATE_ERROR);
            });
            assertDeleteUnavailable(mvc);
        }
        assertThat(deleteCalls.get()).isZero();
        assertThat(snapshot()).isEqualTo(original);
        assertUnrelatedKept(original);
        assertThat(Files.readAllBytes(pathA)).containsExactly(bytesA);
    }

    static Stream<Object[]> normalDeletes() {
        return Stream.of(new Object[]{false, false}, new Object[]{false, true},
                new Object[]{true, false}, new Object[]{true, true});
    }

    @ParameterizedTest
    @MethodSource("normalDeletes")
    void ownedArchivedPhotosCanBeDeletedEvenIfReferencedOrAlreadyMissing(boolean referenced, boolean missing) throws Exception {
        if (referenced) uploads.markReferenced(List.of(photoA.id()));
        if (missing) Files.delete(pathA);
        var original = snapshot();
        mvc.perform(delete("/api/uploads/" + photoA.id())).andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));
        assertThat(deleteCalls.get()).isEqualTo(1);
        assertThat(uploads.get(photoA.id())).isNull();
        assertThat(Files.exists(pathA)).isFalse();
        assertUnrelatedKept(original);
        mvc.perform(delete("/api/uploads/" + photoA.id())).andExpect(status().isNotFound());
        assertThat(deleteCalls.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"foreign", "missing", "no-identity"})
    void deniedRequestsNeverAttemptFileOrMetadataDeletion(String kind) throws Exception {
        var original = snapshot();
        if (kind.equals("no-identity")) actor.set(null);
        String id = kind.equals("foreign") ? photoB.id() : kind.equals("missing") ? "img-missing" : photoA.id();
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            var result = mvc.perform(delete("/api/uploads/" + id).header("X-User-Id", "u-b").param("userId", "u-b"));
            if (kind.equals("no-identity")) result.andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("IDENTITY_UNAVAILABLE"));
            else result.andExpect(status().isNotFound());
            files.verify(() -> Files.deleteIfExists(any(Path.class)), never());
        }
        assertThat(deleteCalls.get()).isZero();
        assertThat(snapshot()).isEqualTo(original);
        assertThat(Files.readAllBytes(pathA)).containsExactly(bytesA);
        assertUnrelatedKept(original);
    }

    @Test
    void aRealZeroRowDeleteIsNotReportedAsSuccess() throws Exception {
        jdbc.execute("CREATE TRIGGER ignore_delete BEFORE DELETE ON uploads BEGIN SELECT RAISE(IGNORE); END");
        var original = snapshot();
        assertDeleteUnavailable(mvc);
        assertThat(deleteCalls.get()).isEqualTo(1);
        assertThat(snapshot()).isEqualTo(original);
        assertUnrelatedKept(original);
        assertThat(Files.readAllBytes(pathA)).containsExactly(bytesA);
    }

    @Test
    void databaseDeleteFailureReturnsSafe503AndRestoresThePhoto() throws Exception {
        jdbc.execute("CREATE TRIGGER fail_delete BEFORE DELETE ON uploads BEGIN SELECT RAISE(ABORT,'synthetic failure'); END");
        var original = snapshot();
        assertDeleteUnavailable(mvc);
        assertThat(snapshot()).isEqualTo(original);
        assertThat(Files.readAllBytes(pathA)).containsExactly(bytesA);
        assertUnrelatedKept(original);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 2})
    void serviceOnlyConfirmsExactlyOneAffectedMetadataRow(int affected) throws Exception {
        forcedDeleteCount = affected;
        var original = snapshot();
        assertThat(uploads.delete(photoA.id())).isFalse();
        assertThat(deleteCalls.get()).isEqualTo(1);
        assertThat(snapshot()).isEqualTo(original);
        assertUnrelatedKept(original);
    }

    @Test
    void controllerDoesNotConvertAFalseServiceResultIntoSuccess() throws Exception {
        var storage = mock(UploadService.class);
        when(storage.delete(photoA.id())).thenReturn(false);
        var original = snapshot();
        assertDeleteUnavailable(controller(storage));
        verify(storage).delete(photoA.id());
        assertThat(snapshot()).isEqualTo(original);
        assertThat(Files.readAllBytes(pathA)).containsExactly(bytesA);
        assertUnrelatedKept(original);
    }

    private MockMvc controller(UploadService storage) {
        return MockMvcBuilders.standaloneSetup(new UploadController(storage, mock(FieldService.class), user))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private void assertDeleteUnavailable(MockMvc target) throws Exception {
        var response = target.perform(delete("/api/uploads/" + photoA.id()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value("UPLOAD_DELETE_UNAVAILABLE"))
                .andExpect(jsonPath("$.error").value("图片删除暂时无法确认，请刷新影像列表核对后再重试"))
                .andExpect(jsonPath("$.deleted").doesNotExist()).andReturn().getResponse();
        String body = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).doesNotContain(PRIVATE_ERROR, directory.toString(), "IOException", "SecurityException");
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        var result = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (String table : List.of("fields", "farm_tasks", "conversations", "uploads"))
            result.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
        return result;
    }

    private void assertUnrelatedKept(Map<String, List<Map<String, Object>>> original) throws Exception {
        var current = snapshot();
        for (String table : List.of("fields", "farm_tasks", "conversations"))
            assertThat(current.get(table)).isEqualTo(original.get(table));
        assertThat(current.get("uploads").stream().filter(row -> photoB.id().equals(row.get("id"))).toList())
                .isEqualTo(original.get("uploads").stream().filter(row -> photoB.id().equals(row.get("id"))).toList());
        assertThat(Files.readAllBytes(pathB)).containsExactly(bytesB);
    }
}
