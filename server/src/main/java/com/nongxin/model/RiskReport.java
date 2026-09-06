package com.nongxin.model;

import java.util.List;

/** 风险判定报告 */
public record RiskReport(String overall, String summary, List<RiskItem> items) {}
