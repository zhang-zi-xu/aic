package com.nongxin.service.impl;

import com.nongxin.service.FieldService;

import com.nongxin.model.FieldProfile;
import com.nongxin.model.FieldRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * 田块档案持久化（SQLite，schema.sql 初始化）。
 */
@Service
@Transactional
public class FieldServiceImpl implements FieldService {

    private final JdbcTemplate jdbc;
    private final com.nongxin.service.CurrentUser currentUser;

    public FieldServiceImpl(JdbcTemplate jdbc, com.nongxin.service.CurrentUser currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    private static final RowMapper<FieldRecord> RECORD_MAPPER = (rs, rowNum) ->
            new FieldRecord(rs.getString("record_date"), rs.getString("note"));

    /** 只列当前用户自己的田块（归属过滤在 SQL 里做，不靠前端）。 */
    public List<FieldProfile> list() {
        return jdbc.query("SELECT * FROM fields WHERE user_id=? ORDER BY created_at DESC", (rs, rowNum) -> toProfile(rs),
                currentUser.id());
    }

    /** 取田块：不是自己的返回 null（调用方按 404 处理，不泄露"存在但不属于你"）。 */
    public FieldProfile get(String id) {
        List<FieldProfile> list = jdbc.query("SELECT * FROM fields WHERE id = ? AND user_id=?", (rs, rowNum) -> toProfile(rs),
                id, currentUser.id());
        return list.isEmpty() ? null : list.get(0);
    }

    public FieldProfile create(FieldProfile field) {
        String id = (field.id() == null || field.id().isBlank()) ? "f-" + UUID.randomUUID() : field.id();
        jdbc.update("INSERT INTO fields (id, name, crop, variety, sow_date, area_mu, notes, user_id) VALUES (?,?,?,?,?,?,?,?)",
                id, field.name(), field.crop(), field.variety(), field.sowDate(), field.areaMu(), field.notes(),
                currentUser.id());
        return get(id);
    }

    public FieldProfile update(String id, FieldProfile field) {
        int changed = jdbc.update("UPDATE fields SET name=?, crop=?, variety=?, sow_date=?, area_mu=?, notes=?"
                        + " WHERE id=? AND user_id=?",
                field.name(), field.crop(), field.variety(), field.sowDate(), field.areaMu(), field.notes(), id,
                currentUser.id());
        return changed == 0 ? null : get(id);
    }

    public boolean delete(String id) {
        // 别人的田块一律不动；删除前先确认归属，避免连带删掉别人的记录
        if (get(id) == null) return false;
        // SQLite connections may not enable foreign keys; keep dependent data consistent explicitly.
        jdbc.update("DELETE FROM field_records WHERE field_id = ?", id);
        jdbc.update("UPDATE farm_tasks SET field_id = NULL WHERE field_id = ?", id);
        jdbc.update("UPDATE conversations SET field_id = NULL WHERE field_id = ?", id);
        return jdbc.update("DELETE FROM fields WHERE id = ? AND user_id=?", id, currentUser.id()) > 0;
    }

    public void addRecord(String fieldId, String date, String note) {
        if (get(fieldId) == null) throw new java.util.NoSuchElementException("田块不存在或不属于当前用户");
        jdbc.update("INSERT INTO field_records (field_id, record_date, note) VALUES (?,?,?)", fieldId, date, note);
    }

    private FieldProfile toProfile(ResultSet rs) throws SQLException {
        List<FieldRecord> records = jdbc.query(
                "SELECT record_date, note FROM field_records WHERE field_id = ? ORDER BY record_date ASC, id ASC",
                RECORD_MAPPER, rs.getString("id"));
        Double area = rs.getObject("area_mu") instanceof Number n ? n.doubleValue() : null;
        return new FieldProfile(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("crop"),
                rs.getString("variety"),
                rs.getString("sow_date"),
                area,
                rs.getString("notes"),
                records);
    }
}
