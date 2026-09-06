package com.nongxin.service;

import com.nongxin.model.FarmTask;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TaskService {
    private final JdbcTemplate jdbc;
    private static final RowMapper<FarmTask> MAPPER = (rs, row) -> new FarmTask(
            rs.getString("id"), rs.getString("title"), rs.getString("task_date"),
            rs.getString("field_id"), rs.getString("field_name"), rs.getString("condition_text"),
            rs.getString("method"), rs.getString("review"), rs.getString("note"), rs.getBoolean("done"),
            rs.getString("created_at"), rs.getString("source_message_id"));

    public TaskService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<FarmTask> list() {
        return jdbc.query("SELECT * FROM farm_tasks ORDER BY task_date ASC, created_at ASC, id ASC", MAPPER);
    }

    public FarmTask get(String id) {
        List<FarmTask> rows = jdbc.query("SELECT * FROM farm_tasks WHERE id=?", MAPPER, id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public FarmTask create(FarmTask task) {
        jdbc.update("INSERT INTO farm_tasks (id,title,task_date,field_id,field_name,condition_text,method,review,note,done,created_at,source_message_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                task.id(), task.title(), task.date(), task.fieldId(), task.fieldName(), task.condition(),
                task.method(), task.review(), task.note(), task.done(), task.createdAt(), task.sourceMessageId());
        return get(task.id());
    }

    public FarmTask update(String id, FarmTask task) {
        int changed = jdbc.update("UPDATE farm_tasks SET title=?,task_date=?,field_id=?,field_name=?,condition_text=?,method=?,review=?,note=?,done=?,source_message_id=? WHERE id=?",
                task.title(), task.date(), task.fieldId(), task.fieldName(), task.condition(),
                task.method(), task.review(), task.note(), task.done(), task.sourceMessageId(), id);
        return changed == 0 ? null : get(id);
    }

    public boolean delete(String id) {
        return jdbc.update("DELETE FROM farm_tasks WHERE id=?", id) > 0;
    }
}
