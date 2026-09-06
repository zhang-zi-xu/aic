package com.nongxin.service.impl;

import com.nongxin.service.GeoService;

import com.nongxin.model.GeoEntry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 地理编码服务：内置全国省市级行政区表（离线、精准）优先，
 * Photon 仅作县级/特殊地名兜底；逆地理解析失败降级为坐标文本。
 */
@Service
public class GeoServiceImpl implements GeoService {

    private static final Logger log = LoggerFactory.getLogger(GeoServiceImpl.class);
    private static final String PHOTON_SEARCH = "https://photon.komoot.io/api";
    private static final String PHOTON_REVERSE = "https://photon.komoot.io/reverse";

    private final RestClient restClient;
    private final List<GeoEntry> cities = new ArrayList<>();
    private final Map<String, GeoEntry> provinceCapital = new HashMap<>();

    public GeoServiceImpl(@Value("${nongxin.cities-resource}") Resource citiesResource) {
        this.restClient = RestClient.builder().build();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(citiesResource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (header) { header = false; continue; }
                if (line.isBlank()) continue;
                String[] parts = line.split(",", 4);
                if (parts.length < 4) continue;
                try {
                    cities.add(new GeoEntry(parts[0], parts[1],
                            Double.parseDouble(parts[2]), Double.parseDouble(parts[3])));
                } catch (NumberFormatException e) {
                    log.warn("cities.csv 无效行: {}", line);
                }
            }
        } catch (Exception e) {
            log.error("加载城市表失败", e);
        }
    }

    @PostConstruct
    void buildProvinceIndex() {
        for (GeoEntry entry : cities) {
            if (!entry.name().endsWith("市")) continue;
            provinceCapital.putIfAbsent(entry.prov(), entry);
        }
    }

    public int cityCount() {
        return cities.size();
    }

    /** 城市名解析（内置表优先） */
    public GeoEntry resolveCity(String query) {
        String raw = query == null ? "" : query.trim();
        if (raw.isEmpty()) return null;
        for (GeoEntry c : cities) {
            if (c.name().equals(raw)) return c;
        }
        for (GeoEntry c : cities) {
            if (raw.startsWith(c.name())) return c;
        }
        for (GeoEntry c : cities) {
            if (raw.contains(c.name())) return c;
        }
        for (Map.Entry<String, GeoEntry> e : provinceCapital.entrySet()) {
            String shortProv = e.getKey().replace("省", "").replace("市", "").replace("自治区", "");
            if (shortProv.length() >= 2 && raw.contains(shortProv)) return e.getValue();
        }
        return null;
    }

    /** Photon 兜底搜索（返回 null 表示失败） */
    public GeoEntry photonSearch(String query) {
        try {
            String url = PHOTON_SEARCH + "?q=" + java.net.URLEncoder.encode(query, StandardCharsets.UTF_8) + "&limit=1";
            Map<?, ?> data = restClient.get().uri(url)
                    .header("Accept", "application/json")
                    .header("User-Agent", "NongxinAgent/1.0")
                    .retrieve().body(Map.class);
            if (data == null) return null;
            Object featuresObj = data.get("features");
            if (!(featuresObj instanceof List<?> features) || features.isEmpty()) return null;
            if (!(features.get(0) instanceof Map<?, ?> feature)) return null;
            Object geometry = feature.get("geometry");
            if (!(geometry instanceof Map<?, ?> geom)) return null;
            Object coords = geom.get("coordinates");
            if (!(coords instanceof List<?> c) || c.size() < 2) return null;
            double lon = ((Number) c.get(0)).doubleValue();
            double lat = ((Number) c.get(1)).doubleValue();
            String name = photonName(feature.get("properties"));
            return new GeoEntry(name != null ? name : query, "", lat, lon);
        } catch (Exception e) {
            log.warn("Photon 搜索失败: {}", e.getMessage());
            return null;
        }
    }

    /** 坐标 → 地名（失败返回 null，调用方降级为坐标文本） */
    public String reverseGeocode(double lat, double lon) {
        try {
            String url = PHOTON_REVERSE + "?lat=" + String.format("%.5f", lat) + "&lon=" + String.format("%.5f", lon);
            Map<?, ?> data = restClient.get().uri(url)
                    .header("Accept", "application/json")
                    .header("User-Agent", "NongxinAgent/1.0")
                    .retrieve().body(Map.class);
            if (data == null) return null;
            Object featuresObj = data.get("features");
            if (!(featuresObj instanceof List<?> features) || features.isEmpty()) return null;
            if (!(features.get(0) instanceof Map<?, ?> feature)) return null;
            return photonName(feature.get("properties"));
        } catch (Exception e) {
            log.warn("Photon 逆地理失败: {}", e.getMessage());
            return null;
        }
    }

    private String photonName(Object propertiesObj) {
        if (!(propertiesObj instanceof Map<?, ?> props)) return null;
        List<String> parts = new ArrayList<>();
        for (String key : List.of("state", "city", "county", "district", "locality")) {
            Object v = props.get(key);
            if (v instanceof String s && StringUtils.hasText(s)) {
                if (!parts.contains(s)) parts.add(s);
            }
        }
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }
}
