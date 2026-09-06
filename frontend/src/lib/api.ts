import type { ChatMessage, LiveContext } from '@/types';
export type Conversation = { id: string; title: string; fieldId: string | null; messages: ChatMessage[]; createdAt: string };
export type AiSettings = { provider: string; model: string; baseUrl: string; apiKey: string };
export const PROVIDERS = [
  { id: 'deepseek', label: 'DeepSeek', baseUrl: 'https://api.deepseek.com/v1', model: 'deepseek-chat' },
  { id: 'openai', label: 'OpenAI', baseUrl: 'https://api.openai.com/v1', model: 'gpt-4o-mini' },
  { id: 'siliconflow', label: '硅基流动', baseUrl: 'https://api.siliconflow.cn/v1', model: 'deepseek-ai/DeepSeek-V3.2' },
  { id: 'custom', label: '自定义兼容接口', baseUrl: '', model: '' },
];
export const DEFAULT_SETTINGS: AiSettings = { provider: 'deepseek', model: 'deepseek-chat', baseUrl: 'https://api.deepseek.com/v1', apiKey: '' };
export const SETTINGS_KEY = 'nongxin-ai-settings';
export function readSettings(): AiSettings {
  try {
    const value = JSON.parse(sessionStorage.getItem(SETTINGS_KEY) || 'null');
    if (value && PROVIDERS.some(p => p.id === value.provider) && ['model', 'baseUrl', 'apiKey'].every(k => typeof value[k] === 'string')) return value;
  } catch { /* Storage may be unavailable in restricted contexts. */ }
  return { ...DEFAULT_SETTINGS };
}
export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response;
  try { response = await fetch(`/api${path}`, { ...init, headers: { 'Content-Type': 'application/json', ...init?.headers } }); }
  catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error;
    throw new Error('无法连接 Java 服务，请检查后端是否已启动。');
  }
  const data = await response.json().catch(() => null);
  if (!response.ok) throw new Error(typeof data?.error === 'string' ? data.error : `请求失败（${response.status}），请检查后端服务。`);
  if (data === null && response.status !== 204) throw new Error('服务返回了无效的数据格式。');
  return data as T;
}
export const errorText = (error: unknown) => error instanceof Error ? error.message : '操作失败，请重试。';
export const uid = () => crypto.randomUUID();
export function normalizeContext(raw: Partial<LiveContext>, method: 'device' | 'manual', accuracy = 0): LiveContext {
  return { ...raw, method, latitude: raw.latitude ?? 0, longitude: raw.longitude ?? 0, accuracy,
    locatedAt: Date.now(), location: raw.location ?? null, timezone: raw.timezone ?? null,
    timezoneAbbreviation: raw.timezoneAbbreviation ?? null, observedAt: raw.observedAt ?? null,
    weather: raw.weather ?? null, daily: Array.isArray(raw.daily) ? raw.daily : [], dailyText: raw.dailyText ?? '',
    weatherError: raw.weatherError ?? null, sources: raw.sources ?? { weather: null, location: null } };
}
export function weatherLabel(code: number | null | undefined): string {
  if (code == null) return '天气未知';
  if (code === 0) return '晴'; if (code <= 3) return '多云'; if (code <= 48) return '雾';
  if (code <= 67) return '雨'; if (code <= 77) return '雪'; if (code <= 82) return '阵雨';
  if (code <= 86) return '阵雪'; return '雷雨';
}
