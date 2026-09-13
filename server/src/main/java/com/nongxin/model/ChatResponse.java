package com.nongxin.model;

import java.util.List;
import java.util.Map;

/** 聊天响应体（对齐前端契约：reply/plan/risk/clarify/sources；degraded 表示工具产出已保留但回答未完整生成） */
public record ChatResponse(
        String reply,
        Map<String, Object> plan,
        Map<String, Object> risk,
        Map<String, Object> clarify,
        List<Map<String, Object>> sources,
        int rounds,
        String provider,
        String model,
        boolean degraded) {}
