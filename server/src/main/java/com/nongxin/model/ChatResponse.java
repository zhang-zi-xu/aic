package com.nongxin.model;

import java.util.Map;

/** 聊天响应体（对齐前端契约：reply/plan/risk/clarify） */
public record ChatResponse(
        String reply,
        Map<String, Object> plan,
        Map<String, Object> risk,
        Map<String, Object> clarify,
        int rounds,
        String provider,
        String model) {}
