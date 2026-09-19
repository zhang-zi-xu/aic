package com.nongxin.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.jdbc.init.DataSourceScriptDatabaseInitializer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** Only the real database initialization beans/autoconfig, no web server, agents or application runners. */
class DatabaseStartupSafetyTest {
    @TempDir Path directory;

    @Test
    void legacyStartupBacksUpBeforeAnyScriptAndPreservesData() {
        Path database = directory.resolve("legacy.db");
        var jdbc = jdbc(database);
        legacy(jdbc);
        var before = schema(jdbc);
        var tasks = jdbc.queryForList("SELECT * FROM farm_tasks");
        start(database, directory.resolve("backup")).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(SchemaMigrationService.class);
            var migration = context.getBean(SchemaMigrationService.class);
            assertThat(migration.version()).isEqualTo(5);
            assertThat(migration.lastBackup()).isNotBlank();
            var backup = jdbc(Path.of(migration.lastBackup()));
            assertThat(schema(backup)).isEqualTo(before);
            assertThat(backup.queryForList("SELECT * FROM farm_tasks")).isEqualTo(tasks);
            assertThat(jdbc.queryForObject("SELECT status FROM farm_tasks WHERE id='t-old'", String.class)).isEqualTo("completed");
            assertThat(jdbc.queryForObject("SELECT user_id FROM farm_tasks WHERE id='t-old'", String.class)).isEqualTo("local-owner");
            assertThat(jdbc.queryForObject("SELECT messages_json FROM conversations WHERE id='c-old'", String.class)).isEqualTo("[]");
            assertThat(jdbc.queryForObject("SELECT name FROM fields WHERE id='f-old'", String.class)).isEqualTo("synthetic field");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_usage", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM answer_cache", Integer.class)).isZero();
            assertThat(context.getBean(DataSourceScriptDatabaseInitializer.class).initializeDatabase()).isFalse();
            assertThat(migration.lastBackup()).isNotBlank(); // The managed service is the original initialized instance.
        });
    }

    @Test
    void backupFailureStopsStartupWithoutEvenCreatingTargetScriptTables() throws Exception {
        Path database = directory.resolve("blocked.db");
        var jdbc = jdbc(database);
        legacy(jdbc);
        var before = schema(jdbc);
        var tasks = jdbc.queryForList("SELECT * FROM farm_tasks");
        Path blocked = directory.resolve("not-a-directory");
        Files.writeString(blocked, "keep");
        start(database, blocked).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(SchemaMigrationService.MigrationBackupUnavailable.class);
            assertThat(schema(jdbc)).isEqualTo(before);
            assertThat(jdbc.queryForList("SELECT * FROM farm_tasks")).isEqualTo(tasks);
        });
        assertThat(Files.readString(blocked)).isEqualTo("keep");
    }

    @Test
    void emptyDatabaseGetsFullTargetSchemaWithoutMigrationBackup() {
        Path database = directory.resolve("new.db");
        start(database, directory.resolve("backup")).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(SchemaMigrationService.class);
            var migration = context.getBean(SchemaMigrationService.class);
            assertThat(migration.version()).isEqualTo(5);
            assertThat(migration.lastBackup()).isEmpty();
            assertThat(Files.exists(directory.resolve("backup"))).isFalse();
            var jdbc = context.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fields", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_usage", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id='local-owner'", Integer.class)).isEqualTo(1);
        });
    }

    @Test
    void currentSchemaIsNotMigratedAgainAndDoesNotRewriteUserData() {
        Path database = directory.resolve("current.db");
        var jdbc = jdbc(database);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(jdbc.getDataSource());
        jdbc.update("INSERT INTO fields(id,name,crop,sow_date) VALUES ('f-current','synthetic field','synthetic crop','2026-09-01')");
        var before = schema(jdbc);
        var rows = jdbc.queryForList("SELECT * FROM fields");
        start(database, directory.resolve("backup")).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SchemaMigrationService.class).lastBackup()).isEmpty();
            assertThat(schema(jdbc)).isEqualTo(before);
            assertThat(jdbc.queryForList("SELECT * FROM fields")).isEqualTo(rows);
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 4})
    void preOwnershipDatabaseIsBackedUpAndMigratedRatherThanRelabeled(int recordedVersion) {
        Path database = directory.resolve("pre-owner.db");
        var jdbc = jdbc(database);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(jdbc.getDataSource());
        for (String table : List.of("fields", "farm_tasks", "conversations", "uploads")) {
            String index = switch (table) {
                case "farm_tasks" -> "idx_tasks_owner";
                default -> "idx_" + table + "_owner";
            };
            jdbc.execute("DROP INDEX " + index);
            jdbc.execute("ALTER TABLE " + table + " DROP COLUMN user_id");
        }
        jdbc.execute("DROP TABLE users");
        jdbc.update("DELETE FROM schema_version");
        if (recordedVersion > 0) jdbc.update("INSERT INTO schema_version(version,applied_at,note) VALUES (?,'synthetic','v4 fixture')", recordedVersion);
        jdbc.update("INSERT INTO fields(id,name,crop,sow_date) VALUES ('f-v4','synthetic field','crop','2026-09-01')");
        jdbc.update("INSERT INTO farm_tasks(id,title,task_date,status,created_at) VALUES ('t-v4','synthetic task','2026-09-01','completed','2026-09-01')");
        var before = schema(jdbc);
        var rows = jdbc.queryForList("SELECT * FROM farm_tasks");
        start(database, directory.resolve("backup")).run(context -> {
            assertThat(context).hasNotFailed();
            var migration = context.getBean(SchemaMigrationService.class);
            assertThat(migration.lastBackup()).isNotBlank();
            var backup = jdbc(Path.of(migration.lastBackup()));
            assertThat(schema(backup)).isEqualTo(before);
            assertThat(backup.queryForList("SELECT * FROM farm_tasks")).isEqualTo(rows);
            assertThat(jdbc.queryForObject("SELECT user_id FROM fields WHERE id='f-v4'", String.class)).isEqualTo("local-owner");
            assertThat(jdbc.queryForObject("SELECT status FROM farm_tasks WHERE id='t-v4'", String.class)).isEqualTo("completed");
            assertThat(jdbc.queryForObject("SELECT MAX(version) FROM schema_version", Integer.class)).isEqualTo(5);
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {5, 6})
    void incompatibleOrFutureVersionStopsWithoutRunningTargetScript(int version) {
        Path database = directory.resolve("incompatible.db");
        var jdbc = jdbc(database);
        legacy(jdbc);
        jdbc.execute("CREATE TABLE schema_version(version INTEGER PRIMARY KEY,applied_at TEXT NOT NULL,note TEXT NOT NULL DEFAULT '')");
        jdbc.update("INSERT INTO schema_version(version,applied_at) VALUES (?,'synthetic')", version);
        var before = schema(jdbc);
        var rows = jdbc.queryForList("SELECT * FROM farm_tasks");
        start(database, directory.resolve("backup")).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
            assertThat(schema(jdbc)).isEqualTo(before);
            assertThat(jdbc.queryForList("SELECT * FROM farm_tasks")).isEqualTo(rows);
            assertThat(Files.exists(directory.resolve("backup"))).isFalse();
        });
    }

    @Test
    void freshSchemaAndVersionRegistrationRollBackIfFinalStructureCheckFails() {
        Path database = directory.resolve("new-failure.db");
        var jdbc = jdbc(database);
        var checking = new JdbcTemplate(jdbc.getDataSource()) {
            @Override public <T> List<T> queryForList(String sql, Class<T> type, Object... args) {
                if (sql.equals("SELECT name FROM pragma_table_info(?)") && args[0].equals("fields")) return List.of();
                return super.queryForList(sql, type, args);
            }
        };
        var migration = new SchemaMigrationService(checking, "jdbc:sqlite:" + database, directory.resolve("backup").toString());
        assertThatThrownBy(migration::initializeApplicationDatabase).isInstanceOf(IllegalStateException.class);
        assertThat(schema(jdbc)).isEmpty();
        assertThat(migration.version()).isEqualTo(-1);
        assertThat(Files.exists(directory.resolve("backup"))).isFalse();
    }

    private ApplicationContextRunner start(Path database, Path backup) {
        // Discover only production bootstrap components, so the same test observes old and repaired wiring.
        var classes = new ClassPathScanningCandidateComponentProvider(true).findCandidateComponents("com.nongxin").stream()
                .map(definition -> definition.getBeanClassName())
                .filter(name -> name.equals(SchemaMigrationService.class.getName())
                        || name.equals("com.nongxin.config.DatabaseInitializationConfiguration"))
                .map(name -> {
                    try { return Class.forName(name); }
                    catch (ClassNotFoundException failure) { throw new AssertionError(failure); }
                }).toArray(Class<?>[]::new);
        assertThat(classes).isNotEmpty();
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        JdbcTemplateAutoConfiguration.class, SqlInitializationAutoConfiguration.class))
                .withUserConfiguration(classes)
                .withPropertyValues("spring.datasource.url=jdbc:sqlite:" + database,
                        "spring.datasource.driver-class-name=org.sqlite.JDBC",
                        "spring.datasource.hikari.maximum-pool-size=1", "spring.datasource.hikari.connection-timeout=500",
                        "spring.sql.init.mode=always", "spring.sql.init.schema-locations=classpath:schema.sql",
                        "nongxin.backup-dir=" + backup);
    }

    private JdbcTemplate jdbc(Path database) {
        return new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + database));
    }

    private List<Map<String, Object>> schema(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT name,sql FROM sqlite_master WHERE name NOT GLOB 'sqlite_*' ORDER BY name");
    }

    private void legacy(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE fields(id TEXT PRIMARY KEY,name TEXT NOT NULL,crop TEXT NOT NULL,variety TEXT,sow_date TEXT NOT NULL,area_mu REAL,notes TEXT,created_at TEXT)");
        jdbc.execute("CREATE TABLE conversations(id TEXT PRIMARY KEY,title TEXT NOT NULL,field_id TEXT,messages_json TEXT NOT NULL,created_at TEXT)");
        jdbc.execute("CREATE TABLE farm_tasks(id TEXT PRIMARY KEY,title TEXT NOT NULL,task_date TEXT NOT NULL,field_id TEXT,"
                + "field_name TEXT NOT NULL DEFAULT '',condition_text TEXT NOT NULL DEFAULT '',method TEXT NOT NULL DEFAULT '',"
                + "review TEXT NOT NULL DEFAULT '',note TEXT NOT NULL DEFAULT '',done INTEGER NOT NULL DEFAULT 0,created_at TEXT NOT NULL,source_message_id TEXT)");
        jdbc.update("INSERT INTO fields(id,name,crop,sow_date) VALUES ('f-old','synthetic field','synthetic crop','2026-09-01')");
        jdbc.update("INSERT INTO conversations(id,title,field_id,messages_json) VALUES ('c-old','synthetic conversation','f-old','[]')");
        jdbc.update("INSERT INTO farm_tasks(id,title,task_date,field_id,done,created_at) VALUES ('t-old','synthetic task','2026-09-01','f-old',1,'2026-09-01')");
    }
}
