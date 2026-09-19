package com.nongxin.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

class CurrentUserTest {
    private static final String UNAVAILABLE = "当前用户身份暂时无法确认，请稍后重试";
    private final CurrentUser user = new CurrentUser();

    @Test
    void defaultIsExplicitlySharedLocalOwnershipNotAuthentication() {
        assertThat(user.id()).isEqualTo(CurrentUser.LOCAL_OWNER);
        assertThat(user.isLocalOwner()).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\r\n", "\u3000"})
    void configuredResolverWithNoIdentityCannotFallBackToLocalOwner(String value) {
        user.setResolver(() -> value);
        assertThatThrownBy(user::id).isInstanceOf(CurrentUser.IdentityUnavailable.class).hasMessage(UNAVAILABLE).hasNoCause();
        assertThatThrownBy(user::isLocalOwner).isInstanceOf(CurrentUser.IdentityUnavailable.class).hasMessage(UNAVAILABLE);
    }

    static Stream<RuntimeException> failures() {
        return Stream.of(new IllegalArgumentException("private resolver detail"),
                new IllegalStateException("private resolver detail"),
                new RuntimeException("private resolver detail", new RuntimeException("private cause")));
    }

    @ParameterizedTest
    @MethodSource("failures")
    void resolverExceptionsAreReplacedWithASafeFailure(RuntimeException failure) {
        user.setResolver(() -> { throw failure; });
        assertThatThrownBy(user::id).isInstanceOf(CurrentUser.IdentityUnavailable.class).hasMessage(UNAVAILABLE).hasNoCause();
        assertThatThrownBy(user::isLocalOwner).isInstanceOf(CurrentUser.IdentityUnavailable.class).hasMessage(UNAVAILABLE);
    }

    @Test
    void nullResolverCannotSilentlyEnableLocalMode() {
        user.setResolver(() -> "u-a");
        assertThatIllegalArgumentException().isThrownBy(() -> user.setResolver(null));
        assertThat(user.id()).isEqualTo("u-a");
    }

    @Test
    void nullResolverCannotClearAnExistingIdentityFailure() {
        user.setResolver(() -> null);
        assertThatIllegalArgumentException().isThrownBy(() -> user.setResolver(null));
        assertThatThrownBy(user::id).hasMessage(UNAVAILABLE);
    }

    @Test
    void explicitResetRestoresLocalModeAndAllowsLaterConfiguration() {
        user.setResolver(() -> { throw new IllegalStateException("private detail"); });
        user.reset();
        assertThat(user.id()).isEqualTo(CurrentUser.LOCAL_OWNER);
        user.setResolver(() -> "u-b");
        assertThat(user.id()).isEqualTo("u-b");
        assertThat(user.isLocalOwner()).isFalse();
    }

    @Test
    void identityIsNotReplacedTrimmedOrCachedBetweenCalls() {
        var actor = new AtomicReference<>(" u-a ");
        var calls = new AtomicInteger();
        user.setResolver(() -> { calls.incrementAndGet(); return actor.get(); });
        assertThat(user.id()).isEqualTo(" u-a ");
        actor.set("u-b");
        assertThat(user.id()).isEqualTo("u-b");
        actor.set(null);
        assertThatThrownBy(user::id).hasMessage(UNAVAILABLE);
        actor.set("u-c");
        assertThat(user.id()).isEqualTo("u-c");
        assertThat(calls.get()).isEqualTo(4);
    }
}
