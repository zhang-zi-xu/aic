package com.nongxin.service;

import com.nongxin.model.RiskReport;

import java.util.List;
import java.util.Map;

/**
 * 农情数据风险规则引擎接口（R0-R5 阈值规则）。
 */
public interface RiskService {

    /** 从自由文本提取指标（温度/湿度/降水/风速/土壤湿度） */
    Map<String, Double> extractMetrics(String text);

    /** 解析「时间 指标 值」文本行（支持逗号/空白分隔） */
    List<Map<String, Object>> parseFarmData(String text);

    /** 风险判定（phaseText 用于冷害规则） */
    RiskReport assess(Map<String, Double> metrics, List<Map<String, Object>> series, String phaseText);

    /** 报告转文字（供 Agent 上下文与前端渲染） */
    String reportText(RiskReport report);
}