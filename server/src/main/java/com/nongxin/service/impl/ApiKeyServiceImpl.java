package com.nongxin.service.impl;

import com.nongxin.service.ApiKeyService;
import com.nongxin.service.QuotaClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 成本护栏实现：
 * - 用户自带 Key 优先且不限流；
 * - 服务端演示 Key 兜底时按「IP 日限额 + 全局日限额」在同一事务中预留额度；
 * - 只有事务提交成功后才放行；限额或数据库故障均不返回演示 Key；
 * - 计数使用请求入口传入的直连来源快照，不在线程内读取请求，也不相信任意转发头。
 */
@Service
public class ApiKeyServiceImpl implements ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyServiceImpl.class);
    private static final int MIN_KEY_LENGTH = 12;
    private static final String QUOTA_UNAVAILABLE =
            "演示额度暂时无法核验，已暂停本次演示请求。请稍后重试，或在设置中填入自己的 API Key 继续使用。";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate quotaTransaction;
    private final String defaultApiKey;
    private final String defaultProvider;
    private final String defaultModel;
    private final boolean limitEnabled;
    private final int dailyPerIp;
    private final int dailyGlobal;

    public ApiKeyServiceImpl(
            JdbcTemplate jdbc,
            @Value("${nongxin.chat.default-api-key:}") String defaultApiKey,
            @Value("${nongxin.chat.default-provider:deepseek}") String defaultProvider,
            @Value("${nongxin.chat.default-model:deepseek-chat}") String defaultModel,
            @Value("${nongxin.usage.enabled:true}") boolean limitEnabled,
            @Value("${nongxin.usage.daily-per-ip:30}") int dailyPerIp,
            @Value("${nongxin.usage.daily-global:500}") int dailyGlobal) {
        this.jdbc = jdbc;
        var transactionManager = new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource()));
        // 提交失败也要尝试回滚，避免连接恢复 autoCommit 时留下部分/未确认的计数。
        transactionManager.setRollbackOnCommitFailure(true);
        this.quotaTransaction = new TransactionTemplate(transactionManager);
        // 返回 Key 前必须已经独立提交，不能依赖调用方稍后提交或回滚外层事务。
        this.quotaTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.defaultApiKey = defaultApiKey == null ? "" : defaultApiKey.trim();
        this.defaultProvider = defaultProvider;
        this.defaultModel = defaultModel;
        this.limitEnabled = limitEnabled;
        this.dailyPerIp = Math.max(1, dailyPerIp);
        this.dailyGlobal = Math.max(1, dailyGlobal);
        if (serverKeyConfigured()) {
            log.info("服务端演示 Key 已启用：provider={} model={}；限额 每IP {} 次/日、全局 {} 次/日",
                    defaultProvider, defaultModel, dailyPerIp, dailyGlobal);
        } else {
            log.info("未配置服务端演示 Key（NONGXIN_CHAT_KEY）：用户需自带 Key，行为与原先一致");
        }
    }

    private boolean serverKeyConfigured() {
        return defaultApiKey.length() >= MIN_KEY_LENGTH;
    }

    @Override
    public ModelSelection select(String requestApiKey, String requestProvider, String requestModel) {
        boolean userKey = requestApiKey != null && requestApiKey.trim().length() >= MIN_KEY_LENGTH;
        // Constructor configuration is immutable, so this choice matches the later resolve call.
        // No credentials are returned and no quota/database operation happens during validation.
        if (userKey || !serverKeyConfigured()) return new ModelSelection(requestProvider, requestModel, userKey, false);
        return new ModelSelection(defaultProvider, defaultModel, true, true);
    }

    @Override
    public Resolution resolve(String requestApiKey, String requestProvider, String requestModel, QuotaClient client) {
        String key = requestApiKey == null ? "" : requestApiKey.trim();
        if (key.length() >= MIN_KEY_LENGTH) {
            // 用户自带 Key：不限流
            return new Resolution(key, false, null, requestProvider, requestModel);
        }
        if (!serverKeyConfigured()) {
            return new Resolution("", false, null, requestProvider, requestModel);
        }
        // Enforce the credential boundary here too, even for callers that skip controller preflight.
        // Disabling quota limits never authorizes sending a server Key to a client-selected URL.
        if (select(requestApiKey, requestProvider, requestModel).serverEndpointUnavailable()) {
            return new Resolution("", true, SERVER_ENDPOINT_UNAVAILABLE_MESSAGE,
                    defaultProvider, defaultModel, Denial.SERVER_ENDPOINT_UNAVAILABLE);
        }
        if (!limitEnabled) {
            return new Resolution(defaultApiKey, true, null, defaultProvider, defaultModel);
        }
        if (client == null || !client.known()) {
            return new Resolution("", true,
                    "无法识别本次请求的网络来源，暂不能使用演示额度。请重试，或在设置中填入自己的 API Key 继续使用。",
                    defaultProvider, defaultModel, Denial.CLIENT_UNAVAILABLE);
        }
        String day = LocalDate.now().toString();
        String ip = client.address();
        try {
            Reservation reservation = quotaTransaction.execute(transaction -> {
                // 第一条语句直接执行条件写入：由 SQLite 串行化写者，不先读出过期余量。
                // 两个作用域共用当前事务和连接，任何失败都会撤销已预留的全局额度。
                if (!incrementWithinLimit(day, "global", "all", dailyGlobal)) {
                    transaction.setRollbackOnly();
                    return Reservation.GLOBAL_LIMIT;
                }
                if (!incrementWithinLimit(day, "ip", ip, dailyPerIp)) {
                    transaction.setRollbackOnly();
                    return Reservation.IP_LIMIT;
                }
                return Reservation.GRANTED;
            });
            if (reservation == Reservation.GLOBAL_LIMIT) {
                return new Resolution("", true,
                        "今日演示额度已用完（全站 " + dailyGlobal + " 次/日）。请在设置中填入自己的 API Key 继续使用，或明天再来。",
                        defaultProvider, defaultModel, Denial.QUOTA_EXHAUSTED);
            }
            if (reservation == Reservation.IP_LIMIT) {
                return new Resolution("", true,
                        "你今天的演示额度已用完（每设备 " + dailyPerIp + " 次/日）。可在设置中填入自己的 API Key 继续使用，或明天再来。",
                        defaultProvider, defaultModel, Denial.QUOTA_EXHAUSTED);
            }
            if (reservation != Reservation.GRANTED) throw new IllegalStateException("Missing quota reservation result");
            // execute 已完成提交；提交/回滚/连接异常会进入下面的安全拒绝分支。
            return new Resolution(defaultApiKey, true, null, defaultProvider, defaultModel);
        } catch (Exception e) {
            log.warn("演示额度核验失败，已拒绝本次请求（{}）", e.getClass().getSimpleName());
            return new Resolution("", true, QUOTA_UNAVAILABLE, defaultProvider, defaultModel, Denial.QUOTA_UNAVAILABLE);
        }
    }

    @Override
    public Map<String, Object> status(QuotaClient client) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("serverKeyConfigured", serverKeyConfigured());
        out.put("limitEnabled", limitEnabled);
        out.put("dailyPerIp", dailyPerIp);
        out.put("dailyGlobal", dailyGlobal);
        if (serverKeyConfigured()) {
            if (client == null || !client.known()) {
                out.put("usageError", "无法识别本次请求的网络来源，暂时无法查询用量。");
                return out;
            }
            String day = LocalDate.now().toString();
            try {
                int globalUsed = currentCount(day, "global", "all");
                int ipUsed = currentCount(day, "ip", client.address());
                // 两次读取都成功才返回用量，故障时不混入部分统计或原始 SQL 错误。
                out.put("globalUsedToday", globalUsed);
                out.put("globalRemaining", Math.max(0, dailyGlobal - globalUsed));
                out.put("ipUsedToday", ipUsed);
            } catch (Exception e) {
                log.warn("演示用量查询失败（{}）", e.getClass().getSimpleName());
                out.put("usageError", "用量暂时无法查询，请稍后重试。");
            }
        }
        return out;
    }

    private int currentCount(String day, String scope, String scopeKey) {
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT count FROM api_usage WHERE day = ? AND scope = ? AND scope_key = ?",
                    Integer.class, day, scope, scopeKey);
            return count == null ? 0 : count;
        } catch (EmptyResultDataAccessException e) {
            // 新日期/新客户端尚无记录时用量为 0；状态查询不创建计数行。
            // 其他数据库异常不能伪装成零用量，仍由调用方处理。
            return 0;
        }
    }

    private boolean incrementWithinLimit(String day, String scope, String scopeKey, int limit) {
        int changed = jdbc.update("INSERT INTO api_usage (day, scope, scope_key, count) VALUES (?,?,?,1) "
                        + "ON CONFLICT(day, scope, scope_key) DO UPDATE SET count = count + 1, "
                        + "updated_at = datetime('now','localtime') WHERE api_usage.count < ?",
                day, scope, scopeKey, limit);
        if (changed != 0 && changed != 1) throw new IllegalStateException("Unexpected quota update count");
        return changed == 1;
    }

    private enum Reservation { GRANTED, GLOBAL_LIMIT, IP_LIMIT }

}
