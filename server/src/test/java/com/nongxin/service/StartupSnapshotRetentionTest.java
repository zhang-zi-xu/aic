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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.*;

/** All potential deletion targets are fixtures created beneath this test's own @TempDir. */
class StartupSnapshotRetentionTest {
    @TempDir Path directory;
    private Path database;
    private Path backups;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() throws Exception {
        database = directory.resolve("source.db");
        backups = Files.createDirectory(directory.resolve("backup"));
        jdbc = jdbc(database);
        service(jdbc).initializeApplicationDatabase();
        jdbc.update("INSERT INTO fields(id,name,crop,sow_date) VALUES ('synthetic','合成田块','合成作物','2026-09-01')");
    }

    @Test
    void unrelatedFilesCorruptDatabasesAndDirectoriesNeitherDeletedNorCounted() throws Exception {
        Path note = safe(backups.resolve("startup-000-notes.txt"));
        Path corrupt = safe(backups.resolve("startup-v1-19900101-000000.db"));
        Path folder = safe(backups.resolve("startup-001-folder"));
        Path migration = safe(backups.resolve("nongxin-v1-19900101-000000.db"));
        Files.writeString(note, "keep unrelated note");
        Files.writeString(corrupt, "keep invalid database for inspection");
        Files.createDirectory(folder);
        Files.writeString(migration, "keep migration backup");
        List<Path> older = snapshots(12, 5, "20000101");
        var service = service(jdbc);
        service.initializeApplicationDatabase();
        assertThat(note).hasContent("keep unrelated note");
        assertThat(corrupt).hasContent("keep invalid database for inspection");
        assertThat(folder).isDirectory();
        assertThat(migration).hasContent("keep migration backup");
        assertRetention(older, 3, service);
    }

    @Test
    void retainsByTimestampRatherThanSchemaVersionPrefix() throws Exception {
        List<Path> older = snapshots(9, 5, "20000101");
        List<Path> newer = snapshots(3, 2, "20000102");
        var service = service(jdbc);
        service.initializeApplicationDatabase();
        assertRetention(older, 3, service);
        for (Path path : newer) assertThat(path).exists();
        assertThat(entries()).hasSize(10);
    }

    @Test
    void justCreatedSnapshotSurvivesEvenWhenOtherFilenamesAreFutureDated() throws Exception {
        List<Path> future = snapshots(11, 5, "20990101");
        var service = service(jdbc);
        service.initializeApplicationDatabase();
        assertRetention(future, 2, service);
        assertThat(entries()).hasSize(10);
    }

    @ParameterizedTest
    @ValueSource(strings = {"throw", "empty", "corrupt"})
    void failedSnapshotDoesNotPruneAnyExistingRecoveryPoint(String failure) throws Exception {
        List<Path> older = snapshots(12, 5, "20000101");
        var faulty = new JdbcTemplate(jdbc.getDataSource()) {
            @Override public void execute(String sql) {
                if (sql.startsWith("VACUUM INTO '")) {
                    if (failure.equals("throw")) throw new DataAccessResourceFailureException("synthetic backup failure");
                    Path target = safe(Path.of(sql.substring("VACUUM INTO '".length(), sql.length() - 1).replace("''", "'")));
                    try { Files.writeString(target, failure.equals("empty") ? "" : "synthetic corrupt output"); }
                    catch (java.io.IOException problem) { throw new AssertionError(problem); }
                    return;
                }
                super.execute(sql);
            }
        };
        var service = service(faulty);
        service.initializeApplicationDatabase();
        assertThat(service.lastSnapshot()).isEmpty();
        assertThat(entries()).containsAll(older).hasSize(failure.equals("throw") ? 12 : 13);
        for (Path path : older) assertThat(path).exists();
    }

