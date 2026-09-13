package com.nongxin.service;

import com.nongxin.model.FieldProfile;
import com.nongxin.service.impl.FieldServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 数据归属：不同用户之间必须互相看不见、也改不动。
 *
 * <p>这是"先把数据分隔好"的验收测试——用 CurrentUser 模拟两个用户，
 * 断言列表、读取、修改、删除、追加记录全部按归属拒绝。
 */
class DataOwnershipTest {

    private final CurrentUser currentUser = new CurrentUser();
    private final JdbcTemplate jdbc;
    private final FieldServiceImpl fields;

    DataOwnershipTest() throws Exception {
        Path dir = Files.createTempDirectory("nongxin-owner-test-");
        DriverManagerDataSource dataSource = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("test.db"));
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        }
        jdbc = new JdbcTemplate(dataSource);
        fields = new FieldServiceImpl(jdbc, currentUser);
    }

    @AfterEach
    void resetUser() {
        currentUser.reset();
    }

    private FieldProfile field(String id, String name) {
        return new FieldProfile(id, name, "水稻", "甬优1540", "2026-06-01", 3.5, null, List.of());
    }

    @Test
    void fieldsAreInvisibleAndImmutableAcrossUsers() {
        // 甲用户建田块，并留下一条种植记录
        currentUser.setResolver(() -> "u-jia");
        fields.create(field("f-jia", "甲田"));
        fields.addRecord("f-jia", "2026-09-12", "南侧田角有积水");

        // 乙用户看不到、也读不到甲的田块
        currentUser.setResolver(() -> "u-yi");
        assertThat(fields.list()).isEmpty();
        assertThat(fields.get("f-jia")).isNull();

        // 乙用户改不动、删不掉甲的田块，也不能往里追加记录
        assertThat(fields.update("f-jia", field("f-jia", "被改名了"))).isNull();
        assertThat(fields.delete("f-jia")).isFalse();
        assertThatThrownBy(() -> fields.addRecord("f-jia", "2026-09-13", "伪造记录"))
                .isInstanceOf(java.util.NoSuchElementException.class);

        // 甲用户的数据完好无损
        currentUser.setResolver(() -> "u-jia");
        FieldProfile kept = fields.get("f-jia");
        assertThat(kept).isNotNull();
        assertThat(kept.name()).isEqualTo("甲田");
        assertThat(kept.records()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM field_records WHERE field_id='f-jia'", Integer.class)).isEqualTo(1);

        // 乙用户自己的田块照常可用，且列表里只有他自己的
        currentUser.setResolver(() -> "u-yi");
        fields.create(field("f-yi", "乙田"));
        assertThat(fields.list()).extracting(FieldProfile::id).containsExactly("f-yi");
    }

    @Test
    void conversationsAreInvisibleAndImmutableAcrossUsers() throws Exception {
        ObjectMapperHolder holder = new ObjectMapperHolder();
        ConversationService conversations = new ConversationService(jdbc, holder.mapper, currentUser);

        currentUser.setResolver(() -> "u-jia");
        conversations.save(new com.nongxin.model.Conversation("c-jia", "甲田稻瘟病怎么防", "f-jia",
                List.of(new com.nongxin.model.SavedChatMessage("m1", "user", "稻瘟病怎么防", null, null, null, null, null, null, null, null, null, null, null)), "2026-09-12T09:00:00"));

        currentUser.setResolver(() -> "u-yi");
        assertThat(conversations.list()).isEmpty();
        assertThat(conversations.get("c-jia")).isNull();
        assertThat(conversations.rename("c-jia", "被改名了")).isFalse();
        assertThat(conversations.delete("c-jia")).isFalse();
        // 用同一个 id 覆盖保存也不行（ON CONFLICT 的 WHERE 会挡住）
        conversations.save(new com.nongxin.model.Conversation("c-jia", "乙的对话", null,
                List.of(new com.nongxin.model.SavedChatMessage("m2", "user", "覆盖试试", null, null, null, null, null, null, null, null, null, null, null)), "2026-09-12T10:00:00"));

        currentUser.setResolver(() -> "u-jia");
        com.nongxin.model.Conversation kept = conversations.get("c-jia");
        assertThat(kept).isNotNull();
        assertThat(kept.title()).isEqualTo("甲田稻瘟病怎么防");
        assertThat(kept.messages()).hasSize(1);
    }

    /** 只为了拿到带 JavaTimeModule 的 ObjectMapper，避免依赖注入。 */
    private static final class ObjectMapperHolder {
        private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    }

    /** 任务与图片：同样看不见、改不动、删不掉。 */
    @Test
    void tasksAndPhotosAreInvisibleAcrossUsers() throws Exception {
        Path dir = Files.createTempDirectory("nongxin-owner-upload-");
        TaskService tasks = new TaskService(jdbc, new ObjectMapperHolder().mapper, currentUser);
        UploadService uploads = new UploadService(jdbc, dir.toString(), 8_388_608, 7, currentUser);

        // 甲的任务与照片（直接落库，模拟既有数据）
        jdbc.update("INSERT INTO farm_tasks (id,title,task_date,status,created_at,updated_at,user_id)"
                + " VALUES ('t-jia','甲的任务','2026-09-12','pending','2026-09-12T08:00:00','2026-09-12T08:00:00','u-jia')");
        jdbc.update("INSERT INTO task_records (id,task_id,kind,record_date,note,created_at)"
                + " VALUES ('r-jia','t-jia','execution','2026-09-12','已执行','2026-09-12T09:00:00')");
        jdbc.update("INSERT INTO uploads (id,mime,ext,bytes,width,height,sha256,created_at,user_id)"
                + " VALUES ('img-jia','image/jpeg','jpg',1024,800,600,'x','2026-09-12T08:30:00','u-jia')");

        currentUser.setResolver(() -> "u-yi");
        assertThat(tasks.list()).isEmpty();
        assertThat(tasks.get("t-jia")).isNull();
        assertThat(tasks.records("t-jia")).isEmpty();
        assertThat(tasks.delete("t-jia")).isFalse();
        assertThat(tasks.changeStatus("t-jia", com.nongxin.model.TaskStatus.PENDING)).isNull();
        assertThat(uploads.get("img-jia")).isNull();
        assertThat(uploads.list()).isEmpty();
        // 删除别人的照片抛 404 语义的异常（与控制器一致，不泄露"存在但不属于你"）
        assertThatThrownBy(() -> uploads.delete("img-jia")).isInstanceOf(java.util.NoSuchElementException.class);

        // 甲的数据与记录一条不少
        currentUser.setResolver(() -> "u-jia");
        assertThat(tasks.get("t-jia")).isNotNull();
        assertThat(tasks.records("t-jia")).hasSize(1);
        assertThat(uploads.get("img-jia")).isNotNull();
        assertThat(uploads.list()).extracting(UploadService.Stored::id).containsExactly("img-jia");
    }
}
