package com.nongxin.service;

import com.nongxin.config.DatabaseInitializationConfiguration;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.sqlite.SQLiteConfig;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/** Restore only completed synthetic backups to NEW paths; never copy an active source database. */
class DatabaseBackupRestoreTest {
    @TempDir Path directory;
    private static final String CHAT = "[{\"role\":\"user\",\"content\":\"合成测试：保留换行\\n与标点 ' 。\"}]";

    @ParameterizedTest
    @ValueSource(strings = {"DELETE", "WAL"})
    void legacyBackupRestoresOriginalDataThenPassesManagedUpgradeAndRestart(String journal) throws Exception {
        Path source = directory.resolve("legacy.db");
        var jdbc = jdbc(source);
        legacy(jdbc);
        // Keep WAL alive through backup/recovery. The final original row must be committed after checkpoint.
        try (var keeper = keepJournal(source, journal)) {
            jdbc.update("INSERT INTO farm_tasks(id,title,task_date,done,created_at,note)"
                    + " VALUES ('t-1','合成已完成任务','2026-09-01',1,'2026-09-01','保留完成备注')");
            assertWalPresent(source, journal);
            var before = snapshot(jdbc);
            var migration = migration(source, "source-backups");
            migration.initializeApplicationDatabase();
            Path backup = checkedBackup(migration.lastBackup());
            byte[] backupBytes = Files.readAllBytes(backup);
            assertThat(snapshot(readOnly(backup))).isEqualTo(before);

            jdbc.update("UPDATE fields SET notes='备份之后才写入，不能出现在恢复副本' WHERE id='f-old'");
            var liveState = snapshot(jdbc);
            Path restored = restoreNew(backup, "legacy-restored");
            assertThat(snapshot(jdbc(restored))).isEqualTo(before);
            assertIntegrity(jdbc(restored));

            Path ownBackups = restored.getParent().resolve("backup");
            start(restored, ownBackups).run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(SchemaMigrationService.class);
                var result = context.getBean(SchemaMigrationService.class);
                assertThat(result.version()).isEqualTo(5);
                Path newBackup = checkedBackup(result.lastBackup());
                assertThat(newBackup.getParent()).isEqualTo(ownBackups.toAbsolutePath());
                assertThat(newBackup).isNotEqualTo(backup);
                assertThat(snapshot(readOnly(newBackup))).isEqualTo(before);
                var restoredJdbc = context.getBean(JdbcTemplate.class);
                assertThat(restoredJdbc.queryForList("SELECT status FROM farm_tasks ORDER BY id", String.class))
                        .containsExactly("pending", "completed");
                assertThat(restoredJdbc.queryForList("SELECT completed_at FROM farm_tasks ORDER BY id", String.class))
                        .containsExactly(null, null); // Migration must not invent historical completion times.
                assertThat(restoredJdbc.queryForObject("SELECT notes FROM fields WHERE id='f-old'", String.class))
                        .isEqualTo("原始田块备注");
                assertThat(restoredJdbc.queryForObject("SELECT messages_json FROM conversations WHERE id='c-old'", String.class))
                        .isEqualTo(CHAT);
                assertThat(restoredJdbc.queryForObject("SELECT note FROM farm_tasks WHERE id='t-1'", String.class))
                        .isEqualTo("保留完成备注");
                for (String table : List.of("fields", "farm_tasks", "conversations")) {
                    assertThat(restoredJdbc.queryForList("SELECT DISTINCT user_id FROM " + table, String.class))
                            .containsExactly("local-owner");
                }
                assertThat(restoredJdbc.queryForObject("SELECT COUNT(*) FROM api_usage", Integer.class)).isZero();
                assertIntegrity(restoredJdbc);
            });
            var upgraded = snapshot(jdbc(restored));
            assertStableRestart(restored, ownBackups, upgraded);
            assertThat(snapshot(jdbc)).isEqualTo(liveState);
            assertThat(Files.readAllBytes(backup)).containsExactly(backupBytes);
            assertThat(snapshot(readOnly(backup))).isEqualTo(before);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELETE", "WAL"})
    void currentSnapshotRestoresAllMetadataButNotLaterWritesOrExternalImages(String journal) throws Exception {
        Path source = directory.resolve("current.db");
        migration(source, "source-backups").initializeApplicationDatabase();
        var jdbc = jdbc(source);
        seedCurrent(jdbc);
        Path image = directory.resolve("source-images/img-synthetic.png");
        Files.createDirectories(image.getParent());
        assertThat(ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", image.toFile())).isTrue();
        byte[] imageBytes = Files.readAllBytes(image);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(imageBytes));
        try (var keeper = keepJournal(source, journal)) {
            jdbc.update("INSERT INTO uploads(id,mime,ext,bytes,width,height,sha256,created_at,referenced_at,field_id,observed_at,note,task_id,user_id)"
                    + " VALUES ('img-synthetic','image/png','png',?,2,2,?,'2026-09-01','2026-09-02','f-a','2026-09-01','合成图片备注','t-a','owner-a')",
                    imageBytes.length, hash);
            assertWalPresent(source, journal);
            var before = snapshot(jdbc);
            var service = migration(source, "source-backups");
            service.initializeApplicationDatabase();
            assertThat(service.lastBackup()).isEmpty();
            Path backup = checkedBackup(service.lastSnapshot());
            byte[] backupBytes = Files.readAllBytes(backup);
            assertThat(snapshot(readOnly(backup))).isEqualTo(before);
            jdbc.update("UPDATE conversations SET title='只存在于备份之后' WHERE id='c-a'");
            jdbc.update("INSERT INTO field_records(field_id,record_date,note) VALUES ('f-a','2026-09-03','快照之后')");
            var liveState = snapshot(jdbc);

            Path restored = restoreNew(backup, "snapshot-restored");
            assertThat(snapshot(jdbc(restored))).isEqualTo(before);
            assertStableRestart(restored, restored.getParent().resolve("backup"), before);
            // Reopen a second context, so restoration is also exercised across pool close/reopen.
            assertStableRestart(restored, restored.getParent().resolve("backup"), before);
            var recovered = jdbc(restored);
            assertThat(recovered.queryForList("SELECT user_id FROM fields ORDER BY id", String.class))
                    .containsExactly("owner-a", "owner-b");
            assertThat(recovered.queryForList("SELECT status FROM farm_tasks ORDER BY id", String.class))
                    .containsExactly("pending", "completed");
            assertThat(recovered.queryForObject("SELECT messages_json FROM conversations WHERE id='c-a'", String.class))
                    .isEqualTo(CHAT);
            assertThat(recovered.queryForObject("SELECT sha256 FROM uploads WHERE id='img-synthetic'", String.class)).isEqualTo(hash);
            // AUTOINCREMENT state is part of the backup, not just the user-visible row values.
            recovered.update("INSERT INTO field_records(field_id,record_date,note) VALUES ('f-a','2026-09-04','仅写入副本')");
            assertThat(recovered.queryForObject("SELECT id FROM field_records WHERE note='仅写入副本'", Integer.class)).isEqualTo(43);
            assertIntegrity(recovered);
            assertThat(Files.exists(restored.getParent().resolve("source-images/img-synthetic.png"))).isFalse();
            assertThat(Files.exists(restored.getParent().resolve("uploads"))).isFalse();
            assertThat(Files.readAllBytes(image)).containsExactly(imageBytes);
            assertThat(snapshot(jdbc)).isEqualTo(liveState);
            assertThat(Files.readAllBytes(backup)).containsExactly(backupBytes);
            assertThat(snapshot(readOnly(backup))).isEqualTo(before);
        }
    }

