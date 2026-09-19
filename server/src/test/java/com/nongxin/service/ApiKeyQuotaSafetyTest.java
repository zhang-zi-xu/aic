package com.nongxin.service;

import com.zaxxer.hikari.HikariDataSource;
import com.nongxin.service.impl.ApiKeyServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

/** Multi-connection SQLite regressions. Every database and key is test-only. */
class ApiKeyQuotaSafetyTest {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 14);
    private static final String KEY = "fake-demo-key-for-quota-tests";
    private static final String IP = "192.0.2.10";
    private static final String PRIVATE_ERROR = "private-db-path-and-query-marker";

    @TempDir
    Path directory;
    private JdbcTemplate jdbc;
    private MockedStatic<LocalDate> dates;

    @BeforeEach
    void setUp() {
        var source = source();
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        dates = fixedDay();
        useClient(IP);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        if (dates != null) dates.close();
    }

    @RepeatedTest(3)
    void multipleServiceInstancesCannotExceedTheSameIpLimit() throws Exception {
        var results = race(8, false, 2, 20);
        assertThat(results.stream().filter(ApiKeyService.Resolution::allowed).count()).isEqualTo(2);
        assertCount("global", "all", 2);
        assertCount("ip", IP, 2);
        assertThat(rows()).isEqualTo(2);
    }

    @RepeatedTest(3)
    void multipleServiceInstancesCannotExceedTheGlobalLimit() throws Exception {
        var results = race(8, true, 20, 3);
        assertThat(results.stream().filter(ApiKeyService.Resolution::allowed).count()).isEqualTo(3);
        assertCount("global", "all", 3);
        for (int i = 0; i < results.size(); i++) {
            var counts = jdbc.queryForList("SELECT count FROM api_usage WHERE scope = 'ip' AND scope_key = ?",
                    Integer.class, "192.0.2." + (20 + i));
            if (results.get(i).allowed()) assertThat(counts).containsExactly(1);
            else assertThat(counts).isEmpty();
        }
        assertThat(rows()).isEqualTo(4);
    }

    @Test
    void exhaustedIpRollsBackTheGlobalReservation() {
        seed("global", "all", 4);
        seed("ip", IP, 2);
        var denied = service(jdbc, 2, 10).resolve(null, null, null);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.apiKey()).isEmpty();
        assertThat(denied.denyReason()).contains("每设备 2 次/日");
        assertCount("global", "all", 4);
        assertCount("ip", IP, 2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"global", "ip"})
    void failedInsertDeniesAccessWithoutPartialCounters(String failedScope) {
        // Static allowlisted trigger scope, in this test's @TempDir database only.
        jdbc.execute("CREATE TRIGGER fail_quota_insert BEFORE INSERT ON api_usage WHEN NEW.scope = '"
                + failedScope + "' BEGIN SELECT RAISE(ABORT, '" + PRIVATE_ERROR + "'); END");
        assertUnavailable(service(jdbc, 3, 10).resolve(null, null, null));
        assertThat(rows()).isZero();
    }

    @Test
    void failedSecondUpdateRestoresExistingCounters() {
        seed("global", "all", 4);
        seed("ip", IP, 1);
        jdbc.execute("CREATE TRIGGER fail_quota_update BEFORE UPDATE ON api_usage WHEN NEW.scope = 'ip' "
                + "BEGIN SELECT RAISE(ABORT, '" + PRIVATE_ERROR + "'); END");
        assertUnavailable(service(jdbc, 3, 10).resolve(null, null, null));
        assertCount("global", "all", 4);
        assertCount("ip", IP, 1);
    }

    @Test
    void exceptionAfterBothWritesRollsBackBothCounters() {
        var failingJdbc = new JdbcTemplate(source()) {
            @Override
            public int update(String sql, Object... args) {
                int changed = super.update(sql, args);
                if (isQuotaWrite(sql, args, "ip")) throw new DataAccessResourceFailureException(PRIVATE_ERROR);
                return changed;
            }
        };
        assertUnavailable(service(failingJdbc, 3, 10).resolve(null, null, null));
        assertThat(rows()).isZero();
    }

    @Test
    void deferredConstraintFailureAtCommitDoesNotReleaseKeyOrLeaveCounters() {
        // A real SQLite deferred FK fails at COMMIT, after both quota writes have succeeded.
        jdbc.execute("CREATE TABLE quota_test_parent (id INTEGER PRIMARY KEY)");
        jdbc.execute("CREATE TABLE quota_test_child (parent_id INTEGER REFERENCES quota_test_parent(id) "
                + "DEFERRABLE INITIALLY DEFERRED)");
        jdbc.execute("CREATE TRIGGER fail_quota_commit AFTER INSERT ON api_usage WHEN NEW.scope = 'ip' "
                + "BEGIN INSERT INTO quota_test_child (parent_id) VALUES (99); END");
        // Also verify that a pooled connection remains usable after the failed commit and rollback.
        try (var pool = new HikariDataSource()) {
            pool.setDataSource(source());
            pool.setMaximumPoolSize(1);
            pool.setMinimumIdle(1);
            pool.setConnectionTimeout(1000);
            var writes = new AtomicInteger();
            var pooledJdbc = new JdbcTemplate(pool) {
                @Override
                public int update(String sql, Object... args) {
                    int changed = super.update(sql, args);
                    if (sql.startsWith("INSERT INTO api_usage")) writes.incrementAndGet();
                    return changed;
                }
            };
            var service = service(pooledJdbc, 3, 10);
            assertUnavailable(service.resolve(null, null, null));
            assertThat(writes.get()).as("Both writes must finish before the deferred COMMIT failure").isEqualTo(2);
            assertThat(rows()).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM quota_test_child", Integer.class)).isZero();
            jdbc.execute("DROP TRIGGER fail_quota_commit");
            assertThat(service.resolve(null, null, null).apiKey()).isEqualTo(KEY);
            assertCount("global", "all", 1);
            assertCount("ip", IP, 1);
        }
    }

    @Test
    void returnedKeyHasACommittedReservationIndependentOfAnOuterRollback() {
        var outer = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        outer.execute(transaction -> {
            assertThat(service(jdbc, 3, 10).resolve(null, null, null).apiKey()).isEqualTo(KEY);
            transaction.setRollbackOnly();
            return null;
        });
        assertCount("global", "all", 1);
        assertCount("ip", IP, 1);
    }

    @Test
    void missingTableDeniesDemoAccessAndSanitizesStatus() {
        jdbc.execute("DROP TABLE api_usage");
        var service = service(jdbc, 3, 10);
        assertUnavailable(service.resolve(null, null, null));
        var status = service.status();
        assertThat(status).containsKey("usageError")
                .doesNotContainKeys("globalUsedToday", "ipUsedToday", "globalRemaining");
        assertThat(status.get("usageError").toString()).contains("暂时无法查询")
                .doesNotContain("api_usage", "SQL", "SELECT", directory.toString());
    }

    @Test
    void secondStatusReadFailureDoesNotReturnPartialOrPrivateUsage() {
        var failingJdbc = new JdbcTemplate(source()) {
            @Override
            public <T> T queryForObject(String sql, Class<T> type, Object... args) {
                if (args.length > 1 && "ip".equals(args[1])) {
                    throw new DataAccessResourceFailureException(PRIVATE_ERROR);
                }
                return super.queryForObject(sql, type, args);
            }
        };
        var status = service(failingJdbc, 3, 10).status();
        assertThat(status).containsKey("usageError")
                .doesNotContainKeys("globalUsedToday", "ipUsedToday", "globalRemaining");
        assertThat(status.get("usageError").toString()).contains("暂时无法查询").doesNotContain(PRIVATE_ERROR);
    }

    @Test
    void databaseConnectionFailureDeniesOnlyGuardedDemoAccess() {
        var attempts = new AtomicInteger();
        var broken = new JdbcTemplate(new AbstractDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                attempts.incrementAndGet();
                throw new SQLException(PRIVATE_ERROR);
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                return getConnection();
            }
        });
        var service = service(broken, 3, 10);
        var userKey = service.resolve("fake-user-key-for-tests", "user-provider", "user-model");
        assertThat(userKey.allowed()).isTrue();
        assertThat(userKey.serverSide()).isFalse();
        assertThat(userKey.apiKey()).isEqualTo("fake-user-key-for-tests");
        var disabled = new ApiKeyServiceImpl(broken, KEY, "demo-provider", "demo-model", false, 3, 10);
        assertThat(disabled.resolve(null, null, null).apiKey()).isEqualTo(KEY);
        var unconfigured = new ApiKeyServiceImpl(broken, "", "demo-provider", "demo-model", true, 3, 10);
        assertThat(unconfigured.resolve(null, null, null).apiKey()).isEmpty();
        assertThat(attempts.get()).isZero();
        assertUnavailable(service.resolve(null, null, null));
        assertThat(attempts.get()).isGreaterThan(0);
    }

    @Test
    void busyDatabaseDeniesAccessWithoutChangingCommittedUsage() throws Exception {
        seed("global", "all", 1);
        seed("ip", IP, 1);
        var impatient = source();
        var properties = new Properties();
        properties.setProperty("busy_timeout", "100");
        impatient.setConnectionProperties(properties);
        try (var held = source().getConnection()) {
            held.setAutoCommit(false);
            try (var statement = held.createStatement()) {
                statement.executeUpdate("UPDATE api_usage SET count = 2 WHERE scope = 'global'");
            }
            assertUnavailable(service(new JdbcTemplate(impatient), 3, 10).resolve(null, null, null));
            held.rollback();
        }
        assertCount("global", "all", 1);
        assertCount("ip", IP, 1);
    }

    private List<ApiKeyService.Resolution> race(int clients, boolean distinctIps, int perIp, int global) throws Exception {
        var firstWrite = new CyclicBarrier(clients);
        var executor = Executors.newFixedThreadPool(clients);
        var futures = new ArrayList<Future<ApiKeyService.Resolution>>();
        try {
            for (int i = 0; i < clients; i++) {
                String ip = distinctIps ? "192.0.2." + (20 + i) : IP;
                // Independent service + data source instances, all targeting the same SQLite file.
                var gatedJdbc = new JdbcTemplate(source()) {
                    @Override
                    public int update(String sql, Object... args) {
                        if (isQuotaWrite(sql, args, "global")) {
                            try {
                                firstWrite.await(5, TimeUnit.SECONDS);
                            } catch (Exception e) {
                                throw new AssertionError("All requests must reach the first quota write", e);
                            }
                        }
                        return super.update(sql, args);
                    }
                };
                var service = service(gatedJdbc, perIp, global);
                futures.add(executor.submit(() -> {
                    try (var workerDate = fixedDay()) {
                        useClient(ip);
                        return service.resolve(null, null, null);
                    } finally {
                        RequestContextHolder.resetRequestAttributes();
                    }
                }));
            }
            var results = new ArrayList<ApiKeyService.Resolution>();
            for (var future : futures) {
                var result = future.get(10, TimeUnit.SECONDS);
                if (result.allowed()) assertThat(result.apiKey()).isEqualTo(KEY);
                else {
                    assertThat(result.apiKey()).isEmpty();
                    assertThat(result.denyReason()).contains("额度已用完");
                }
                results.add(result);
            }
            return results;
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private DriverManagerDataSource source() {
        var source = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("quota.db"));
        var properties = new Properties();
        properties.setProperty("foreign_keys", "true");
        properties.setProperty("busy_timeout", "2000");
        source.setConnectionProperties(properties);
        return source;
    }

    private static boolean isQuotaWrite(String sql, Object[] args, String scope) {
        return sql.startsWith("INSERT INTO api_usage") && args.length > 1 && scope.equals(args[1]);
    }

    private static MockedStatic<LocalDate> fixedDay() {
        var dates = mockStatic(LocalDate.class, CALLS_REAL_METHODS);
        dates.when(LocalDate::now).thenReturn(DAY);
        return dates;
    }

    private static void useClient(String ip) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(ip);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static ApiKeyServiceImpl service(JdbcTemplate jdbc, int perIp, int global) {
        return new ApiKeyServiceImpl(jdbc, KEY, "demo-provider", "demo-model", true, perIp, global);
    }

    private static void assertUnavailable(ApiKeyService.Resolution resolution) {
        assertThat(resolution.allowed()).isFalse();
        assertThat(resolution.serverSide()).isTrue();
        assertThat(resolution.apiKey()).isEmpty();
        assertThat(resolution.denyReason()).contains("暂时无法核验", "API Key")
                .doesNotContain(PRIVATE_ERROR, "api_usage", "SQLITE", "SELECT", "INSERT");
    }

    private void seed(String scope, String key, int count) {
        jdbc.update("INSERT INTO api_usage (day, scope, scope_key, count) VALUES (?, ?, ?, ?)",
                DAY.toString(), scope, key, count);
    }

    private void assertCount(String scope, String key, int count) {
        assertThat(jdbc.queryForList("SELECT count FROM api_usage WHERE day = ? AND scope = ? AND scope_key = ?",
                Integer.class, DAY.toString(), scope, key)).containsExactly(count);
    }

    private int rows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM api_usage", Integer.class);
    }
}
