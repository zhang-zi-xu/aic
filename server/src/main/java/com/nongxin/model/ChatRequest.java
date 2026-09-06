package com.nongxin.model;

import java.util.List;

/** 聊天请求体（对齐前端 /api/chat 契约） */
public record ChatRequest(
        String provider,
        String model,
        String baseUrl,
        String apiKey,
        List<ChatMsg> messages,
        java.util.Map<String, Object> field,
        java.util.Map<String, Object> location,
        java.util.Map<String, Object> weather) {}
