package com.nongxin.model;

/** 行政区条目（内置城市表） */
public record GeoEntry(String name, String prov, double lat, double lon) {}
