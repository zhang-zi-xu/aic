package com.nongxin.controller;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** Shared API limits, without changing the user's existing database values. */
public final class RequestValidation {
    private RequestValidation() {}

    public static String requiredText(String value, String label, int max) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "必填");
        return optionalText(value, label, max);
    }

    public static String optionalText(String value, String label, int max) {
        if (value == null) return "";
        if (value.length() > max) throw new IllegalArgumentException(label + "长度不能超过 " + max + " 个字符");
        return value.trim();
    }

    public static String id(String value) {
        String result = requiredText(value, "ID", 120);
        if (!result.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("ID 只能包含字母、数字、下划线和连字符");
        return result;
    }

    public static String date(String value, String label) {
        requiredText(value, label, 10);
        try {
            if (!value.matches("\\d{4}-\\d{2}-\\d{2}")) throw new DateTimeParseException("format", value, 0);
            LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(label + "必须是有效的 YYYY-MM-DD 日期");
        }
        return value;
    }

    public static String createdAt(String value) {
        if (value == null || value.isBlank()) return Instant.now().toString();
        try {
            return Instant.parse(value).toString();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("创建时间必须是有效的 ISO 时间");
        }
    }

    public static void matchingId(String pathId, String bodyId) {
        id(pathId);
        if (bodyId != null && !bodyId.isBlank() && !pathId.equals(bodyId)) {
            throw new IllegalArgumentException("请求 ID 与路径不一致");
        }
    }
}
