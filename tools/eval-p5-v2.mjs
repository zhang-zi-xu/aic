// P5 评测 v2：两轮流程 + 人工撰写的真实回答（不再机械照抄选项）+ 场景化判定
// 用法：NONGXIN_TEST_KEY=... node tools/eval-p5-v2.mjs
import { writeFileSync, mkdirSync } from 'node:fs';
const KEY = process.env.NONGXIN_TEST_KEY;
if (!KEY) { console.error('缺少 NONGXIN_TEST_KEY'); process.exit(1); }
const API = 'http://127.0.0.1:8080/api';

// 第二轮由人（这里以手写文本代表）真实作答；requirePlan 标明该场景信息补全后是否应产出方案卡
const CASES = [
  { id: 'enough', cat: '信息充分', requirePlan: true,
    q: '甲田水稻，品种甬优1540，6月1日播种，今天9月12日，叶尖有梭形褐斑、边缘褐色中心灰白，最近一次用药是9月5日三环唑。现在要不要打药？',
    answer: '1. 刚破口\n2. 上部新叶为主\n3. 浙江杭州临安区，田在村口，离家不远' },
  { id: 'thin', cat: '信息不足→补全', requirePlan: true,
    q: '我的水稻叶子出问题了，怎么办？',
    answer: '1. 两头尖的梭形斑，边缘褐色、中间灰白\n2. 孕穗到破口期\n3. 9月5日打过三环唑，之后没打过' },
  { id: 'noweather', cat: '天气缺失→补全', requirePlan: true,
    q: '这周能打药吗？',
    answer: '1. 水稻\n2. 破口抽穗期\n3. 防病，主要是稻瘟病\n地块在浙江杭州临安区' },
  { id: 'conflict', cat: '互相矛盾→澄清', requirePlan: true,
    q: '甲田水稻6月1日播种，但上周已经收割了，现在叶子上有褐色斑点，要不要打药？',
    answer: '是我记错了，收割的是隔壁乙田，甲田还没收，还在灌浆期。斑点长在上部叶片。' },
  { id: 'history', cat: '带历史连续追问', requirePlan: false,
    q: '那我到底该怎么办？',
    prior: '甲田水稻叶尖有梭形褐斑、边缘褐色中间灰白，现在刚破口。',
    answer: null },
];

async function turn(messages) {
  const started = Date.now();
  const response = await fetch(`${API}/chat`, { method: 'POST', headers: { 'Content-Type': 'application/json; charset=utf-8' },
    body: JSON.stringify({ provider: 'deepseek', model: 'deepseek-chat', baseUrl: null, apiKey: KEY,
      messages, field: null, location: null, weather: null, priorSources: null, imageIds: [], imageInput: 'auto', autoFieldPhotos: false }) });
  const body = await response.json().catch(() => null);
  return { status: response.status, ms: Date.now() - started, reply: String(body?.reply ?? ''), plan: body?.plan ?? null, clarify: body?.clarify ?? null, sources: body?.sources ?? [] };
}

// 场景化判定：通用知识类不要求"最可能是…"句式；其余要求首段有判断
const VERDICT = /最可能|很可能|大概率|判断|应该是|更像|倾向于|可能是|我怀疑|问题出在|不是.{0,6}而是/;
const firstLine = (text) => text.split('\n').map(l => l.trim()).filter(Boolean)[0] ?? '';
const planItems = (plan) => (plan?.items ?? []).length;

const rows = [];
for (const item of CASES) {
  process.stdout.write(`\n[${item.id}] ${item.cat} … `);
  let first;
  let messages;
  if (item.prior) {
    // 带历史：先问一句，拿到回复后接着追问
    first = await turn([{ role: 'user', content: item.prior }]);
    messages = [{ role: 'user', content: item.prior }, { role: 'assistant', content: first.reply }, { role: 'user', content: item.q }];
  } else {
    first = await turn([{ role: 'user', content: item.q }]);
    messages = [{ role: 'user', content: item.q }, { role: 'assistant', content: first.reply }, { role: 'user', content: item.answer }];
  }
  const second = await turn(messages);
  const verdict = VERDICT.test(firstLine(second.reply));
  const ok = item.requirePlan ? Boolean(second.plan) : verdict;
  process.stdout.write(`第一轮 方案卡${first.plan ? '有' : '无'}/确认卡${first.clarify ? '有' : '无'} → 第二轮 方案卡${second.plan ? `有(${planItems(second.plan)}项)` : '无'} 判断句${verdict ? '✓' : '✗'} ${second.ms}ms ${ok ? '✅' : '⚠️'}`);
  rows.push({ id: item.id, cat: item.cat, requirePlan: item.requirePlan, ok,
    question: item.q, prior: item.prior ?? null, answer: item.answer,
    first: { status: first.status, ms: first.ms, hasPlan: Boolean(first.plan), hasClarify: Boolean(first.clarify), clarify: first.clarify, reply: first.reply.slice(0, 600) },
    second: { status: second.status, ms: second.ms, planItems: planItems(second.plan), hasPlan: Boolean(second.plan), hasClarify: Boolean(second.clarify), verdictFirst: verdict, reply: second.reply.slice(0, 1000), sources: (second.sources ?? []).map(s => s.id ?? s.chunkId) } });
}

const withPlan = rows.filter(r => r.requirePlan);
const summary = {
  version: 'p5-eval-v2', ranAt: new Date().toISOString(), model: 'deepseek-chat',
  cases: rows.length,
  planExpected: `${withPlan.filter(r => r.ok).length}/${withPlan.length}`,
  verdictInTurn2: `${rows.filter(r => r.second.verdictFirst).length}/${rows.length}`,
  plansInTurn1: `${rows.filter(r => r.first.hasPlan).length}/${rows.length}`,
  avgTurn2Ms: Math.round(rows.reduce((s, r) => s + r.second.ms, 0) / rows.length),
  avgTurn2Chars: Math.round(rows.reduce((s, r) => s + r.second.reply.length, 0) / rows.length),
  casesFailed: rows.filter(r => !r.ok).map(r => r.id),
};
mkdirSync('docs/eval', { recursive: true });
writeFileSync(`docs/eval/p5-v2-${new Date().toISOString().slice(0, 10)}.json`, JSON.stringify({ summary, rows }, null, 2), 'utf8');
console.log('\n\n=== v2 汇总（人工作答 + 场景化判定）===\n' + JSON.stringify(summary, null, 2));
