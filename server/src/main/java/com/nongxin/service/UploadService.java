package com.nongxin.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.regex.Pattern;

/**
 * 图片附件：原图存磁盘，库里只登记元数据。
 *
 * <p>为什么服务端还要重新编码一遍：
 * <ul>
 *   <li>EXIF（含 GPS、设备、拍摄时间）在重编码后不会保留——客户端可能失败或被绕过，服务端必须兜底；</li>
 *   <li>统一成 JPEG 并限制最长边，避免超大图进入供应商请求和磁盘；</li>
 *   <li>顺便校验"能不能真的解码成图片"，防止改了后缀的非图片文件。</li>
 * </ul>
 * 生命周期：启动时只清理超过保留期、无归档/引用标记且已核实不被已保存对话引用的附件。
 * 无法核实引用时跳过清理，不将未知状态当作“未使用”。
 */
@Service
public class UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int MAX_EDGE = 1600;
    private static final float JPEG_QUALITY = 0.85f;
    private static final int MIN_EDGE = 200;
    // Bounded JVM-local locks, shared by service instances. Same photo IDs must not race a rollback restoration.
    private static final Object[] DELETE_LOCKS = java.util.stream.IntStream.range(0, 64)
            .mapToObj(index -> new Object()).toArray();
    private static final Pattern IMAGE_ID_IN_TEXT = Pattern.compile("img-[A-Za-z0-9_-]+");
    private static final ObjectReader RETENTION_JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).reader()
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public record Stored(String id, String mime, String ext, long bytes, int width, int height,
                         String sha256, String createdAt, String referencedAt,
                         String fieldId, String observedAt, String note, String taskId) {}

    private static final RowMapper<Stored> MAPPER = (rs, row) -> new Stored(
            rs.getString("id"), rs.getString("mime"), rs.getString("ext"), rs.getLong("bytes"),
            rs.getInt("width"), rs.getInt("height"), rs.getString("sha256"),
            rs.getString("created_at"), rs.getString("referenced_at"),
            rs.getString("field_id"), rs.getString("observed_at"), rs.getString("note"), rs.getString("task_id"));

    private final JdbcTemplate jdbc;
    private final TransactionTemplate saveTransaction;
    private final TransactionTemplate deleteTransaction;
    private final TransactionTemplate archiveTransaction;
    private final CurrentUser currentUser;
    private final Path uploadDir;
    private final int maxBytes;
    private final int retentionDays;

    public UploadService(JdbcTemplate jdbc,
                         @Value("${nongxin.upload-dir:./data/uploads}") String uploadDir,
                         @Value("${nongxin.upload-max-bytes:8388608}") int maxBytes,
                         @Value("${nongxin.upload-retention-days:7}") int retentionDays,
                         CurrentUser currentUser) {
        this.jdbc = jdbc;
        var manager = new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource()));
        // Try rollback on commit failure, but never treat that as proof that commit did not reach disk.
        manager.setRollbackOnCommitFailure(true);
        this.saveTransaction = new TransactionTemplate(manager);
        this.deleteTransaction = new TransactionTemplate(manager);
        this.archiveTransaction = new TransactionTemplate(manager);
        this.uploadDir = Path.of(uploadDir);
        this.maxBytes = maxBytes;
        this.retentionDays = retentionDays;
        this.currentUser = currentUser;
    }

    /** 固定错误，不把 SQL、磁盘路径或原始异常带入 HTTP 响应。提交结果不明时须先核对列表。 */
    public static final class SaveUnavailable extends RuntimeException {
        private SaveUnavailable() {
            super("图片保存暂时无法确认，请刷新影像列表核对后再重试");
        }
    }

    /** 删除未获确认，不保证文件仍存在；无路径、SQL 或原始原因的固定错误。 */
    public static final class DeleteUnavailable extends RuntimeException {
        public DeleteUnavailable() {
            super("图片删除暂时无法确认，请刷新影像列表核对后再重试");
        }
    }

    public static final class ArchiveChanged extends RuntimeException {
        private ArchiveChanged() {
            super("图片归档状态已发生变化，可能已被删除或清理，请刷新影像列表后重试");
        }
    }

    public static final class ArchiveUnavailable extends RuntimeException {
        private ArchiveUnavailable() {
            super("图片归档保存暂时无法确认，请保留修改内容，刷新影像列表核对后再重试");
        }
    }

    /** 上传：校验 → 重编码/暂存 → 事务内核对 ID、发布文件、登记 → 提交。不是分布式事务。 */
    public Stored store(byte[] raw, String declaredContentType, String fieldId, String observedAt, String note, String taskId) {
        if (raw == null || raw.length == 0) throw new IllegalArgumentException("没有收到图片内容");
        if (raw.length > maxBytes) {
            throw new IllegalArgumentException("图片超过 " + (maxBytes / 1024 / 1024) + " MB，请压缩后再传");
        }
        String declared = declaredContentType == null ? "" : declaredContentType.toLowerCase(Locale.ROOT);
        if (!declared.isBlank() && !declared.startsWith("image/jpeg") && !declared.startsWith("image/png")
                && !declared.startsWith("image/jpg")) {
            throw new IllegalArgumentException("只支持 JPEG / PNG 图片（当前：" + declared + "）。HEIC/WEBP 请先在相册里转存或截图后再传。");
        }
        // Resolve ownership once, before any file is written. Controller validation is not a service boundary.
        String owner = currentUser.id();
        fieldId = optionalId(fieldId);
        taskId = optionalId(taskId);
        requireOwnedField(fieldId, owner);
        requireOwnedTask(taskId, owner);
        BufferedImage source;
        try {
            source = ImageIO.read(new ByteArrayInputStream(raw));
        } catch (IOException e) {
            throw new IllegalArgumentException("图片读取失败，请重新选择文件");
        }
        if (source == null) throw new IllegalArgumentException("这不是可识别的图片文件（可能已损坏或格式不受支持）");
        int width = source.getWidth(), height = source.getHeight();
        if (Math.min(width, height) < MIN_EDGE) {
            throw new IllegalArgumentException("图片分辨率太低（" + width + "×" + height + "），病害识别需要更清楚的照片，请靠近重拍");
        }

        // Returning before an outer transaction commits would report false success. Do not silently join it,
        // or suspend it and wait for another connection from a one-connection SQLite pool.
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.hasResource(jdbc.getDataSource())) {
            log.warn("[upload] 保存入口不支持未完成的外层事务，尚未创建文件");
            throw new SaveUnavailable();
        }
        byte[] encoded = encodeJpeg(source);
        String id = "img-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        Path target = fileFor(id, "jpg");
        String createdAt = LocalDateTime.now().withNano(0).toString();
        int outWidth = (int) Math.round(width * scale(width, height));
        int outHeight = (int) Math.round(height * scale(width, height));
        var candidate = new Stored(id, "image/jpeg", "jpg", encoded.length, outWidth, outHeight,
                sha256(encoded), createdAt, null, fieldId,
                observedAt == null || observedAt.isBlank() ? createdAt.substring(0, 10) : observedAt,
                note == null ? "" : note.trim(), taskId);
        Path temporary = null;
        try {
            Files.createDirectories(uploadDir);
            // Atomically allocate our own staging path; never truncate a pre-existing .tmp file.
            temporary = Files.createTempFile(uploadDir, id + "-", ".tmp");
            Files.write(temporary, encoded);
            Path staged = temporary;
            saveTransaction.executeWithoutResult(status -> {
                var publication = new Publication(id, target);
                TransactionSynchronizationManager.registerSynchronization(publication);
                // Check globally, not just for the current owner: do not fill/overwrite an older row's missing file.
                Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM uploads WHERE id=?", Integer.class, id);
                if (existing == null || existing != 0) throw new SaveUnavailable();
                try {
                    // No REPLACE_EXISTING: a collision must preserve the original destination.
                    Files.move(staged, target);
                    publication.created = true;
                } catch (IOException failure) {
                    // A failed move may have an uncertain outcome; only a successfully returned move proves ownership.
                    throw new SaveUnavailable();
                }
                // Publish before INSERT: even failed rollback/connection cleanup cannot commit a row before its file exists.
                int inserted = jdbc.update("INSERT INTO uploads (id,mime,ext,bytes,width,height,sha256,created_at,referenced_at,"
                                + "field_id,observed_at,note,task_id,user_id) VALUES (?,?,?,?,?,?,?,?,NULL,?,?,?,?,?)",
                        id, candidate.mime(), candidate.ext(), candidate.bytes(), outWidth, outHeight, candidate.sha256(),
                        createdAt, candidate.fieldId(), candidate.observedAt(), candidate.note(), candidate.taskId(), owner);
                if (inserted != 1) throw new SaveUnavailable();
            });
        } catch (IOException | RuntimeException failure) {
            log.warn("[upload] 保存未确认 {}（{}）；未确认的最终文件保留供核对", id, failure.getClass().getSimpleName());
            throw new SaveUnavailable();
        } finally {
            if (temporary != null) removeCreatedFile(temporary, id, "暂存文件");
        }
        log.info("[upload] 已保存 {}：{}×{} {} KB（原图 {}×{}，已重编码去除 EXIF）{}",
                id, outWidth, outHeight, encoded.length / 1024, width, height,
                fieldId == null || fieldId.isBlank() ? "" : "，归入田块 " + fieldId);
        // Avoid a second DB read after commit turning a successful save into a null/failed response.
        return candidate;
    }

    private final class Publication implements TransactionSynchronization {
        private final String id;
        private final Path target;
        private boolean created;
        private boolean commitStarted;

        private Publication(String id, Path target) { this.id = id; this.target = target; }

        @Override public void beforeCommit(boolean readOnly) { commitStarted = true; }

        @Override public void afterCompletion(int status) {
            // Even a successful rollback after a failed commit is not proof of non-commit (lost ACK).
            if (created && status == STATUS_ROLLED_BACK && !commitStarted) {
                removeCreatedFile(target, id, "已回滚的新文件");
            } else if (created && status != STATUS_COMMITTED) {
                log.warn("[upload] 提交结果未确认，保留新图片 {}，需核对登记状态", id);
            }
        }
    }

    private void removeCreatedFile(Path path, String id, String phase) {
        try { Files.deleteIfExists(path); }
        catch (IOException | RuntimeException failure) {
            // Only this call's known new files. Cleanup failure must neither hide the original error nor report data loss.
            log.warn("[upload] {}清理未完成 {}（{}），保留供核对", phase, id, failure.getClass().getSimpleName());
        }
    }

    /** 某田块的影像档案：按观察日期倒序（同日按上传时间倒序）。 */
    public List<Stored> byField(String fieldId, int limit) {
        return jdbc.query("SELECT * FROM uploads WHERE field_id=? AND user_id=?"
                        + " ORDER BY COALESCE(observed_at, created_at) DESC, created_at DESC LIMIT ?",
                MAPPER, fieldId, currentUser.id(), limit <= 0 ? 500 : limit);
    }

    /** 田块影像占用：张数与总字节数，用于界面上显示"占用体积"。 */
    public Map<String, Object> usage(String fieldId) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT COUNT(*) AS photos, COALESCE(SUM(bytes),0) AS bytes FROM uploads WHERE field_id=? AND user_id=?",
                fieldId, currentUser.id());
        return Map.of("photos", row.get("photos"), "bytes", row.get("bytes"));
    }

    /** 最近几张（用于"让农心看这块地最近的状况"自动带图）。 */
    public List<Stored> latestForField(String fieldId, int limit) {
        return byField(fieldId, Math.max(1, limit));
    }

    public Stored get(String id) {
        return get(id, currentUser.id());
    }

    private Stored get(String id, String owner) {
        List<Stored> rows = jdbc.query("SELECT * FROM uploads WHERE id=? AND user_id=?", MAPPER, id, owner);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public List<Stored> find(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        String owner = currentUser.id();
        return ids.stream().map(id -> get(id, owner)).filter(java.util.Objects::nonNull).toList();
    }

    /** 读取原图字节（用于转发给供应商；不写进任何文本字段）。 */
    public byte[] read(String id) {
        Stored stored = get(id);
        if (stored == null) return null;
        Path path = fileFor(stored.id(), stored.ext());
        try {
            return Files.exists(path) ? Files.readAllBytes(path) : null;
        } catch (IOException e) {
            log.warn("[upload] 读取 {} 失败：{}", id, e.getMessage());
            return null;
        }
    }

    public Path fileFor(String id, String ext) { return uploadDir.resolve(id + "." + ext); }

    /** 会话保存时调用：这些图片已经被引用，不再参与清理。 */
    public int markReferenced(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        String owner = currentUser.id();
        int changed = 0;
        String now = LocalDateTime.now().withNano(0).toString();
        for (String id : ids) {
            changed += jdbc.update("UPDATE uploads SET referenced_at=? WHERE id=? AND referenced_at IS NULL AND user_id=?", now, id, owner);
        }
        return changed;
    }

    /** Revalidate/pin sanitized image IDs before saving the conversation, in that save's existing transaction. */
    public boolean confirmConversationReferences(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) return true;
        // Never perform a partial standalone pin or borrow another connection from a single-connection pool.
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(jdbc.getDataSource())) return false;
        String owner = currentUser.id();
        String now = LocalDateTime.now().withNano(0).toString();
        for (String id : ids) {
            // Updating even an already-referenced row distinguishes it from a missing/foreign row.
            int affected = jdbc.update("UPDATE uploads SET referenced_at=COALESCE(referenced_at,?) WHERE id=? AND user_id=?",
                    now, id, owner);
            if (affected != 1) return false;
        }
        return true;
    }

    /**
     * 删除一张图片。用户对自己的照片有最终处置权：即使已经随会话保存也允许删除，
     * 历史消息里会显示"照片已删除"占位，而不是留下一张无法解释的图。
     * 删除前复制到本次独有恢复目录；明确回滚则尝试无覆盖恢复，结果不明则保留副本。
     * false 表示元数据未确认；不是跨介质原子事务，崩溃/恢复失败仍需人工核对。
     */
    public boolean delete(String id) {
        String owner = currentUser.id();
        synchronized (DELETE_LOCKS[Objects.hashCode(id) & (DELETE_LOCKS.length - 1)]) {
            return deleteOwned(id, owner);
        }
    }

    private boolean deleteOwned(String id, String owner) {
        Stored stored;
        try { stored = get(id, owner); }
        catch (RuntimeException failure) { throw new DeleteUnavailable(); }
        if (stored == null) throw new java.util.NoSuchElementException("图片不存在或已被清理");
        return deleteWithRecovery(stored, () -> jdbc.update("DELETE FROM uploads WHERE id=? AND user_id=?", id, owner));
    }

    /** Called while holding the photo's JVM-local lock; authorization/maintenance eligibility stays with the caller. */
    private boolean deleteWithRecovery(Stored stored, IntSupplier deleteRegistration) {
        return deleteWithRecovery(stored, deleteRegistration, () -> true);
    }

    /** The maintenance-only guard reads current state inside the deletion transaction, after the backup copy. */
    private boolean deleteWithRecovery(Stored stored, IntSupplier deleteRegistration, BooleanSupplier beforeUnlink) {
        // Never report success while an outer transaction can still roll back; do not borrow a second pooled connection.
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.hasResource(jdbc.getDataSource())) throw new DeleteUnavailable();
        var recovery = new UploadDeletion(uploadDir, stored.id(), stored.ext());
        try {
            recovery.prepare();
            boolean deleted = Boolean.TRUE.equals(deleteTransaction.execute(status -> {
                TransactionSynchronizationManager.registerSynchronization(recovery);
                if (!beforeUnlink.getAsBoolean()) {
                    status.setRollbackOnly();
                    return false;
                }
                try { recovery.unlink(); }
                catch (IOException | SecurityException failure) { throw new DeleteUnavailable(); }
                int affected = deleteRegistration.getAsInt();
                if (affected != 1) {
                    status.setRollbackOnly();
                    log.warn("[upload] 删除 {} 未确认登记影响行数，已请求回滚", stored.id());
                }
                return affected == 1;
            }));
            if (!deleted) {
                recovery.failed();
                return false;
            }
            // A retained private copy means physical deletion is not complete. Tell the client to refresh/check.
            if (!recovery.discard()) throw new DeleteUnavailable();
            return true;
        } catch (IOException | RuntimeException failure) {
            recovery.failed();
            if (failure instanceof CleanupVerificationUnavailable unavailable) throw unavailable;
            log.warn("[upload] 删除未确认 {}（{}），请核对登记与恢复副本", stored.id(), failure.getClass().getSimpleName());
            throw new DeleteUnavailable();
        }
    }

    /** 修改归档信息；更新与响应快照同事务。直接在外层事务中调用时，最终提交由调用方负责。 */
    public Stored updateArchive(String id, String note, String observedAt, String fieldId) {
        String owner = currentUser.id();
        try {
            if (get(id, owner) == null) throw new java.util.NoSuchElementException("图片不存在或已被清理");
            String field = optionalId(fieldId);
            requireOwnedField(field, owner);
            // The first transactional statement is a write, not an upgrade of a stale read snapshot.
            // Do not take the deletion lock or borrow a second connection for the response read.
            return archiveTransaction.execute(status -> {
                int changed = jdbc.update("UPDATE uploads SET note=?, observed_at=?, field_id=? WHERE id=? AND user_id=?",
                        note == null ? "" : note.trim(),
                        observedAt == null || observedAt.isBlank() ? null : observedAt,
                        field, id, owner);
                if (changed == 0) throw new ArchiveChanged();
                if (changed != 1) throw new ArchiveUnavailable();
                Stored saved = get(id, owner);
                if (saved == null) throw new ArchiveChanged();
                return saved;
            });
        } catch (DataAccessException | TransactionException failure) {
            log.warn("[upload] 归档保存未确认（{}），请刷新列表核对", failure.getClass().getSimpleName());
            throw new ArchiveUnavailable();
        }
    }

    /** System maintenance across all owners; uncertain archive/reference state is retained. */
    public int cleanupUnreferenced() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.hasResource(jdbc.getDataSource())) {
            log.warn("[upload] 自动清理不加入未完成的外层事务或绑定连接，已跳过");
            return 0;
        }
        if (retentionDays < 1) {
            log.warn("[upload] 附件保留天数无效，已跳过自动清理");
            return 0;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays).withNano(0);
        List<Stored> stale;
        Set<String> referenced;
        try {
            // Non-null markers are deliberately retained, even blank/dangling legacy associations.
            stale = jdbc.query("SELECT * FROM uploads WHERE referenced_at IS NULL"
                            + " AND field_id IS NULL AND task_id IS NULL AND created_at < ?", MAPPER, cutoff.toString())
                    .stream().filter(photo -> knownExpired(photo.createdAt(), cutoff)).toList();
            if (stale.isEmpty()) return 0;
            Set<String> candidates = new HashSet<>();
            stale.forEach(photo -> candidates.add(photo.id()));
            // Saving a conversation and setting referenced_at are separate operations. Check actual saved data too.
            referenced = conversationReferences(candidates);
        } catch (Exception e) {
            // Complete verification before the first deletion. Do not print saved text, SQL or paths.
            log.warn("[upload] 附件引用暂时无法核实，已跳过本次清理（{}）", e.getClass().getSimpleName());
            return 0;
        }
        int removed = 0;
        for (Stored stored : stale) {
            if (referenced.contains(stored.id())) continue;
            // Share only physical deletion/recovery with manual delete, never its CurrentUser-scoped authorization.
            synchronized (DELETE_LOCKS[Objects.hashCode(stored.id()) & (DELETE_LOCKS.length - 1)]) {
                try {
                    if (deleteWithRecovery(stored, () -> jdbc.update("DELETE FROM uploads WHERE id=?", stored.id()),
                            () -> cleanupStillEligible(stored, cutoff))) {
                        removed++; // One confirmed commit AND completed copy purge, not an uncertain SQL result.
                    }
                } catch (CleanupVerificationUnavailable failure) {
                    log.warn("[upload] 自动清理前状态无法核实，已停止后续候选；此前确认的清理不会撤销");
                    break;
                } catch (RuntimeException failure) {
                    log.warn("[upload] 自动清理 {} 未确认，未计为成功，请核对登记与恢复副本（{}）",
                            stored.id(), failure.getClass().getSimpleName());
                }
            }
        }
        if (removed > 0) log.info("[upload] 清理已核实未归档、未引用的附件 {} 张（保留期 {} 天）", removed, retentionDays);
        return removed;
    }

    private static final class CleanupVerificationUnavailable extends RuntimeException {}

    private boolean cleanupStillEligible(Stored scanned, LocalDateTime cutoff) {
        try {
            List<Stored> current = jdbc.query("SELECT * FROM uploads WHERE id=?", MAPPER, scanned.id());
            // Any known metadata change invalidates this scan's decision, including a changed path or new notes.
            if (current.size() != 1 || !scanned.equals(current.getFirst())) return false;
            Stored photo = current.getFirst();
            if (photo.fieldId() != null || photo.taskId() != null || photo.referencedAt() != null
                    || !knownExpired(photo.createdAt(), cutoff)) return false;
            // Include newly saved cross-owner conversations, even before referenced_at has been written.
            return !conversationReferences(Set.of(photo.id())).contains(photo.id());
        } catch (RuntimeException failure) {
            log.warn("[upload] 自动清理前登记/引用复核失败（{}）", failure.getClass().getSimpleName());
            throw new CleanupVerificationUnavailable();
        }
    }

    private static boolean knownExpired(String createdAt, LocalDateTime cutoff) {
        if (createdAt == null) return false;
        try { return LocalDateTime.parse(createdAt).isBefore(cutoff); }
        catch (DateTimeParseException e) { return false; }
    }

    private Set<String> conversationReferences(Set<String> candidates) {
        Set<String> referenced = new HashSet<>();
        // No CurrentUser filter: legacy or cross-owner references also protect data from automatic deletion.
        jdbc.query("SELECT messages_json FROM conversations", (org.springframework.jdbc.core.RowCallbackHandler) row -> {
            JsonNode messages;
            try { messages = RETENTION_JSON.readTree(row.getString(1)); }
            catch (JsonProcessingException | IllegalArgumentException e) { throw new SQLException("Invalid saved conversation data"); }
            if (messages == null || !messages.isArray()) throw new SQLException("Unknown saved conversation structure");
            for (JsonNode message : messages) {
                if (!message.isObject()) throw new SQLException("Unknown saved message structure");
                JsonNode images = message.get("images");
                if (images != null && !images.isNull()) {
                    if (!images.isArray()) throw new SQLException("Unknown saved image structure");
                    for (JsonNode image : images) {
                        if (!image.isObject() || !image.path("id").isTextual() || image.path("id").asText().isBlank()) {
                            throw new SQLException("Unknown saved image reference");
                        }
                    }
                }
            }
            // Also retain IDs in snapshots or inline links/text. False positives only retain files.
            var pending = new ArrayDeque<JsonNode>();
            pending.push(messages);
            while (!pending.isEmpty()) {
                JsonNode node = pending.pop();
                if (node.isTextual()) {
                    String text = node.textValue();
                    if (candidates.contains(text)) referenced.add(text);
                    var ids = IMAGE_ID_IN_TEXT.matcher(text);
                    while (ids.find()) if (candidates.contains(ids.group())) referenced.add(ids.group());
                } else if (node.isContainerNode()) node.elements().forEachRemaining(pending::push);
            }
        });
        return referenced;
    }

    /** 供运维查看：当前附件占用与状态。 */
    public List<Stored> list() {
        return jdbc.query("SELECT * FROM uploads WHERE user_id=? ORDER BY created_at DESC", MAPPER, currentUser.id());
    }

    // ---- 内部 ----

    private static String optionalId(String id) { return id == null || id.isBlank() ? null : id; }

    private void requireOwnedField(String fieldId, String owner) {
        if (fieldId == null) return;
        Integer matches = jdbc.queryForObject("SELECT COUNT(*) FROM fields WHERE id=? AND user_id=?", Integer.class, fieldId, owner);
        if (matches == null || matches != 1) throw new IllegalArgumentException("关联田块不存在");
    }

    private void requireOwnedTask(String taskId, String owner) {
        if (taskId == null) return;
        Integer matches = jdbc.queryForObject("SELECT COUNT(*) FROM farm_tasks WHERE id=? AND user_id=?", Integer.class, taskId, owner);
        if (matches == null || matches != 1) throw new IllegalArgumentException("关联任务不存在");
    }

    private static double scale(int width, int height) {
        int longest = Math.max(width, height);
        return longest <= MAX_EDGE ? 1.0 : (double) MAX_EDGE / longest;
    }

    /** 重编码为 JPEG：透明区域铺白底，EXIF/GPS 等元数据不会带入新文件。 */
    private static byte[] encodeJpeg(BufferedImage source) {
        double factor = scale(source.getWidth(), source.getHeight());
        int width = Math.max(1, (int) Math.round(source.getWidth() * factor));
        int height = Math.max(1, (int) Math.round(source.getHeight() * factor));
        BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = canvas.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(JPEG_QUALITY);
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ImageOutputStream stream = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(canvas, null, null), param);
            stream.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("图片处理失败：" + e.getMessage());
        } finally {
            writer.dispose();
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)).substring(0, 32);
        } catch (Exception e) {
            return "";
        }
    }
}
