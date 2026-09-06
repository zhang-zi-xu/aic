package com.nongxin.service.impl;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RiskServiceImplTest {
    private final RiskServiceImpl risk = new RiskServiceImpl();

    @Test
    void absentOrInvalidMetricsAreUnknownInsteadOfLowRisk() {
        var absent = risk.assess(Map.of(), List.of(), "");
        var invalid = risk.assess(Map.of("湿度", 150.0, "温度", Double.NaN, "value", 60.0), List.of(), "");

        assertThat(absent.overall()).isEqualTo("未知");
        assertThat(absent.items().get(0).level()).isEqualTo("未知");
        assertThat(absent.summary()).contains("无法评估");
        assertThat(risk.reportText(absent)).contains("未知（无法评估）");
        assertThat(invalid.overall()).isEqualTo("未知");
    }

    @Test
    void doesNotTreatUnlabeledSeriesAsAnySpecificMetric() {
        var report = risk.assess(Map.of(), List.of(Map.of("time", "2026-09-01", "value", 99.0)), "");

        assertThat(report.overall()).isEqualTo("未知");
    }

    @Test
    void rainfallWithoutDurationDoesNotClaimAnObservedDailyTotal() {
        var report = risk.assess(Map.of("降水量", 60.0), List.of(), "");

        assertThat(report.items().get(0).reason()).contains("输入降水量", "统计时段尚未核实").doesNotContain("近24小时降水量达");
        assertThat(report.items().get(0).suggestion()).contains("先确认降水统计时段");
    }

    @Test
    void preservesNegativeTemperatureAndAvoidsSoilAirHumidityConfusion() {
        Map<String, Double> metrics = risk.extractMetrics("温度-3°C，土壤湿度90%");

        assertThat(metrics).containsEntry("温度", -3.0).containsEntry("土壤湿度", 90.0).doesNotContainKey("湿度");
        assertThat(risk.assess(metrics, List.of(), "抽穗扬花期（估）").items()).anyMatch(item -> "R5".equals(item.rule()));
    }

    @Test
    void missingTemperatureDoesNotBorrowAnotherMetricsValue() {
        assertThat(risk.extractMetrics("温度未知，湿度90%")).doesNotContainKey("温度").containsEntry("湿度", 90.0);
    }

    @Test
    void parsesPercentageSeriesAndUsesNamedObservations() {
        var series = risk.parseFarmData("2026-09-01 湿度 92%\n2026-09-02 湿度 80%");

        assertThat(series).hasSize(2);
        var report = risk.assess(Map.of(), series, "");
        assertThat(report.overall()).isEqualTo("中");
        assertThat(report.items().get(0).reason()).contains("92%").doesNotContain("连续2天");
    }

    @Test
    void convertsExplicitMetersPerSecondAndDoesNotInterpretBeaufortAsKilometersPerHour() {
        assertThat(risk.extractMetrics("风速20 m/s")).containsEntry("风速", 72.0);
        assertThat(risk.extractMetrics("风力6级")).doesNotContainKey("风速");
    }

    @Test
    void lowRiskSummaryDoesNotClaimAllRisksAreExcluded() {
        assertThat(risk.assess(Map.of("温度", 25.0), List.of(), "").summary())
                .contains("未提供的指标", "不能据此排除风险").doesNotContain("保持常规巡查即可");
    }
}
