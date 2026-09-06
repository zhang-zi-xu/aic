package com.nongxin.service;

import com.nongxin.model.FieldProfile;

import java.util.List;

/**
 * 田块档案服务接口（SQLite 持久化）。
 */
public interface FieldService {

    List<FieldProfile> list();

    FieldProfile get(String id);

    FieldProfile create(FieldProfile field);

    FieldProfile update(String id, FieldProfile field);

    boolean delete(String id);

    /** 追加田块记录（复查打卡等） */
    void addRecord(String fieldId, String date, String note);
}
