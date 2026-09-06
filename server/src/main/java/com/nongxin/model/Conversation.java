package com.nongxin.model;

import java.util.List;

public record Conversation(
        String id, String title, String fieldId, List<SavedChatMessage> messages, String createdAt) {}
