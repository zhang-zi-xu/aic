package com.nongxin.service;

import com.nongxin.model.DailyWeather;
import com.nongxin.model.WeatherBundle;

import java.util.List;

/**
 * 天气服务接口：Open-Meteo 当前天气 + 7 日预报。
 */
public interface WeatherService {

    /** 获取天气（days=1 或 7） */
    WeatherBundle fetch(double lat, double lon, int days);

    /** 生成 7 日预报文字（供 Agent 上下文） */
    String describeDaily(List<DailyWeather> daily);

    /** 天气代码 → 中文描述 */
    static String weatherText(Integer code) {
        if (code == null) return "未知";
        return switch (code) {
            case 0 -> "晴";
            case 1 -> "大部晴";
            case 2 -> "少云";
            case 3 -> "阴";
            case 45 -> "雾";
            case 48 -> "雾凇";
            case 51, 53, 55 -> "毛毛雨";
            case 56, 57 -> "冻毛毛雨";
            case 61 -> "小雨";
            case 63 -> "中雨";
            case 65 -> "大雨";
            case 66, 67 -> "冻雨";
            case 71 -> "小雪";
            case 73 -> "中雪";
            case 75 -> "大雪";
            case 77 -> "雪粒";
            case 80, 81 -> "阵雨";
            case 82 -> "强阵雨";
            case 85 -> "阵雪";
            case 86 -> "强阵雪";
            case 95 -> "雷暴";
            case 96, 99 -> "雷暴伴冰雹";
            default -> "代码" + code;
        };
    }
}