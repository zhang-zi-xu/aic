package com.nongxin.controller;

import com.nongxin.agent.StreamObserver;
import jakarta.annotation.PreDestroy;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** Bounded workers, heartbeat disconnect detection and request-scoped cancellation. */
@Component
public class ChatStreams {
    /** Worker pool saturated; mapped to HTTP 429 by the API exception handler. */
    public static class Overloaded extends RuntimeException {}

    private final ExecutorService workers = new ThreadPoolExecutor(2, 8, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), Thread.ofPlatform().daemon().name("chat-stream-", 0).factory());
    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("chat-heartbeat").factory());

    public SseEmitter open(Function<StreamObserver, ResponseEntity<?>> work) {
        SseEmitter emitter = new SseEmitter(180_000L);
        AtomicBoolean closed = new AtomicBoolean();
        StreamObserver observer = new StreamObserver() {
            public boolean cancelled() { return closed.get() || Thread.currentThread().isInterrupted(); }
            public void event(String name, Object data) {
                check();
                try { emitter.send(SseEmitter.event().name(name).data(data)); }
                catch (IOException | IllegalStateException e) { closed.set(true); throw new CancellationException(); }
            }
        };
        FutureTask<Void> task = new FutureTask<>(() -> {
            try {
                observer.event("status", Map.of("text", "请求已接收…"));
                ResponseEntity<?> result = work.apply(observer);
                if (result.getStatusCode().is2xxSuccessful()) {
                    observer.event("done", result.getBody());
                } else {
                    // The SSE HTTP response is already 200; retain the logical error status in its event.
                    Map<String, Object> error = new LinkedHashMap<>();
                    if (result.getBody() instanceof Map<?, ?> body) {
                        body.forEach((key, value) -> { if (key instanceof String name) error.put(name, value); });
                    }
                    error.putIfAbsent("error", "对话暂时不可用，请稍后重试");
                    error.put("status", result.getStatusCode().value());
                    observer.event("error", error);
                }
                emitter.complete();
            } catch (CancellationException ignored) { emitter.complete(); }
            catch (Exception e) {
                try { observer.event("error", Map.of("error", "对话暂时不可用，请稍后重试", "status", 500)); }
                catch (CancellationException ignored) { /* disconnected */ }
                emitter.complete();
            } finally { closed.set(true); }
            return null;
        });
        ScheduledFuture<?> heartbeat = clock.scheduleAtFixedRate(() -> {
            if (closed.get()) return;
            try { observer.event("ping", Map.of()); }
            catch (CancellationException e) { task.cancel(true); emitter.complete(); }
        }, 10, 10, TimeUnit.SECONDS);
        Runnable cancel = () -> { closed.set(true); heartbeat.cancel(false); task.cancel(true); };
        emitter.onCompletion(cancel); emitter.onTimeout(cancel); emitter.onError(error -> cancel.run());
        try { workers.execute(task); }
        catch (RejectedExecutionException e) {
            cancel.run();
            throw new Overloaded();
        }
        return emitter;
    }

    @PreDestroy public void close() { workers.shutdownNow(); clock.shutdownNow(); }
}
