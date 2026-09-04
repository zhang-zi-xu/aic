// 农情数据风险规则引擎：解析上传的时序/指标数据，按阈值规则输出风险分级。
// 规则集为公开农技常识（可复核、可解释），每条风险附「判定依据」与处置建议。

export type RiskLevel = '低' | '中' | '高';

export type RiskItem = {
  level: RiskLevel;
  title: string;
  reason: string;      // 判定依据（数据 → 规则）
  suggestion: string;  // 处置建议
  rule: string;        // 规则编号（可追溯）
};

export type RiskReport = {
  items: RiskItem[];
  overall: RiskLevel;
  summary: string;
};

// ---------- 数据解析 ----------

export type ParsedData = {
  kind: 'timeseries' | 'snapshot';
  series: Array<{ time: string; [key: string]: number | string }>;
  metrics: string[]; // 已识别的指标名
  recognized: Array<{ raw: string; name: string }>;
};

/** 从原始文本/CSV 解析时序数据（支持逗号/制表符/空格分隔 CSV 与「时间 指标 值」自由文本） */
export function parseFarmData(text: string): ParsedData {
  const lines = text.split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
  const series: ParsedData['series'] = [];
  const recognized: ParsedData['recognized'] = [];
  const metrics = new Set<string>();
  let headers: string[] | null = null;

  const parseNumber = (value: string) => {
    const match = value.trim().match(/^-?\d+(?:\.\d+)?/);
    return match ? Number(match[0]) : null;
  };

  const normalizeMetric = (value: string) => {
    const name = value.trim().replace(/[（(].*?[）)]/g, '');
    if (/土壤湿度|地墒|soil moisture/i.test(name)) return '土壤湿度';
    if (/空气湿度|相对湿度|湿度|humidity/i.test(name)) return '湿度';
    if (/降水|降雨|雨量|precip/i.test(name)) return '降水量';
    if (/气温|温度|temperature/i.test(name)) return '温度';
    if (/风速|wind/i.test(name)) return '风速';
    return name;
  };

  for (const line of lines) {
    const cols = line.split(/[,，\t;；\s]+/).filter(Boolean);
    if (cols.length < 2) continue;
    const timeCol = cols[0];
    const rest = cols.slice(1);

    // 标准 CSV/TSV：日期,温度,湿度,降水量
    if (/^(日期|时间|date|time)$/i.test(timeCol) && rest.some((value) => parseNumber(value) === null)) {
      headers = rest.map(normalizeMetric);
      continue;
    }

    if (headers && rest.length >= headers.length) {
      const row: { time: string; [key: string]: number | string } = { time: timeCol };
      let added = false;
      headers.forEach((name, index) => {
        const value = parseNumber(rest[index] ?? '');
        if (!name || value === null) return;
        row[name] = value;
        metrics.add(name);
        recognized.push({ raw: rest[index] ?? '', name });
        added = true;
      });
      if (added) series.push(row);
      continue;
    }

    // 自然文本行：「6月1日 湿度 92%」或「6月1日 降水量 45mm」
    if (rest.length >= 2) {
      const value = parseNumber(rest[rest.length - 1]);
      const name = normalizeMetric(rest.slice(0, -1).join(''));
      if (value === null || !name) continue;
      series.push({ time: timeCol, [name]: value });
      metrics.add(name);
      recognized.push({ raw: rest.slice(0, -1).join(' '), name });
      continue;
    }

    // 两列数据没有表头时保留为通用值，但不冒充具体指标。
    const value = parseNumber(rest[0]);
    if (value !== null) series.push({ time: timeCol, value });
  }

  return { kind: series.length ? 'timeseries' : 'snapshot', series, metrics: [...metrics], recognized };
}

// ---------- 通用指标解析（自由文本 → 指标提取） ----------

const METRIC_PATTERNS: Array<{ name: string; re: RegExp; unit?: string }> = [
  { name: '温度', re: /(?:气温|温度|气温约|温度约|air temp)[^0-9]*(-?\d+(?:\.\d+)?)\s*°?C?/i },
  { name: '湿度', re: /(?:空气湿度|湿度|相对湿度|humidity)[^0-9]*(\d+(?:\.\d+)?)\s*%?/i },
  { name: '降水量', re: /(?:降水|降雨|雨量|下了|下过)[^0-9]*(\d+(?:\.\d+)?)\s*(?:mm|毫米)?/i },
  { name: '风速', re: /(?:风速|风力|wind)[^0-9]*(\d+(?:\.\d+)?)/i },
  { name: '土壤湿度', re: /(?:土壤湿度|地墒|soil moisture)[^0-9]*(\d+(?:\.\d+)?)\s*%?/i },
];

export function extractMetrics(text: string): Array<{ name: string; value: number }> {
  const out: Array<{ name: string; value: number }> = [];
  for (const m of METRIC_PATTERNS) {
    const match = text.match(m.re);
    if (match) out.push({ name: m.name, value: Number(match[1]) });
  }
  return out;
}

