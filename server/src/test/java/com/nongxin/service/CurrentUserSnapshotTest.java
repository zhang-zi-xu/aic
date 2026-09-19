package com.nongxin.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class CurrentUserSnapshotTest {
    private final CurrentUser user = new CurrentUser();

    @Test
    void snapshotIsFixedWithoutReResolvingAndNestedScopesRestoreTheirParent() {
        var calls = new AtomicInteger();
        user.setResolver(() -> { calls.incrementAndGet(); return "u-a"; });
        var first = user.capture();
        user.setResolver(() -> { calls.incrementAndGet(); return "u-b"; });
        var second = user.capture();
        user.setResolver(() -> { throw new IllegalStateException("outside unavailable"); });
        user.withSnapshot(first, () -> {
            assertThat(user.id()).isEqualTo("u-a");
            assertThatThrownBy(() -> user.withSnapshot(second, () -> {
                assertThat(user.id()).isEqualTo("u-b");
                throw new IllegalStateException("test failure");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(user.id()).isEqualTo("u-a");
            assertThat(user.withSnapshot(user.capture(), user::id)).isEqualTo("u-a");
            return null;
        });
        assertThat(calls.get()).isEqualTo(2);
        assertThatThrownBy(user::id).isInstanceOf(CurrentUser.IdentityUnavailable.class);
    }

    @Test
    void invalidSnapshotsCannotRunWorkOrOverwriteAnExistingScope() {
        var first = user.capture();
        var otherServiceSnapshot = new CurrentUser().capture();
        user.withSnapshot(first, () -> {
            for (var invalid : new CurrentUser.Snapshot[]{null, otherServiceSnapshot}) {
                assertThatThrownBy(() -> user.withSnapshot(invalid, () -> { throw new AssertionError("must not run"); }))
                        .isInstanceOf(CurrentUser.IdentityUnavailable.class);
                assertThat(user.id()).isEqualTo(CurrentUser.LOCAL_OWNER);
            }
            return null;
        });
    }

    @Test
    void failedCaptureNeverBorrowsAPreviousSuccessfulSnapshot() {
        var snapshot = user.capture();
        user.setResolver(() -> null);
        assertThatThrownBy(user::capture).isInstanceOf(CurrentUser.IdentityUnavailable.class);
        assertThat(user.withSnapshot(snapshot, user::id)).isEqualTo(CurrentUser.LOCAL_OWNER);
        assertThatThrownBy(user::capture).isInstanceOf(CurrentUser.IdentityUnavailable.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"normal", "exception", "cancel", "error"})
    void reusedWorkerDoesNotRetainSnapshotAfterAnyExit(String exit) throws Exception {
        user.setResolver(() -> "u-entry");
        var snapshot = user.capture();
        user.setResolver(() -> "u-outside");
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> user.withSnapshot(snapshot, () -> {
                assertThat(user.id()).isEqualTo("u-entry");
                return switch (exit) {
                    case "exception" -> throw new IllegalStateException("test failure");
                    case "cancel" -> throw new CancellationException();
                    case "error" -> throw new AssertionError("test failure");
                    default -> user.id();
                };
            }));
            if (exit.equals("normal")) assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo("u-entry");
            else assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
            assertThat(executor.submit(user::id).get(5, TimeUnit.SECONDS)).isEqualTo("u-outside");
        }
    }

    @Test
    void interruptionAlsoReleasesTheWorkerScopeBeforeReuse() throws Exception {
        user.setResolver(() -> "u-entry");
        var snapshot = user.capture();
        user.setResolver(() -> "u-outside");
        var started = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> user.withSnapshot(snapshot, () -> {
                started.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new CancellationException(); }
                return null;
            }));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            future.cancel(true);
            assertThat(executor.submit(user::id).get(5, TimeUnit.SECONDS)).isEqualTo("u-outside");
        } finally { executor.shutdownNow(); assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test
    void childThreadsDoNotInheritAParentScopeImplicitly() throws Exception {
        var snapshot = user.capture();
        user.setResolver(() -> null);
        try (var executor = Executors.newSingleThreadExecutor()) {
            user.withSnapshot(snapshot, () -> {
                assertThatThrownBy(() -> executor.submit(user::id).get(5, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(CurrentUser.IdentityUnavailable.class);
                return null;
            });
            assertThat(executor.submit(() -> user.withSnapshot(snapshot, user::id)).get(5, TimeUnit.SECONDS))
                    .isEqualTo(CurrentUser.LOCAL_OWNER);
        }
    }
}
