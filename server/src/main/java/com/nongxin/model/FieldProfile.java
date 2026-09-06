package com.nongxin.model;

import java.util.List;

/** 田块档案（对齐前端 FieldProfile） */
public record FieldProfile(
        String id,
        String name,
        String crop,
        String variety,
        String sowDate,
        Double areaMu,
        String notes,
        List<FieldRecord> records) {}
