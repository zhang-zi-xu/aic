package com.nongxin.service;

import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 当前数据归属用户。
 *
 * <p>还没有登录体系时，一律返回本机所有者 {@link #LOCAL_OWNER}——所有数据都属于它。
 * 将来接入登录（手机号 / 微信）后，**只需要替换 {@link #setResolver} 注入的解析逻辑**（从登录态取用户 id），
 * 各服务的 SQL 条件与表结构都不用重做。这是"先把数据分隔好、再谈登录"的关键接缝。
 */
@Component
public class CurrentUser {

    /** 本机所有者：登录体系上线前所有数据的归属。 */
    public static final String LOCAL_OWNER = "local-owner";

    private Supplier<String> resolver = () -> LOCAL_OWNER;

    /** 当前用户 id（永不返回 null，避免 SQL 条件被绕过）。 */
    public String id() {
        String id = resolver.get();
        return id == null || id.isBlank() ? LOCAL_OWNER : id;
    }

    /** 是否还是"没有登录"的本机所有者模式。 */
    public boolean isLocalOwner() {
        return LOCAL_OWNER.equals(id());
    }

    /** 替换解析逻辑（接入登录时调用；测试里也用它模拟另一个用户）。 */
    public void setResolver(Supplier<String> resolver) {
        this.resolver = resolver == null ? () -> LOCAL_OWNER : resolver;
    }

    /** 恢复为本机所有者。 */
    public void reset() {
        this.resolver = () -> LOCAL_OWNER;
    }
}
