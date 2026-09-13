package com.nongxin.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

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
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 图片附件：原图存磁盘，库里只登记元数据。
 *
 * <p>为什么服务端还要重新编码一遍：
 * <ul>
 *   <li>EXIF（含 GPS、设备、拍摄时间）在重编码后不会保留——客户端可能失败或被绕过，服务端必须兜底；</li>
 *   <li>统一成 JPEG 并限制最长边，避免超大图进入供应商请求和磁盘；</li>
 *   <li>顺便校验"能不能真的解码成图片"，防止改了后缀的非图片文件。</li>
 * </ul>
 * 生命周期：上传后 7 天内若没有被任何会话引用（{@link #markReferenced}），启动时会被清理。
 */
@Service
public class UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int MAX_EDGE = 1600;
    private static final float JPEG_QUALITY = 0.85f;
    private static final int MIN_EDGE = 200;

    public record Stored(String id, String mime, String ext, long bytes, int width, int height,
                         String sha256, String createdAt, String referencedAt,
                         String fieldId, String observedAt, String note, String taskId) {}

    private static final RowMapper<Stored> MAPPER = (rs, row) -> new Stored(
            rs.getString("id"), rs.getString("mime"), rs.getString("ext"), rs.getLong("bytes"),
            rs.getInt("width"), rs.getInt("height"), rs.getString("sha256"),
            rs.getString("created_at"), rs.getString("referenced_at"),
            rs.getString("field_id"), rs.getString("observed_at"), rs.getString("note"), rs.getString("task_id"));

    private final JdbcTemplate jdbc;
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
        this.uploadDir = Path.of(uploadDir);
        this.maxBytes = maxBytes;
        this.retentionDays = retentionDays;
        this.currentUser = currentUser;
    }

    /** 上传：校验 → 重编码（去 EXIF）→ 落盘 → 登记。fieldId 非空时同时归入该田块的影像档案。 */
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

        byte[] encoded = encodeJpeg(source);
        String id = "img-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        Path target = fileFor(id, "jpg");
        String createdAt = LocalDateTime.now().withNano(0).toString();
        try {
            Files.createDirectories(uploadDir);
            Path temp = uploadDir.resolve(id + ".tmp");
            Files.write(temp, encoded);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("图片保存失败：" + e.getMessage());
        }
        int outWidth = (int) Math.round(width * scale(width, height));
        int outHeight = (int) Math.round(height * scale(width, height));
        jdbc.update("INSERT INTO uploads (id,mime,ext,bytes,width,height,sha256,created_at,referenced_at,"
                        + "field_id,observed_at,note,task_id,user_id) VALUES (?,?,?,?,?,?,?,?,NULL,?,?,?,?,?)",
                id, "image/jpeg", "jpg", (long) encoded.length, outWidth, outHeight, sha256(encoded), createdAt,
                fieldId == null || fieldId.isBlank() ? null : fieldId,
                observedAt == null || observedAt.isBlank() ? createdAt.substring(0, 10) : observedAt,
                note == null ? "" : note.trim(),
                taskId == null || taskId.isBlank() ? null : taskId,
                currentUser.id());
        log.info("[upload] 已保存 {}：{}×{} {} KB（原图 {}×{}，已重编码去除 EXIF）{}",
                id, outWidth, outHeight, encoded.length / 1024, width, height,
                fieldId == null || fieldId.isBlank() ? "" : "，归入田块 " + fieldId);
        return get(id);
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
                "SELECT COUNT(*) AS photos, COALESCE(SUM(bytes),0) AS bytes FROM uploads WHERE field_id=?", fieldId);
        return Map.of("photos", row.get("photos"), "bytes", row.get("bytes"));
    }

    /** 最近几张（用于"让农心看这块地最近的状况"自动带图）。 */
    public List<Stored> latestForField(String fieldId, int limit) {
        return byField(fieldId, Math.max(1, limit));
    }

    public Stored get(String id) {
        List<Stored> rows = jdbc.query("SELECT * FROM uploads WHERE id=? AND user_id=?", MAPPER, id, currentUser.id());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public List<Stored> find(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return ids.stream().map(this::get).filter(java.util.Objects::nonNull).toList();
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
        int changed = 0;
        String now = LocalDateTime.now().withNano(0).toString();
        for (String id : ids) {
            changed += jdbc.update("UPDATE uploads SET referenced_at=? WHERE id=? AND referenced_at IS NULL AND user_id=?", now, id, currentUser.id());
        }
        return changed;
    }

    /**
     * 删除一张图片。用户对自己的照片有最终处置权：即使已经随会话保存也允许删除，
     * 历史消息里会显示"照片已删除"占位，而不是留下一张无法解释的图。
     */
    public boolean delete(String id) {
        Stored stored = get(id);
        if (stored == null) throw new java.util.NoSuchElementException("图片不存在或已被清理");
        try {
            Files.deleteIfExists(fileFor(stored.id(), stored.ext()));
        } catch (IOException e) {
            log.warn("[upload] 删除文件 {} 失败：{}", id, e.getMessage());
        }
        return jdbc.update("DELETE FROM uploads WHERE id=? AND user_id=?", id, currentUser.id()) > 0;
    }

    /** 修改归档信息（备注、观察日期、归属田块）。 */
    public Stored updateArchive(String id, String note, String observedAt, String fieldId) {
        if (get(id) == null) throw new java.util.NoSuchElementException("图片不存在或已被清理");
        jdbc.update("UPDATE uploads SET note=?, observed_at=?, field_id=? WHERE id=? AND user_id=?",
                note == null ? "" : note.trim(),
                observedAt == null || observedAt.isBlank() ? null : observedAt,
                fieldId == null || fieldId.isBlank() ? null : fieldId, id, currentUser.id());
        return get(id);
    }

    /** 清理超过保留期且从未被会话引用的图片（启动时执行）。 */
    public int cleanupUnreferenced() {
        String cutoff = LocalDateTime.now().minusDays(retentionDays).withNano(0).toString();
        List<Stored> stale = jdbc.query(
                "SELECT * FROM uploads WHERE referenced_at IS NULL AND created_at < ?", MAPPER, cutoff);
        int removed = 0;
        for (Stored stored : stale) {
            try {
                Files.deleteIfExists(fileFor(stored.id(), stored.ext()));
            } catch (IOException e) {
                log.warn("[upload] 删除文件 {} 失败：{}", stored.id(), e.getMessage());
            }
            removed += jdbc.update("DELETE FROM uploads WHERE id=?", stored.id());
        }
        if (removed > 0) log.info("[upload] 清理未引用图片 {} 张（保留期 {} 天）", removed, retentionDays);
        return removed;
    }

    /** 供运维查看：当前附件占用与状态。 */
    public List<Stored> list() {
        return jdbc.query("SELECT * FROM uploads WHERE user_id=? ORDER BY created_at DESC", MAPPER, currentUser.id());
    }

    // ---- 内部 ----

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
