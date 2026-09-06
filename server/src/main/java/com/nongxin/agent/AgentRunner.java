package com.nongxin.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Agent 多轮执行循环（yoked chatWithTools 移植）：
 * 一次请求 → AI 自主选工具 → 执行 → 回传结果 → 再请求，直至无 tool_calls。
 * submit_* 产出型工具的结果结构化采集。
 * 支持 forceTool：只暴露目标工具 + tool_choice='required'（兼容 DeepSeek/OpenAI）。
 */
@Component
public class AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);
    private static final int MAX_ROUNDS = 5;
    private static final long MAX_RUN_NANOS = TimeUnit.SECONDS.toNanos(60);
    private static final Set<String> SUBMISSION_TOOLS = Set.of("submit_farm_plan", "submit_risk_report", "submit_clarify");
    static final String SUBMISSION_RESULT_KEY = "toolSubmissionResult";

    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    @Autowired
    public AgentRunner(ObjectMapper objectMapper) {
        this(objectMapper, defaultRestClient());
    }

    AgentRunner(ObjectMapper objectMapper, RestClient restClient) {
        this.objectMapper = objectMapper;
        this.restClient = restClient;
    }

    private static RestClient defaultRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(30000);
        return RestClient.builder().requestFactory(factory).build();
    }

    public record Config(String model, String endpoint, String apiKey, String systemPrompt,
                         ToolRegistry tools, AgentContext ctx, int maxRounds, String forceTool) {}

    public static final class ProviderException extends RuntimeException {
        private final boolean timeout;

        public ProviderException(String message, boolean timeout) {
            super(message);
            this.timeout = timeout;
        }

        public boolean timeout() {
            return timeout;
        }
    }

    /** 运行 Agent；供应商错误使用不含密钥、原始响应或内部地址的稳定错误信息。 */
    public AgentResult run(Config config, List<Map<String, Object>> history) {
        String model = config.model();
        String endpoint = config.endpoint();
        String apiKey = config.apiKey();

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", config.systemPrompt()));
        for (Map<String, Object> m : history) {
            Map<String, Object> copy = new LinkedHashMap<>(m);
            messages.add(copy);
        }

        List<ToolSubmission> submissions = new ArrayList<>();
        boolean forced = config.forceTool() != null && !config.forceTool().isBlank();
        int maxRounds = config.maxRounds() > 0 ? Math.min(config.maxRounds(), MAX_ROUNDS) : MAX_ROUNDS;
        int rounds = 0;
        long startedAt = System.nanoTime();

        while (rounds < maxRounds) {
            if (System.nanoTime() - startedAt >= MAX_RUN_NANOS) {
                if (!submissions.isEmpty()) {
                    return new AgentResult("本次对话处理超时，已生成的工具结果保留在下方。", submissions, rounds, true);
                }
                throw new ProviderException("对话处理超时，请稍后重试或简化问题", true);
            }
            List<Map<String, Object>> toolDefs = config.tools().collect(config.ctx());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", messages);
            body.put("temperature", 0.35);

            if (forced) {
                // 强制工具轮：只暴露目标工具 + required
                List<Map<String, Object>> filtered = new ArrayList<>();
                for (Map<String, Object> t : toolDefs) {
                    Object fn = t.get("function");
                    if (fn instanceof Map<?, ?> fnMap && config.forceTool().equals(fnMap.get("name"))) {
                        filtered.add(t);
                    }
                }
                body.put("tools", filtered);
                body.put("tool_choice", "required");
                forced = false;
            } else if (!toolDefs.isEmpty()) {
                body.put("tools", toolDefs);
            }

            Map<?, ?> message;
            try {
                message = requestCompletion(endpoint, apiKey, body);
            } catch (ProviderException e) {
                if (!submissions.isEmpty()) {
                    return new AgentResult(e.getMessage() + "。已生成的工具结果保留在下方。", submissions, rounds + 1, true);
                }
                throw e;
            }

            String content = message.get("content") instanceof String s ? s : "";
            List<Map<String, Object>> toolCalls = normalizeToolCalls(message.get("tool_calls"));

            if (toolCalls.isEmpty()) {
                if (content.isBlank()) throw new ProviderException("供应商没有返回可用的回答，请稍后重试", false);
                return new AgentResult(content.trim(), submissions, rounds + 1, false);
            }

            // 记录 assistant 消息
            Map<String, Object> assistantMsg = new LinkedHashMap<>();
            assistantMsg.put("role", "assistant");
            assistantMsg.put("content", content);
            assistantMsg.put("tool_calls", toolCalls);
            messages.add(assistantMsg);

            for (Map<String, Object> tc : toolCalls) {
                Map<?, ?> function = (Map<?, ?>) tc.get("function");
                String name = (String) function.get("name");
                String resultText;
                config.ctx().put(SUBMISSION_RESULT_KEY, null);
                try {
                    Map<String, Object> args = parseArgs((String) function.get("arguments"));
                    resultText = config.tools().execute(name, args, config.ctx());
                    boolean failed = resultText == null || resultText.startsWith("工具执行失败") || resultText.startsWith("错误：");
                    if (failed) {
                        resultText = "工具未成功执行，请检查参数并补充必要信息后重试。";
                    } else if (SUBMISSION_TOOLS.contains(name)) {
                        Object computedResult = config.ctx().extra(SUBMISSION_RESULT_KEY);
                        // Risk cards must contain the rule engine result, never the model's input text.
                        if (computedResult != null) {
                            Map<String, Object> output = objectMapper.convertValue(computedResult, new TypeReference<>() {});
                            submissions.add(new ToolSubmission(name, output, resultText));
                        } else if (!"submit_risk_report".equals(name)) {
                            submissions.add(new ToolSubmission(name, args, resultText));
                        }
                    }
                } catch (IllegalArgumentException e) {
                    resultText = "工具参数格式不正确，请按工具定义提供 JSON 对象。";
                }
                messages.add(Map.of("role", "tool", "tool_call_id", tc.get("id"), "content", resultText.length() > 8000 ? resultText.substring(0, 8000) : resultText));
            }
            rounds++;
        }

        String fallback = submissions.isEmpty()
                ? "工具调用轮次已用尽，请简化问题后重试。"
                : "工具调用轮次已用尽，已为你整理出上方结构化结果，可继续追问。";
        return new AgentResult(fallback, submissions, rounds, true);
    }

    private Map<?, ?> requestCompletion(String endpoint, String apiKey, Map<String, Object> body) {
        try {
            Map<?, ?> data = restClient.post()
                    .uri(endpoint)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .onStatus(status -> status.value() != 200, (req, res) -> {
                        int status = res.getStatusCode().value();
                        log.warn("对话供应商返回 HTTP {}", status);
                        String detail = switch (status) {
                            case 401, 403 -> "供应商鉴权失败，请检查 API 密钥与模型权限";
                            case 429 -> "供应商请求额度或频率受限，请稍后重试";
                            case 400, 404 -> "供应商不接受当前请求，请检查模型名称和 API 地址";
                            default -> "对话供应商暂时不可用，请稍后重试";
                        };
                        throw new ProviderException(detail, status == 408 || status == 504);
                    })
                    .body(Map.class);
            if (data == null || !(data.get("choices") instanceof List<?> choices) || choices.isEmpty()
                    || !(choices.get(0) instanceof Map<?, ?> choice)
                    || !(choice.get("message") instanceof Map<?, ?> message)) {
                throw new ProviderException("供应商没有返回有效的回答，请稍后重试", false);
            }
            return message;
        } catch (ResourceAccessException e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) {
                    throw new ProviderException("连接对话供应商超时，请稍后重试", true);
                }
            }
            throw new ProviderException("无法连接对话供应商，请检查 API 地址或稍后重试", false);
        } catch (RestClientException e) {
            throw new ProviderException("供应商返回的内容无法处理，请稍后重试", false);
        } catch (IllegalArgumentException e) {
            throw new ProviderException("供应商请求配置无效，请检查模型名称、API 地址和密钥格式", false);
        }
    }

    private List<Map<String, Object>> normalizeToolCalls(Object value) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(value instanceof List<?> list)) return out;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> call)) continue;
            Object id = call.get("id");
            Object fn = call.get("function");
            if (!(id instanceof String callId) || callId.isBlank() || !(fn instanceof Map<?, ?> fnMap)) continue;
            Object name = fnMap.get("name");
            Object args = fnMap.get("arguments");
            if (!(name instanceof String toolName) || toolName.isBlank()) continue;
            Map<String, Object> tc = new LinkedHashMap<>();
            tc.put("id", id);
            tc.put("type", "function");
            tc.put("function", Map.of("name", name,
                    "arguments", args instanceof String s ? s : objectMapper.valueToTree(args == null ? Map.of() : args).toString()));
            out.add(tc);
        }
        return out;
    }

    private Map<String, Object> parseArgs(String json) {
        try {
            var node = objectMapper.readTree(json);
            if (node == null || !node.isObject()) throw new IllegalArgumentException("参数必须为对象");
            return objectMapper.convertValue(node, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("工具参数格式不正确");
        }
    }
}