// ---------- 风险规则 ----------

export type FarmContext = {
  crop?: string;
  phaseText?: string; // 生育期描述（含名称即可）
  weather?: {
    temperature?: number | null;
    humidity?: number | null;
    precipitation?: number | null;
    windSpeed?: number | null; // km/h
  } | null;
};

export function assessRisk(input: {
  metrics: Array<{ name: string; value: number }>;
  series: ParsedData['series'];
  context?: FarmContext;
}): RiskReport {
  const items: RiskItem[] = [];
  const { metrics, series, context } = input;
  const has = (name: string) => metrics.some((m) => m.name === name);
  const metric = (name: string) => metrics.find((m) => m.name === name)?.value ?? null;

  // 规则 R1：连续高湿 → 病害风险
  if (has('湿度')) {
    const h = metric('湿度');
    const recent = recentSeriesValues(series, '湿度');
    const highDays = recent.filter((v) => v >= 85).length;
    if (h !== null && h >= 85 || highDays >= 2) {
      items.push({
        level: '中',
        title: '病害高发条件',
        reason: `空气湿度${h !== null ? h + '%' : '连续2天以上≥85%'}，叶片易长时间保持湿润，真菌性病害（稻瘟/纹枯/锈病等）风险上升。`,
        suggestion: '加强田间巡查，雨后及时排水；叶面病原斑出现时按农技指导用药。',
        rule: 'R1',
      });
    }
  }

  // 规则 R2：强降水 → 涝渍/倒伏风险
  if (has('降水量')) {
    const p = metric('降水量');
    const recent = recentSeriesValues(series, '降水量');
    const maxRain = Math.max(p ?? 0, ...recent);
    if (maxRain >= 50) {
      items.push({
        level: '高',
        title: '涝渍风险',
        reason: `近24小时降水量达${maxRain.toFixed(1)}mm（≥50mm），排水不畅地块有明水与根系缺氧风险。`,
        suggestion: '提前疏通沟渠；暴雨后及时排涝、清洗叶面泥污，酌情追施恢复肥。',
        rule: 'R2',
      });
    }
  }

  // 规则 R3：大风 → 倒伏风险
  if (has('风速')) {
    const w = metric('风速');
    if (w !== null && w >= 50) { // km/h ≈ 6 级
      items.push({
        level: '中',
        title: '大风倒伏风险',
        reason: `风速${w.toFixed(0)}km/h（约6级以上），灌浆期水稻/高秆作物易倒伏。`,
        suggestion: '关注预警；灌浆后期控制渍水，成熟期大风来临前抢收或加固。',
        rule: 'R3',
      });
    }
  }

  // 规则 R4：持续干旱（土壤湿度低） → 旱情风险
  if (has('土壤湿度')) {
    const s = metric('土壤湿度');
    if (s !== null && s < 40) {
      items.push({
        level: '中',
        title: '土壤偏干，旱情风险',
        reason: `土壤湿度${s}%（<40%），连续无雨时作物供水不足。`,
        suggestion: '结合预报安排灌溉；作物需水关键期（抽雄/扬花/灌浆）优先保障。',
        rule: 'R4',
      });
    }
  }

  // 规则 R5：低温冷害（季节敏感）
  if (has('温度')) {
    const t = metric('温度');
    const phase = context?.phaseText ?? '';
    if (t !== null && t <= 0 && /抽穗|扬花|花期|孕穗/.test(phase)) {
      items.push({
        level: '高',
        title: '冷害风险（敏感生育期）',
        reason: `气温${t}°C，当前处于${phase}，低温对花器官与结实影响敏感。`,
        suggestion: '低温前灌水保温；叶面喷施磷酸二氢钾增强抗性；关注气象预警。',
        rule: 'R5',
      });
    }
  }

  // 未能识别有效指标
  if (!items.length && !has('湿度') && !has('降水量') && !has('温度')) {
    items.push({
      level: '低',
      title: '暂未识别到风险指标',
      reason: '上传内容中未能识别温度/湿度/降水/风速/土壤湿度等指标，当前无法进行风险判断。',
      suggestion: '提供包含「时间、指标、数值」的数据（如：6月1日 湿度 92%），或直接用自然语言描述田里情况。',
      rule: 'R0',
    });
  }

  const overall: RiskLevel = items.some((i) => i.level === '高') ? '高' : items.some((i) => i.level === '中') ? '中' : '低';
  const summary = overall === '高'
    ? `发现 ${items.filter((i) => i.level === '高').length} 项高风险项，建议尽快处置并咨询本地农技员。`
    : overall === '中'
      ? '存在中等级别风险，建议按提示加强巡查并提前准备处置措施。'
      : '未发现明显风险，保持常规巡查即可。';

  return { items, overall, summary };
}

function recentSeriesValues(series: ParsedData['series'], key: string): number[] {
  return series
    .map((s) => {
      const v = s[key] ?? s['value'];
      return typeof v === 'number' ? v : null;
    })
    .filter((v): v is number => v !== null)
    .slice(-10);
}
