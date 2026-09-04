type ChatRole = 'user' | 'assistant';

type ChatMessage = {
  role: ChatRole;
  content: string;
};

const providerEndpoints: Record<string, string> = {
  deepseek: 'https://api.deepseek.com/v1/chat/completions',
  openai: 'https://api.openai.com/v1/chat/completions',
  siliconflow: 'https://api.siliconflow.cn/v1/chat/completions',
};

const blockedHosts = new Set(['localhost', '0.0.0.0', '::1']);

function isPrivateHost(hostname: string) {
  const host = hostname.toLowerCase().replace(/^\[|\]$/g, '');
  if (blockedHosts.has(host) || host.endsWith('.local')) return true;
  if (host.startsWith('127.') || host.startsWith('10.') || host.startsWith('192.168.') || host.startsWith('169.254.')) return true;
  const match = host.match(/^172\.(\d{1,3})\./);
  if (match && Number(match[1]) >= 16 && Number(match[1]) <= 31) return true;
  return host.includes(':') && (host.startsWith('fc') || host.startsWith('fd') || host.startsWith('fe80:'));
}

function resolveEndpoint(provider: string, baseUrl: unknown) {
  if (providerEndpoints[provider]) return providerEndpoints[provider];
  if (provider !== 'custom' || typeof baseUrl !== 'string') throw new Error('不支持的供应商');

  const parsed = new URL(baseUrl.trim());
  if (parsed.protocol !== 'https:' || parsed.username || parsed.password || isPrivateHost(parsed.hostname)) {
    throw new Error('自定义 API 地址必须是公开的 HTTPS 地址');
  }

  const path = parsed.pathname.replace(/\/$/, '');
  parsed.pathname = path.endsWith('/chat/completions')
    ? path
    : `${path}${path.endsWith('/v1') ? '' : '/v1'}/chat/completions`;
  parsed.search = '';
  parsed.hash = '';
  return parsed.toString();
}

function sanitizeMessages(value: unknown): ChatMessage[] {
  if (!Array.isArray(value)) throw new Error('对话内容格式不正确');
  const messages = value.slice(-20).flatMap((item): ChatMessage[] => {
    if (!item || typeof item !== 'object') return [];
    const role = (item as { role?: unknown }).role;
    const content = (item as { content?: unknown }).content;
    if ((role !== 'user' && role !== 'assistant') || typeof content !== 'string') return [];
    const clean = content.trim().slice(0, 4000);
    return clean ? [{ role, content: clean }] : [];
  });
  if (!messages.length || messages[messages.length - 1].role !== 'user') throw new Error('请先输入问题');
  return messages;
}

function liveContextMessage(value: unknown) {
  if (!value || typeof value !== 'object') return null;
  const context = value as Record<string, unknown>;
  const latitude = typeof context.latitude === 'number' && Number.isFinite(context.latitude) ? context.latitude : null;
  const longitude = typeof context.longitude === 'number' && Number.isFinite(context.longitude) ? context.longitude : null;
  if (latitude === null || longitude === null || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) return null;

  const weather = context.weather && typeof context.weather === 'object' ? context.weather as Record<string, unknown> : {};
  const numberFact = (name: string, unit: string) => {
    const value = weather[name];
    return typeof value === 'number' && Number.isFinite(value) ? `${value}${unit}` : '未获取';
  };
  const safeText = (value: unknown, fallback = '未获取') => typeof value === 'string' && value.trim() ? value.trim().slice(0, 120) : fallback;

  return [
    '以下信息来自用户主动授权后的实时定位与天气接口，可作为本轮回答的已知事实；不得把它扩展成未提供的田块或作物信息：',
    `位置：${safeText(context.location, '地名未解析')}（坐标 ${latitude.toFixed(5)}, ${longitude.toFixed(5)}）`,
    `当地时间：${safeText(context.localTime)}；时区：${safeText(context.timezone)}`,
    `天气观测时间：${safeText(context.observedAt)}`,
    `气温：${numberFact('temperature', '°C')}；体感：${numberFact('apparentTemperature', '°C')}；湿度：${numberFact('humidity', '%')}；降水：${numberFact('precipitation', ' mm')}；天气代码：${numberFact('weatherCode', '')}；风速：${numberFact('windSpeed', ' km/h')}；风向角：${numberFact('windDirection', '°')}`,
  ].join('\n');
}

export async function POST(request: Request) {
  try {
    const body = await request.json() as Record<string, unknown>;
    const provider = typeof body.provider === 'string' ? body.provider : '';
    const model = typeof body.model === 'string' ? body.model.trim().slice(0, 160) : '';
    const apiKey = typeof body.apiKey === 'string' ? body.apiKey.trim() : '';
    if (!model) return Response.json({ error: '请填写模型名称' }, { status: 400 });
    if (!apiKey || apiKey.length < 12) return Response.json({ error: '请填写有效的 API 密钥' }, { status: 400 });

    const endpoint = resolveEndpoint(provider, body.baseUrl);
    const messages = sanitizeMessages(body.messages);
    const contextMessage = liveContextMessage(body.liveContext);
    const upstream = await fetch(endpoint, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${apiKey}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        model,
        messages: [
          {
            role: 'system',
            content: '你是“农心”，一名稳重、实用的农业生产助手。用简洁自然的中文回答，先给结论，再给可执行步骤。只能把用户在本次对话中明确提供的内容当作已知事实；绝不能擅自假设或编造用户的姓名、农场、地区、田块、作物、生育期、天气、传感器数据、病害诊断、政策或资料来源。信息不足时先说明缺少哪些信息并明确追问。涉及农药时提醒以当地登记标签和农技人员意见为准。',
          },
          ...(contextMessage ? [{ role: 'system', content: contextMessage }] : []),
          ...messages,
        ],
        temperature: 0.35,
      }),
    });

    const data = await upstream.json().catch(() => null) as {
      choices?: Array<{ message?: { content?: unknown } }>;
      error?: { message?: unknown };
    } | null;

    if (!upstream.ok) {
      const detail = typeof data?.error?.message === 'string'
        ? data.error.message.slice(0, 240)
        : `供应商返回 ${upstream.status}`;
      return Response.json({ error: `对话请求失败：${detail}` }, { status: 502 });
    }

    const reply = data?.choices?.[0]?.message?.content;
    if (typeof reply !== 'string' || !reply.trim()) {
      return Response.json({ error: '供应商没有返回可用的回答' }, { status: 502 });
    }

    return Response.json({ reply: reply.trim(), provider, model });
  } catch (error) {
    const message = error instanceof Error ? error.message : '请求格式不正确';
    return Response.json({ error: message }, { status: 400 });
  }
}
