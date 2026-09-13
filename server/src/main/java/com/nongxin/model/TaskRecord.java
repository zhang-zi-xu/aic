package com.nongxin.model;

/**
 * 任务执行 / 复查记录。用户自己写的事实，不是模型推断。
 *
 * @param kind {@link #KIND_EXECUTION} 执行记录（实际做了什么、什么时候做的）；
 *             {@link #KIND_REVIEW} 复查记录（复查看到什么、结论如何）
 */
public record TaskRecord(
        String id,
        String taskId,
        String fieldId,
        String kind,
        String date,
        String note,
        String outcome,
        String sourceMessageId,
        String createdAt) {

    public static final String KIND_EXECUTION = "execution";
    public static final String KIND_REVIEW = "review";

    public static final String OUTCOME_RESOLVED = "resolved";
    public static final String OUTCOME_IMPROVED = "improved";
    public static final String OUTCOME_UNCHANGED = "unchanged";
    public static final String OUTCOME_WORSE = "worse";
    public static final String OUTCOME_OTHER = "other";

    public static String kindLabel(String kind) {
        return KIND_REVIEW.equals(kind) ? "复查记录" : "执行记录";
    }

    public static String outcomeLabel(String outcome) {
        if (outcome == null || outcome.isBlank()) return "";
        return switch (outcome) {
            case OUTCOME_RESOLVED -> "问题已解决";
            case OUTCOME_IMPROVED -> "明显好转";
            case OUTCOME_UNCHANGED -> "没有变化";
            case OUTCOME_WORSE -> "反而变差";
            case OUTCOME_OTHER -> "其他情况";
            default -> "";
        };
    }
}
