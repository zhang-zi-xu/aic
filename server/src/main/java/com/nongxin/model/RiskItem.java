package com.nongxin.model;

/** 风险条目（规则引擎输出） */
public record RiskItem(String level, String title, String reason, String suggestion, String rule) {}
