package com.nongxin.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 结构迁移：老库升级必须保留真实数据并先备份，且可重复执行。 */
class SchemaMigrationServiceTest {

    @TempDir
    Path tempDir;

    private JdbcTemplate jdbcFor(Path database) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:sqlite:" + database.toAbsolutePath());
        dataSource.setDriverClassName("org.sqlite.JDBC");
        return new JdbcTemplate(dataSource);
    }

    private SchemaMigrationService migrate(JdbcTemplate jdbc, Path database) {
        SchemaMigrationService service = new SchemaMigrationService(jdbc, "jdbc:sqlite:" + database.toAbsolutePath(), "");
        service.afterPropertiesSet();
        return service;
    }

    private static boolean hasColumn(JdbcTemplate jdbc, String table, String column) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM pragma_table_info(?) WHERE name=?", Integer.class, table, column);
        return count != null && count > 0;
    }

    private static boolean hasTable(JdbcTemplate jdbc, String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?", Integer.class, table);
        return count != null && count > 0;
    }

    /** v1 老库（只有 done 布尔量）：升级后状态正确、数据不丢、done 并入 status。 */
    @Test
    void upgradesLegacyTaskTableKeepingRowsAndDroppingDoneFlag() throws IOException {
        Path database = tempDir.resolve("legacy.db");
        JdbcTemplate jdbc = jdbcFor(database);
        jdbc.execute("CREATE TABLE farm_tasks (id TEXT PRIMARY KEY, title TEXT NOT NULL, task_date TEXT NOT NULL,"
                + " field_id TEXT, field_name TEXT NOT NULL DEFAULT '', condition_text TEXT NOT NULL DEFAULT '',"
                + " method TEXT NOT NULL DEFAULT '', review TEXT NOT NULL DEFAULT '', note TEXT NOT NULL DEFAULT '',"
                + " done INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL, source_message_id TEXT)");
        jdbc.update("INSERT INTO farm_tasks (id,title,task_date,done,created_at,source_message_id)"
                + " VALUES ('t-done','已完成的施药','2026-09-10',1,'2026-09-01T08:00:00','msg-1')");
        jdbc.update("INSERT INTO farm_tasks (id,title,task_date,done,created_at) VALUES ('t-open','待做的观察','2026-09-12',0,'2026-09-02T08:00:00')");

        SchemaMigrationService service = migrate(jdbc, database);

        assertThat(service.version()).isEqualTo(SchemaMigrationService.LATEST_VERSION);
        assertThat(jdbc.queryForObject("SELECT status FROM farm_tasks WHERE id='t-done'", String.class)).isEqualTo("completed");
        assertThat(jdbc.queryForObject("SELECT status FROM farm_tasks WHERE id='t-open'", String.class)).isEqualTo("pending");
        assertThat(jdbc.queryForObject("SELECT title FROM farm_tasks WHERE id='t-done'", String.class)).isEqualTo("已完成的施药");
        assertThat(hasColumn(jdbc, "farm_tasks", "done")).isFalse();
        assertThat(hasColumn(jdbc, "farm_tasks", "status")).isTrue();
        assertThat(hasColumn(jdbc, "farm_tasks", "plan_item_id")).isTrue();
        assertThat(hasTable(jdbc, "task_records")).isTrue();
        assertThat(hasTable(jdbc, "uploads")).isTrue();
        // 迁移来的历史任务不猜测完成时间
        assertThat(jdbc.queryForObject("SELECT completed_at FROM farm_tasks WHERE id='t-done'", String.class)).isNull();

        // 备份是升级前的形态：有 done、没有 status，行数一致
        assertThat(service.lastBackup()).isNotBlank();
        Path backup = Path.of(service.lastBackup());
        assertThat(backup).exists();
        JdbcTemplate backupJdbc = jdbcFor(backup);
        assertThat(hasColumn(backupJdbc, "farm_tasks", "done")).isTrue();
        assertThat(hasColumn(backupJdbc, "farm_tasks", "status")).isFalse();
        assertThat(backupJdbc.queryForObject("SELECT COUNT(*) FROM farm_tasks", Integer.class)).isEqualTo(2);
    }

    /** 重复启动不会重复迁移，也不会再产生备份。 */
    @Test
    void rerunningMigrationIsNoOpAndDoesNotBackUpAgain() throws IOException {
        Path database = tempDir.resolve("legacy.db");
        JdbcTemplate jdbc = jdbcFor(database);
        jdbc.execute("CREATE TABLE farm_tasks (id TEXT PRIMARY KEY, title TEXT NOT NULL, task_date TEXT NOT NULL,"
                + " done INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL, source_message_id TEXT)");
        SchemaMigrationService first = migrate(jdbc, database);
        assertThat(first.lastBackup()).isNotBlank();
        long backupsAfterFirstRun = countFiles(tempDir.resolve("backup"), "nongxin-");

        SchemaMigrationService second = migrate(jdbc, database);

        assertThat(second.version()).isEqualTo(SchemaMigrationService.LATEST_VERSION);
        assertThat(second.lastBackup()).isEmpty();
        assertThat(countFiles(tempDir.resolve("backup"), "nongxin-")).isEqualTo(backupsAfterFirstRun);
    }

    private static long countFiles(Path dir, String prefix) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(path -> path.getFileName().toString().startsWith(prefix)).count();
        }
    }

    /** 全新库（schema.sql 已建基线）直接补到目标结构，无需备份。 */
    @Test
    void freshDatabaseGetsTargetSchemaWithoutBackup() throws IOException {
        Path database = tempDir.resolve("fresh.db");
        JdbcTemplate jdbc = jdbcFor(database);

        SchemaMigrationService service = migrate(jdbc, database);

        assertThat(service.version()).isEqualTo(SchemaMigrationService.LATEST_VERSION);
        assertThat(service.lastBackup()).isEmpty();
        assertThat(Files.exists(tempDir.resolve("backup"))).isFalse();
        assertThat(hasColumn(jdbc, "farm_tasks", "status")).isTrue();
        assertThat(hasTable(jdbc, "task_records")).isTrue();
        assertThat(hasTable(jdbc, "uploads")).isTrue();
        // v2 状态机、v3 附件、v4 归档、v5 数据归属：四步迁移各记一条
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM schema_version", Integer.class)).isEqualTo(4);
        assertThat(hasTable(jdbc, "users")).isTrue();
        assertThat(hasColumn(jdbc, "farm_tasks", "user_id")).isTrue();
    }

    /** 有用户数据时每次启动留一份快照，且只保留最近 10 份。 */
    @Test
    void startupSnapshotKeepsRecentCopiesAndSkipsEmptyDatabases() throws IOException {
        Path database = tempDir.resolve("snapshot.db");
        JdbcTemplate jdbc = jdbcFor(database);
        jdbc.execute("CREATE TABLE fields (id TEXT PRIMARY KEY, name TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE conversations (id TEXT PRIMARY KEY, title TEXT NOT NULL, messages_json TEXT NOT NULL)");
        jdbc.update("INSERT INTO fields (id,name) VALUES ('f-1','甲田')");
        jdbc.update("INSERT INTO conversations (id,title,messages_json) VALUES ('c-1','稻瘟病','[]')");

        SchemaMigrationService first = migrate(jdbc, database);
        assertThat(first.lastSnapshot()).isNotBlank();
        Path snapshot = Path.of(first.lastSnapshot());
        assertThat(snapshot).exists();
        assertThat(snapshot.getFileName().toString()).startsWith("startup-");
        // 快照里带着当时的数据，可直接回滚
        JdbcTemplate restored = jdbcFor(snapshot);
        assertThat(restored.queryForObject("SELECT COUNT(*) FROM fields", Integer.class)).isEqualTo(1);
        assertThat(restored.queryForObject("SELECT COUNT(*) FROM conversations", Integer.class)).isEqualTo(1);

        // 再启动两次 → 三份快照；用已完成的真实快照补到 12 份后只保留最近 10 份。
        // 文本/损坏文件不再被当作可删除的数据库备份。
        migrate(jdbc, database);
        migrate(jdbc, database);
        Path dir = tempDir.resolve("backup");
        for (int i = 0; i < 9; i++) Files.copy(snapshot, dir.resolve("startup-v5-19990101-00000" + i + ".db"));
        migrate(jdbc, database);
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files.filter(path -> path.getFileName().toString().startsWith("startup-")).count()).isEqualTo(10);
        }
    }

    /** 空库不产生启动快照（新装环境不刷屏）。 */
    @Test
    void startupSnapshotSkippedForEmptyDatabase() throws IOException {
        Path database = tempDir.resolve("empty.db");
        JdbcTemplate jdbc = jdbcFor(database);

        SchemaMigrationService service = migrate(jdbc, database);

        assertThat(service.lastSnapshot()).isEmpty();
        assertThat(Files.exists(tempDir.resolve("backup"))).isFalse();
    }
}
