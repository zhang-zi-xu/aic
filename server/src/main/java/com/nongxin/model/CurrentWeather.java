package com.nongxin.model;

/** 当前天气（Open-Meteo current 字段） */
public record CurrentWeather(
        Double temperature,
        Double apparentTemperature,
        Double humidity,
        Double precipitation,
        Integer weatherCode,
        Double windSpeed,
        Double windDirection) {}
