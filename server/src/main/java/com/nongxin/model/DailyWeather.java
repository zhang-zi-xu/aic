package com.nongxin.model;

/** 单日预报 */
public record DailyWeather(
        String date,
        Integer weatherCode,
        Double tempMax,
        Double tempMin,
        Double precipitationSum,
        Double precipitationProbability,
        Double windSpeedMax,
        Double humidityMax) {}
