package com.nongxin.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompletionStreamTest {
    private final ObjectMapper json = new ObjectMapper();

    private static class Recording implements StreamObserver {
        final List<String> names = new ArrayList<>();
        final List<Object> data = new ArrayList<>();

        public void event(String name, Object data) { names.add(name); this.data.add(data); }
    }

    private Recording read(String sse) throws Exception {
        Recording recording = new Recording();
        new CompletionStream(json, recording).read(new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)));
        return recording;
    }

    private Map<String, Object> readMap(String sse) throws Exception {
        Recording recording = new Recording();
        return new CompletionStream(json, recording).read(new ByteArrayInputStream(sse.getBytes(StandardCharsets.UTF_8)));
    }

    private static String delta(String text, String finish) {
        return "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + text + "\"}"
                + (finish.isEmpty() ? "" : ",\"finish_reason\":\"" + finish + "\"") + "}]}\n\n";
    }

    @Test
    void assemblesTextDeltasAndEmitsThemInOrder() throws Exception {
        Recording recording = new Recording();
        Map<String, Object> message = new CompletionStream(json, recording).read(new ByteArrayInputStream(
                ("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"先观察\"}}]}\n\n" +
                        "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"叶片\"},\"finish_reason\":\"stop\"}]}\n\n")
                        .getBytes(StandardCharsets.UTF_8)));

        assertThat(message.get("content")).isEqualTo("先观察叶片");
        assertThat(message.get("tool_calls")).isEqualTo(List.of());
        assertThat(recording.names).containsExactly("delta", "delta");
        List<String> texts = recording.data.stream().map(d -> String.valueOf(((Map<?, ?>) d).get("text"))).toList();
        assertThat(texts).containsExactly("先观察", "叶片");
    }

    @Test
    void ignoresKeepAliveCommentsCrlfAndReasoningContent() throws Exception {
        Map<String, Object> message = readMap(": keepalive\r\n\r\n"
                + "data: {\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"私密推理\",\"content\":\"公开\"},\"finish_reason\":\"stop\"}]}\r\n\r\n");
        assertThat(message.get("content")).isEqualTo("公开");
    }

    @Test
    void joinsMultipleDataLinesIntoOnePayload() throws Exception {
        Map<String, Object> message = readMap("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"断\"},\n" +
                "data: \"finish_reason\":\"stop\"}]}\n\n");
        assertThat(message.get("content")).isEqualTo("断");
    }

    @Test
    void assemblesFragmentedToolCallsAcrossDeltas() throws Exception {
        Map<String, Object> message = readMap("data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-\",\"function\":{\"name\":\"submit_clar\",\"arguments\":\"{\\\"items\\\":[{\"}}]}}]}}\n\n"
                + "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"1\",\"function\":{\"name\":\"ify\",\"arguments\":\"\\\"question\\\":\\\"作物？\\\"}]}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n");
        List<?> calls = (List<?>) message.get("tool_calls");
        assertThat(calls).hasSize(1);
        Map<?, ?> call = (Map<?, ?>) calls.getFirst();
        assertThat(call.get("id")).isEqualTo("call-1");
        Map<?, ?> fn = (Map<?, ?>) call.get("function");
        assertThat(fn.get("name")).isEqualTo("submit_clarify");
        assertThat((String) fn.get("arguments")).contains("作物？").contains("\"items\"");
    }

    @Test
    void eofWithoutFinishNeverCountsAsSuccess() {
        assertThatThrownBy(() -> read(delta("半截", "")))
                .isInstanceOf(AgentRunner.ProviderException.class).hasMessageContaining("未收到完整结果");
    }

    @Test
    void doneMarkerWithoutFinishStillFails() {
        assertThatThrownBy(() -> read("data: [DONE]\n\n"))
                .isInstanceOf(AgentRunner.ProviderException.class).hasMessageContaining("未收到完整结果");
    }

    @Test
    void lengthFinishIsReportedAsUnfinished() {
        assertThatThrownBy(() -> read(delta("截断", "length")))
                .isInstanceOf(AgentRunner.ProviderException.class).hasMessageContaining("长度限制");
    }

    @Test
    void otherFinishReasonsAreReportedAsUnfinished() {
        assertThatThrownBy(() -> read(delta("内容", "content_filter")))
                .isInstanceOf(AgentRunner.ProviderException.class).hasMessageContaining("未完成回答");
    }

    @Test
    void errorPayloadFailsWithoutExposingBody() {
        assertThatThrownBy(() -> read("data: {\"error\":{\"message\":\"private-upstream-secret\"}}\n\n"))
                .isInstanceOf(AgentRunner.ProviderException.class).hasMessageContaining("流式响应异常")
                .hasMessageNotContaining("private-upstream-secret");
    }

    @Test
    void malformedChoicesShapeFails() {
        assertThatThrownBy(() -> read("data: {\"choices\":\"invalid\"}\n\n"))
                .isInstanceOf(AgentRunner.ProviderException.class).hasMessageContaining("格式不正确");
    }

    @Test
    void toolCallFinishWithoutAnyCallFails() {
        assertThatThrownBy(() -> read("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n"))
                .isInstanceOf(AgentRunner.ProviderException.class).hasMessageContaining("有效的工具调用");
    }

    @Test
    void nonZeroChoiceIndexIsIgnored() {
        assertThatThrownBy(() -> read("data: {\"choices\":[{\"index\":1,\"delta\":{\"content\":\"忽略\"},\"finish_reason\":\"stop\"}]}\n\n"))
                .isInstanceOf(AgentRunner.ProviderException.class).hasMessageContaining("未收到完整结果");
    }

    @Test
    void contentBeyondTwentyThousandCharactersFails() {
        assertThatThrownBy(() -> read(delta("字".repeat(20001), "stop")))
                .isInstanceOf(AgentRunner.ProviderException.class).hasMessageContaining("回答过长");
    }

    @Test
    void totalStreamBeyondTwoMegabytesFails() {
        assertThatThrownBy(() -> read("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + "x".repeat(2_000_000) + "\"}}]}\n\n"))
                .isInstanceOf(AgentRunner.ProviderException.class).hasMessageContaining("响应过长");
    }

    @Test
    void oversizedToolArgumentsFailInsteadOfBeingBuffered() {
        assertThatThrownBy(() -> read("data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c\",\"function\":{\"name\":\"submit_risk_report\",\"arguments\":\""
                        + "{\\\"data\\\":\\\"" + "x".repeat(200_001) + "\\\"}\"}}]}}]}\n\n"))
                .isInstanceOf(AgentRunner.ProviderException.class).hasMessageContaining("工具调用参数过长");
    }

    @Test
    void observerCancellationStopsReading() {
        Recording recording = new Recording() {
            public boolean cancelled() { return true; }
        };
        assertThatThrownBy(() -> new CompletionStream(json, recording)
                .read(new ByteArrayInputStream("data: {}\n\n".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(java.util.concurrent.CancellationException.class);
    }
}
