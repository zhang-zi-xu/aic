package com.nongxin.agent;

import java.util.Map;
import java.util.function.BiFunction;

/**
 * 工具定义（yoked ToolCenter 设计）：名称/描述/JSON Schema 参数/执行器/可用条件。
 * 执行器返回文本：普通工具返回给模型的资料；submit_* 产出型工具返回确认文本，
 * 其 args 会被 AgentRunner 结构化采集供前端渲染卡片。
 */
public record ToolDefinition(
        String name,
        String description,
        Map<String, Object> parameters,
        BiFunction<Map<String, Object>, AgentContext, String> executor,
        java.util.function.Predicate<AgentContext> available) {

    public static ToolDefinition of(String name, String description, Map<String, Object> parameters,
                                    BiFunction<Map<String, Object>, AgentContext, String> executor) {
        return new ToolDefinition(name, description, parameters, executor, ctx -> true);
    }

    public static ToolDefinition of(String name, String description, Map<String, Object> parameters,
                                    BiFunction<Map<String, Object>, AgentContext, String> executor,
                                    java.util.function.Predicate<AgentContext> available) {
        return new ToolDefinition(name, description, parameters, executor, available);
    }

    public boolean isAvailable(AgentContext ctx) {
        return available == null || available.test(ctx);
    }
}
