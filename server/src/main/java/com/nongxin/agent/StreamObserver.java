package com.nongxin.agent;

import java.util.concurrent.CancellationException;

/** Only public answer text/status leaves the runner, never reasoning or raw tool arguments. */
public interface StreamObserver {
    void event(String name, Object data);
    default boolean cancelled() { return Thread.currentThread().isInterrupted(); }
    default void check() { if (cancelled()) throw new CancellationException(); }
}
