package com.nongxin.service.impl;

import com.nongxin.service.WeatherService;

import com.nongxin.model.CurrentWeather;
import com.nongxin.model.DailyWeather;
import com.nongxin.model.WeatherBundle;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 天气服务：Open-Meteo 当前天气 + 7 日预报（供农事方案做决策变量）。
 */
@Service
public class WeatherServiceImpl implements WeatherService {

    private static final String BASE = "https://api.open-meteo.com/v1/forecast";
    private static final Map<Integer, String> WEATHER_CODES = Map.ofEntries(
            Map.entry(0, "晴"), Map.entry(1, "大部晴"), Map.entry(2, "少云"), Map.entry(3, "阴"),
            Map.entry(45, "雾"), Map.entry(48, "雾凇"),
            Map.entry(51, "毛毛雨"), Map.entry(53, "毛毛雨"), Map.entry(55, "毛毛雨"),
            Map.entry(56, "冻毛毛雨"), Map.entry(57, "冻毛毛雨"),
            Map.entry(61, "小雨"), Map.entry(63, "中雨"), Map.entry(65, "大雨"),
            Map.entry(66, "冻雨"), Map.entry(67, "冻雨"),
            Map.entry(71, "小雪"), Map.entry(73, "中雪"), Map.entry(75, "大雪"), Map.entry(77, "雪粒"),
            Map.entry(80, "阵雨"), Map.entry(81, "阵雨"), Map.entry(82, "强阵雨"),
            Map.entry(85, "阵雪"), Map.entry(86, "强阵雪"),
            Map.entry(95, "雷暴"), Map.entry(96, "雷暴伴冰雹"), Map.entry(99, "雷暴伴冰雹"));

    private final RestClient restClient;

    public WeatherServiceImpl(@Value("${nongxin.weather-timeout-ms:8000}") int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    public static String weatherText(Integer code) {
        if (code == null) return "未知";
        return WEATHER_CODES.getOrDefault(code, "代码" + code);
    }

    /** 获取天气（days=1 或 7） */
    public WeatherBundle fetch(double lat, double lon, int days) {
        StringBuilder url = new StringBuilder(BASE)
                .append("?latitude=").append(String.format("%.4f", lat))
                .append("&longitude=").append(String.format("%.4f", lon))
                .append("&current=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m,wind_direction_10m")
                .append("&timezone=auto&forecast_days=").append(days);
        if (days > 1) {
            url.append("&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max,wind_speed_10m_max,relative_humidity_2m_max");
        }
        Map<?, ?> data = restClient.get().uri(url.toString())
                .header("Accept", "application/json")
                .retrieve().body(Map.class);
        if (data == null || !(data.get("current") instanceof Map<?, ?> current)) {
            throw new IllegalStateException("天气服务没有返回当前数据");
        }

        CurrentWeather cw = new CurrentWeather(
                num(current.get("temperature_2m")), num(current.get("apparent_temperature")),
                num(current.get("relative_humidity_2m")), num(current.get("precipitation")),
                intOrNull(current.get("weather_code")), num(current.get("wind_speed_10m")),
                num(current.get("wind_direction_10m")));

        List<DailyWeather> dailyList = new ArrayList<>();
        Object dailyObj = data.get("daily");
        if (dailyObj instanceof Map<?, ?> daily) {
            List<?> times = listOf(daily.get("time"));
            List<?> codes = listOf(daily.get("weather_code"));
            List<?> tMax = listOf(daily.get("temperature_2m_max"));
            List<?> tMin = listOf(daily.get("temperature_2m_min"));
            List<?> pSum = listOf(daily.get("precipitation_sum"));
            List<?> pProb = listOf(daily.get("precipitation_probability_max"));
            List<?> wMax = listOf(daily.get("wind_speed_10m_max"));
            List<?> hMax = listOf(daily.get("relative_humidity_2m_max"));
            for (int i = 0; i < times.size(); i++) {
                dailyList.add(new DailyWeather(
                        strAt(times, i), intAt(codes, i), numAt(tMax, i), numAt(tMin, i),
                        numAt(pSum, i), numAt(pProb, i), numAt(wMax, i), numAt(hMax, i)));
            }
        }

        return new WeatherBundle(
                str(data.get("timezone")),
                str(data.get("timezone_abbreviation")),
                str(current.get("time")),
                cw,
                dailyList);
    }

    /** 生成 7 日预报文字（供 Agent 上下文） */
    public String describeDaily(List<DailyWeather> daily) {
        StringBuilder sb = new StringBuilder();
        for (DailyWeather d : daily) {
            if (d.date() == null || d.date().isEmpty()) continue;
            sb.append(d.date()).append(' ').append(weatherText(d.weatherCode()));
            if (d.tempMax() != null && d.tempMin() != null) {
                sb.append("，最高").append(d.tempMax().intValue()).append("°C/最低").append(d.tempMin().intValue()).append("°C");
            }
            if (d.precipitationProbability() != null) {
                sb.append("，降水概率").append(Math.round(d.precipitationProbability())).append('%');
            }
            if (d.precipitationSum() != null && d.precipitationSum() > 0) {
                sb.append("，降水").append(String.format("%.1f", d.precipitationSum())).append("mm");
            }
            if (d.windSpeedMax() != null) {
                sb.append("，最大风速").append(d.windSpeedMax().intValue()).append("km/h");
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    private static Double num(Object v) {
        return v instanceof Number n ? n.doubleValue() : null;
    }

    private static Integer intOrNull(Object v) {
        return v instanceof Number n ? n.intValue() : null;
    }

    private static List<?> listOf(Object v) {
        return v instanceof List<?> list ? list : List.of();
    }

    private static String strAt(List<?> list, int i) {
        return i < list.size() && list.get(i) instanceof String s ? s : "";
    }

    private static Integer intAt(List<?> list, int i) {
        return i < list.size() && list.get(i) instanceof Number n ? n.intValue() : null;
    }

    private static Double numAt(List<?> list, int i) {
        return i < list.size() && list.get(i) instanceof Number n ? n.doubleValue() : null;
    }

    private static String str(Object v) {
        return v instanceof String s ? s : null;
    }
}
