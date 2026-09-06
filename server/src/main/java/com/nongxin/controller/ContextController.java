package com.nongxin.controller;

import com.nongxin.model.GeoEntry;
import com.nongxin.model.WeatherBundle;
import com.nongxin.service.GeoService;
import com.nongxin.service.WeatherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 实时环境（对齐前端 /api/context 契约）：
 * 位置（内置城市表优先，Photon 兜底）+ 当前天气 + 可选 7 日预报。
 * 天气失败不致命：降级返回位置信息。
 */
@RestController
@RequestMapping("/api/context")
public class ContextController {

    private static final Logger log = LoggerFactory.getLogger(ContextController.class);

    private final GeoService geo;
    private final WeatherService weather;

    public ContextController(GeoService geo, WeatherService weather) {
        this.geo = geo;
        this.weather = weather;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> context(
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lon,
            @RequestParam(required = false) String city,
            @RequestParam(defaultValue = "1") int days) {
        int forecastDays = days == 7 ? 7 : 1;
        boolean hasCoords = lat != null && lon != null && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;

        double latitude;
        double longitude;
        String location = null;
        String geocodeSource = null;

        if (hasCoords) {
            latitude = lat;
            longitude = lon;
            location = geo.reverseGeocode(latitude, longitude);
            geocodeSource = "device";
        } else if (city != null && !city.isBlank()) {
            String clean = city.trim();
            if (clean.length() > 80) clean = clean.substring(0, 80);
            GeoEntry entry = geo.resolveCity(clean);
            String source = "table";
            if (entry == null) {
                entry = geo.photonSearch(clean);
                source = "photon";
            }
            if (entry == null) {
                return error(HttpStatus.NOT_FOUND, "找不到「" + clean + "」的位置，请尝试输入地级市名称（如：杭州市）。");
            }
            latitude = entry.lat();
            longitude = entry.lon();
            location = entry.name();
            geocodeSource = source;
        } else {
            return error(HttpStatus.BAD_REQUEST, "请提供定位坐标或城市名称");
        }

        // 天气失败不致命
        WeatherBundle bundle = null;
        String weatherError = null;
        try {
            bundle = weather.fetch(latitude, longitude, forecastDays);
        } catch (Exception e) {
            log.warn("天气服务失败: {}", e.getMessage());
            weatherError = "当前天气服务暂时不可用，请稍后重试";
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("location", location != null ? location : String.format("坐标 %.4f, %.4f", latitude, longitude));
        out.put("latitude", latitude);
        out.put("longitude", longitude);
        out.put("timezone", bundle != null ? bundle.timezone() : null);
        out.put("timezoneAbbreviation", bundle != null ? bundle.timezoneAbbreviation() : null);
        out.put("observedAt", bundle != null ? bundle.observedAt() : null);
        out.put("weather", bundle != null ? bundle.current() : null);
        out.put("daily", bundle != null ? bundle.daily() : null);
        out.put("dailyText", bundle != null ? weather.describeDaily(bundle.daily()) : "");
        out.put("weatherError", weatherError);
        Map<String, Object> sources = new LinkedHashMap<>();
        sources.put("weather", bundle != null ? "Open-Meteo" : null);
        sources.put("location", "table".equals(geocodeSource) ? "内置城市表"
                : "photon".equals(geocodeSource) ? "Photon / OpenStreetMap"
                : "device".equals(geocodeSource) ? "Photon / OpenStreetMap" : null);
        out.put("sources", sources);
        return ResponseEntity.ok(out);
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        return ResponseEntity.status(status).body(body);
    }
}
