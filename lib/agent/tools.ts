// Agent 工具系统（移植自 yoked-main 的 ToolCenter 设计）：
// 工具定义（name/description/parameters）+ 执行器 + 条件注册 + 统一采集。

export type JsonSchema = {
  type: 'object';
  properties: Record<string, unknown>;
  required?: string[];
};

export type ToolExecutor = (args: Record<string, unknown>, ctx: ToolContext) => Promise<string> | string;

export type ToolContext = {
  userId: string;
  extra: Record<string, unknown>;
};

export type ToolDefinition = {
  name: string;
  description: string;
  parameters: JsonSchema;
  executor: ToolExecutor;
  available?: (ctx: ToolContext) => boolean; // 条件工具：不满足则不注册
};

export class ToolRegistry {
  private tools = new Map<string, ToolDefinition>();

  register(tool: ToolDefinition) {
    this.tools.set(tool.name, tool);
  }

  /** 按当前上下文收集可用工具（条件评估），返回 OpenAI 兼容 tools 数组 */
  collect(ctx: ToolContext): Array<Record<string, unknown>> {
    const defs: ToolDefinition[] = [];
    for (const tool of this.tools.values()) {
      if (tool.available && !tool.available(ctx)) continue;
      defs.push(tool);
    }
    return defs.map((t) => ({
      type: 'function',
      function: {
        name: t.name,
        description: t.description,
        parameters: t.parameters,
      },
    }));
  }

  /** 执行工具，返回执行结果文本（工具 JSON 输出用于结构化采集） */
  async execute(name: string, args: Record<string, unknown>, ctx: ToolContext): Promise<string> {
    const tool = this.tools.get(name);
    if (!tool) return `错误：工具 ${name} 不存在`;
    try {
      return await tool.executor(args ?? {}, ctx);
    } catch (error) {
      return `工具执行失败：${error instanceof Error ? error.message : '未知错误'}`;
    }
  }
}

/** 创建 OpenAI 兼容的 function tool 定义（yoked functionDef 辅助方法的 TS 版） */
export function functionDef(name: string, description: string, properties: Record<string, unknown>, required: string[] = []): ToolDefinition {
  return {
    name,
    description,
    parameters: { type: 'object', properties, required },
    executor: () => '',
  };
}
