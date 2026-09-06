package com.nongxin.model;

import java.util.List;

/** 天气组合结果 */
public record WeatherBundle(
        String timezone,
        String timezoneAbbreviation,
        String observedAt,
        CurrentWeather current,
        List<DailyWeather> daily) {}
