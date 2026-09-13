package com.nongxin.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Parses SSE framing and indexed tool fragments; EOF alone never means success. */
final class CompletionStream {
    private final ObjectMapper json;
    private final StreamObserver observer;
    private final StringBuilder content = new StringBuilder();
    private final Map<Integer, ToolParts> calls = new TreeMap<>();
    private String finish;
    private int size;

    CompletionStream(ObjectMapper json, StreamObserver observer) { this.json = json; this.observer = observer; }

    Map<String, Object> read(InputStream input) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                observer.check();
                size += line.length();
                if (size > 2_000_000) throw failure("供应商流式响应过长，请简化问题后重试");
                if (line.isEmpty()) {
                    if (!data.isEmpty()) {
                        String payload = data.toString(); data.setLength(0);
                        if (payload.equals("[DONE]")) break;
                        accept(json.readTree(payload));
                    }
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) data.append('\n');
                    data.append(line.substring(5).stripLeading());
                }
            }
        }
        observer.check();
        if (finish == null) throw failure("回答连接中断，未收到完整结果，请重试");
        if (!"stop".equals(finish) && !"tool_calls".equals(finish)) {
            throw failure("length".equals(finish) ? "模型输出达到长度限制，回答未完成，请简化问题后重试" : "供应商未完成回答，请调整问题后重试");
        }
        var tools = new ArrayList<Map<String, Object>>();
        for (ToolParts part : calls.values()) {
            if (part.id.isEmpty() || part.name.isEmpty()) throw failure("供应商返回了不完整的工具调用，请重试");
            tools.add(Map.of("id", part.id.toString(), "type", "function", "function",
                    Map.of("name", part.name.toString(), "arguments", part.args.toString())));
        }
        if ("tool_calls".equals(finish) && tools.isEmpty()) throw failure("供应商没有返回有效的工具调用，请重试");
        return new LinkedHashMap<>(Map.of("content", content.toString(), "tool_calls", tools));
    }

    private void accept(JsonNode data) {
        if (data == null || data.has("error")) throw failure("供应商流式响应异常，请稍后重试");
        JsonNode choices = data.path("choices");
        if (!choices.isArray()) throw failure("供应商流式响应格式不正确，请检查接口兼容性");
        for (JsonNode choice : choices) {
            if (choice.path("index").asInt(0) != 0) continue;
            if (choice.path("finish_reason").isTextual()) finish = choice.get("finish_reason").asText();
            JsonNode delta = choice.path("delta");
            if (delta.path("content").isTextual()) {
                String text = delta.get("content").asText(); content.append(text);
                if (content.length() > 20_000) throw failure("模型回答过长，未保存为完整答案，请简化问题后重试");
                if (!text.isEmpty()) observer.event("delta", Map.of("text", text));
            }
            // Deliberately ignore reasoning_content and other provider-private metadata.
            if (delta.path("tool_calls").isArray()) for (JsonNode call : delta.get("tool_calls")) {
                int index = call.path("index").asInt(-1);
                if (index < 0 || index > 63) throw failure("供应商工具调用格式不正确");
                ToolParts part = calls.computeIfAbsent(index, unused -> new ToolParts());
                if (call.path("id").isTextual()) part.id.append(call.get("id").asText());
                JsonNode function = call.path("function");
                if (function.path("name").isTextual()) part.name.append(function.get("name").asText());
                if (function.path("arguments").isTextual()) {
                    part.args.append(function.get("arguments").asText());
                    if (part.args.length() > 200_000) throw failure("供应商工具调用参数过长，请简化问题后重试");
                }
            }
        }
    }

    private static AgentRunner.ProviderException failure(String text) { return new AgentRunner.ProviderException(text, false); }
    private static final class ToolParts {
        final StringBuilder id = new StringBuilder(), name = new StringBuilder(), args = new StringBuilder();
    }
}
