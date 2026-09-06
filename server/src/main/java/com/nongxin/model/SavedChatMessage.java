package com.nongxin.model;

import java.util.List;
import java.util.Map;

/** Only conversation content is persisted; provider settings and API keys are not fields. */
public record SavedChatMessage(
        String id, String role, String content, String attachedData,
        Map<String, Object> plan, Map<String, Object> risk, Map<String, Object> clarify,
        List<Map<String, Object>> evidence) {}
