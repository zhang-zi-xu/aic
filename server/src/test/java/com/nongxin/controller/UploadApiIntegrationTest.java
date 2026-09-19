package com.nongxin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nongxin.service.UploadService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 图片附件链路：上传 → 重编码（去 EXIF、限尺寸）→ 存储 → 读取 → 被会话引用后不再清理。
 * 以及"模型不支持看图时明确拒绝、绝不发送伪视觉请求"。
 * 使用临时数据库与临时上传目录，不触碰用户数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
class UploadApiIntegrationTest {

    private static final Path DATABASE = temporary("nongxin-upload-test-").resolve("test.db");
    private static final Path UPLOAD_DIR = temporary("nongxin-uploads-");

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private UploadService uploads;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
        registry.add("nongxin.upload-dir", () -> UPLOAD_DIR.toString());
    }

    private static Path temporary(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(60, 120, 60));
        graphics.fillRect(0, 0, width, height);
        graphics.setColor(new Color(220, 220, 120));
        graphics.fillOval(width / 4, height / 4, width / 2, height / 2);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** 在正常 JPEG 前面塞一段假的 APP1/Exif（含 GPS 字样），用来验证上传后元数据被丢掉。 */
    private static byte[] jpegWithExif() throws IOException {
        byte[] jpeg = toJpeg(png(600, 400));
        byte[] payload = "Exif\u0000\u0000GPSLatitude=30.123456;Make=TestPhone".getBytes(StandardCharsets.ISO_8859_1);
        byte[] segment = new byte[payload.length + 4];
        segment[0] = (byte) 0xFF;
        segment[1] = (byte) 0xE1;
        segment[2] = (byte) ((payload.length + 2) >> 8);
        segment[3] = (byte) ((payload.length + 2) & 0xFF);
        System.arraycopy(payload, 0, segment, 4, payload.length);
        byte[] merged = new byte[2 + segment.length + jpeg.length - 2];
        merged[0] = jpeg[0];
        merged[1] = jpeg[1];
        System.arraycopy(segment, 0, merged, 2, segment.length);
        System.arraycopy(jpeg, 2, merged, 2 + segment.length, jpeg.length - 2);
        return merged;
    }

    private static byte[] toJpeg(byte[] png) throws IOException {
        BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(png));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", out);
        return out.toByteArray();
    }

    private Map<String, Object> upload(String filename, String contentType, byte[] bytes) throws Exception {
        return upload(filename, contentType, bytes, Map.of());
    }

    private Map<String, Object> upload(String filename, String contentType, byte[] bytes, Map<String, String> extra) throws Exception {
        var builder = multipart("/api/uploads")
                .file(new MockMultipartFile("file", filename, contentType, bytes));
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request = builder;
        for (Map.Entry<String, String> entry : extra.entrySet()) request = request.param(entry.getKey(), entry.getValue());
        String body = mvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readValue(body, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    }

    @Test
    void uploadedImageIsReencodedToJpegAndServedWithMetadata() throws Exception {
        Map<String, Object> card = upload("leaf.png", "image/png", png(900, 700));

        assertThat(card.get("mime")).isEqualTo("image/jpeg");
        assertThat(card.get("width")).isEqualTo(900);
        assertThat(card.get("height")).isEqualTo(700);
        assertThat((Integer) card.get("bytes")).isGreaterThan(1000);
        assertThat(card.get("referenced")).isEqualTo(false);
        String id = String.valueOf(card.get("id"));
        assertThat(id).startsWith("img-");

        byte[] served = mvc.perform(get("/api/uploads/" + id))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", MediaType.IMAGE_JPEG_VALUE))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(served[0] & 0xFF).isEqualTo(0xFF);
        assertThat(served[1] & 0xFF).isEqualTo(0xD8);
        assertThat(Files.exists(UPLOAD_DIR.resolve(id + ".jpg"))).isTrue();

        mvc.perform(get("/api/uploads/img-not-exists")).andExpect(status().isNotFound());
    }

    @Test
    void exifAndGpsMetadataAreStrippedOnTheServer() throws Exception {
        byte[] withExif = jpegWithExif();
        assertThat(new String(withExif, StandardCharsets.ISO_8859_1)).contains("GPSLatitude");

        String id = String.valueOf(upload("photo.jpg", "image/jpeg", withExif).get("id"));
        byte[] served = mvc.perform(get("/api/uploads/" + id)).andReturn().getResponse().getContentAsByteArray();

        String asText = new String(served, StandardCharsets.ISO_8859_1);
        assertThat(asText).doesNotContain("Exif").doesNotContain("GPSLatitude").doesNotContain("TestPhone");
        assertThat(asText).doesNotContain("Make=");
    }

    @Test
    void oversizedImagesAreScaledDownAndTinyOnesRejected() throws Exception {
        Map<String, Object> card = upload("big.png", "image/png", png(2400, 1800));
        assertThat(card.get("width")).isEqualTo(1600);
        assertThat(card.get("height")).isEqualTo(1200);

        mvc.perform(multipart("/api/uploads").file(new MockMultipartFile("file", "tiny.png", "image/png", png(40, 30))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("分辨率太低")));
    }

    @Test
    void nonImageOversizedAndUnsupportedTypesAreRejected() throws Exception {
        mvc.perform(multipart("/api/uploads").file(new MockMultipartFile("file", "notes.png", "image/png",
                        "这不是图片，只是改了后缀".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("可识别的图片")));

        mvc.perform(multipart("/api/uploads").file(new MockMultipartFile("file", "leaf.heic", "image/heic", png(600, 400))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("只支持 JPEG / PNG")));

        byte[] tooBig = new byte[9 * 1024 * 1024];
        mvc.perform(multipart("/api/uploads").file(new MockMultipartFile("file", "huge.jpg", "image/jpeg", tooBig)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void conversationSaveKeepsOnlyRealImageIdsAndMarksThemReferenced() throws Exception {
        String id = String.valueOf(upload("leaf.png", "image/png", png(800, 600)).get("id"));
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", "m-photo");
        message.put("role", "user");
        message.put("content", "叶子这样了，是什么问题？");
        message.put("images", List.of(Map.of("id", id, "width", 9999, "bytes", 1), Map.of("id", "img-forged")));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", "c-photo");
        body.put("title", "叶片问题");
        body.put("messages", List.of(message));

        mvc.perform(put("/api/conversations/c-photo").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].images.length()").value(1))
                .andExpect(jsonPath("$.messages[0].images[0].id").value(id))
                .andExpect(jsonPath("$.messages[0].images[0].width").value(800))
                .andExpect(jsonPath("$.messages[0].images[0].bytes").value(org.hamcrest.Matchers.greaterThan(1000)))
                .andExpect(jsonPath("$.messages[0].images[0].url").value("/api/uploads/" + id));

        assertThat(uploads.get(id).referencedAt()).isNotNull();
    }

    /**
     * 归档：照片带田块与拍摄日期入库，成为该田块的影像档案（时间轴 + 占用统计），
     * 备注可改，用户随时可以删除自己的照片——即使已经随对话保存。
     */
    @Test
    void photosCanBeArchivedToAFieldAndDeletedAtAnyTime() throws Exception {
        createFieldForArchive();

        Map<String, Object> first = upload("day1.jpg", "image/jpeg", toJpeg(png(800, 600)),
                Map.of("fieldId", "f-archive", "observedAt", "2026-09-03", "note", "南侧田角，叶尖发黄"));
        Map<String, Object> second = upload("day2.jpg", "image/jpeg", toJpeg(png(800, 600)),
                Map.of("fieldId", "f-archive", "observedAt", "2026-09-20"));
        assertThat(first.get("fieldId")).isEqualTo("f-archive");
        assertThat(first.get("observedAt")).isEqualTo("2026-09-03");
        assertThat(first.get("note")).isEqualTo("南侧田角，叶尖发黄");

        String body = mvc.perform(get("/api/uploads/field/f-archive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.photos.length()").value(2))
                .andExpect(jsonPath("$.usage.photos").value(2))
                .andExpect(jsonPath("$.usage.bytes").value(org.hamcrest.Matchers.greaterThan(1000)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // 时间轴按拍摄日期倒序：9/20 在前
        assertThat(body.indexOf(String.valueOf(second.get("id")))).isLessThan(body.indexOf(String.valueOf(first.get("id"))));

        mvc.perform(patch("/api/uploads/" + first.get("id")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"复查：叶尖已转绿\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").value("复查：叶尖已转绿"));

        mvc.perform(get("/api/uploads/field/f-missing")).andExpect(status().isBadRequest());

        // 已随对话保存的照片同样允许删除（用户对自己的照片有最终处置权）
        mvc.perform(delete("/api/uploads/" + second.get("id"))).andExpect(status().isOk());
        mvc.perform(get("/api/uploads/field/f-archive"))
                .andExpect(jsonPath("$.photos.length()").value(1))
                .andExpect(jsonPath("$.usage.photos").value(1));
    }

    private void createFieldForArchive() throws Exception {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("id", "f-archive");
        field.put("name", "影像档案试验田");
        field.put("crop", "水稻");
        field.put("sowDate", "2026-06-01");
        mvc.perform(post("/api/fields").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(field)))
                .andExpect(status().isOk());
    }
}
