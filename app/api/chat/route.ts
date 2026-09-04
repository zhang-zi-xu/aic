// 农心 Agent 对话入口：供应商安全代理 + 工具循环（yoked-main chatWithTools 架构移植）。
// 请求体：provider/model/baseUrl/apiKey + messages + field（田块档案）+ location + weather。

import { buildAgriRegistry, type AgentExtras, type FieldProfile } from '@/lib/agent/agriTools';
import type { ToolContext } from '@/lib/agent/tools';
import { runAgent, type ChatMessage } from '@/lib/agent/run';

type ProviderId = 'deepseek' | 'openai' | 'siliconflow' | 'custom';

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
    const farmData = role === 'user' ? textOrNull((item as { farmData?: unknown }).farmData, 4000) : null;
    const merged = farmData ? `${clean}\n\n【用户附加的农情数据】\n${farmData}` : clean;
    return merged ? [{ role, content: merged }] : [];
  });
  if (!messages.length || messages[messages.length - 1].role !== 'user') throw new Error('请先输入问题');
  return messages;
}

function objectValue(value: unknown) {
  return value && typeof value === 'object' ? value as Record<string, unknown> : {};
}

function numberOrNull(value: unknown) {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function textOrNull(value: unknown, maxLength = 200) {
  return typeof value === 'string' && value.trim() ? value.trim().slice(0, maxLength) : null;
}
function buildSystemPrompt(field: FieldProfile | null, locationText: string | null): string {
  return [
    '你是「农心」，以县域农技服务人员的方式提供农业信息。语气朴实、清楚、有人情味，但不能虚构亲身经历、当地流行情况或现场观察。先说明基于哪些已知信息，再给能做的，最后才问缺的。',
    '',
    '【回答结构 · 必须遵守】',
    '- 第一段直接给结论或今天就能执行的事，共 1-3 段，别绕；',
    '- 结论给完再列依据（引用知识库条目），依据放正文里简短带上；',
    '- 缺关键信息时：能给的先给，然后把追问交给 submit_clarify（结构化确认卡，带选项，最多 3 项）——不要用大段文字追问，不要整篇都是「请确认」；',
    '- 追问唯一通道是 submit_clarify：**只调用工具才算完成追问**——如果你只在文字里写「请确认 XX」，用户那边不会出现确认卡，等于没完成回答；',
    '- 结尾只有一句话的提示（涉及用药时才写「具体药剂与用量，以当地登记标签为准」），不要把免责声明挂在开头。',
    '',
    '【说话方式】',
    '- 可以用「咱田里」「这块地」等自然表述；只有取得实时天气或用户描述墒情后，才能谈「今年天气」「当前墒情」；',
    '- 信息不足时先共情再要：小的信息缺口一句话带过就好，别让用户觉得欠你三件事；',
    '- 数字保留原单位与必要精度，不把监测值换成未经定义的口语量级。',
    '',
    '【事实纪律】',
    '- 只能使用用户提供的田块信息、工具返回结果、知识库条目作为事实；绝不编造品种、生育期、测报数据或药剂剂量。',
    '- 不确定的诊断不硬下结论，但要给出「下一步怎么核查」，并说明为什么需要现场核实。',
    '',
    '【工具使用】',
    '- 涉及：病虫害识别/防治、施肥浇水、农时操作 → 调用 search_agri_knowledge 获取依据，回答中标注依据来源；',
    '- 涉及：田块档案（作物/播期/生育期/记录）→ 调用 get_field_context；',
    '- 涉及：7 日内农事安排、施药窗口、暴雨大风判断 → 调用 get_weather_forecast；',
    '- 用户上传农情数据或给数值 → 调用 submit_risk_report 完成风险判定，并在回答中引用风险等级；',
    '- 用户要求「方案/计划/处方/怎么办/安排」且信息足够 → 先取依据，再调用 submit_farm_plan 提交结构化处方单；',
    '- 信息不足影响判断 → 回答给出能做的部分后，调用 submit_clarify 提交确认清单；',
    '- 常识性、确定性知识可直接回答，不要滥用工具。',
    '',
    '【专业红线】',
    '- 药剂名称与用量只引用知识库/登记标签内容；',
    '- 食品安全相关（赤霉病病粒、药残）要给出明确的禁食/留种警示。',
    field
      ? `\n【当前田块】${field.name}，${field.crop}，播期 ${field.sowDate}（档案由 get_field_context 提供，以工具返回为准）。`
      : '\n【当前无田块】用户还未建立田块档案。常识问题照常回答；涉及具体田块时，先给不依赖田块信息的安全建议，再询问真正影响判断的缺失信息。',
    locationText ? `\n【位置与天气背景】${locationText}\n（天气以 get_weather_forecast 返回为准，勿自行假设）` : '',
  ].join('\n');
}
export async function POST(request: Request) {
  try {
    const body = await request.json() as Record<string, unknown>;
    const provider = typeof body.provider === 'string' ? body.provider as ProviderId : 'custom';
    const model = typeof body.model === 'string' ? body.model.trim().slice(0, 160) : '';
    const apiKey = typeof body.apiKey === 'string' ? body.apiKey.trim() : '';
    if (!model) return Response.json({ error: '请填写模型名称' }, { status: 400 });
    if (!apiKey || apiKey.length < 12) return Response.json({ error: '请填写有效的 API 密钥' }, { status: 400 });

    const endpoint = resolveEndpoint(provider as string, body.baseUrl);
    const history = sanitizeMessages(body.messages);

    // 田块档案（前端 localStorage 带来）
    const fieldObj = objectValue(body.field);
    const fieldId = textOrNull(fieldObj.id, 100);
    const fieldName = textOrNull(fieldObj.name, 80);
    const fieldCrop = textOrNull(fieldObj.crop, 40);
    const sowDate = textOrNull(fieldObj.sowDate, 10);
    const records = Array.isArray(fieldObj.records)
      ? fieldObj.records.slice(-20).flatMap((record) => {
          const item = objectValue(record);
          const date = textOrNull(item.date, 10);
          const note = textOrNull(item.note, 300);
          return date && note ? [{ date, note }] : [];
        })
      : [];
    const field: FieldProfile | null = fieldId && fieldName && fieldCrop && sowDate
      ? {
          id: fieldId,
          name: fieldName,
          crop: fieldCrop,
          variety: textOrNull(fieldObj.variety, 80) ?? undefined,
          sowDate,
          areaMu: numberOrNull(fieldObj.areaMu) ?? undefined,
          notes: textOrNull(fieldObj.notes, 500) ?? undefined,
          records,
        }
      : null;

    // 位置与天气（前端 context 面板状态带来）
    const locObj = objectValue(body.location);
    const latitude = numberOrNull(locObj.latitude) ?? numberOrNull(locObj.lat);
    const longitude = numberOrNull(locObj.longitude) ?? numberOrNull(locObj.lon);
    const location = latitude !== null && longitude !== null
      && latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180
      ? { lat: latitude, lon: longitude, label: textOrNull(locObj.label, 120) }
      : null;
    const weatherObj = objectValue(body.weather);
    const weatherKeys = weatherObj.weather ? Object.keys(objectValue(weatherObj.weather)) : [];
    const dailyText = textOrNull(weatherObj.dailyText, 8000);
    const locationText = [
      location?.label ? `位置：${location.label}` : null,
      textOrNull(weatherObj.locationText, 200),
      Array.isArray(weatherObj.daily) && weatherObj.daily.length && dailyText ? `七日预报：\n${dailyText}` : null,
    ].filter(Boolean).join('\n') || null;

    // 组装 Agent 工具与上下文（yoked：buildTools → chatWithTools）
    const registry = buildAgriRegistry();
    const toolCtx: ToolContext = {
      userId: 'web-user',
      extra: {
        field,
        location,
        forecastText: dailyText,
        weatherText: weatherKeys.length ? JSON.stringify(weatherObj.weather) : null,
        daily: Array.isArray(weatherObj.daily) ? weatherObj.daily.slice(0, 7) : [],
      } as AgentExtras,
    };

    const systemPrompt = buildSystemPrompt(field, locationText);
    const result = await runAgent({
      model,
      endpoint,
      apiKey,
      systemPrompt,
      tools: registry,
      toolCtx,
      maxRounds: 5,
      temperature: 0.35,
    }, history as ChatMessage[]);

    if ('error' in result) {
      return Response.json({ error: result.error }, { status: 502 });
    }

    // 结构化产出：处方单 / 风险报告
    const plan = result.submissions.find((s) => s.name === 'submit_farm_plan')?.args ?? null;
    const riskSubmission = result.submissions.find((s) => s.name === 'submit_risk_report');
    const risk = riskSubmission ? { ...riskSubmission.args, result: riskSubmission.result } : null;
    const clarArgs = result.submissions.find((s) => s.name === 'submit_clarify')?.args ?? null;

    return Response.json({
      reply: result.reply,
      plan: plan ?? null,
      risk: risk ?? null,
      clarify: clarArgs ?? null,
      rounds: result.rounds,
      provider,
      model,
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : '请求格式不正确';
    return Response.json({ error: message }, { status: 400 });
  }
}
