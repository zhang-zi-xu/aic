// 农心 Agent 农业工具集：知识检索 / 田块档案 / 天气预报 / 风险判定 / 处方单提交。
// 注册到 ToolRegistry；「submit_*」为产出型工具，结果结构化采集供前端渲染卡片。

import { ToolRegistry } from './tools';
import { searchKnowledge, formatKnowledgeHits } from '@/lib/kb/search';
import { getPhenology } from '@/lib/fields/phenology';
import { parseFarmData, extractMetrics, assessRisk, type RiskReport, type RiskLevel } from '@/lib/fields/risk';

export type FieldProfile = {
  id: string;
  name: string;
  crop: string;
  variety?: string;
  sowDate: string; // YYYY-MM-DD
  areaMu?: number;
  location?: { lat: number; lon: number; label?: string };
  notes?: string;
  records?: Array<{ date: string; note: string }>;
};

export type AgentExtras = {
  field?: FieldProfile | null;
  location?: { lat: number; lon: number; label?: string } | null;
  weatherText?: string | null;
  forecastText?: string | null;
  daily?: Array<{ date: string; weatherCode: number; tempMax: number | null; tempMin: number | null; precipitationSum: number | null; precipitationProbability: number | null; windSpeedMax: number | null }>;
};

export function buildAgriRegistry(): ToolRegistry {
  const registry = new ToolRegistry();

  // ---- 田块档案 ----
  registry.register({
    name: 'get_field_context',
    description: '获取当前田块的档案信息（作物、品种、播期、生育期、位置、历史记录）。' +
      '当用户的问题与具体田块有关时应调用本工具获取田块事实，不要凭记忆编造。',
    parameters: { type: 'object', properties: {} },
    executor: (_args, ctx) => {
      const field = ctx.extra.field as FieldProfile | null;
      if (!field) return '当前未选择任何田块。请先让用户新建或选择田块。';
      const phen = getPhenology(field.crop, field.sowDate);
      const sowingTiming = phen.days < 0 ? '播期尚未到' : `距今约 ${phen.days} 天`;
      const lines = [
        `田块：${field.name}`,
        `作物：${field.crop}${field.variety ? `（${field.variety}）` : ''}`,
        `播期：${field.sowDate}（${sowingTiming}）`,
        `估算生育期：${phen.note}`,
        `面积：${field.areaMu ? `${field.areaMu} 亩` : '未填'}`,
        `位置：${field.location?.label ?? '未填'}`,
      ];
      if (field.records?.length) lines.push(`历史记录：${field.records.slice(-5).map((r) => `${r.date} ${r.note}`).join('；')}`);
      if (field.notes) lines.push(`备注：${field.notes}`);
      return lines.join('\n');
    },
  });

  // ---- 知识检索 ----
  registry.register({
    name: 'search_agri_knowledge',
    description: '在本地农业知识库检索病虫害防治、水肥调控、农时操作等条目（带来源）。' +
      '涉及病害/虫害识别与防治、施肥浇水、农时操作时必须调用本工具获取专业依据；' +
      '常识性问题不需要调用。检索结果中的来源与条款要用于支撑你的回答。',
    parameters: {
      type: 'object',
      properties: {
        query: { type: 'string', description: '检索关键词，如：水稻稻瘟病、小麦赤霉病、玉米倒伏、连阴雨后高温' },
      },
      required: ['query'],
    },
    executor: (args) => {
      const query = typeof args.query === 'string' ? args.query : '';
      if (!query) return '请提供检索关键词。';
      const hits = searchKnowledge(query, { topK: 3 });
      if (!hits.length) return '知识库暂无高度相关的条目，请根据常识审慎回答，明确说明依据不足。';
      return formatKnowledgeHits(hits);
    },
  });

  // ---- 7 日天气 ----
  registry.register({
    name: 'get_weather_forecast',
    description: '获取田块所在地未来 7 天天气预报（温度/降水概率/风速）。' +
      '当需要制定农事时间安排、判断施药窗口、评估暴雨大风影响时调用。',
    parameters: { type: 'object', properties: {} },
    executor: (_args, ctx) => {
      const forecast = typeof ctx.extra.forecastText === 'string' ? ctx.extra.forecastText : '';
      if (!forecast) return '未获取到 7 日天气预报：请确保已设置田块位置或所在城市。';
      return forecast;
    },
  });

  // ---- 风险判定（产出型）----
  registry.register({
    name: 'submit_risk_report',
    description: '对用户提供的农情数据（温度/湿度/降水量/风速/土壤湿度等时序或快照数据）' +
      '进行规则化风险判定，输出结构化风险报告（分级+依据+建议）。' +
      '用户上传数据文件或给出数值时调用；调用后向用户引用风险等级并给出建议。',
    parameters: {
      type: 'object',
      properties: {
        data: { type: 'string', description: '用户提供的农情数据原文（CSV 或自然语言描述，需保留数值）' },
      },
      required: ['data'],
    },
    executor: (args, ctx) => {
      const data = typeof args.data === 'string' ? args.data : '';
      if (!data.trim()) return '未提供数据内容：请让用户粘贴或上传农情数据。';
      const parsed = parseFarmData(data);
      const metrics = parsed.metrics.length
        ? parsed.metrics.map((name) => ({ name, value: latestValue(parsed.series, name) ?? 0 }))
        : extractMetrics(data);
      const field = ctx.extra.field as FieldProfile | null;
      const phen = field ? getPhenology(field.crop, field.sowDate) : null;
      const report = assessRisk({
        metrics,
        series: parsed.series,
        context: {
          crop: field?.crop,
          phaseText: phen?.note ?? '',
        },
      });
      return riskReportText(report);
    },
  });

  // ---- 处方单（产出型）----
  registry.register({
    name: 'submit_farm_plan',
    description: '提交一份结构化农事处方单（方案）。当用户要求「方案/计划/处方/怎么办/安排」' +
      '且你已掌握足够信息（作物、生育期、天气、知识库依据）时，必须调用本工具提交最终方案。' +
      '每个动作需给出时间（具体日期）、用量（无则写明条件）、方法、风险与复查点，' +
      '不得填写知识库或登记标签没有提供的精确用量；依据不足时 dosage 留空。' +
      '并在 evidence 中引用知识库条目 id（如 rice-blast）。生成前先调用 get_field_context、' +
      'get_weather_forecast、search_agri_knowledge 获取依据。',
    parameters: {
      type: 'object',
      properties: {
        title: { type: 'string', description: '方案标题，如：水稻分蘖期一周农事方案' },
        crop: { type: 'string', description: '作物名称' },
        fieldName: { type: 'string', description: '田块名称' },
        summary: { type: 'string', description: '方案要点总结（2-3 句话）' },
        items: {
          type: 'array',
          description: '按时间顺序的农事动作清单（每日 1-3 件）',
          items: {
            type: 'object',
            properties: {
              date: { type: 'string', description: '执行日期 YYYY-MM-DD；若天气相关，注明条件（如：雨前）' },
              task: { type: 'string', description: '动作名称，如：晒田、喷施防治药' },
              dosage: { type: 'string', description: '用量/配比（参考区间，注明以登记标签为准）' },
              method: { type: 'string', description: '操作方法' },
              condition: { type: 'string', description: '执行条件（天气/生育期/观察前提）' },
              warning: { type: 'string', description: '风险提示与避开事项' },
              review: { type: 'string', description: '复查动作与时间' },
              evidence: { type: 'array', items: { type: 'string' }, description: '依据的知识库条目 id 列表，如 ["rice-blast"]' },
            },
            required: ['date', 'task', 'condition', 'review', 'evidence'],
          },
        },
      },
      required: ['title', 'crop', 'fieldName', 'summary', 'items'],
    },
    executor: (args) => {
      const title = typeof args.title === 'string' ? args.title : '农事方案';
      const count = Array.isArray(args.items) ? args.items.length : 0;
      return `已登记处方单「${title}」：共 ${count} 项动作。请用自然语言向用户简短说明方案要点，并提醒以当地登记标签为准。`;
    },
  });

  // ---- 待确认项（产出型）----
  registry.register({
    name: 'submit_clarify',
    description: '提交结构化「待确认清单」，用于向用户追问缺失的关键信息（最多 3 项）。' +
      '只有信息不足影响判断结果时才调用；每个问题尽量给出可点选的选项。' +
      '调用前必须已在回答中给出能给的结论或可执行部分，不要把追问当成回答的主体。' +
      '注意：本工具是追问的唯一通道——只有调用它，用户界面才会出现确认卡。禁止只用文字描述清单代替工具调用，那样用户看不到任何可回复的项目。',
    parameters: {
      type: 'object',
      properties: {
        intro: { type: 'string', description: '给用户的一句话引导，先共情一句，再说明确认后能做什么' },
        items: {
          type: 'array',
          description: '需要用户确认的信息项，最多 3 项',
          items: {
            type: 'object',
            properties: {
              question: { type: 'string', description: '问题本身，口语化，如：病斑是什么形状的？' },
              options: { type: 'array', items: { type: 'string' }, description: '可点选的选项（2-4 个）；无法提供选项时传空数组' },
              hint: { type: 'string', description: '可选补充说明，如：拍照发来更好' },
            },
            required: ['question'],
          },
        },
      },
      required: ['intro', 'items'],
    },
    executor: (args) => {
      const count = Array.isArray(args.items) ? args.items.length : 0;
      return `已登记确认清单（${count} 项）。请用 1-2 句话告知用户信息已记录，等用户点选后继续。`;
    },
  });
  return registry;
}

function latestValue(series: Array<Record<string, number | string>>, key: string): number | null {
  for (let i = series.length - 1; i >= 0; i--) {
    const value = series[i][key];
    if (typeof value === 'number') return value;
  }
  return null;
}

function riskReportText(report: RiskReport): string {
  const levelColor = (level: RiskLevel) => ({ 高: '高风险', 中: '中风险', 低: '低风险' })[level];
  return [
    `风险判定：总体${levelColor(report.overall)}。${report.summary}`,
    ...report.items.map((item) =>
      `【${levelColor(item.level)}】${item.title}\n依据：${item.reason}\n建议：${item.suggestion}\n规则：${item.rule}`),
  ].join('\n');
}
