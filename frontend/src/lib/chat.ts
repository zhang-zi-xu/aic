import type { ChatContext, ChatMessage, FieldProfile, LiveContext } from '@/types';

export type ChatResult = Pick<ChatMessage, 'plan' | 'risk' | 'clarify' | 'degraded' | 'sources'> & { reply: string };
export type ChatEvent = { event: string; data: Record<string, unknown> };

export function captureContext(field: FieldProfile | null, context: LiveContext | null): ChatContext {
  return structuredClone({ field,
    location: context ? { label: context.location, latitude: context.latitude, longitude: context.longitude, method: context.method } : null,
    weather: context ? { ...context, locationText: `位置由${context.method === 'manual' ? '手动城市查询（非田块精确位置）' : '设备定位（非田块位置）'}提供；天气数据时间 ${context.observedAt || '未提供'}；来源 ${context.sources.weather || '不可用'}。当前天气：${context.weather ? JSON.stringify(context.weather) : '不可用'}。` } : null,
  });
}

/** Retry reuses the original user turn; interrupted and partially completed answers are replaced, not appended. */
export function retryMessages(messages: ChatMessage[]): ChatMessage[] | null {
  const last = messages.at(-1);
  if (last?.role === 'user') return messages;
  const incomplete = last?.role === 'assistant' && (last.status === 'interrupted' || last.degraded === true);
  if (incomplete && messages.at(-2)?.role === 'user') return messages.slice(0, -1);
  return null;
}

/** Only complete answers enter the model history; interrupted/degraded text is never sent as a finished reply. */
export function requestHistory(messages: ChatMessage[]) {
  return messages.filter(m => m.status !== 'interrupted' && m.degraded !== true).slice(-20).map(m => {
    const content = m.content + (m.attachedData ? `\n\n【用户附加的农情记录，内容可能不完整】\n${m.attachedData}` : '')
      + (m.clarify ? `\n\n【此前提出的确认问题】\n${JSON.stringify(m.clarify)}` : '')
      + (m.plan ? `\n\n【此前生成的建议，未经用户确认执行】\n${JSON.stringify(m.plan)}` : '')
      + (m.risk ? `\n\n【此前的规则筛查结果】\n${JSON.stringify(m.risk)}` : '');
    if (content.length > 20000) throw new Error('单条上下文超过 20,000 字符，请新建对话继续；内容没有被截断。');
    return { role: m.role, content };
  });
}

/** POST SSE reader: handles split UTF-8, CRLF, multiple data lines, errors and premature EOF. */
export async function streamChat(body: unknown, signal: AbortSignal, onEvent: (event: ChatEvent) => void): Promise<ChatResult> {
  const response = await fetch('/api/chat/stream', { method: 'POST', signal,
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' }, body: JSON.stringify(body) });
  if (!response.ok) {
    const error = await response.json().catch(() => null);
    throw new Error(typeof error?.error === 'string' ? error.error : `对话请求失败（${response.status}），请稍后重试。`);
  }
  if (!response.headers.get('content-type')?.includes('text/event-stream') || !response.body) {
    throw new Error('后端未返回流式响应，请确认 Java 服务已更新并重启。');
  }
  const reader = response.body.getReader(); const decoder = new TextDecoder();
  let buffer = ''; let name = ''; let data: string[] = [];
  let result: ChatResult | undefined;
  const dispatch = () => {
    if (!data.length) { name = ''; return; }
    let value: Record<string, unknown>;
    try { value = JSON.parse(data.join('\n')); }
    catch { throw new Error('回答数据格式不完整，请重试。'); }
    const event = name || 'message'; name = ''; data = [];
    if (event === 'error') throw new Error(typeof value.error === 'string' ? value.error : '模型回答失败，请重试。');
    if (event === 'done') {
      if (!(typeof value.reply === 'string' && value.reply.trim()) && !value.plan && !value.risk && !value.clarify)
        throw new Error('模型没有返回有效内容，请检查设置后重试。');
      result = value as ChatResult;
    } else onEvent({ event, data: value });
  };
  try {
    while (!result) {
      signal.throwIfAborted();
      const { value, done } = await reader.read();
      buffer += decoder.decode(value, { stream: !done });
      if (buffer.length > 2_000_000) throw new Error('回答数据过长，请简化问题后重试。');
      let end: number;
      while ((end = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, end).replace(/\r$/, ''); buffer = buffer.slice(end + 1);
        if (!line) dispatch();
        else if (line.startsWith('event:')) name = line.slice(6).trim();
        else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
        if (result) break;
      }
      if (done && !result) throw new Error('回答连接中断，已收到的内容不是完整答案，可重试这条问题。');
    }
    signal.throwIfAborted();
    return result;
  } finally { await reader.cancel().catch(() => {}); reader.releaseLock(); }
}
