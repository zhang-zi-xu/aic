package com.nongxin.agent;

import java.util.Map;

/** 结构化产出采集（submit_* 工具） */
public record ToolSubmission(String name, Map<String, Object> args, String result) {}
