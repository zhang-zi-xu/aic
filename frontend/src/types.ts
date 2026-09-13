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
  /** 服务端为每个方案项注入的稳定 ID，用于「加入任务」的幂等登记 */
  itemId?: string;
  date?: string;
  /** 建议时间窗口 / 物候窗口，缺失时为「待确认」 */
  window?: string;
  task: string;
  /** 所需物料，缺失时为「待确认」 */
  materials?: string;
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

/** 随消息发送的图片附件（元数据来自服务端，客户端不保存 base64）。 */
export type AttachedImage = {
  id: string;
  url: string;
  mime: string;
  width: number;
  height: number;
  bytes: number;
  /** 仅本地预览用：用户选择时的文件名 */
  name?: string;
  /** 归档信息：属于哪个田块、哪一天拍的、备注；createdAt 是上传时间（缺拍摄日期时按它排序） */
  fieldId?: string;
  observedAt?: string;
  note?: string;
  createdAt?: string;
};

export type ChatMessage = {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  attachedData?: string;
  images?: AttachedImage[];
  plan?: PlanArgs | null;
  risk?: RiskArgs | null;
  clarify?: ClarifyArgs | null;
  evidence?: Array<{ id: string; title: string; crop: string; source: string }>;
  /** 本次回答实际检索命中的资料依据（后端生成，模型无法伪造）。 */
  sources?: KnowledgeSourceCard[];
  status?: 'interrupted';
  /** 工具产出已保留但回答未完整生成（供应商在工具轮后失败或超时）；可重试，不进入后续对话历史。 */
  degraded?: boolean;
  error?: string;
  requestContext?: ChatContext;
};

/** 对话中的来源卡：只包含后端资料库真实存在的片段。 */
export type KnowledgeSourceCard = {
  id: string;
  title: string;
  institution?: string;
  url?: string;
  publishedAt?: string;
  region?: string;
  crop?: string;
  growthStage?: string;
  heading?: string;
  status: 'verified' | 'unverified';
  excerpt?: string;
};

/** 来源登记条目（/api/knowledge）。 */
export type KnowledgeSource = {
  id: string;
  title: string;
  institution?: string;
  url?: string;
  publishedAt?: string;
  fetchedAt?: string;
  region?: string;
  crops?: string[];
  topic?: string;
  version?: string;
  license?: string;
  reviewStatus: 'verified' | 'unverified';
  reviewNote?: string;
  chunks?: Array<{
    id: string;
    heading?: string;
    locator?: string;
    crop?: string;
    region?: string;
    growthStage?: string;
    text?: string;
  }>;
};

export type ChatContext = {
  field: FieldProfile | null;
  location: { label: string | null; latitude: number; longitude: number; method: LiveContext['method'] } | null;
  weather: (LiveContext & { locationText: string }) | null;
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