    private void assertStableRestart(Path restored, Path backups, Map<String, Object> expected) {
        start(restored, backups).run(context -> {
            assertThat(context).hasNotFailed();
            var service = context.getBean(SchemaMigrationService.class);
            assertThat(service.version()).isEqualTo(5);
            assertThat(service.lastBackup()).isEmpty();
            assertThat(snapshot(context.getBean(JdbcTemplate.class))).isEqualTo(expected);
            assertThat(snapshot(readOnly(checkedBackup(service.lastSnapshot())))).isEqualTo(expected);
            assertIntegrity(context.getBean(JdbcTemplate.class));
        });
    }

    private Path restoreNew(Path backup, String name) throws Exception {
        Path parent = Files.createDirectory(directory.resolve(name + " 恢复 '副本'"));
        Path target = parent.resolve("nongxin.db").toAbsolutePath().normalize();
        assertThat(target.startsWith(directory.toAbsolutePath().normalize())).isTrue();
        assertThat(target).isNotEqualTo(backup);
        assertThat(Files.exists(target, LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertIntegrity(readOnly(backup));
        Files.copy(backup, target); // Complete, closed VACUUM output only. No REPLACE_EXISTING or sidecar copying.
        assertThat(Files.mismatch(backup, target)).isEqualTo(-1);
        return target;
    }

    private Path checkedBackup(String value) {
        assertThat(value).isNotBlank();
        Path path = Path.of(value).toAbsolutePath().normalize();
        assertThat(path.startsWith(directory.toAbsolutePath().normalize())).isTrue();
        assertThat(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).isTrue();
        return path;
    }

    private JdbcTemplate readOnly(Path path) {
        assertThat(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).isTrue();
        var config = new SQLiteConfig();
        config.setReadOnly(true);
        return new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + path, config.toProperties()));
    }

