package com.nongxin.service.impl;

import com.nongxin.service.ApiKeyService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 成本护栏实现：
 * - 用户自带 Key 优先且不限流；
 * - 服务端演示 Key 兜底时按「IP 日限额 + 全局日限额」计数（SQLite 原子 upsert）；
 * - 计数用的 IP 取 X-Forwarded-For 首个地址（兼容反向代理），否则用 remoteAddr。
 */
@Service
public class ApiKeyServiceImpl implements ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyServiceImpl.class);
    private static final int MIN_KEY_LENGTH = 12;

    private final JdbcTemplate jdbc;
    private final String defaultApiKey;
    private final String defaultProvider;
    private final String defaultModel;
    private final boolean limitEnabled;
    private final int dailyPerIp;
    private final int dailyGlobal;

    public ApiKeyServiceImpl(
            JdbcTemplate jdbc,
            @Value("${nongxin.chat.default-api-key:}") String defaultApiKey,
            @Value("${nongxin.chat.default-provider:deepseek}") String defaultProvider,
            @Value("${nongxin.chat.default-model:deepseek-chat}") String defaultModel,
            @Value("${nongxin.usage.enabled:true}") boolean limitEnabled,
            @Value("${nongxin.usage.daily-per-ip:30}") int dailyPerIp,
            @Value("${nongxin.usage.daily-global:500}") int dailyGlobal) {
        this.jdbc = jdbc;
        this.defaultApiKey = defaultApiKey == null ? "" : defaultApiKey.trim();
        this.defaultProvider = defaultProvider;
        this.defaultModel = defaultModel;
        this.limitEnabled = limitEnabled;
        this.dailyPerIp = Math.max(1, dailyPerIp);
        this.dailyGlobal = Math.max(1, dailyGlobal);
        if (serverKeyConfigured()) {
            log.info("服务端演示 Key 已启用：provider={} model={}；限额 每IP {} 次/日、全局 {} 次/日",
                    defaultProvider, defaultModel, dailyPerIp, dailyGlobal);
        } else {
            log.info("未配置服务端演示 Key（NONGXIN_CHAT_KEY）：用户需自带 Key，行为与原先一致");
        }
    }

    private boolean serverKeyConfigured() {
        return defaultApiKey.length() >= MIN_KEY_LENGTH;
    }

    @Override
    public Resolution resolve(String requestApiKey, String requestProvider, String requestModel) {
        String key = requestApiKey == null ? "" : requestApiKey.trim();
        if (key.length() >= MIN_KEY_LENGTH) {
            // 用户自带 Key：不限流
            return new Resolution(key, false, null, requestProvider, requestModel);
        }
        if (!serverKeyConfigured()) {
            return new Resolution("", false, null, requestProvider, requestModel);
        }
        if (!limitEnabled) {
            return new Resolution(defaultApiKey, true, null, defaultProvider, defaultModel);
        }
        String day = LocalDate.now().toString();
        String ip = clientIp();
        try {
            int globalUsed = currentCount(day, "global", "all");
            if (globalUsed >= dailyGlobal) {
                log.warn("全局日限额已用尽：{}/{}", globalUsed, dailyGlobal);
                return new Resolution("", true,
                        "今日演示额度已用完（全站 " + dailyGlobal + " 次/日）。请在设置中填入自己的 API Key 继续使用，或明天再来。",
                        defaultProvider, defaultModel);
            }
            int ipUsed = currentCount(day, "ip", ip);
            if (ipUsed >= dailyPerIp) {
                return new Resolution("", true,
                        "你今天的演示额度已用完（每设备 " + dailyPerIp + " 次/日）。可在设置中填入自己的 API Key 继续使用，或明天再来。",
                        defaultProvider, defaultModel);
            }
            increment(day, "global", "all");
            increment(day, "ip", ip);
            return new Resolution(defaultApiKey, true, null, defaultProvider, defaultModel);
        } catch (Exception e) {
            // 计数失败不应阻断正常使用（宁可少赚不能误伤）
            log.warn("用量计数失败，放行本次请求：{}", e.getMessage());
            return new Resolution(defaultApiKey, true, null, defaultProvider, defaultModel);
        }
    }

    @Override
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("serverKeyConfigured", serverKeyConfigured());
        out.put("limitEnabled", limitEnabled);
        out.put("dailyPerIp", dailyPerIp);
        out.put("dailyGlobal", dailyGlobal);
        if (serverKeyConfigured()) {
            String day = LocalDate.now().toString();
            try {
                int globalUsed = currentCount(day, "global", "all");
                out.put("globalUsedToday", globalUsed);
                out.put("globalRemaining", Math.max(0, dailyGlobal - globalUsed));
                out.put("ipUsedToday", currentCount(day, "ip", clientIp()));
            } catch (Exception e) {
                out.put("usageError", e.getMessage());
            }
        }
        return out;
    }

    private int currentCount(String day, String scope, String scopeKey) {
        Integer count = jdbc.queryForObject(
                "SELECT count FROM api_usage WHERE day = ? AND scope = ? AND scope_key = ?",
                Integer.class, day, scope, scopeKey);
        return count == null ? 0 : count;
    }

    private void increment(String day, String scope, String scopeKey) {
        jdbc.update("INSERT INTO api_usage (day, scope, scope_key, count) VALUES (?,?,?,1) "
                        + "ON CONFLICT(day, scope, scope_key) DO UPDATE SET count = count + 1, updated_at = datetime('now','localtime')",
                day, scope, scopeKey);
    }

    /** 取真实客户端 IP：优先 X-Forwarded-For 首段（兼容 Nginx 反代） */
    private String clientIp() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return "unknown";
            HttpServletRequest request = attrs.getRequest();
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                int comma = forwarded.indexOf(',');
                return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
            }
            String realIp = request.getHeader("X-Real-IP");
            if (realIp != null && !realIp.isBlank()) return realIp.trim();
            String remote = request.getRemoteAddr();
            return remote == null || remote.isBlank() ? "unknown" : remote;
        } catch (Exception e) {
            return "unknown";
        }
    }
}
