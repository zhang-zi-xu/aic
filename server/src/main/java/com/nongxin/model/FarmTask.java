package com.nongxin.model;

import java.util.List;
import java.util.Map;

/**
 * 农事任务：由 AI 方案草稿经用户确认后登记，带完整的状态、依据与用户执行/复查记录。
 *
 * <p>{@code statusLabel} 由 {@code status} 推导，忽略调用方传入的值，避免前后端两套文案。
 * {@code evidenceCards} 是服务端按来源库解析出的展示卡片，客户端无法伪造。
 */
public record FarmTask(
        String id,
        String title,
        String date,
        String fieldId,
        String fieldName,
        String condition,
        String method,
        String review,
        String note,
        String status,
        String statusLabel,
        String timeWindow,
        String materials,
        String risk,
        List<String> evidence,
        List<Map<String, Object>> evidenceCards,
        String planItemId,
        String sourceMessageId,
        String createdAt,
        String updatedAt,
        String confirmedAt,
        String executedAt,
        String completedAt,
        List<TaskRecord> records) {

    public FarmTask {
        statusLabel = TaskStatus.of(status).label();
        status = TaskStatus.of(status).code();
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        evidenceCards = evidenceCards == null ? List.of() : List.copyOf(evidenceCards);
        records = records == null ? List.of() : List.copyOf(records);
    }

    /** 判断当前状态能否直接切到目标状态（需要记录的目标状态由 TaskService 校验）。 */
    public boolean canMoveTo(TaskStatus target) {
        return TaskStatus.of(status).allows(target);
    }

    /** 附上执行/复查记录（记录单独成表，查询时挂载）。 */
    public FarmTask withRecords(List<TaskRecord> records) {
        return new FarmTask(id, title, date, fieldId, fieldName, condition, method, review, note, status, statusLabel,
                timeWindow, materials, risk, evidence, evidenceCards, planItemId, sourceMessageId,
                createdAt, updatedAt, confirmedAt, executedAt, completedAt, records);
    }

    /** 附上服务端解析出的依据卡片（客户端无法伪造）。 */
    public FarmTask withEvidenceCards(List<Map<String, Object>> cards) {
        return new FarmTask(id, title, date, fieldId, fieldName, condition, method, review, note, status, statusLabel,
                timeWindow, materials, risk, evidence, cards, planItemId, sourceMessageId,
                createdAt, updatedAt, confirmedAt, executedAt, completedAt, records);
    }
}
