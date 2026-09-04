// Agent 多轮执行循环（移植自 yoked-main chatWithTools 设计）：
// 一次请求 → AI 自主选工具 → 执行 → 回传结果 → 再请求，直至无 tool_calls。
// 产出型工具（submit_*）的结果会被结构化采集，供前端渲染卡片。
// 支持 forceTool：强制模型调用指定工具（tool_choice 锁定），用于兜底轮。

import { ToolRegistry, type ToolContext } from './tools';

export type ChatMessage = {
  role: 'user' | 'assistant' | 'system' | 'tool';
  content: string;
  tool_calls?: Array<{
    id: string;
    type: 'function';
    function: { name: string; arguments: string };
  }>;
  tool_call_id?: string;
};

export type AgentConfig = {
  model: string;
  endpoint: string;
  apiKey: string;
  systemPrompt: string;
  tools: ToolRegistry;
  toolCtx: ToolContext;
  maxRounds?: number; // 默认 5（同 yoked MAX_FC_ROUNDS）
  temperature?: number;
  forceTool?: string | null; // 非空时本轮的首次调用强制指定工具
};

export type ToolSubmission = {
  name: string;
  args: Record<string, unknown>;
  result?: string; // 产出型工具的执行输出（用于前端卡片渲染）
};

export type AgentResult = {
  reply: string;
  submissions: ToolSubmission[]; // 产出型工具（submit_*）的结构化结果
  rounds: number;
  degraded: boolean; // 是否降级为自由对话
};

const MAX_ROUNDS = 5;

export async function runAgent(config: AgentConfig, history: ChatMessage[]): Promise<AgentResult | { error: string }> {
  const { model, endpoint, apiKey, systemPrompt, tools, toolCtx } = config;
  const maxRounds = config.maxRounds ?? MAX_ROUNDS;
  const submissions: ToolSubmission[] = [];

  const messages: ChatMessage[] = [
    { role: 'system', content: systemPrompt },
    ...history,
  ];

  let rounds = 0;
  let forced = Boolean(config.forceTool);

  try {
    while (rounds < maxRounds) {
      let toolDefs = tools.collect(toolCtx);
      const body: Record<string, unknown> = {
        model,
        messages,
        temperature: config.temperature ?? 0.35,
      };
      // 强制工具轮：只暴露目标工具 + tool_choice='required'（兼容 DeepSeek/OpenAI，
      // 两者均不保证支持 {type:'function',function:{name}} 对象形式）
      if (forced && config.forceTool) {
        toolDefs = toolDefs.filter((t) => (t.function as { name?: unknown })?.name === config.forceTool);
        body.tools = toolDefs;
        body.tool_choice = 'required';
        forced = false;
      } else if (toolDefs.length) {
        body.tools = toolDefs;
      }

      const response = await fetch(endpoint, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${apiKey}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(body),
      });

      const data = await response.json().catch(() => null) as {
        choices?: Array<{ message?: { content?: unknown; tool_calls?: unknown } }>;
        error?: { message?: unknown };
      } | null;

      if (!response.ok) {
        const detail = typeof data?.error?.message === 'string' ? data.error.message.slice(0, 240) : `供应商返回 ${response.status}`;
        return { error: `对话请求失败：${detail}` };
      }

      const message = data?.choices?.[0]?.message;
      const content = typeof message?.content === 'string' ? message.content : '';
      const toolCalls = normalizeToolCalls(message?.tool_calls);

      if (!toolCalls || !toolCalls.length) {
        // 无工具调用：最终回复
        if (!content.trim()) return { error: '供应商没有返回可用的回答' };
        return {
          reply: content.trim(),
          submissions,
          rounds: rounds + 1,
          degraded: false,
        };
      }

      // 有工具调用：记录 assistant 消息，逐一执行工具，回传结果
      messages.push({
        role: 'assistant',
        content,
        tool_calls: toolCalls.map((tc) => ({ id: tc.id, type: 'function', function: tc.function })),
      });

      for (const tc of toolCalls) {
        let args: Record<string, unknown> = {};
        try {
          args = tc.function.arguments ? JSON.parse(tc.function.arguments) : {};
        } catch {
          args = {};
        }
        // 产出型工具：结果进 submissions（含执行输出），同时给模型确认文本
        if (tc.function.name.startsWith('submit_')) {
          const resultText = await tools.execute(tc.function.name, args, toolCtx);
          submissions.push({ name: tc.function.name, args, result: resultText });
          messages.push({
            role: 'tool',
            tool_call_id: tc.id,
            content: resultText.slice(0, 8000),
          });
          continue;
        }
        const result = await tools.execute(tc.function.name, args, toolCtx);
        messages.push({
          role: 'tool',
          tool_call_id: tc.id,
          content: result.slice(0, 8000),
        });
      }

      rounds += 1;
    }

    // 轮次用尽：给出兜底文本（有结构化产出时优先提示产出已生成）
    const fallback = submissions.length
      ? '工具调用轮次已用尽，已为你整理出上方结构化结果，可继续追问。'
      : '工具调用轮次已用尽，请简化问题后重试。';
    return {
      reply: fallback,
      submissions,
      rounds,
      degraded: true,
    };
  } catch (error) {
    return { error: error instanceof Error ? error.message : 'Agent 运行失败' };
  }
}

function normalizeToolCalls(value: unknown): Array<{ id: string; function: { name: string; arguments: string } }> {
  if (!Array.isArray(value)) return [];
  const out: Array<{ id: string; function: { name: string; arguments: string } }> = [];
  for (const item of value) {
    if (!item || typeof item !== 'object') continue;
    const call = item as { id?: unknown; function?: unknown };
    const fn = call.function as { name?: unknown; arguments?: unknown } | undefined;
    if (typeof call.id !== 'string' || !fn || typeof fn.name !== 'string') continue;
    out.push({
      id: call.id,
      function: {
        name: fn.name,
        arguments: typeof fn.arguments === 'string' ? fn.arguments : JSON.stringify(fn.arguments ?? {}),
      },
    });
  }
  return out;
}