    @Test
    void invalidDatesVersionsAndUnrelatedSqliteDatabasesArePreservedWithoutConsumingSlots() throws Exception {
        List<Path> unknown = new ArrayList<>();
        for (String name : List.of("startup-v5-19990230-000000.db", "startup-v5-19990101-250000.db",
                "startup-v5-19990101-000000-extra.db", "startup-v5-19990101-000000-123-bad-uuid.db",
                "startup-v9999999999999999999999-19990101-000000.db", "startup-v6-19990101-000000.db")) {
            Path path = safe(backups.resolve(name));
            snapshot(path, name.startsWith("startup-v6-") ? 6 : 5);
            unknown.add(path);
        }
        Path mismatched = safe(backups.resolve("startup-v2-19990101-000000.db"));
        snapshot(mismatched, 5);
        unknown.add(mismatched);
        Path unrelated = safe(backups.resolve("startup-v3-19990101-000000.db"));
        jdbc(unrelated).execute("CREATE TABLE unrelated(note TEXT)");
        unknown.add(unrelated);
        Path missingCore = safe(backups.resolve("startup-v4-19990101-000000.db"));
        snapshot(missingCore, 4);
        jdbc(missingCore).execute("DROP TABLE fields"); // Only this generated retention fixture.
        unknown.add(missingCore);
        Path folder = safe(backups.resolve("startup-v5-19990102-000000.db"));
        Files.createDirectory(folder);
        Path note = safe(folder.resolve("keep.txt"));
        Files.writeString(note, "do not recurse into directories");
        unknown.add(note);
        var contents = new LinkedHashMap<Path, byte[]>();
        for (Path path : unknown) contents.put(path, Files.readAllBytes(path));
        List<Path> older = snapshots(12, 5, "20000101");
        var service = service(jdbc);
        service.initializeApplicationDatabase();
        for (var entry : contents.entrySet()) assertThat(Files.readAllBytes(entry.getKey())).containsExactly(entry.getValue());
        assertRetention(older, 3, service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-wal", "-shm", "-journal"})
    void filesWithSqliteSidecarsAreNotOpenedCountedOrDeleted(String suffix) throws Exception {
        Path protectedDb = safe(backups.resolve("startup-v5-19900101-000000.db"));
        snapshot(protectedDb, 5);
        byte[] original = Files.readAllBytes(protectedDb);
        Path sidecar = safe(Path.of(protectedDb + suffix));
        Files.writeString(sidecar, "synthetic sidecar must not be touched");
        List<Path> older = snapshots(12, 5, "20000101");
        var service = service(jdbc);
        service.initializeApplicationDatabase();
        assertThat(Files.readAllBytes(protectedDb)).containsExactly(original);
        assertThat(sidecar).hasContent("synthetic sidecar must not be touched");
        assertRetention(older, 3, service);
    }

    @Test
    void activeDatabaseAndItsHardLinkAreNeverRetentionCandidates() throws Exception {
        database = safe(backups.resolve("startup-v5-19900101-000000.db"));
        jdbc.execute("VACUUM INTO '" + database.toString().replace('\\', '/').replace("'", "''") + "'");
        jdbc = jdbc(database);
        Path alias = safe(backups.resolve("startup-v5-19900101-000001.db"));
        Files.createLink(alias, database);
        assertThat(Files.isSameFile(alias, database)).isTrue();
        List<Path> older = snapshots(12, 5, "20000101");
        var service = service(jdbc);
        service.initializeApplicationDatabase();
        assertThat(database).isRegularFile();
        assertThat(alias).isRegularFile();
        assertThat(jdbc(alias).queryForObject("SELECT name FROM fields WHERE id='synthetic'", String.class)).isEqualTo("合成田块");
        assertRetention(older, 3, service);
    }

    @Test
    void legacySecondsAndCurrentNanosecondsNamesShareTheSameChronologicalOrder() throws Exception {
        var ordered = new ArrayList<Path>();
        Path legacy = safe(backups.resolve("startup-v5-20000101-000000.db"));
        snapshot(legacy, 5);
        ordered.add(legacy);
        for (int nano = 1; nano <= 10; nano++) {
            Path path = safe(backups.resolve("startup-v5-20000101-000000-" + String.format("%09d", nano)
                    + "-00000000-0000-0000-0000-000000000001.db"));
            snapshot(path, 5);
            ordered.add(path);
        }
        var service = service(jdbc);
        service.initializeApplicationDatabase();
        assertRetention(ordered, 2, service);
        assertThat(entries()).hasSize(10);
    }

    private void assertRetention(List<Path> ordered, int removed, SchemaMigrationService service) {
        assertThat(service.lastSnapshot()).isNotBlank();
        assertThat(safe(Path.of(service.lastSnapshot()))).isRegularFile();
        for (int i = 0; i < ordered.size(); i++) {
            assertThat(Files.exists(ordered.get(i), LinkOption.NOFOLLOW_LINKS)).as(ordered.get(i).getFileName().toString())
                    .isEqualTo(i >= removed);
        }
        assertThat(jdbc.queryForObject("SELECT name FROM fields WHERE id='synthetic'", String.class)).isEqualTo("合成田块");
    }

    private List<Path> snapshots(int count, int version, String day) {
        var paths = new ArrayList<Path>();
        for (int i = 0; i < count; i++) {
            Path path = safe(backups.resolve("startup-v" + version + "-" + day + "-" + String.format("%06d", i) + ".db"));
            snapshot(path, version);
            paths.add(path);
        }
        return paths;
    }

    private void snapshot(Path path, int version) {
        assertThat(Files.exists(safe(path))).isFalse();
        var fixture = jdbc(path);
        fixture.execute("CREATE TABLE schema_version(version INTEGER PRIMARY KEY,applied_at TEXT NOT NULL,note TEXT NOT NULL DEFAULT '')");
        fixture.update("INSERT INTO schema_version VALUES (?,'synthetic','synthetic retention fixture')", version);
        for (String table : List.of("fields", "farm_tasks", "conversations")) {
            fixture.execute("CREATE TABLE " + table + " (id TEXT PRIMARY KEY)");
            fixture.update("INSERT INTO " + table + " VALUES ('synthetic')");
        }
    }

    private Path safe(Path path) {
        Path resolved = path.toAbsolutePath().normalize();
        assertThat(resolved.startsWith(directory.toAbsolutePath().normalize())).isTrue();
        assertThat(resolved).isNotEqualTo(directory.toAbsolutePath().normalize());
        return resolved;
    }

    private List<Path> entries() throws Exception {
        try (var stream = Files.list(backups)) { return stream.map(this::safe).toList(); }
    }

    private JdbcTemplate jdbc(Path path) {
        return new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + safe(path)));
    }

    private SchemaMigrationService service(JdbcTemplate template) {
        return new SchemaMigrationService(template, "jdbc:sqlite:" + safe(database), safe(backups).toString());
    }
}
