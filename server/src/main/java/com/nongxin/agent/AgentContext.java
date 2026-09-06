package com.nongxin.agent;

import java.util.HashMap;
import java.util.Map;

/**
 * 工具执行上下文：携带田块档案、天气、位置等请求级数据。
 */
public class AgentContext {

    private final String userId;
    private final Map<String, Object> extras;

    public AgentContext(String userId, Map<String, Object> extras) {
        this.userId = userId;
        this.extras = extras == null ? new HashMap<>() : new HashMap<>(extras);
    }

    public String userId() {
        return userId;
    }

    public Object extra(String key) {
        return extras.get(key);
    }

    public String extraString(String key) {
        Object v = extras.get(key);
        return v instanceof String s ? s : "";
    }

    public void put(String key, Object value) {
        extras.put(key, value);
    }
}
