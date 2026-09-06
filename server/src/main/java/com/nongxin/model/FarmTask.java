package com.nongxin.model;

public record FarmTask(
        String id, String title, String date, String fieldId, String fieldName,
        String condition, String method, String review, String note, boolean done,
        String createdAt, String sourceMessageId) {}
