package com.nongxin.service;

import com.nongxin.service.impl.ApiKeyServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

/** Real SQLite counting tests; fake keys only, without starting a provider or application server. */
class ApiKeyServiceImplTest {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 14);
    private static final String SERVER_KEY = "fake-server-key-for-tests-only";
    private static final String FIRST_IP = "192.0.2.10";
    private static final String SECOND_IP = "192.0.2.20";

    @TempDir
    Path directory;

    private JdbcTemplate jdbc;
    private MockedStatic<LocalDate> dates;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("usage.db"));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        dates = mockStatic(LocalDate.class, CALLS_REAL_METHODS);
        dates.when(LocalDate::now).thenReturn(DAY);
        useClient(FIRST_IP);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        if (dates != null) dates.close();
    }

    @Test
    void freshStatusReportsZeroWithoutCreatingUsageRows() {
        var service = service(true, 2, 5);

        assertThat(service.status())
                .containsEntry("serverKeyConfigured", true)
                .containsEntry("globalUsedToday", 0)
                .containsEntry("globalRemaining", 5)
                .containsEntry("ipUsedToday", 0)
                .doesNotContainKey("usageError");
        assertNoUsageRows();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void serverCustomKeyIsNeverReleasedWithoutATrustedServerEndpoint(boolean limited) {
        var service = new ApiKeyServiceImpl(jdbc, SERVER_KEY, "custom", "demo-model", limited, 2, 5);
        var denied = service.resolve(null, "openai", "client-model");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.apiKey()).isEmpty();
        assertThat(denied.denial().name()).isEqualTo("SERVER_ENDPOINT_UNAVAILABLE");
        assertThat(denied.denyReason()).contains("服务端", "自定义").doesNotContain(SERVER_KEY);
        assertNoUsageRows();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void endpointBoundaryDoesNotDependOnDatabaseOrClientAvailability(boolean limited) {
        var monitoredJdbc = org.mockito.Mockito.spy(jdbc);
        var service = new ApiKeyServiceImpl(monitoredJdbc, SERVER_KEY, "custom", "demo-model", limited, 2, 5);
        org.mockito.Mockito.clearInvocations(monitoredJdbc); // Constructor only asks for the data source.
        var selected = service.select(null, "openai", "client-model");
        assertThat(selected.serverSide()).isTrue();
        assertThat(selected.serverEndpointUnavailable()).isTrue();
        var denied = service.resolve(null, "openai", "client-model", null);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.apiKey()).isEmpty();
        assertThat(denied.denial()).isEqualTo(ApiKeyService.Denial.SERVER_ENDPOINT_UNAVAILABLE);

        String userKey = "fake-user-key-without-source";
        var user = service.resolve("  " + userKey + "  ", "custom", "user-model", null);
        assertThat(user.allowed()).isTrue();
        assertThat(user.serverSide()).isFalse();
        assertThat(user.apiKey()).isEqualTo(userKey);
        assertThat(user.provider()).isEqualTo("custom");
        assertThat(user.model()).isEqualTo("user-model");
        org.mockito.Mockito.verifyNoInteractions(monitoredJdbc);
        assertNoUsageRows();
    }

    @Test
    void firstRequestOnANewDayCreatesBothCountersWithoutChangingYesterday() {
        var yesterday = DAY.minusDays(1);
        seed(yesterday, "global", "all", 5);
        seed(yesterday, "ip", FIRST_IP, 2);
        var service = service(true, 2, 5);

        assertServerResolution(service.resolve(null, "requested-provider", "requested-model"));

        assertUsage(DAY, "global", "all", 1);
        assertUsage(DAY, "ip", FIRST_IP, 1);
        assertUsage(yesterday, "global", "all", 5);
        assertUsage(yesterday, "ip", FIRST_IP, 2);
        assertThat(service.status())
                .containsEntry("globalUsedToday", 1)
                .containsEntry("globalRemaining", 4)
                .containsEntry("ipUsedToday", 1)
                .doesNotContainKey("usageError");
    }

    @Test
    void sequentialRequestsStartingWithNoRowsReachTheIpLimit() {
        var service = service(true, 2, 5);
        assertServerResolution(service.resolve("", null, null));
        assertServerResolution(service.resolve("", null, null));

        var denied = service.resolve("", null, null);

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.apiKey()).isEmpty();
        assertThat(denied.denyReason()).contains("每设备 2 次/日");
        assertUsage(DAY, "global", "all", 2);
        assertUsage(DAY, "ip", FIRST_IP, 2);
    }

    @Test
    void aNewClientIsCountedWhenTheGlobalCounterAlreadyExists() {
        seed(DAY, "global", "all", 1);
        seed(DAY, "ip", FIRST_IP, 1);
        useClient(SECOND_IP);
        var service = service(true, 2, 5);

        assertThat(service.status()).containsEntry("globalUsedToday", 1)
                .containsEntry("ipUsedToday", 0).doesNotContainKey("usageError");
        assertServerResolution(service.resolve(null, null, null));

        assertUsage(DAY, "global", "all", 2);
        assertUsage(DAY, "ip", FIRST_IP, 1);
        assertUsage(DAY, "ip", SECOND_IP, 1);
    }

    @Test
    void sequentialClientsStartingWithNoRowsReachTheGlobalLimit() {
        var service = service(true, 3, 2);
        assertServerResolution(service.resolve(null, null, null));
        useClient(SECOND_IP);
        assertServerResolution(service.resolve(null, null, null));
        useClient("192.0.2.30");

        var denied = service.resolve(null, null, null);

        assertThat(denied.allowed()).isFalse();
        assertThat(denied.apiKey()).isEmpty();
        assertThat(denied.denyReason()).contains("全站 2 次/日");
        assertUsage(DAY, "global", "all", 2);
        assertUsage(DAY, "ip", FIRST_IP, 1);
        assertUsage(DAY, "ip", SECOND_IP, 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_usage", Integer.class)).isEqualTo(3);
    }

    @Test
    void userSuppliedKeyBypassesExhaustedDemoQuotaWithoutChangingCounters() {
        seed(DAY, "global", "all", 5);
        seed(DAY, "ip", FIRST_IP, 2);
        var service = service(true, 2, 5);

        var resolution = service.resolve("  fake-user-key-for-tests-only  ", "user-provider", "user-model");

        assertThat(resolution.allowed()).isTrue();
        assertThat(resolution.serverSide()).isFalse();
        assertThat(resolution.apiKey()).isEqualTo("fake-user-key-for-tests-only");
        assertThat(resolution.provider()).isEqualTo("user-provider");
        assertThat(resolution.model()).isEqualTo("user-model");
        assertUsage(DAY, "global", "all", 5);
        assertUsage(DAY, "ip", FIRST_IP, 2);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "short")
    void unconfiguredServerKeyDoesNotCreateUsageRows(String serverKey) {
        var service = new ApiKeyServiceImpl(jdbc, serverKey, "demo-provider", "demo-model", true, 2, 5);

        var resolution = service.resolve(null, "user-provider", "user-model");

        assertThat(resolution.allowed()).isTrue();
        assertThat(resolution.apiKey()).isEmpty();
        assertThat(resolution.serverSide()).isFalse();
        assertThat(resolution.provider()).isEqualTo("user-provider");
        assertThat(resolution.model()).isEqualTo("user-model");
        assertThat(service.status()).containsEntry("serverKeyConfigured", false);
        assertNoUsageRows();
    }

    @Test
    void disabledLimitsKeepDemoAccessWithoutCreatingUsageRows() {
        var service = service(false, 1, 1);

        assertServerResolution(service.resolve(null, null, null));
        assertServerResolution(service.resolve(null, null, null));

        assertThat(service.status()).containsEntry("limitEnabled", false)
                .containsEntry("globalUsedToday", 0).containsEntry("ipUsedToday", 0)
                .doesNotContainKey("usageError");
        assertNoUsageRows();
    }

    @Test
    void recreatedServiceUsesPersistedCountersFromTheFirstRequest() {
        assertServerResolution(service(true, 1, 5).resolve(null, null, null));

        var recreated = service(true, 1, 5);

        assertThat(recreated.status()).containsEntry("globalUsedToday", 1).containsEntry("ipUsedToday", 1);
        assertThat(recreated.resolve(null, null, null).allowed()).isFalse();
        assertUsage(DAY, "global", "all", 1);
        assertUsage(DAY, "ip", FIRST_IP, 1);
    }

    @Test
    void aDatabaseFailureIsNotMisreportedAsZeroUsage() {
        // Only this test's @TempDir database is affected; error policy is reviewed separately in R03.
        jdbc.execute("DROP TABLE api_usage");

        assertThat(service(true, 2, 5).status()).containsKey("usageError")
                .doesNotContainKeys("globalUsedToday", "ipUsedToday");
    }

    private ApiKeyServiceImpl service(boolean enabled, int perIp, int global) {
        return new ApiKeyServiceImpl(jdbc, SERVER_KEY, "demo-provider", "demo-model", enabled, perIp, global);
    }

    private void useClient(String ip) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(ip);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private void seed(LocalDate day, String scope, String key, int count) {
        jdbc.update("INSERT INTO api_usage (day, scope, scope_key, count) VALUES (?, ?, ?, ?)",
                day.toString(), scope, key, count);
    }

    private void assertUsage(LocalDate day, String scope, String key, int count) {
        assertThat(jdbc.queryForList("SELECT count FROM api_usage WHERE day = ? AND scope = ? AND scope_key = ?",
                Integer.class, day.toString(), scope, key)).containsExactly(count);
    }

    private void assertNoUsageRows() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_usage", Integer.class)).isZero();
    }

    private void assertServerResolution(ApiKeyService.Resolution resolution) {
        assertThat(resolution.allowed()).isTrue();
        assertThat(resolution.serverSide()).isTrue();
        assertThat(resolution.apiKey()).isEqualTo(SERVER_KEY);
        assertThat(resolution.provider()).isEqualTo("demo-provider");
        assertThat(resolution.model()).isEqualTo("demo-model");
    }
}
