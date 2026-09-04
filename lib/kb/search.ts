// 知识库检索：关键词加权打分（标题 > 关键词 > 症状 > 正文），
// 支持作物分面过滤。无外部 embedding 依赖即可运行，正式版可替换为向量检索。

import { KB_ENTRIES, type KbEntry } from './data';

export type SearchHit = {
  entry: KbEntry;
  score: number;
  matchedTerms: string[];
};

const SYNONYMS: Array<[string, string[]]> = [
  ['稻瘟病', ['稻瘟', '叶瘟', '穗颈瘟']],
  ['纹枯病', ['纹枯', '云纹']],
  ['飞虱', ['稻飞虱', '褐飞虱', '白背飞虱']],
  ['螟虫', ['二化螟', '三化螟', '钻心虫']],
  ['赤霉病', ['赤霉', '穗腐']],
  ['锈病', ['条锈', '叶锈']],
  ['蚜虫', ['麦蚜', '穗蚜']],
  ['玉米螟', ['钻心虫']],
  ['大斑病', ['叶斑']],
  ['干热风', ['高温逼熟']],
  ['倒伏', ['抗倒', '控旺']],
  ['涝', ['积水', '洪涝', '渍']],
  ['晒田', ['烤田', '控蘖']],
  ['药害', ['烧叶', '畸形']],
  ['化肥', ['施肥', '追肥', '氮肥']],
];

function tokenize(text: string): string[] {
  // 中文按字 + 常用词；这里先做简单切分：去标点，保留 2 字以上片段
  const cleaned = text.replace(/[\s，。？！、；：""''（）【】\-—…·%0-9a-zA-Z]/g, ' ').split(/\s+/);
  const out: string[] = [];
  for (const token of cleaned) {
    if (!token || token.length < 2) continue;
    out.push(token);
  }
  return out;
}

function expandTerms(terms: string[]): string[] {
  const expanded = new Set<string>(terms);
  for (const [key, aliases] of SYNONYMS) {
    if (terms.some((t) => key.includes(t) || t.includes(key))) {
      aliases.forEach((a) => expanded.add(a));
    }
  }
  return [...expanded];
}

/** 简单二元打分：标题命中权重高，正文命中权重低 */
function scoreEntry(entry: KbEntry, terms: string[]): { score: number; matched: string[] } {
  let score = 0;
  const matched: string[] = [];
  const body = `${entry.title} ${entry.symptom} ${entry.diagnosis} ${entry.advice.join(' ')} ${entry.review}`.toLowerCase();
  const title = entry.title.toLowerCase();

  for (const term of terms) {
    const t = term.toLowerCase();
    if (!t) continue;
    if (title.includes(t)) { score += 10; matched.push(term); continue; }
    if (entry.keywords.some((k) => k.toLowerCase().includes(t) || t.includes(k.toLowerCase()))) { score += 8; matched.push(term); continue; }
    if (entry.crop.includes(t) || t.includes(entry.crop)) { score += 5; matched.push(term); continue; }
    if (body.includes(t)) { score += 3; matched.push(term); }
  }
  return { score, matched };
}

export function searchKnowledge(query: string, opts?: { crop?: string; topic?: string; topK?: number }): SearchHit[] {
  const topK = opts?.topK ?? 3;
  let terms = tokenize(query);
  if (!terms.length) terms = tokenize(query.replace(/[0-9a-zA-Z]/g, ' ')).filter((t) => t.length >= 2);
  terms = expandTerms(terms);
  if (!terms.length) return [];

  // 作物限定：query 中识别作物名（水稻/小麦/玉米/稻子/麦子/玉米/禾苗）
  const cropAliases: Record<string, string[]> = {
    '水稻': ['水稻', '稻子', '稻', '稻田', '秧'],
    '小麦': ['小麦', '麦子', '麦田', '麦'],
    '玉米': ['玉米', '苞谷', '棒子'],
  };
  let cropFilter: string | null = null;
  for (const [crop, aliases] of Object.entries(cropAliases)) {
    if (aliases.some((a) => query.includes(a))) { cropFilter = crop; break; }
  }
  if (opts?.crop) cropFilter = opts.crop;

  const hits = KB_ENTRIES
    .filter((e) => !cropFilter || e.crop === cropFilter || e.crop === '通用')
    .map((entry) => {
      const { score, matched } = scoreEntry(entry, terms);
      return { entry, score, matchedTerms: matched };
    })
    .filter((h) => h.score > 0)
    .sort((a, b) => b.score - a.score || a.entry.id.localeCompare(b.entry.id))
    .slice(0, topK);

  return hits;
}

/** 生成供 agent 使用的检索结果文本 */
export function formatKnowledgeHits(hits: SearchHit[]): string {
  if (!hits.length) return '';
  return hits.map((hit, i) => {
    const e = hit.entry;
    return [
      `【${i + 1}】${e.crop}·${e.topic}｜${e.title}`,
      `症状：${e.symptom}`,
      `判断：${e.diagnosis}`,
      `处置：${e.advice.map((a, j) => `${j + 1}. ${a}`).join('\n')}`,
      `复查：${e.review}`,
      `依据：${e.source}`,
    ].join('\n');
  }).join('\n\n');
}
