package com.nongxin.service;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

/** Controlled failures against generated legacy data only; does not start a server or overwrite any backup. */
class MigrationInterruptionSafetyTest {
    private static final String FAILURE = "synthetic migration interruption";
    @TempDir Path directory;
    private Path database;
    private Path backups;
    private DriverManagerDataSource source;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        database = directory.resolve("legacy.db");
        backups = directory.resolve("backup");
        source = new DriverManagerDataSource("jdbc:sqlite:" + database);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE fields(id TEXT PRIMARY KEY,name TEXT NOT NULL,crop TEXT NOT NULL,variety TEXT,sow_date TEXT NOT NULL,area_mu REAL,notes TEXT,created_at TEXT)");
        jdbc.execute("CREATE TABLE conversations(id TEXT PRIMARY KEY,title TEXT NOT NULL,field_id TEXT,messages_json TEXT NOT NULL,created_at TEXT)");
        jdbc.execute("CREATE TABLE farm_tasks(id TEXT PRIMARY KEY,title TEXT NOT NULL,task_date TEXT NOT NULL,field_id TEXT,"
                + "field_name TEXT NOT NULL DEFAULT '',condition_text TEXT NOT NULL DEFAULT '',method TEXT NOT NULL DEFAULT '',"
                + "review TEXT NOT NULL DEFAULT '',note TEXT NOT NULL DEFAULT '',done INTEGER NOT NULL DEFAULT 0,created_at TEXT NOT NULL,source_message_id TEXT)");
        jdbc.update("INSERT INTO fields(id,name,crop,sow_date,notes) VALUES ('f-old','synthetic field','synthetic crop','2026-09-01','keep field note')");
        jdbc.update("INSERT INTO conversations(id,title,field_id,messages_json) VALUES ('c-old','synthetic conversation','f-old','[]')");
        for (int done = 0; done <= 1; done++) {
            jdbc.update("INSERT INTO farm_tasks(id,title,task_date,field_id,done,created_at,note) VALUES (?,'synthetic task','2026-09-01','f-old',?,'2026-09-01','keep task note')",
                    "t-" + done, done);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"after-v5-record", "target-check"})
    void failureAfterVersionFiveCannotStrandAFalseCurrentDatabase(String point) throws Exception {
        var before = snapshot(jdbc);
        var reached = new AtomicBoolean();
        var faulty = new JdbcTemplate(source) {
            @Override public int update(String sql, Object... args) {
                int affected = super.update(sql, args);
                if (point.equals("after-v5-record") && sql.startsWith("INSERT OR REPLACE INTO schema_version") && args[0].equals(5)) {
                    reached.set(true);
                    throw new DataAccessResourceFailureException(FAILURE);
                }
                return affected;
            }
            @Override public <T> List<T> queryForList(String sql, Class<T> type, Object... args) {
                if (point.equals("target-check") && sql.equals("SELECT name FROM pragma_table_info(?)")) {
                    reached.set(true);
                    throw new DataAccessResourceFailureException(FAILURE);
                }
                return super.queryForList(sql, type, args);
            }
        };
        var migration = migration(faulty);
        assertThatThrownBy(migration::initializeApplicationDatabase).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(reached).isTrue();
        var interrupted = snapshot(jdbc);
        Path backup = assertOriginalBackup(migration, before);
        byte[] savedBackup = Files.readAllBytes(backup);
        var retry = migration(jdbc);
        Throwable retryFailure = catchThrowable(retry::initializeApplicationDatabase);
        assertThat(retryFailure).as("restart must not be blocked by a partially migrated v5 marker").isNull();
        assertThat(interrupted).as("confirmed rollback must undo both schema changes and version registration").isEqualTo(before);
        assertThat(migration.version()).isEqualTo(-1);
        assertThat(migration.lastSnapshot()).isEmpty();
        assertHealthy(retry);
        assertThat(Files.readAllBytes(backup)).containsExactly(savedBackup);
        assertThat(retry.lastBackup()).isNotEqualTo(migration.lastBackup());
        assertThat(TransactionSynchronizationManager.hasResource(source)).isFalse();
    }

    @Test
    void successfulUpgradeKeepsBothTaskStatesAndTheOriginalBackup() throws Exception {
        var before = snapshot(jdbc);
        var migration = migration(jdbc);
        migration.initializeApplicationDatabase();
        assertOriginalBackup(migration, before);
        assertHealthy(migration);
    }

    @ParameterizedTest
    @ValueSource(strings = {"begin", "before-commit", "after-commit"})
    void transactionFailureDoesNotPublishSuccessOrOverwriteAnUncertainCommit(String point) throws Exception {
        var before = snapshot(jdbc);
        var faultySource = new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                var connection = source.getConnection();
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("setAutoCommit") && point.equals("begin") && Boolean.FALSE.equals(args[0])) throw new SQLException(FAILURE);
                    if (method.getName().equals("commit") && point.equals("before-commit")) throw new SQLException(FAILURE);
                    try {
                        Object result = method.invoke(connection, args);
                        if (method.getName().equals("commit") && point.equals("after-commit")) throw new SQLException(FAILURE);
                        return result;
                    } catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
            }
            @Override public Connection getConnection(String username, String password) throws SQLException { return getConnection(); }
        };
        var migration = migration(new JdbcTemplate(faultySource));
        assertThatThrownBy(migration::initializeApplicationDatabase).isInstanceOf(org.springframework.transaction.TransactionException.class);
        assertThat(migration.version()).isEqualTo(-1);
        assertThat(migration.lastSnapshot()).isEmpty();
        Path backup = assertOriginalBackup(migration, before);
        byte[] originalBackup = Files.readAllBytes(backup);
        assertThat(TransactionSynchronizationManager.hasResource(faultySource)).isFalse();
        if (point.equals("after-commit")) {
            assertThat(jdbc.queryForObject("SELECT MAX(version) FROM schema_version", Integer.class)).isEqualTo(5);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_usage", Integer.class)).isZero();
        } else {
            assertThat(snapshot(jdbc)).isEqualTo(before);
        }
        var retry = migration(jdbc);
        retry.initializeApplicationDatabase();
        assertHealthy(retry);
        if (point.equals("after-commit")) assertThat(retry.lastBackup()).isEmpty(); // Already complete; do not re-migrate.
        else assertThat(retry.lastBackup()).isNotEqualTo(migration.lastBackup());
        assertThat(Files.readAllBytes(backup)).containsExactly(originalBackup);
    }

    @ParameterizedTest
    @CsvSource({"active,application", "bound,application", "active,standalone", "bound,standalone"})
    void callerTransactionOrBoundConnectionCannotDelayTheMigrationCommit(String state, String entry) throws Exception {
        var before = snapshot(jdbc);
        var migration = migration(jdbc);
        Runnable call = entry.equals("application") ? migration::initializeApplicationDatabase : migration::afterPropertiesSet;
        if (state.equals("active")) {
            new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status -> {
                assertThatThrownBy(call::run).isInstanceOf(IllegalStateException.class).hasMessageContaining("外层事务");
                assertThat(TransactionSynchronizationManager.hasResource(source)).isTrue();
                status.setRollbackOnly();
            });
        } else {
            try (var connection = source.getConnection()) {
                TransactionSynchronizationManager.bindResource(source, new ConnectionHolder(connection));
                try { assertThatThrownBy(call::run).isInstanceOf(IllegalStateException.class).hasMessageContaining("绑定连接"); }
                finally { TransactionSynchronizationManager.unbindResource(source); }
            }
        }
        assertThat(Files.exists(backups)).isFalse();
        assertThat(snapshot(jdbc)).isEqualTo(before);
        assertThat(migration.version()).isEqualTo(-1);
        assertThat(TransactionSynchronizationManager.hasResource(source)).isFalse();
    }

    @Test
    void singleConnectionPoolRollsBackAndCanRetryWithoutNestedConnectionBorrowing() throws Exception {
        var before = snapshot(jdbc);
        try (var pool = new HikariDataSource()) {
            pool.setDataSource(source);
            pool.setMaximumPoolSize(1);
            pool.setMinimumIdle(1);
            pool.setConnectionTimeout(500);
            var faulty = new JdbcTemplate(pool) {
                @Override public int update(String sql, Object... args) {
                    int count = super.update(sql, args);
                    if (sql.startsWith("INSERT OR REPLACE INTO schema_version") && args[0].equals(5)) throw new DataAccessResourceFailureException(FAILURE);
                    return count;
                }
            };
            var migration = migration(faulty);
            assertThatThrownBy(migration::initializeApplicationDatabase).isInstanceOf(DataAccessResourceFailureException.class);
            assertThat(snapshot(jdbc)).isEqualTo(before);
            assertOriginalBackup(migration, before);
            assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
            var retry = migration(new JdbcTemplate(pool));
            retry.initializeApplicationDatabase();
            assertHealthy(retry);
            assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
            assertThat(TransactionSynchronizationManager.hasResource(pool)).isFalse();
        }
    }

    private SchemaMigrationService migration(JdbcTemplate template) {
        return new SchemaMigrationService(template, source.getUrl(), backups.toString());
    }

    private Map<String, Object> snapshot(JdbcTemplate template) {
        var out = new LinkedHashMap<String, Object>();
        out.put("schema", template.queryForList("SELECT name,sql FROM sqlite_master WHERE name NOT GLOB 'sqlite_*' ORDER BY name"));
        for (String table : List.of("fields", "conversations", "farm_tasks")) {
            out.put(table, template.queryForList("SELECT * FROM " + table + " ORDER BY id"));
        }
        return out;
    }

    private Path assertOriginalBackup(SchemaMigrationService migration, Map<String, Object> expected) {
        assertThat(migration.lastBackup()).isNotBlank();
        Path path = Path.of(migration.lastBackup()).toAbsolutePath().normalize();
        assertThat(path.startsWith(directory.toAbsolutePath().normalize())).isTrue();
        var backup = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + path));
        assertThat(backup.queryForObject("PRAGMA integrity_check", String.class)).isEqualTo("ok");
        assertThat(snapshot(backup)).isEqualTo(expected);
        return path;
    }

    private void assertHealthy(SchemaMigrationService migration) {
        assertThat(migration.version()).isEqualTo(5);
        assertThat(jdbc.queryForList("SELECT status FROM farm_tasks ORDER BY id", String.class)).containsExactly("pending", "completed");
        assertThat(jdbc.queryForList("SELECT note FROM farm_tasks ORDER BY id", String.class)).containsExactly("keep task note", "keep task note");
        assertThat(jdbc.queryForObject("SELECT notes FROM fields WHERE id='f-old'", String.class)).isEqualTo("keep field note");
        assertThat(jdbc.queryForObject("SELECT messages_json FROM conversations WHERE id='c-old'", String.class)).isEqualTo("[]");
        assertThat(jdbc.queryForList("SELECT user_id FROM farm_tasks ORDER BY id", String.class)).containsOnly("local-owner");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_usage", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM answer_cache", Integer.class)).isZero();
    }
}
