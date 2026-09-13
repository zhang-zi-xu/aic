package com.nongxin.model;

import java.util.List;

/**
 * 农事任务状态机。
 *
 * <p>设计要点：状态推进必须由"事实"驱动——「已执行待复查」只能由用户提交执行记录进入，
 * 「已完成」只能由用户提交复查记录进入，AI 不能自动宣告任务完成。
 * 其余可逆操作（确认安排、取消、重新打开、退回重做）允许直接改状态。
 */
public enum TaskStatus {
    /** 方案草稿已登记为任务，等待用户确认时间/条件后再执行。 */
    PENDING_CONFIRMATION("pending_confirmation", "待确认"),
    /** 用户已确认安排，等待执行。 */
    PENDING("pending", "待执行"),
    /** 用户提交了执行记录，等待复查。 */
    AWAITING_REVIEW("awaiting_review", "已执行待复查"),
    /** 用户提交了复查记录，任务闭环完成。 */
    COMPLETED("completed", "已完成"),
    /** 用户取消，保留全部历史记录。 */
    CANCELLED("cancelled", "取消");

    private final String code;
    private final String label;

    TaskStatus(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() { return code; }

    public String label() { return label; }

    public static TaskStatus of(String code) {
        if (code == null || code.isBlank()) return PENDING;
        String value = code.trim();
        for (TaskStatus status : values()) {
            if (status.code.equalsIgnoreCase(value) || status.name().equalsIgnoreCase(value)) return status;
        }
        throw new IllegalArgumentException("未知的任务状态：" + code + "（可用：" + codes() + "）");
    }

    public static String labelOf(String code) { return of(code).label(); }

    public static List<String> codes() {
        return java.util.Arrays.stream(values()).map(TaskStatus::code).toList();
    }

    /** 不依赖记录即可直接切换到的目标状态。 */
    public boolean allows(TaskStatus target) {
        if (target == this) return true;
        return switch (this) {
            case PENDING_CONFIRMATION -> target == PENDING || target == CANCELLED;
            case PENDING -> target == CANCELLED;
            case AWAITING_REVIEW -> target == PENDING || target == CANCELLED;
            case COMPLETED, CANCELLED -> target == PENDING;
        };
    }

    /** 目标状态是否必须提交执行/复查记录才能进入。 */
    public static boolean requiresRecord(TaskStatus target) {
        return target == AWAITING_REVIEW || target == COMPLETED;
    }

    /** 是否仍在进行中（用于"待办"统计与到期提醒）。 */
    public boolean isOpen() {
        return this != COMPLETED && this != CANCELLED;
    }
}
