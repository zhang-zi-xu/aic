package com.nongxin.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册中心（yoked ToolCenter 移植）：注册、按条件采集、执行。
 */
public class ToolRegistry {

    private final Map<String, ToolDefinition> tools = new LinkedHashMap<>();

    public void register(ToolDefinition tool) {
        tools.put(tool.name(), tool);
    }

    /** 采集当前上下文可用的工具（OpenAI tools 数组格式） */
    public List<Map<String, Object>> collect(AgentContext ctx) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ToolDefinition t : tools.values()) {
            if (!t.isAvailable(ctx)) continue;
            Map<String, Object> fn = new LinkedHashMap<>();
            fn.put("name", t.name());
            fn.put("description", t.description());
            fn.put("parameters", t.parameters());
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("type", "function");
            tool.put("function", fn);
            out.add(tool);
        }
        return out;
    }

    /** 采集全部工具（forceTool 时按名称过滤用） */
    public List<Map<String, Object>> collectAll(AgentContext ctx) {
        return collect(ctx);
    }

    public String execute(String name, Map<String, Object> args, AgentContext ctx) {
        ToolDefinition tool = tools.get(name);
        if (tool == null) return "错误：工具 " + name + " 不存在";
        try {
            return tool.executor().apply(args == null ? Map.of() : args, ctx);
        } catch (Exception e) {
            return "工具执行失败：" + e.getMessage();
        }
    }
}
