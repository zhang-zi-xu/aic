package com.nongxin.controller;

import com.nongxin.service.CurrentUser;
import com.nongxin.service.impl.FieldServiceImpl;
import com.nongxin.service.UploadService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real HTTP/service behavior, generated photos and disposable SQLite only. */
class UploadArchiveSafetyTest {
    private static final String IMAGE = "img-edit";
    private static final String PRIVATE_ERROR = "synthetic-private-sql-or-path";
    @TempDir Path directory;
    private DriverManagerDataSource source;
    private JdbcTemplate jdbc;
    private CurrentUser user;
    private Path images;
    private Path original;
    private byte[] bytes;

    @BeforeEach
    void setUp() throws Exception {
        source = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("archive.db"));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        user = new CurrentUser();
        user.setResolver(() -> "u-a");
        images = Files.createDirectory(directory.resolve("images"));
        original = images.resolve(IMAGE + ".jpg");
        var buffer = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(240, 240, BufferedImage.TYPE_INT_RGB), "jpeg", buffer);
        bytes = buffer.toByteArray();
        Files.write(original, bytes);
        Files.write(images.resolve("img-other.jpg"), bytes);
        for (String owner : List.of("a", "b")) {
            jdbc.update("INSERT INTO fields (id,name,crop,sow_date,user_id) VALUES (?,?,?,?,?)",
                    "f-" + owner, "合成测试田", "合成作物", "2026-09-01", "u-" + owner);
            jdbc.update("INSERT INTO uploads (id,mime,ext,bytes,width,height,created_at,note,user_id)"
                            + " VALUES (?,'image/jpeg','jpg',?,240,240,?,'original',?)",
                    owner.equals("a") ? IMAGE : "img-other", bytes.length,
                    LocalDateTime.now().minusDays(owner.equals("a") ? 30 : 1).toString(), "u-" + owner);
        }
    }

    @ParameterizedTest
    @CsvSource({"delete,cleanup", "wal,cleanup", "delete,manual", "wal,manual"})
    void deletionCommittedAfterServiceCheckReturnsReadableConflict(String journal, String operation) throws Exception {
        assertThat(jdbc.queryForObject("PRAGMA journal_mode=" + journal, String.class)).isEqualTo(journal);
        var checked = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var checking = new JdbcTemplate(source) {
            private int reads;
            @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
                List<T> result = super.query(sql, mapper, args);
                // First read is the controller's PATCH merge; second is the service's ownership check.
                if (sql.equals("SELECT * FROM uploads WHERE id=? AND user_id=?") && ++reads == 2) {
                    assertThat(result).hasSize(1);
                    checked.countDown();
                    await(resume);
                }
                return result;
            }
        };
        var mvc = mvc(checking);
        var remover = uploads(new JdbcTemplate(new DriverManagerDataSource(source.getUrl())));
        var otherBefore = jdbc.queryForMap("SELECT * FROM uploads WHERE id='img-other'");
        var executor = Executors.newFixedThreadPool(2);
        try {
            var editing = executor.submit(() -> {
                try { return mvc.perform(patch("/api/uploads/" + IMAGE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fieldId\":\"f-a\",\"note\":\"changed\"}")).andReturn().getResponse(); }
                finally { assertThat(TransactionSynchronizationManager.hasResource(source)).isFalse(); }
            });
            await(checked);
            var deleted = executor.submit(() -> operation.equals("cleanup")
                    ? remover.cleanupUnreferenced() == 1 : remover.delete(IMAGE));
            assertThat(deleted.get(8, TimeUnit.SECONDS)).isTrue();
            assertThat(Files.exists(original)).isFalse();
            resume.countDown();
            var response = editing.get(8, TimeUnit.SECONDS);
            assertThat(response.getStatus()).isEqualTo(409);
            assertThat(response.getContentAsString()).contains("UPLOAD_ARCHIVE_CHANGED").doesNotContain("NullPointer", "SQLITE");
            assertThat(response.getHeader("Cache-Control")).contains("no-store");
            assertThat(uploads(jdbc).get(IMAGE)).isNull();
            assertThat(jdbc.queryForMap("SELECT * FROM uploads WHERE id='img-other'")).isEqualTo(otherBefore);
            assertThat(Files.readAllBytes(images.resolve("img-other.jpg"))).containsExactly(bytes);
            mvc.perform(patch("/api/uploads/" + IMAGE).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isNotFound());
        } finally {
            resume.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(8, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void ignoredUpdateCannotReportTheOldCardAsSuccessfullySaved() throws Exception {
        jdbc.execute("CREATE TRIGGER ignore_archive BEFORE UPDATE OF note ON uploads BEGIN SELECT RAISE(IGNORE); END");
        var before = uploads(jdbc).get(IMAGE);
        mvc(jdbc).perform(patch("/api/uploads/" + IMAGE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"changed\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("UPLOAD_ARCHIVE_CHANGED"));
        assertThat(uploads(jdbc).get(IMAGE)).isEqualTo(before);
        assertThat(Files.readAllBytes(original)).containsExactly(bytes);
    }

    @Test
    void patchPreservesOmittedValuesAndAllowsExplicitRemoval() throws Exception {
        jdbc.update("UPDATE uploads SET field_id='f-a', observed_at='2026-09-02', referenced_at='kept', task_id='t-kept' WHERE id=?", IMAGE);
        var mvc = mvc(jdbc);
        mvc.perform(patch("/api/uploads/" + IMAGE).contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"  new note  \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.note").value("new note"))
                .andExpect(jsonPath("$.fieldId").value("f-a")).andExpect(jsonPath("$.observedAt").value("2026-09-02"))
                .andExpect(jsonPath("$.taskId").value("t-kept")).andExpect(jsonPath("$.referenced").value(true));
        mvc.perform(patch("/api/uploads/" + IMAGE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":null,\"fieldId\":null,\"observedAt\":null}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.note").value(""))
                .andExpect(jsonPath("$.fieldId").value("")).andExpect(jsonPath("$.observedAt").value(""));
        assertThat(Files.readAllBytes(original)).containsExactly(bytes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"after-update", "read-back", "zero-after-update", "wrong-count"})
    void updateOrReadFailureRollsBackRatherThanLeavingAnUnreportedEdit(String failure) throws Exception {
        var before = uploads(jdbc).get(IMAGE);
        var faulty = new JdbcTemplate(source) {
            private boolean updated;
            @Override public int update(String sql, Object... args) {
                int count = super.update(sql, args);
                if (sql.startsWith("UPDATE uploads SET note=")) {
                    updated = true;
                    if (failure.equals("after-update")) throw new DataAccessResourceFailureException(PRIVATE_ERROR);
                    if (failure.equals("zero-after-update")) return 0;
                    if (failure.equals("wrong-count")) return 2;
                }
                return count;
            }
            @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
                if (updated && failure.equals("read-back")) throw new DataAccessResourceFailureException(PRIVATE_ERROR);
                return super.query(sql, mapper, args);
            }
        };
        var response = mvc(faulty).perform(patch("/api/uploads/" + IMAGE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"changed\"}"))
                .andExpect(status().is(failure.equals("zero-after-update") ? 409 : 503))
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain(PRIVATE_ERROR, directory.toString());
        assertThat(uploads(jdbc).get(IMAGE)).isEqualTo(before);
        assertThat(Files.readAllBytes(original)).containsExactly(bytes);
        assertThat(TransactionSynchronizationManager.hasResource(source)).isFalse();
    }

    @Test
    void nullReadBackCannotEscapeAndItsTransactionIsRolledBack() throws Exception {
        jdbc.execute("CREATE TRIGGER disappear_after_archive AFTER UPDATE OF note ON uploads BEGIN DELETE FROM uploads WHERE id=NEW.id; END");
        var before = uploads(jdbc).get(IMAGE);
        mvc(jdbc).perform(patch("/api/uploads/" + IMAGE).contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"changed\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("UPLOAD_ARCHIVE_CHANGED"));
        assertThat(uploads(jdbc).get(IMAGE)).isEqualTo(before);
        assertThat(Files.readAllBytes(original)).containsExactly(bytes);
    }

    @Test
    void oneConnectionPoolSupportsHttpUpdatesAndJoinsExistingServiceTransactions() throws Exception {
        try (var pool = new HikariDataSource()) {
            pool.setDataSource(source);
            pool.setMaximumPoolSize(1);
            pool.setMinimumIdle(1);
            pool.setConnectionTimeout(500);
            var template = new JdbcTemplate(pool);
            var mvc = mvc(template);
            for (int i = 0; i < 2; i++) {
                mvc.perform(patch("/api/uploads/" + IMAGE).contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"saved\"}"))
                        .andExpect(status().isOk()).andExpect(jsonPath("$.note").value("saved"));
            }
            new TransactionTemplate(new DataSourceTransactionManager(pool)).executeWithoutResult(status -> {
                assertThat(uploads(template).updateArchive(IMAGE, "uncommitted", null, "f-a").note()).isEqualTo("uncommitted");
                assertThat(TransactionSynchronizationManager.hasResource(pool)).isTrue();
                status.setRollbackOnly(); // Direct service callers retain ownership of their surrounding transaction.
            });
            assertThat(uploads(template).get(IMAGE).note()).isEqualTo("saved");
            assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
            assertThat(TransactionSynchronizationManager.hasResource(pool)).isFalse();
        }
        assertThat(Files.readAllBytes(original)).containsExactly(bytes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"begin", "before-commit", "after-commit"})
    void transactionFaultDoesNotReportConfirmedSuccessOrPromiseNoChanges(String failure) throws Exception {
        var faulty = new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                Connection delegate = source.getConnection();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("setAutoCommit") && failure.equals("begin") && Boolean.FALSE.equals(args[0])) throw new SQLException(PRIVATE_ERROR);
                    if (method.getName().equals("commit") && failure.equals("before-commit")) throw new SQLException(PRIVATE_ERROR);
                    try {
                        Object result = method.invoke(delegate, args);
                        if (method.getName().equals("commit") && failure.equals("after-commit")) throw new SQLException(PRIVATE_ERROR);
                        return result;
                    } catch (InvocationTargetException e) { throw e.getCause(); }
                });
            }
            @Override public Connection getConnection(String username, String password) throws SQLException { return getConnection(); }
        };
        var response = mvc(new JdbcTemplate(faulty)).perform(patch("/api/uploads/" + IMAGE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"changed\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("UPLOAD_ARCHIVE_UNAVAILABLE"))
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain(PRIVATE_ERROR, "SQLException", directory.toString());
        assertThat(uploads(jdbc).get(IMAGE).note()).isEqualTo(failure.equals("after-commit") ? "changed" : "original");
        assertThat(Files.readAllBytes(original)).containsExactly(bytes);
        assertThat(TransactionSynchronizationManager.hasResource(faulty)).isFalse();
    }

    private UploadService uploads(JdbcTemplate template) {
        return new UploadService(template, images.toString(), 8_388_608, 7, user);
    }

    private MockMvc mvc(JdbcTemplate template) {
        return MockMvcBuilders.standaloneSetup(new UploadController(uploads(template), new FieldServiceImpl(template, user), user))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private static void await(CountDownLatch latch) {
        try { assertThat(latch.await(8, TimeUnit.SECONDS)).as("bounded archive race synchronization").isTrue(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
