package com.nongxin.model;

import java.util.List;

/**
 * 聊天请求体（对齐前端 /api/chat 契约）。
 * priorSources：本会话此前各轮已检索命中的来源ID——多轮对话中模型会继续引用它们，
 * 校验时必须与本轮命中集合合并，否则合法引用会被误判为不存在。
 * imageIds：本轮随提问发送的图片附件 id（原图存服务端，请求里不传 base64）。
 * imageInput：图片输入策略 auto/on/off，用户可在模型设置里覆盖名单判断。
 * autoFieldPhotos：田块近况分析——自动带上该田块最近几张照片（仅被明确要求时使用，不会每轮都带）。
 */
public record ChatRequest(
        String provider,
        String model,
        String baseUrl,
        String apiKey,
        List<ChatMsg> messages,
        java.util.Map<String, Object> field,
        java.util.Map<String, Object> location,
        java.util.Map<String, Object> weather,
        List<String> priorSources,
        List<String> imageIds,
        String imageInput,
        Boolean autoFieldPhotos) {}