    private Connection keepJournal(Path source, String journal) throws Exception {
        Connection connection = jdbc(source).getDataSource().getConnection();
        try (var sql = connection.createStatement()) {
            try (var result = sql.executeQuery("PRAGMA journal_mode=" + journal)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualToIgnoringCase(journal);
            }
            if (journal.equals("WAL")) {
                sql.execute("PRAGMA wal_autocheckpoint=0");
                sql.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            }
            return connection;
        } catch (Throwable failure) {
            connection.close();
            throw failure;
        }
    }

    private void assertWalPresent(Path source, String journal) throws Exception {
        if (journal.equals("WAL")) assertThat(Files.size(Path.of(source + "-wal"))).isPositive();
    }

    private void assertIntegrity(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForList("PRAGMA integrity_check", String.class)).containsExactly("ok");
        assertThat(jdbc.queryForList("PRAGMA foreign_key_check")).isEmpty();
    }

    private Map<String, Object> snapshot(JdbcTemplate jdbc) {
        var result = new LinkedHashMap<String, Object>();
        result.put("schema", jdbc.queryForList("SELECT type,name,tbl_name,sql FROM sqlite_master ORDER BY type,name"));
        for (String table : jdbc.queryForList("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name", String.class)) {
            String quoted = "\"" + table.replace("\"", "\"\"") + "\"";
            int columns = jdbc.queryForList("PRAGMA table_info(" + quoted + ")").size();
            String order = java.util.stream.IntStream.rangeClosed(1, columns).mapToObj(Integer::toString)
                    .collect(java.util.stream.Collectors.joining(","));
            result.put(table, jdbc.queryForList("SELECT * FROM " + quoted + " ORDER BY " + order));
        }
        return result;
    }

    private JdbcTemplate jdbc(Path path) {
        return new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + path));
    }

    private SchemaMigrationService migration(Path path, String backupName) {
        return new SchemaMigrationService(jdbc(path), "jdbc:sqlite:" + path, directory.resolve(backupName).toString());
    }

    private ApplicationContextRunner start(Path database, Path backups) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        JdbcTemplateAutoConfiguration.class, SqlInitializationAutoConfiguration.class))
                .withUserConfiguration(DatabaseInitializationConfiguration.class)
                .withPropertyValues("spring.datasource.url=jdbc:sqlite:" + database,
                        "spring.datasource.driver-class-name=org.sqlite.JDBC",
                        "spring.datasource.hikari.maximum-pool-size=1", "spring.datasource.hikari.connection-timeout=500",
                        "spring.sql.init.mode=always", "spring.sql.init.schema-locations=classpath:schema.sql",
                        "nongxin.backup-dir=" + backups);
    }

    private void legacy(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE fields(id TEXT PRIMARY KEY,name TEXT NOT NULL,crop TEXT NOT NULL,variety TEXT,sow_date TEXT NOT NULL,area_mu REAL,notes TEXT,created_at TEXT)");
        jdbc.execute("CREATE TABLE conversations(id TEXT PRIMARY KEY,title TEXT NOT NULL,field_id TEXT,messages_json TEXT NOT NULL,created_at TEXT)");
        jdbc.execute("CREATE TABLE farm_tasks(id TEXT PRIMARY KEY,title TEXT NOT NULL,task_date TEXT NOT NULL,field_id TEXT,"
                + "field_name TEXT NOT NULL DEFAULT '',condition_text TEXT NOT NULL DEFAULT '',method TEXT NOT NULL DEFAULT '',"
                + "review TEXT NOT NULL DEFAULT '',note TEXT NOT NULL DEFAULT '',done INTEGER NOT NULL DEFAULT 0,created_at TEXT NOT NULL,source_message_id TEXT)");
        jdbc.update("INSERT INTO fields(id,name,crop,sow_date,notes) VALUES ('f-old','合成田块','合成作物','2026-09-01','原始田块备注')");
        jdbc.update("INSERT INTO conversations(id,title,field_id,messages_json) VALUES ('c-old','合成对话','f-old',?)", CHAT);
        jdbc.update("INSERT INTO farm_tasks(id,title,task_date,field_id,done,created_at) VALUES ('t-0','合成待办','2026-09-01','f-old',0,'2026-09-01')");
    }

    private void seedCurrent(JdbcTemplate jdbc) {
        for (String suffix : List.of("a", "b")) {
            jdbc.update("INSERT INTO users(id,display_name,created_at) VALUES (?,?,?)", "owner-" + suffix, "合成用户", "2026-09-01");
            jdbc.update("INSERT INTO fields(id,name,crop,sow_date,notes,user_id) VALUES (?, '合成田块','合成作物','2026-09-01','保留备注',?)",
                    "f-" + suffix, "owner-" + suffix);
            jdbc.update("INSERT INTO farm_tasks(id,title,task_date,field_id,status,created_at,user_id) VALUES (?,'合成任务','2026-09-01',?,?,'2026-09-01',?)",
                    "t-" + suffix, "f-" + suffix, suffix.equals("a") ? "pending" : "completed", "owner-" + suffix);
            jdbc.update("INSERT INTO conversations(id,title,field_id,messages_json,user_id) VALUES (?,'合成对话',?,?,?)",
                    "c-" + suffix, "f-" + suffix, CHAT, "owner-" + suffix);
        }
        jdbc.update("INSERT INTO field_records(id,field_id,record_date,note) VALUES (42,'f-a','2026-09-01','合成观察')");
        jdbc.update("INSERT INTO task_records(id,task_id,field_id,kind,record_date,note,outcome,created_at)"
                + " VALUES ('r-b','t-b','f-b','review','2026-09-02','合成复查','合成结果','2026-09-02')");
        jdbc.update("INSERT INTO kb_vectors(chunk_id,model,dim,vector) VALUES ('synthetic-chunk','synthetic-model',2,'[0.1,0.2]')");
        jdbc.update("INSERT INTO api_usage(day,scope,scope_key,count) VALUES ('2026-09-01','global','all',3)");
        jdbc.update("INSERT INTO answer_cache(cache_key,reply,plan_json,risk_json,clarify_json,hit_count)"
                + " VALUES ('synthetic-key','合成缓存','{}','{}','{}',2)");
    }
}
