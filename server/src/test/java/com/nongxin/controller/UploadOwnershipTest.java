package com.nongxin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nongxin.service.CurrentUser;
import com.nongxin.service.UploadService;
import com.nongxin.service.impl.FieldServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Ownership checks with synthetic images and a fresh DB per case, not an authentication test. */
class UploadOwnershipTest {
    @TempDir Path directory;
    private final CurrentUser currentUser = new CurrentUser();
    private final AtomicReference<String> actor = new AtomicReference<>("u-a");
    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate jdbc;
    private UploadService uploads;
    private MockMvc mvc;
    private byte[] png;
    private UploadService.Stored photoA;
    private UploadService.Stored photoB;

    @BeforeEach
    void setUp() throws Exception {
        var source = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("ownership.db"));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        currentUser.setResolver(actor::get);
        uploads = new UploadService(jdbc, directory.resolve("images").toString(), 8_388_608, 7, currentUser);
        mvc = MockMvcBuilders.standaloneSetup(new UploadController(uploads, new FieldServiceImpl(jdbc, currentUser), currentUser))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        for (String suffix : List.of("a", "b")) {
            jdbc.update("INSERT INTO fields (id,name,crop,sow_date,user_id) VALUES (?,?,?,?,?)",
                    "f-" + suffix, "测试田", "测试作物", "2026-09-01", "u-" + suffix);
            jdbc.update("INSERT INTO farm_tasks (id,title,task_date,created_at,user_id) VALUES (?,?,?,?,?)",
                    "t-" + suffix, "测试任务", "2026-09-15", "2026-09-15T00:00:00", "u-" + suffix);
        }
        var buffer = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(240, 240, BufferedImage.TYPE_INT_RGB), "png", buffer);
        png = buffer.toByteArray();
        photoA = uploads.store(png, "image/png", "f-a", "2026-09-15", "甲的照片", "t-a");
        actor.set("u-b");
        photoB = uploads.store(png, "image/png", "f-b", "2026-09-15", "乙的照片", "t-b");
        actor.set("u-a");
    }

    @Test
    void otherOwnersPhotosCannotBeReadChangedDeletedOrMarkedReferenced() throws Exception {
        byte[] kept = Files.readAllBytes(uploads.fileFor(photoB.id(), "jpg"));
        assertThat(uploads.get(photoB.id())).isNull();
        assertThat(uploads.read(photoB.id())).isNull();
        assertThat(uploads.list()).extracting(UploadService.Stored::id).containsExactly(photoA.id());
        assertThat(uploads.byField("f-b", 10)).isEmpty();
        assertThat(uploads.latestForField("f-b", 3)).isEmpty();
        assertThat(uploads.find(List.of(photoB.id(), photoA.id(), "img-missing")))
                .extracting(UploadService.Stored::id).containsExactly(photoA.id());
        assertThat(uploads.markReferenced(List.of(photoB.id(), "img-missing"))).isZero();
        assertThatThrownBy(() -> uploads.updateArchive(photoB.id(), "changed", "2026-09-16", "f-a"))
                .isInstanceOf(java.util.NoSuchElementException.class);
        assertThatThrownBy(() -> uploads.delete(photoB.id())).isInstanceOf(java.util.NoSuchElementException.class);
        actor.set("u-b");
        assertThat(uploads.get(photoB.id())).isEqualTo(photoB);
        assertThat(uploads.read(photoB.id())).containsExactly(kept);
    }

    @Test
    void usageDoesNotDiscloseAnotherOwnersTotals() {
        assertThat(((Number) uploads.usage("f-b").get("photos")).intValue()).isZero();
        assertThat(((Number) uploads.usage("f-b").get("bytes")).longValue()).isZero();
    }

    @Test
    void fieldStatisticsAndPhotosUseTheSameOwnerEvenForLegacyCrossLinkedRows() throws Exception {
        // Simulate a legacy bad association; do not repair or delete it as part of a read.
        jdbc.update("UPDATE uploads SET field_id='f-a' WHERE id=?", photoB.id());
        mvc.perform(get("/api/uploads/field/f-a"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.photos.length()").value(1))
                .andExpect(jsonPath("$.usage.photos").value(1))
                .andExpect(jsonPath("$.usage.bytes").value(photoA.bytes()));
        assertThat(jdbc.queryForObject("SELECT field_id FROM uploads WHERE id=?", String.class, photoB.id())).isEqualTo("f-a");
    }

    static Stream<Object[]> invalidLinks() {
        return Stream.of("foreign-field", "missing-field", "foreign-task", "missing-task")
                .flatMap(kind -> Stream.of(new Object[] {false, kind}, new Object[] {true, kind}));
    }

    @ParameterizedTest
    @MethodSource("invalidLinks")
    void invalidAssociationsAreRejectedBeforeAnyFileOrRowIsCreated(boolean http, String kind) throws Exception {
        String field = kind.endsWith("field") ? (kind.startsWith("foreign") ? "f-b" : "f-missing") : "f-a";
        String task = kind.endsWith("task") ? (kind.startsWith("foreign") ? "t-b" : "t-missing") : "t-a";
        if (http) {
            mvc.perform(multipart("/api/uploads").file(image()).param("fieldId", field).param("taskId", task))
                    .andExpect(status().isBadRequest());
        } else {
            assertThatThrownBy(() -> uploads.store(png, "image/png", field, null, "invalid", task))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertOnlyOriginalPhotosRemain();
    }

    static Stream<Object[]> invalidArchiveLinks() {
        return Stream.of("f-b", "f-missing")
                .flatMap(field -> Stream.of(new Object[] {false, field}, new Object[] {true, field}));
    }

    @ParameterizedTest
    @MethodSource("invalidArchiveLinks")
    void archiveChangesCannotAttachToForeignOrMissingFields(boolean http, String field) throws Exception {
        if (http) {
            mvc.perform(patch("/api/uploads/" + photoA.id()).contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsBytes(Map.of("fieldId", field, "note", "changed"))))
                    .andExpect(status().isBadRequest());
        } else {
            assertThatThrownBy(() -> uploads.updateArchive(photoA.id(), "changed", null, field))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(uploads.get(photoA.id())).isEqualTo(photoA);
        assertOnlyOriginalPhotosRemain();
    }

    @ParameterizedTest
    @ValueSource(strings = {"read", "edit", "delete"})
    void httpForeignIdsAreIndistinguishableFromMissingIds(String operation) throws Exception {
        String previousBody = null;
        for (String id : List.of(photoB.id(), "img-missing")) {
            var request = switch (operation) {
                case "edit" -> patch("/api/uploads/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"changed\"}");
                case "delete" -> delete("/api/uploads/" + id);
                default -> get("/api/uploads/" + id);
            };
            var response = mvc.perform(request.header("X-User-Id", "u-b").param("userId", "u-b"))
                    .andExpect(status().isNotFound()).andReturn().getResponse();
            if (previousBody != null) assertThat(response.getContentAsByteArray()).isEqualTo(previousBody.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            previousBody = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        }
        assertOnlyOriginalPhotosRemain();
    }

    @Test
    void ownerCanUploadReadEditDetachAndDeleteTheirOwnReferencedPhoto() throws Exception {
        String response = mvc.perform(multipart("/api/uploads").file(image()).param("fieldId", "f-a").param("taskId", "t-a"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.taskId").value("t-a"))
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(response).path("id").asText();
        mvc.perform(get("/api/uploads/" + id)).andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_JPEG));
        mvc.perform(patch("/api/uploads/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"fieldId\":\"\",\"note\":\"updated\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.fieldId").value(""))
                .andExpect(jsonPath("$.note").value("updated"));
        assertThat(uploads.markReferenced(List.of(id))).isEqualTo(1);
        mvc.perform(delete("/api/uploads/" + id)).andExpect(status().isOk());
        mvc.perform(get("/api/uploads/" + id)).andExpect(status().isNotFound());
        assertThat(Files.exists(uploads.fileFor(id, "jpg"))).isFalse();
        assertOnlyOriginalPhotosRemain();
    }

    @Test
    void imageBytesAreNotPubliclyOrPersistentlyCacheable() throws Exception {
        for (String id : List.of(photoA.id(), "img-missing")) {
            var response = mvc.perform(get("/api/uploads/" + id)).andReturn().getResponse();
            assertThat(response.getHeader("Cache-Control")).contains("no-store").doesNotContain("public", "immutable", "max-age");
        }
    }

    @Test
    void singleServiceOperationKeepsTheOwnerItInitiallyResolved() {
        var calls = new AtomicInteger();
        currentUser.setResolver(() -> calls.getAndIncrement() == 0 ? "u-a" : "u-b");
        var stored = uploads.store(png, "image/png", "f-a", null, "snapshot", "t-a");
        assertThat(stored).isNotNull();
        assertThat(jdbc.queryForObject("SELECT user_id FROM uploads WHERE id=?", String.class, stored.id())).isEqualTo("u-a");
        assertThat(calls.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"find", "mark", "edit", "delete"})
    void compoundOperationsDoNotResolveADifferentOwnerHalfwayThrough(String operation) throws Exception {
        var calls = new AtomicInteger();
        currentUser.setResolver(() -> calls.getAndIncrement() == 0 ? "u-a" : "u-b");
        switch (operation) {
            case "find" -> assertThat(uploads.find(List.of(photoA.id(), photoB.id())))
                    .extracting(UploadService.Stored::id).containsExactly(photoA.id());
            case "mark" -> assertThat(uploads.markReferenced(List.of(photoA.id(), photoB.id()))).isEqualTo(1);
            case "edit" -> assertThat(uploads.updateArchive(photoA.id(), "snapshot", null, "f-a").note()).isEqualTo("snapshot");
            case "delete" -> {
                assertThat(uploads.delete(photoA.id())).isTrue();
                assertThat(Files.exists(uploads.fileFor(photoA.id(), "jpg"))).isFalse();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uploads WHERE id=?", Integer.class, photoA.id())).isZero();
            }
            default -> throw new AssertionError(operation);
        }
        assertThat(calls.get()).isEqualTo(1);
        currentUser.setResolver(() -> "u-b");
        assertThat(uploads.get(photoB.id())).isEqualTo(photoB);
        assertThat(uploads.read(photoB.id())).isNotEmpty();
    }

    @Test
    void failedTaskLookupCannotCreateAnImageOrMetadataRow() throws Exception {
        // Fault only in this test's disposable DB, never the user's database.
        jdbc.execute("DROP TABLE farm_tasks");
        mvc.perform(multipart("/api/uploads").file(image()).param("fieldId", "f-a").param("taskId", "t-a"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error").value("数据保存服务暂时不可用，请稍后重试"));
        assertOnlyOriginalPhotosRemain();
    }

    @Test
    void httpListsAndFieldGateDoNotAcceptClientSuppliedIdentity() throws Exception {
        mvc.perform(get("/api/uploads").header("X-User-Id", "u-b").param("userId", "u-b"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(photoA.id()));
        for (String field : List.of("f-b", "f-missing")) {
            mvc.perform(get("/api/uploads/field/" + field)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("关联田块不存在"));
        }
    }

    @Test
    void localDefaultIsStillSharedIdentityNotAuthentication() throws Exception {
        // No login was added: only explicitly configured resolver failures are denied.
        currentUser.reset();
        var local = uploads.store(png, "image/png", null, null, "local fixture", null);
        assertThat(currentUser.id()).isEqualTo(CurrentUser.LOCAL_OWNER);
        assertThat(uploads.get(local.id())).isNotNull();
        assertThat(uploads.get(photoA.id())).isNull();
        mvc.perform(get("/api/uploads/" + local.id()).with(request -> { request.setRemoteAddr(null); return request; }))
                .andExpect(status().isOk()); // No account or source authentication exists for this endpoint yet.
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "empty", "blank", "exception"})
    void identityFailureCannotReadOrMutateLocalPhotosOverHttp(String failure) throws Exception {
        currentUser.reset();
        var local = uploads.store(png, "image/png", null, null, "local fixture", null);
        byte[] bytes = uploads.read(local.id());
        var originalRows = jdbc.queryForList("SELECT * FROM uploads ORDER BY id");
        currentUser.setResolver(() -> switch (failure) {
            case "null" -> null;
            case "empty" -> "";
            case "blank" -> " \t\n";
            default -> throw new IllegalArgumentException("private resolver detail");
        });
        var requests = List.of(
                get("/api/uploads"),
                get("/api/uploads/" + local.id()),
                get("/api/uploads/" + photoA.id()),
                get("/api/uploads/img-missing"),
                get("/api/uploads/field/f-a"),
                multipart("/api/uploads").file(image()),
                patch("/api/uploads/" + local.id()).contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"changed\"}"),
                delete("/api/uploads/" + local.id()));
        for (var request : requests) {
            mvc.perform(request.header("X-User-Id", CurrentUser.LOCAL_OWNER).param("userId", CurrentUser.LOCAL_OWNER))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.code").value("IDENTITY_UNAVAILABLE"))
                    .andExpect(jsonPath("$.error").value("当前用户身份暂时无法确认，请稍后重试"));
            assertThat(jdbc.queryForList("SELECT * FROM uploads ORDER BY id")).isEqualTo(originalRows);
        }
        assertThat(Files.readAllBytes(uploads.fileFor(local.id(), "jpg"))).containsExactly(bytes);
        try (var files = Files.list(directory.resolve("images"))) {
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                    .containsExactlyInAnyOrder(local.id() + ".jpg", photoA.id() + ".jpg", photoB.id() + ".jpg");
        }
        currentUser.reset();
        assertThat(uploads.get(local.id())).isEqualTo(local);
        mvc.perform(get("/api/uploads/" + local.id())).andExpect(status().isOk());
    }

    private MockMultipartFile image() { return new MockMultipartFile("file", "test.png", "image/png", png); }

    private void assertOnlyOriginalPhotosRemain() throws Exception {
        assertThat(jdbc.queryForList("SELECT id FROM uploads", String.class)).containsExactlyInAnyOrder(photoA.id(), photoB.id());
        try (var files = Files.list(directory.resolve("images"))) {
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                    .containsExactlyInAnyOrder(photoA.id() + ".jpg", photoB.id() + ".jpg");
        }
    }
}
