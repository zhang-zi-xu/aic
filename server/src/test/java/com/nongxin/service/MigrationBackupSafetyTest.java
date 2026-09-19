package com.nongxin.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** Only self-created legacy databases and backups inside @TempDir. Never starts the application. */
class MigrationBackupSafetyTest {
    private static final String PRIVATE_ERROR = "synthetic-private-sql-or-path";
    @TempDir Path directory;
    private Path database;
    private DriverManagerDataSource source;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        database = directory.resolve("legacy.db");
        source = new DriverManagerDataSource("jdbc:sqlite:" + database);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE farm_tasks (id TEXT PRIMARY KEY, title TEXT NOT NULL, task_date TEXT NOT NULL,"
                + " done INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL, source_message_id TEXT)");
        jdbc.update("INSERT INTO farm_tasks (id,title,task_date,done,created_at) VALUES ('old','synthetic old task','2026-09-01',1,'2026-09-01')");
    }

    @Test
    void unusableBackupDirectoryStopsBeforeCreatingVersionTableOrChangingLegacyData() throws Exception {
        Path blocked = directory.resolve("not-a-directory");
        Files.writeString(blocked, "keep this file");
        var before = schema();
        var rows = tasks();
        var migration = migration(jdbc, blocked);
        assertThatThrownBy(migration::afterPropertiesSet).isInstanceOf(IllegalStateException.class);
        assertThat(schema()).isEqualTo(before);
        assertThat(tasks()).isEqualTo(rows);
        assertThat(migration.version()).isEqualTo(-1);
        assertThat(migration.lastBackup()).isEmpty();
        assertThat(Files.readString(blocked)).isEqualTo("keep this file");
    }

    @Test
    void walVacuumFailureMustNotFallBackToAnIncompleteMainFileCopy() throws Exception {
        try (var keeper = source.getConnection(); var statement = keeper.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA wal_autocheckpoint=0");
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            jdbc.update("INSERT INTO farm_tasks (id,title,task_date,created_at) VALUES ('wal-new','committed in WAL','2026-09-02','2026-09-02')");
            assertThat(Files.size(Path.of(database + "-wal"))).isPositive();
            // Prove the risk with a disposable, explicitly test-only raw copy before exercising production code.
            Path rawCopy = directory.resolve("unsafe-copy.db");
            Files.copy(database, rawCopy);
            var raw = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + rawCopy));
            assertThat(raw.queryForObject("PRAGMA integrity_check", String.class)).isEqualTo("ok");
            assertThat(raw.queryForObject("SELECT COUNT(*) FROM farm_tasks", Integer.class)).isEqualTo(1);
            assertThat(tasks()).hasSize(2); // A structurally valid raw copy is still missing committed data.
            var before = schema();
            var rows = tasks();
            var faulty = new JdbcTemplate(source) {
                @Override public void execute(String sql) {
                    if (sql.startsWith("VACUUM INTO")) throw new DataAccessResourceFailureException(PRIVATE_ERROR);
                    super.execute(sql);
                }
            };
            var migration = migration(faulty, directory.resolve("backup"));
            assertThatThrownBy(migration::afterPropertiesSet).isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining(PRIVATE_ERROR).hasNoCause();
            assertThat(migration.lastBackup()).isEmpty();
            assertThat(schema()).isEqualTo(before);
            assertThat(tasks()).isEqualTo(rows);
        }
    }

    @Test
    void backupIsRequiredEvenWhenOnlyFieldsExistWithoutATaskTable() throws Exception {
        jdbc.execute("DROP TABLE farm_tasks"); // Only the synthetic fixture created above.
        jdbc.execute("CREATE TABLE fields (id TEXT PRIMARY KEY, name TEXT NOT NULL)");
        jdbc.update("INSERT INTO fields VALUES ('f-old','synthetic field')");
        Path blocked = directory.resolve("blocked");
        Files.writeString(blocked, "keep");
        var before = schema();
        assertThatThrownBy(migration(jdbc, blocked)::afterPropertiesSet).isInstanceOf(IllegalStateException.class);
        assertThat(schema()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT name FROM fields WHERE id='f-old'", String.class)).isEqualTo("synthetic field");
    }

    @Test
    void successfulVacuumBackupContainsCommittedWalRowsAndThePreMigrationSchema() throws Exception {
        try (var keeper = source.getConnection(); var statement = keeper.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA wal_autocheckpoint=0");
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            jdbc.update("INSERT INTO farm_tasks (id,title,task_date,created_at) VALUES ('wal-new','committed in WAL','2026-09-02','2026-09-02')");
            var before = schema();
            var rows = tasks();
            var migration = migration(jdbc, directory.resolve("backup"));
            migration.afterPropertiesSet();
            var backup = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + migration.lastBackup()));
            assertThat(backup.queryForObject("PRAGMA integrity_check", String.class)).isEqualTo("ok");
            assertThat(backup.queryForList("SELECT * FROM farm_tasks ORDER BY id")).isEqualTo(rows);
            assertThat(backup.queryForList("SELECT name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY name")).isEqualTo(before);
            assertThat(migration.version()).isEqualTo(SchemaMigrationService.LATEST_VERSION);
            assertThat(jdbc.queryForObject("SELECT status FROM farm_tasks WHERE id='old'", String.class)).isEqualTo("completed");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "empty", "corrupt", "partial-then-error"})
    void unusableVacuumOutputCannotAuthorizeMigration(String mode) {
        var before = schema();
        var rows = tasks();
        var faulty = new JdbcTemplate(source) {
            @Override public void execute(String sql) {
                if (!sql.startsWith("VACUUM INTO '")) { super.execute(sql); return; }
                // Parse only this application's generated SQL targeting the test's private backup directory.
                String literal = sql.substring("VACUUM INTO '".length(), sql.length() - 1).replace("''", "'");
                Path target = Path.of(literal).toAbsolutePath().normalize();
                assertThat(target.startsWith(directory.toAbsolutePath().normalize())).isTrue();
                try {
                    if (!mode.equals("absent")) Files.writeString(target, mode.equals("empty") ? "" : "not a SQLite database");
                } catch (java.io.IOException e) { throw new AssertionError(e); }
                if (mode.equals("partial-then-error")) throw new DataAccessResourceFailureException(PRIVATE_ERROR);
            }
        };
        var migration = migration(faulty, directory.resolve("backup"));
        assertThatThrownBy(migration::afterPropertiesSet).isInstanceOf(SchemaMigrationService.MigrationBackupUnavailable.class)
                .hasMessageNotContaining(PRIVATE_ERROR).hasNoCause();
        assertThat(migration.lastBackup()).isEmpty();
        assertThat(migration.lastSnapshot()).isEmpty();
        assertThat(schema()).isEqualTo(before);
        assertThat(tasks()).isEqualTo(rows);
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4})
    void existingVersionIsNotAdvancedWhenBackupFails(int version) throws Exception {
        jdbc.execute("CREATE TABLE schema_version (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL, note TEXT NOT NULL DEFAULT '')");
        jdbc.update("INSERT INTO schema_version (version,applied_at) VALUES (?,'synthetic-date')", version);
        var before = schema();
        Path blocked = directory.resolve("blocked");
        Files.writeString(blocked, "keep");
        assertThatThrownBy(migration(jdbc, blocked)::afterPropertiesSet).isInstanceOf(SchemaMigrationService.MigrationBackupUnavailable.class);
        assertThat(schema()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT MAX(version) FROM schema_version", Integer.class)).isEqualTo(version);
        assertThat(tasks()).hasSize(1);
    }

    @Test
    void exclusiveLockAcquiredAfterReadChecksMakesVacuumFailClosed() throws Exception {
        var shortWait = new DriverManagerDataSource(source.getUrl()) {
            @Override public Connection getConnection() throws SQLException {
                Connection connection = super.getConnection();
                try (var statement = connection.createStatement()) { statement.execute("PRAGMA busy_timeout=100"); }
                return connection;
            }
        };
        var before = schema();
        var rows = tasks();
        try (var writer = source.getConnection(); var statement = writer.createStatement()) {
            var lockBeforeVacuum = new JdbcTemplate(shortWait) {
                @Override public void execute(String sql) {
                    if (sql.startsWith("VACUUM INTO")) {
                        // A RESERVED write lock permits a consistent read backup. EXCLUSIVE blocks it.
                        try { statement.execute("BEGIN EXCLUSIVE"); }
                        catch (SQLException e) { throw new AssertionError(e); }
                    }
                    super.execute(sql);
                }
            };
            try {
                var migration = migration(lockBeforeVacuum, directory.resolve("backup"));
                assertThatThrownBy(migration::afterPropertiesSet).isInstanceOf(SchemaMigrationService.MigrationBackupUnavailable.class);
                assertThat(migration.lastBackup()).isEmpty();
            } finally { statement.execute("ROLLBACK"); }
        }
        assertThat(schema()).isEqualTo(before);
        assertThat(tasks()).isEqualTo(rows);
        // Once the independent writer releases its lock, the unchanged database can be backed up/upgraded normally.
        var retried = migration(jdbc, directory.resolve("backup"));
        retried.afterPropertiesSet();
        assertThat(retried.version()).isEqualTo(SchemaMigrationService.LATEST_VERSION);
        assertThat(retried.lastBackup()).isNotBlank();
    }

    @Test
    void unresolvedSourceLocationDoesNotSilentlySkipRequiredBackup() {
        var before = schema();
        var migration = new SchemaMigrationService(jdbc, "jdbc:sqlite:" + directory.resolve("absent.db"), directory.resolve("backup").toString());
        assertThatThrownBy(migration::afterPropertiesSet).isInstanceOf(SchemaMigrationService.MigrationBackupUnavailable.class);
        assertThat(Files.exists(directory.resolve("absent.db"))).isFalse();
        assertThat(schema()).isEqualTo(before);
    }

    @Test
    void retryAfterLaterMigrationFailureDoesNotOverwriteAnEarlierRecoveryPoint() throws Exception {
        var stopAfterBackup = new JdbcTemplate(source) {
            @Override public void execute(String sql) {
                if (sql.startsWith("CREATE TABLE IF NOT EXISTS schema_version")) throw new DataAccessResourceFailureException(PRIVATE_ERROR);
                super.execute(sql);
            }
        };
        Path backups = Files.createDirectory(directory.resolve("backup's"));
        var first = migration(stopAfterBackup, backups);
        assertThatThrownBy(first::afterPropertiesSet).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(first.lastBackup()).isNotBlank();
        Path kept = Path.of(first.lastBackup());
        byte[] original = Files.readAllBytes(kept);
        var second = migration(stopAfterBackup, backups);
        assertThatThrownBy(second::afterPropertiesSet).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(second.lastBackup()).isNotBlank().isNotEqualTo(first.lastBackup());
        assertThat(Files.readAllBytes(kept)).containsExactly(original);
        try (var files = Files.list(backups)) { assertThat(files.toList()).hasSize(2); }
    }

    private SchemaMigrationService migration(JdbcTemplate template, Path backup) {
        return new SchemaMigrationService(template, source.getUrl(), backup.toString());
    }

    private List<Map<String, Object>> schema() {
        return jdbc.queryForList("SELECT name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY name");
    }

    private List<Map<String, Object>> tasks() { return jdbc.queryForList("SELECT * FROM farm_tasks ORDER BY id"); }
}
