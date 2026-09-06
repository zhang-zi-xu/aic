package com.nongxin.agent;

import java.util.List;

/** Agent 运行结果 */
public record AgentResult(String reply, List<ToolSubmission> submissions, int rounds, boolean degraded) {}
