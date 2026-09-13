package com.nongxin.model;

import java.util.List;
import java.util.Map;

/**
 * Only conversation content is persisted; provider settings and API keys are not fields.
 * images：随消息发送的图片附件元数据（id/尺寸/字节数），原图存磁盘，**不保存 base64**。
 */
public record SavedChatMessage(
        String id, String role, String content, String attachedData,
        Map<String, Object> plan, Map<String, Object> risk, Map<String, Object> clarify,
        List<Map<String, Object>> evidence,
        String status, String error, Map<String, Object> requestContext,
        Boolean degraded, List<Map<String, Object>> sources,
        List<Map<String, Object>> images) {}
