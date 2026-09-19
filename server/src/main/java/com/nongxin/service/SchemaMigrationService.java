package com.nongxin.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteConfig;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 数据库结构版本化迁移。
 *
 * <p>约定：
 * <ul>
 *   <li>应用初始化入口先区分空库与已有库，空库才直接运行目标结构 {@code schema.sql}；</li>
 *   <li>本服务负责把老库按版本逐步升到目标版本，<b>升级前先备份数据库文件</b>到 {@code data/backup/}；</li>
 *   <li>旧库备份和版本迁移后才补建配套表；已有目标版本先核对结构，不靠重复跑脚本掩盖坏版本。</li>
 * </ul>
 *
 * <p>为什么不用清库解决：用户的田块、任务、对话是真实数据，字段变化只能靠迁移。
 */
public class SchemaMigrationService {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrationService.class);

    /** schema.sql 对应的基线版本。 */
    public static final int BASELINE_VERSION = 1;
    /** P2：任务状态机、执行/复查记录、幂等登记。 */
    public static final int TARGET_VERSION = 2;
    /** P3：图片附件表（原图不落库，只登记元数据与引用状态）。 */
    public static final int UPLOAD_VERSION = 3;
    /** P3.5：图片归档到田块（田块时间轴 + 复查来源）。 */
    public static final int ARCHIVE_VERSION = 4;
    /** 数据归属：users 表 + 各资源 user_id（为账号体系与数据隔离打底）。 */
    public static final int OWNER_VERSION = 5;
    /** 当前目标版本。 */
    public static final int LATEST_VERSION = OWNER_VERSION;

    /** 本机所有者：还没有登录体系之前，所有数据都归它，将来接入登录后由登录用户取代。 */
    public static final String LOCAL_OWNER_ID = "local-owner";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSSSSSSSS");
    private static final DateTimeFormatter SNAPSHOT_TIME = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss")
            .withResolverStyle(ResolverStyle.STRICT);
    // Accept only the historical seconds format or the current nanoseconds + UUID format.
    private static final Pattern SNAPSHOT_NAME = Pattern.compile("^startup-v([1-9][0-9]*)-([0-9]{8}-[0-9]{6})"
            + "(?:-([0-9]{9})-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})?\\.db$");

    private final JdbcTemplate jdbc;
    private final String jdbcUrl;
    private final String configuredBackupDir;

    public SchemaMigrationService(JdbcTemplate jdbc, String jdbcUrl, String configuredBackupDir) {
        this.jdbc = jdbc;
        this.jdbcUrl = jdbcUrl;
        this.configuredBackupDir = configuredBackupDir;
    }

    private int version = -1;
    private String lastBackup = "";
    private String lastSnapshot = "";

    /** 启动快照保留份数。 */
    private static final int SNAPSHOT_KEEP = 10;

    /** 当前结构版本（迁移完成后即为 {@link #LATEST_VERSION}）。 */
    public int version() { return version; }

    /** 最近一次迁移前的备份文件路径；未产生备份时为空串。 */
    public String lastBackup() { return lastBackup; }

    /** 最近一次启动快照路径；库里没有用户数据时为空串。 */
    public String lastSnapshot() { return lastSnapshot; }

    /** Backup failure must stop this service before its first schema/data write. */
    public static final class MigrationBackupUnavailable extends IllegalStateException {
        private MigrationBackupUnavailable() {
            super("数据库升级前备份未获确认，已停止本次迁移，请检查备份目录与数据库状态后重试");
        }
    }

    /** Application bootstrap; called once by the sole Boot database initializer, before application JDBC users. */
    public void initializeApplicationDatabase() {
        requireIndependentInitialization();
        boolean existed = hasUserTables();
        int initialVersion = tableExists("schema_version") ? currentVersion() : 0;
        if (initialVersion > LATEST_VERSION) {
            throw new IllegalStateException("数据库版本高于当前程序支持范围，已停止启动，请使用匹配版本的程序");
        }
        if (!existed) {
            applyTargetSchema();
            version = currentVersion();
        } else {
            if (initialVersion == LATEST_VERSION) requireTargetSchema();
            migrate(false, true); // Backup first, then one transaction through complete target schema validation.
        }
        requireTargetSchema();
        snapshotOnStartup();
    }

    /** Standalone migration API retained for focused legacy migration tests; not a Spring lifecycle callback. */
    public void afterPropertiesSet() {
        requireIndependentInitialization();
        migrate(true, false);
    }

    private void migrate(boolean takeSnapshot, boolean completeApplicationSchema) {
        version = -1;
        lastBackup = "";
        lastSnapshot = "";
        boolean hasTaskTable = tableExists("farm_tasks");
        // Do not mistake a fields-only/partially initialized legacy database for an empty new one.
        boolean existed = hasUserTables();
        int current = tableExists("schema_version") ? currentVersion() : 0;
        if (current <= 0) current = hasTaskTable ? BASELINE_VERSION : 0;

        if (current >= LATEST_VERSION) {
            version = current;
            log.info("[schema] 数据库结构版本 {}，无需迁移", current);
            if (takeSnapshot) snapshotOnStartup();
            return;
        }
        // Exactly once, before even creating schema_version. Failure throws instead of returning an empty path.
        if (existed) lastBackup = backupDatabase(current);
        int fromVersion = current;
        try {
            writeTransaction().executeWithoutResult(status -> {
                applyMigrations(fromVersion);
                if (completeApplicationSchema) applyTargetSchema(); // Joins this transaction on the same connection.
            });
        } catch (RuntimeException failure) {
            log.error("[schema] 升级结果未确认，停止启动并保留迁移前备份（{}）：{}",
                    failure.getClass().getSimpleName(), lastBackup);
            throw failure; // Commit may be uncertain; never automatically overwrite the source with the backup.
        }
        version = LATEST_VERSION; // Publish completion only after the outermost commit has returned.
        log.info("[schema] 结构版本 {} 已提交；迁移前备份：{}", version,
                lastBackup.isBlank() ? "（新库，无需备份）" : lastBackup);
        if (takeSnapshot) snapshotOnStartup();
    }

    private void applyMigrations(int current) {
        ensureVersionTable();
        if (current < TARGET_VERSION) {
            applyV2();
            record(TARGET_VERSION, "任务状态机、执行/复查记录、方案项幂等登记");
            current = TARGET_VERSION;
        }
        if (current < UPLOAD_VERSION) {
            applyV3();
            record(UPLOAD_VERSION, "图片附件表（uploads）");
            current = UPLOAD_VERSION;
        }
        if (current < ARCHIVE_VERSION) {
            applyV4();
            record(ARCHIVE_VERSION, "图片归档到田块（field_id / observed_at / note / task_id）");
            current = ARCHIVE_VERSION;
        }
        if (current < OWNER_VERSION) {
            applyV5();
            record(OWNER_VERSION, "数据归属（users 表 + 田块/任务/对话/图片的 user_id）");
        }
    }

    private void requireIndependentInitialization() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.hasResource(Objects.requireNonNull(jdbc.getDataSource()))) {
            throw new IllegalStateException("数据库初始化不能加入未完成的外层事务或绑定连接，已停止启动");
        }
    }

    private TransactionTemplate writeTransaction() {
        var manager = new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource()));
        manager.setRollbackOnCommitFailure(true);
        return new TransactionTemplate(manager);
    }

    private boolean hasUserTables() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sqlite_master WHERE type='table'"
                + " AND name NOT GLOB 'sqlite_*'", Integer.class) > 0;
    }

    private void applyTargetSchema() {
        var source = Objects.requireNonNull(jdbc.getDataSource());
        writeTransaction().executeWithoutResult(status -> {
            var script = new ResourceDatabasePopulator(new ClassPathResource("schema.sql"));
            script.setSqlScriptEncoding("UTF-8");
            script.execute(source);
            requireTargetSchema(); // New schema/marker is rolled back together if initialization is incomplete.
        });
    }

    /** Structural gate, not a data repair or a complete integrity/constraint audit. Only schema metadata is read. */
    private void requireTargetSchema() {
        var required = Map.ofEntries(
                Map.entry("fields", "id name crop variety sow_date area_mu notes created_at user_id"),
                Map.entry("field_records", "id field_id record_date note"),
                Map.entry("farm_tasks", "id title task_date field_id field_name condition_text method review note status time_window materials risk evidence plan_item_id source_message_id created_at updated_at confirmed_at executed_at completed_at user_id"),
                Map.entry("task_records", "id task_id field_id kind record_date note outcome source_message_id created_at"),
                Map.entry("conversations", "id title field_id messages_json created_at user_id"),
                Map.entry("users", "id display_name phone phone_verified wechat_open_id status created_at last_login_at"),
                Map.entry("uploads", "id mime ext bytes width height sha256 created_at referenced_at field_id observed_at note task_id user_id"),
                Map.entry("kb_vectors", "chunk_id model dim vector updated_at"),
                Map.entry("api_usage", "day scope scope_key count updated_at"),
                Map.entry("answer_cache", "cache_key reply plan_json risk_json clarify_json cached_at hit_count"),
                Map.entry("schema_version", "version applied_at note"));
        for (var entry : required.entrySet()) {
            var columns = jdbc.queryForList("SELECT name FROM pragma_table_info(?)", String.class, entry.getKey());
            if (!columns.containsAll(List.of(entry.getValue().split(" ")))) {
                throw new IllegalStateException("数据库版本与结构不一致，已停止启动，请核对备份和迁移记录；不会自动重写版本号");
            }
        }
        if (currentVersion() != LATEST_VERSION) throw new IllegalStateException("数据库版本未确认，已停止启动");
    }

    /**
     * 启动快照：库里有用户数据时先留一份，再对确认属于支持格式的快照保留 {@link #SNAPSHOT_KEEP} 份。
     * 本次快照固定保留，其余按文件名中的时间排序；未知文件不计数、不自动删除。
     * 用途：误删、误操作、脚本写坏数据时，至少能回到"最近一次启动前"的状态。
     */
    private void snapshotOnStartup() {
        if (userRowCount() == 0) return;
        Path source = databaseFile();
        if (source == null || !Files.exists(source)) return;
        try {
            Path dir = backupDir(source);
            Files.createDirectories(dir);
            Path target = snapshotTarget(dir, "startup-v" + Math.max(version, 1));
            if (!copyDatabase(target)) return;
            pruneSnapshots(dir, target);
            lastSnapshot = target.toAbsolutePath().toString();
            log.info("[schema] 启动快照：{}（本次保留，已识别快照的保留目标 {} 份）", lastSnapshot, SNAPSHOT_KEEP);
        } catch (Exception e) {
            log.warn("[schema] 启动快照失败（不影响启动）：{}", e.getMessage());
        }
    }

    private record SnapshotCandidate(Path path, LocalDateTime time) {}

    private void pruneSnapshots(Path dir, Path current) {
        // Do not traverse a redirected directory. An uncertain candidate is kept, never counted as expendable.
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return;
        Path source = databaseFile();
        try (var files = Files.list(dir)) {
            List<SnapshotCandidate> snapshots = files
                    .map(path -> snapshotCandidate(path, source, current))
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(SnapshotCandidate::time)
                            .thenComparing(candidate -> candidate.path().getFileName().toString()))
                    .toList();
            // The current snapshot is excluded above and occupies one retained slot even if the clock went back.
            for (int i = 0; i < snapshots.size() - (SNAPSHOT_KEEP - 1); i++) {
                var candidate = snapshots.get(i);
                if (!candidate.equals(snapshotCandidate(candidate.path(), source, current))) continue;
                Files.deleteIfExists(candidate.path());
            }
        } catch (Exception e) {
            log.warn("[schema] 清理旧快照未完成，停止后续删除（{}）", e.getClass().getSimpleName());
        }
    }

    /** Filename + read-only SQLite checks are a conservative recognition gate, not proof against external tampering. */
    private SnapshotCandidate snapshotCandidate(Path path, Path source, Path current) {
        try {
            var match = SNAPSHOT_NAME.matcher(path.getFileName().toString());
            if (!match.matches()) return null;
            int recordedVersion = Integer.parseInt(match.group(1));
            if (recordedVersion > LATEST_VERSION) return null;
            LocalDateTime time = LocalDateTime.parse(match.group(2), SNAPSHOT_TIME);
            if (match.group(3) != null) time = time.withNano(Integer.parseInt(match.group(3)));
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) == 0) return null;
            if (Files.isSameFile(path, current) || source == null || Files.isSameFile(path, source)) return null;
            // A sidecar may contain uncheckpointed data or indicate an active database; never prune it.
            for (String suffix : List.of("-wal", "-shm", "-journal")) {
                if (!Files.notExists(Path.of(path + suffix), LinkOption.NOFOLLOW_LINKS)) return null;
            }
            var config = new SQLiteConfig();
            config.setReadOnly(true);
            config.setBusyTimeout(100);
            try (var connection = config.createConnection("jdbc:sqlite:" + path.toAbsolutePath());
                 var sql = connection.createStatement()) {
                try (var check = sql.executeQuery("PRAGMA quick_check")) {
                    if (!check.next() || !"ok".equals(check.getString(1)) || check.next()) return null;
                }
                try (var versionRows = sql.executeQuery("SELECT MAX(version) FROM schema_version")) {
                    if (!versionRows.next() || versionRows.getInt(1) != recordedVersion) return null;
                }
                try (var tables = sql.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table'"
                        + " AND name IN ('fields','farm_tasks','conversations')")) {
                    if (!tables.next() || tables.getInt(1) != 3) return null;
                }
            }
            return new SnapshotCandidate(path, time);
        } catch (Exception unknown) {
            return null; // Invalid dates/content, missing entries, unsupported versions and access failures are retained.
        }
    }

    private long userRowCount() {
        long count = 0;
        for (String table : List.of("fields", "farm_tasks", "conversations", "field_records")) {
            if (!tableExists(table)) continue;
            Long rows = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
            count += rows == null ? 0 : rows;
        }
        return count;
    }

    // ---- 版本表 ----

    private void ensureVersionTable() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS schema_version ("
                + "version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL, note TEXT NOT NULL DEFAULT '')");
    }

    private int currentVersion() {
        List<Integer> rows = jdbc.queryForList("SELECT MAX(version) FROM schema_version", Integer.class);
        return rows.isEmpty() || rows.getFirst() == null ? 0 : rows.getFirst();
    }

    private void record(int version, String note) {
        jdbc.update("INSERT OR REPLACE INTO schema_version (version, applied_at, note) VALUES (?,?,?)",
                version, LocalDateTime.now().withNano(0).toString(), note);
    }

    // ---- 迁移前备份 ----

    /** 旧库必须先取得可打开的一致性快照；失败不再放行迁移或复制正在使用的主库文件。 */
    private String backupDatabase(int fromVersion) {
        try {
            Path source = databaseFile();
            if (source == null || !Files.isRegularFile(source)) throw new MigrationBackupUnavailable();
            Path dir = backupDir(source);
            Files.createDirectories(dir);
            Path target = snapshotTarget(dir, "nongxin-v" + fromVersion);
            if (!copyDatabase(target)) throw new MigrationBackupUnavailable();
            return target.toAbsolutePath().toString();
        } catch (Exception e) {
            log.error("[schema] 迁移前备份未确认，停止本次迁移（{}）", e.getClass().getSimpleName());
            throw new MigrationBackupUnavailable();
        }
    }

    private Path snapshotTarget(Path directory, String prefix) {
        // Multiple starts in one second must never overwrite an earlier recovery point.
        return directory.resolve(prefix + "-" + LocalDateTime.now().format(STAMP) + "-" + UUID.randomUUID() + ".db");
    }

    /** 一致性快照：失败关闭。普通文件复制可能遗漏 WAL 中的已提交内容，不能当作安全后备。 */
    private boolean copyDatabase(Path target) {
        Path source = databaseFile();
        if (source == null || !Files.exists(source)) return false;
        String literal = target.toAbsolutePath().toString().replace('\\', '/').replace("'", "''");
        try {
            jdbc.execute("VACUUM INTO '" + literal + "'");
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.size(target) == 0) return false;
            // Read-only verification must not create a new empty database when an expected output is missing.
            var config = new SQLiteConfig();
            config.setReadOnly(true);
            try (var connection = config.createConnection("jdbc:sqlite:" + target.toAbsolutePath());
                 var statement = connection.createStatement();
                 var result = statement.executeQuery("PRAGMA quick_check")) {
                return result.next() && "ok".equals(result.getString(1)) && !result.next();
            }
        } catch (Exception failure) {
            log.warn("[schema] 一致性备份未确认，不使用主库文件复制后备（{}）", failure.getClass().getSimpleName());
            return false;
        }
    }

    private Path backupDir(Path databaseFile) {
        if (configuredBackupDir != null && !configuredBackupDir.isBlank()) return Path.of(configuredBackupDir);
        Path parent = databaseFile.toAbsolutePath().getParent();
        return (parent == null ? Path.of(".") : parent).resolve("backup");
    }

    /** 从 JDBC URL 解析 SQLite 文件路径；内存库返回 null。 */
    private Path databaseFile() {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:sqlite:")) return null;
        String path = jdbcUrl.substring("jdbc:sqlite:".length());
        if (path.isBlank() || path.startsWith(":memory:") || path.startsWith("file::memory:")) return null;
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        return Path.of(path);
    }

    // ---- v2：任务闭环 ----

    private void applyV2() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS farm_tasks ("
                + "id TEXT PRIMARY KEY, title TEXT NOT NULL, task_date TEXT NOT NULL, field_id TEXT,"
                + "field_name TEXT NOT NULL DEFAULT '', condition_text TEXT NOT NULL DEFAULT '',"
                + "method TEXT NOT NULL DEFAULT '', review TEXT NOT NULL DEFAULT '', note TEXT NOT NULL DEFAULT '',"
                + "status TEXT NOT NULL DEFAULT 'pending', time_window TEXT NOT NULL DEFAULT '',"
                + "materials TEXT NOT NULL DEFAULT '', risk TEXT NOT NULL DEFAULT '',"
                + "evidence TEXT NOT NULL DEFAULT '[]', plan_item_id TEXT, source_message_id TEXT,"
                + "created_at TEXT NOT NULL, updated_at TEXT, confirmed_at TEXT, executed_at TEXT, completed_at TEXT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS task_records ("
                + "id TEXT PRIMARY KEY, task_id TEXT NOT NULL, field_id TEXT, kind TEXT NOT NULL,"
                + "record_date TEXT NOT NULL, note TEXT NOT NULL, outcome TEXT NOT NULL DEFAULT '',"
                + "source_message_id TEXT, created_at TEXT NOT NULL)");

        addColumn("farm_tasks", "status", "TEXT NOT NULL DEFAULT 'pending'");
        addColumn("farm_tasks", "time_window", "TEXT NOT NULL DEFAULT ''");
        addColumn("farm_tasks", "materials", "TEXT NOT NULL DEFAULT ''");
        addColumn("farm_tasks", "risk", "TEXT NOT NULL DEFAULT ''");
        addColumn("farm_tasks", "evidence", "TEXT NOT NULL DEFAULT '[]'");
        addColumn("farm_tasks", "plan_item_id", "TEXT");
        addColumn("farm_tasks", "updated_at", "TEXT");
        addColumn("farm_tasks", "confirmed_at", "TEXT");
        addColumn("farm_tasks", "executed_at", "TEXT");
        addColumn("farm_tasks", "completed_at", "TEXT");

        // 旧的 done 布尔量并入状态机：已完成→completed，其余→pending。
        // 完成时间不猜测，迁移来的历史任务 completed_at 保持为空。
        if (columnExists("farm_tasks", "done")) {
            jdbc.update("UPDATE farm_tasks SET status = CASE WHEN done = 1 THEN 'completed' ELSE 'pending' END");
            jdbc.execute("ALTER TABLE farm_tasks DROP COLUMN done");
            log.info("[schema] 事务内合并 farm_tasks.done 到 status，待提交（completed={}）",
                    jdbc.queryForObject("SELECT COUNT(*) FROM farm_tasks WHERE status='completed'", Integer.class));
        }

        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_tasks_date ON farm_tasks(task_date)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_tasks_status ON farm_tasks(status)");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_tasks_plan_item ON farm_tasks(source_message_id, plan_item_id)"
                + " WHERE plan_item_id IS NOT NULL AND source_message_id IS NOT NULL");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_task_records_task ON task_records(task_id, created_at)");
    }

    /** v3：图片附件表。原图存磁盘（data/uploads），库里只留元数据与引用状态。 */
    private void applyV3() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS uploads ("
                + "id TEXT PRIMARY KEY, mime TEXT NOT NULL, ext TEXT NOT NULL, bytes INTEGER NOT NULL,"
                + "width INTEGER NOT NULL, height INTEGER NOT NULL, sha256 TEXT NOT NULL DEFAULT '',"
                + "created_at TEXT NOT NULL, referenced_at TEXT)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_uploads_created ON uploads(created_at)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_uploads_referenced ON uploads(referenced_at)");
    }

    /** v4：图片归档到田块，成为田块的影像档案（时间轴 + 复查来源）。 */
    private void applyV4() {
        addColumn("uploads", "field_id", "TEXT");
        addColumn("uploads", "observed_at", "TEXT");
        addColumn("uploads", "note", "TEXT NOT NULL DEFAULT ''");
        addColumn("uploads", "task_id", "TEXT");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_uploads_field ON uploads(field_id, observed_at)");
    }

    /**
     * v5：数据归属。
     *
     * <p>还没有登录体系，所以先把"归属"落到结构里：建 {@code users} 表，给田块、任务、对话、图片加 {@code user_id}，
     * 老数据全部补给本机所有者 {@link #LOCAL_OWNER_ID}。将来接入登录时，只需把"当前用户从哪来"换成登录态，
     * 表结构与查询条件不用重做。
     */
    private void applyV5() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS users ("
                + "id TEXT PRIMARY KEY,"
                + "display_name TEXT NOT NULL DEFAULT '',"
                + "phone TEXT,"
                + "phone_verified INTEGER NOT NULL DEFAULT 0,"
                + "wechat_open_id TEXT,"
                + "status TEXT NOT NULL DEFAULT 'active',"
                + "created_at TEXT NOT NULL,"
                + "last_login_at TEXT)");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_users_phone ON users(phone) WHERE phone IS NOT NULL");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_users_wechat ON users(wechat_open_id) WHERE wechat_open_id IS NOT NULL");
        // 本机所有者：老数据全部归它，保证升级后一条都不丢
        jdbc.update("INSERT OR IGNORE INTO users (id, display_name, status, created_at, phone_verified)"
                + " VALUES (?, ?, 'active', ?, 0)", LOCAL_OWNER_ID, "本机所有者", java.time.OffsetDateTime.now().toString());
        // 加列用常量默认值，已有行自动填入归属，不必逐行 UPDATE；
        // 表可能不存在（极老的库或测试库），逐个判存在再动，保证迁移对任何库都安全
        addOwnerColumn("fields", "idx_fields_owner");
        addOwnerColumn("farm_tasks", "idx_tasks_owner");
        addOwnerColumn("conversations", "idx_conversations_owner");
        addOwnerColumn("uploads", "idx_uploads_owner");
    }

    /** 给某张表加 user_id 与归属索引；表不存在就跳过（不报错）。 */
    private void addOwnerColumn(String table, String indexName) {
        if (!tableExists(table)) return;
        addColumnWithDefault(table, "user_id", "TEXT NOT NULL DEFAULT '" + LOCAL_OWNER_ID + "'");
        jdbc.execute("CREATE INDEX IF NOT EXISTS " + indexName + " ON " + table + "(user_id)");
    }

    /** 加一列：列已存在就跳过（SQLite 的 ADD COLUMN 支持常量默认值，老行会自动带上）。 */
    private void addColumnWithDefault(String table, String column, String definition) {
        if (columnExists(table, column)) return;
        jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }

    private void addColumn(String table, String column, String definition) {
        if (columnExists(table, column)) return;
        jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        log.info("[schema] 事务内补齐 {}.{}，待提交", table, column);
    }

    private boolean columnExists(String table, String column) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pragma_table_info(?) WHERE name = ?", Integer.class, table, column);
        return count != null && count > 0;
    }

    private boolean tableExists(String table) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?", Integer.class, table);
        return count != null && count > 0;
    }
}
