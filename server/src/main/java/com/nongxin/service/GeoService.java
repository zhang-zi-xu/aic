package com.nongxin.service;

import com.nongxin.model.GeoEntry;

/**
 * 地理编码服务接口：内置城市表 + Photon 兜底。
 */
public interface GeoService {

    int cityCount();

    /** 城市名解析（内置表优先），返回 null 表示未命中 */
    GeoEntry resolveCity(String query);

    /** Photon 兜底搜索（县级/特殊地名），失败返回 null */
    GeoEntry photonSearch(String query);

    /** 坐标 → 地名（失败返回 null，调用方降级为坐标文本） */
    String reverseGeocode(double lat, double lon);
}