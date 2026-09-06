package com.nongxin.model;

import java.util.List;

/** 知识库条目（与前端 kb.json 对齐） */
public record KbEntry(
        String id,
        String crop,
        String topic,
        String title,
        List<String> keywords,
        String symptom,
        String diagnosis,
        List<String> advice,
        String review,
        String source) {}
