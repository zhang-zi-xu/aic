package com.nongxin.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 数据库结构版本化迁移。
 *
 * <p>约定：
 * <ul>
 *   <li>{@code schema.sql} 负责"新库直接建到 v1 基线结构"（CREATE TABLE IF NOT EXISTS，对已有库无副作用）；</li>
 *   <li>本服务负责把老库按版本逐步升到目标版本，<b>升级前先备份数据库文件</b>到 {@code data/backup/}；</li>
 *   <li>每一步都必须可重复执行（列存在就跳过、表存在就跳过），因此中断后重启不会卡住也不会重复加列。</li>
 * </ul>
 *
 * <p>为什么不用清库解决：用户的田块、任务、对话是真实数据，字段变化只能靠迁移。
 */
@Component
@DependsOn("dataSourceScriptDatabaseInitializer")
public class SchemaMigrationService implements InitializingBean {

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

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final JdbcTemplate jdbc;
    private final String jdbcUrl;
    private final String configuredBackupDir;

    public SchemaMigrationService(JdbcTemplate jdbc,
                                  @Value("${spring.datasource.url:}") String jdbcUrl,
                                  @Value("${nongxin.backup-dir:}") String configuredBackupDir) {
        this.jdbc = jdbc;
        this.jdbcUrl = jdbcUrl;
        this.configuredBackupDir = configuredBackupDir;
    }

    private int version = -1;
    private String lastBackup = "";
    private String lastSnapshot = "";

    /** 启动快照保留份数。 */
    private static final int SNAPSHOT_KEEP = 10;

    /** 当前结构版本（迁移完成后即为 {@link #TARGET_VERSION}）。 */
    public int version() { return version; }

    /** 最近一次迁移前的备份文件路径；未产生备份时为空串。 */
    public String lastBackup() { return lastBackup; }

    /** 最近一次启动快照路径；库里没有用户数据时为空串。 */
    public String lastSnapshot() { return lastSnapshot; }

    @Override
    public void afterPropertiesSet() {
        ensureVersionTable();
        boolean hasTaskTable = tableExists("farm_tasks");
        // 启动时就已存在表 → 这是"老库"，升级前值得先备份；全新库不需要
        boolean existed = hasTaskTable;
        int current = currentVersion();
        if (current <= 0) current = hasTaskTable ? BASELINE_VERSION : 0;

        if (current >= LATEST_VERSION) {
            version = current;
            log.info("[schema] 数据库结构版本 {}，无需迁移", current);
            snapshotOnStartup();
            return;
        }
        if (current < TARGET_VERSION) {
            if (existed) lastBackup = backupDatabase(current);
            applyV2();
            record(TARGET_VERSION, "任务状态机、执行/复查记录、方案项幂等登记");
            log.info("[schema] 结构已从 v{} 升级到 v{}", current, TARGET_VERSION);
            current = TARGET_VERSION;
        }
        if (current < UPLOAD_VERSION) {
            // 一次启动里只备份一次：已有备份就复用，避免连升两级时重复拷贝
            if (existed && lastBackup.isBlank()) lastBackup = backupDatabase(current);
            applyV3();
            record(UPLOAD_VERSION, "图片附件表（uploads）");
            log.info("[schema] 结构已从 v{} 升级到 v{}", current, UPLOAD_VERSION);
            current = UPLOAD_VERSION;
        }
        if (current < ARCHIVE_VERSION) {
            if (existed && lastBackup.isBlank()) lastBackup = backupDatabase(current);
            applyV4();
            record(ARCHIVE_VERSION, "图片归档到田块（field_id / observed_at / note / task_id）");
            log.info("[schema] 结构已从 v{} 升级到 v{}", current, ARCHIVE_VERSION);
            current = ARCHIVE_VERSION;
        }
        if (current < OWNER_VERSION) {
            if (existed && lastBackup.isBlank()) lastBackup = backupDatabase(current);
            applyV5();
            record(OWNER_VERSION, "数据归属（users 表 + 田块/任务/对话/图片的 user_id）");
            log.info("[schema] 结构已从 v{} 升级到 v{}", current, OWNER_VERSION);
        }
        version = LATEST_VERSION;
        log.info("[schema] 结构版本 {}；迁移前备份：{}", version,
                lastBackup.isBlank() ? "（新库，无需备份）" : lastBackup);
        snapshotOnStartup();
    }

    /**
     * 启动快照：库里有用户数据时，每次启动先留一份，只保留最近 {@link #SNAPSHOT_KEEP} 份。
     * 用途：误删、误操作、脚本写坏数据时，至少能回到"最近一次启动前"的状态。
     */
    private void snapshotOnStartup() {
        if (userRowCount() == 0) return;
        Path source = databaseFile();
        if (source == null || !Files.exists(source)) return;
        try {
            Path dir = backupDir(source);
            Files.createDirectories(dir);
            Path target = dir.resolve("startup-v" + Math.max(version, 1) + "-" + LocalDateTime.now().format(STAMP) + ".db");
            if (!copyDatabase(target)) return;
            pruneSnapshots(dir);
            lastSnapshot = target.toAbsolutePath().toString();
            log.info("[schema] 启动快照：{}（保留最近 {} 份）", lastSnapshot, SNAPSHOT_KEEP);
        } catch (Exception e) {
            log.warn("[schema] 启动快照失败（不影响启动）：{}", e.getMessage());
        }
    }

    private void pruneSnapshots(Path dir) {
        try (var files = Files.list(dir)) {
            List<Path> snapshots = files
                    .filter(path -> path.getFileName().toString().startsWith("startup-"))
                    .sorted()
                    .toList();
            for (int i = 0; i < snapshots.size() - SNAPSHOT_KEEP; i++) Files.deleteIfExists(snapshots.get(i));
        } catch (Exception e) {
            log.warn("[schema] 清理旧快照失败：{}", e.getMessage());
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

    /** 用 VACUUM INTO 做一致性快照；不支持时退回文件复制。 */
    private String backupDatabase(int fromVersion) {
        Path source = databaseFile();
        if (source == null || !Files.exists(source)) return "";
        try {
            Path dir = backupDir(source);
            Files.createDirectories(dir);
            Path target = dir.resolve("nongxin-v" + fromVersion + "-" + LocalDateTime.now().format(STAMP) + ".db");
            return copyDatabase(target) ? target.toAbsolutePath().toString() : "";
        } catch (Exception e) {
            log.error("[schema] 迁移前备份失败：{}", e.getMessage());
            return "";
        }
    }

    /** 一致性快照：优先 VACUUM INTO（不依赖进程外的文件锁），失败再退回文件复制。 */
    private boolean copyDatabase(Path target) {
        Path source = databaseFile();
        if (source == null || !Files.exists(source)) return false;
        String literal = target.toAbsolutePath().toString().replace('\\', '/').replace("'", "''");
        try {
            jdbc.execute("VACUUM INTO '" + literal + "'");
            return true;
        } catch (Exception vacuumFailed) {
            log.warn("[schema] VACUUM INTO 备份失败（{}），改用文件复制", vacuumFailed.getMessage());
            try {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (Exception copyFailed) {
                log.error("[schema] 备份失败：{}", copyFailed.getMessage());
                return false;
            }
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
            log.info("[schema] 已把 farm_tasks.done 并入 status（completed={}）",
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
        log.info("[schema] {}.{} 已补齐", table, column);
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
