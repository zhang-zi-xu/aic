// 前端共享类型（自包含，不依赖后端）

/** 田块档案（对齐后端 FieldProfile） */
export type FieldProfile = {
  id: string;
  name: string;
  crop: string;
  variety?: string;
  sowDate: string; // YYYY-MM-DD
  areaMu?: number;
  notes?: string;
  records?: Array<{ date: string; note: string }>;
};

export type PlanItem = {
  date?: string;
  task: string;
  dosage?: string;
  method?: string;
  condition?: string;
  warning?: string;
  review?: string;
  evidence?: string[];
};

export type PlanArgs = {
  title?: string;
  crop?: string;
  fieldName?: string;
  summary?: string;
  items?: PlanItem[];
};

export type RiskArgs = {
  data?: string;
  result?: string; // 工具执行输出（服务端 riskReportText）
};

export type ClarifyItem = {
  question?: string;
  options?: string[];
  hint?: string;
};

export type ClarifyArgs = {
  intro?: string;
  items?: ClarifyItem[];
};

export type ChatMessage = {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  attachedData?: string;
  plan?: PlanArgs | null;
  risk?: RiskArgs | null;
  clarify?: ClarifyArgs | null;
  evidence?: Array<{ id: string; title: string; crop: string; source: string }>;
};

export type LiveContext = {
  method: 'device' | 'manual' | null;
  location: string | null;
  latitude: number;
  longitude: number;
  accuracy: number;
  locatedAt: number;
  timezone: string | null;
  timezoneAbbreviation: string | null;
  observedAt: string | null;
  weather: {
    temperature: number | null;
    apparentTemperature: number | null;
    humidity: number | null;
    precipitation: number | null;
    weatherCode: number | null;
    windSpeed: number | null;
    windDirection: number | null;
  } | null;
  daily: Array<{
    date: string;
    weatherCode: number | null;
    tempMax: number | null;
    tempMin: number | null;
    precipitationSum: number | null;
    precipitationProbability: number | null;
    windSpeedMax: number | null;
  }>;
  dailyText: string;
  weatherError: string | null;
  sources: { weather: string | null; location: string | null };
};

export type WeatherCodeText = (code: number | null) => string;
