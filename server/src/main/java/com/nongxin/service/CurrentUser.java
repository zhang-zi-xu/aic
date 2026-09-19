package com.nongxin.service;

import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 当前数据归属用户。
 *
 * <p>默认采用本机共享模式 {@link #LOCAL_OWNER}，它不是登录认证。
 * 一旦配置解析器，必须得到非空身份；解析失败不能回落到本机所有者。
 * 解析器只能由服务端可信逻辑配置，不能直接采信客户端传入的 userId 或请求头。
 * 聊天与田块/会话/任务/上传入口显式使用身份快照；新增数据入口仍需接入，不能只替换解析器就宣称完成认证。
 */
@Component
public class CurrentUser {

    /** 本机所有者：登录体系上线前所有数据的归属。 */
    public static final String LOCAL_OWNER = "local-owner";

    private static final Supplier<String> LOCAL_RESOLVER = () -> LOCAL_OWNER;
    private volatile Supplier<String> resolver = LOCAL_RESOLVER;
    private final ThreadLocal<Snapshot> active = new ThreadLocal<>();

    /** 只能由可信服务端解析得到的不可变快照；不含请求对象，也不能从客户端字符串构造。 */
    public static final class Snapshot {
        private final CurrentUser source;
        private final String owner;

        private Snapshot(CurrentUser source, String owner) {
            this.source = source;
            this.owner = owner;
        }
    }

    /** 在请求入口调用；解析失败不产生可执行的快照。 */
    public Snapshot capture() {
        return new Snapshot(this, id());
    }

    /** 允许上传读流等操作原样抛出受检异常，无需包装成另一种业务错误。 */
    @FunctionalInterface
    public interface ScopedWork<T, E extends Exception> {
        T get() throws E;
    }

    /** 仅在此回调期间固定归属；正常、异常、取消均恢复原上下文，不继承到其他线程。 */
    public <T, E extends Exception> T withSnapshot(Snapshot snapshot, ScopedWork<T, E> work) throws E {
        if (snapshot == null || snapshot.source != this) throw new IdentityUnavailable();
        java.util.Objects.requireNonNull(work, "身份作用域回调不能为空");
        Snapshot previous = active.get();
        active.set(snapshot);
        try {
            return work.get();
        } finally {
            if (previous == null) active.remove();
            else active.set(previous);
        }
    }

    /** 不保留解析器的原始异常消息或原因，避免把凭据、请求或内部路径带入响应/日志。 */
    public static final class IdentityUnavailable extends RuntimeException {
        private IdentityUnavailable() {
            super("当前用户身份暂时无法确认，请稍后重试");
        }
    }

    /** 当前用户 id（永不返回 null，避免 SQL 条件被绕过）。 */
    public String id() {
        Snapshot snapshot = active.get();
        if (snapshot != null) return snapshot.owner;
        final String id;
        try {
            id = resolver.get();
        } catch (RuntimeException failure) {
            throw new IdentityUnavailable();
        }
        if (id == null || id.isBlank()) throw new IdentityUnavailable();
        return id;
    }

    /** 当前归属是否为本机所有者；解析失败仍抛错，不把它伪装成某种有效归属。 */
    public boolean isLocalOwner() {
        return LOCAL_OWNER.equals(id());
    }

    /** 配置解析逻辑，而非设置本次请求的身份；测试中也用它模拟不同归属。 */
    public void setResolver(Supplier<String> resolver) {
        if (resolver == null) throw new IllegalArgumentException("身份解析器不能为空；恢复本机共享模式须显式调用 reset()");
        this.resolver = resolver;
    }

    /** 显式恢复本机共享模式；不得作为请求身份解析失败的兜底。 */
    public void reset() {
        this.resolver = LOCAL_RESOLVER;
    }
}
