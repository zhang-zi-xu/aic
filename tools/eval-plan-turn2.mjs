// 两轮评测：第一轮提问 → 用确认卡答案回复 → 检查第二轮是否产出方案卡与判断句。
// 用法：NONGXIN_TEST_KEY=... node tools/eval-plan-turn2.mjs
import { writeFileSync, mkdirSync } from 'node:fs';
const KEY = process.env.NONGXIN_TEST_KEY;
if (!KEY) { console.error('缺少 NONGXIN_TEST_KEY'); process.exit(1); }
const API = 'http://127.0.0.1:8080/api';

const CASES = [
  { id: 'enough', q: '甲田水稻，品种甬优1540，6月1日播种，今天9月12日，叶尖有梭形褐斑、边缘褐色中心灰白，最近一次用药是9月5日三环唑。现在要不要打药？' },
  { id: 'thin', q: '我的水稻叶子出问题了，怎么办？' },
  { id: 'noweather', q: '这周能打药吗？' },
];

async function turn(messages) {
  const started = Date.now();
  const response = await fetch(`${API}/chat`, { method: 'POST', headers: { 'Content-Type': 'application/json; charset=utf-8' },
    body: JSON.stringify({ provider: 'deepseek', model: 'deepseek-chat', baseUrl: null, apiKey: KEY,
      messages, field: null, location: null, weather: null, priorSources: null, imageIds: [], imageInput: 'auto', autoFieldPhotos: false }) });
  const body = await response.json().catch(() => null);
  return { status: response.status, ms: Date.now() - started, reply: String(body?.reply ?? ''), plan: body?.plan ?? null, clarify: body?.clarify ?? null, sources: body?.sources ?? [] };
}

/** 把确认卡按"用户会怎么答"转成一句自然回复（第一项选项，或"记不清"） */
function answerFor(clarify) {
  const items = clarify?.items ?? [];
  if (!items.length) return '田块位置在浙江杭州，其他按您说的办。';
  return items.map((item, index) => {
    const options = item.options ?? [];
    const pick = options.length ? options[Math.min(1, options.length - 1)] : '记不清，按常见情况处理';
    return `${index + 1}. ${pick}`;
  }).join('\n') + '\n田块位置在浙江杭州。';
}

const VERDICT = /最可能|很可能|大概率|判断|应该是|更像|倾向于|可能是|我怀疑|问题出在/;
const rows = [];
for (const item of CASES) {
  process.stdout.write(`\n[${item.id}] 第一轮 … `);
  const first = await turn([{ role: 'user', content: item.q }]);
  const answer = answerFor(first.clarify);
  process.stdout.write(`方案卡${first.plan ? '有' : '无'}/确认卡${first.clarify ? '有' : '无'} → 第二轮 … `);
  const second = await turn([
    { role: 'user', content: item.q },
    { role: 'assistant', content: first.reply },
    { role: 'user', content: answer },
  ]);
  const verdict = VERDICT.test(second.reply.split('\n').map(l => l.trim()).filter(Boolean)[0] ?? '');
  process.stdout.write(`方案卡${second.plan ? `有(${second.plan.items?.length ?? '?'}项)` : '无'} 判断句${verdict ? '✓' : '✗'} ${second.ms}ms`);
  rows.push({ id: item.id, question: item.q, answerToClarify: answer,
    first: { ...first, reply: first.reply.slice(0, 800) },
    second: { ...second, reply: second.reply.slice(0, 1200), verdictFirst: verdict } });
}

const summary = {
  ranAt: new Date().toISOString(),
  planInTurn1: `${rows.filter(r => r.first.plan).length}/${rows.length}`,
  planInTurn2: `${rows.filter(r => r.second.plan).length}/${rows.length}`,
  verdictInTurn2: `${rows.filter(r => r.second.verdictFirst).length}/${rows.length}`,
  avgTurn2Ms: Math.round(rows.reduce((s, r) => s + r.second.ms, 0) / rows.length),
  avgTurn2Chars: Math.round(rows.reduce((s, r) => s + r.second.reply.length, 0) / rows.length),
};
mkdirSync('docs/eval', { recursive: true });
writeFileSync(`docs/eval/p5-turn2-${new Date().toISOString().slice(0, 10)}.json`, JSON.stringify({ summary, rows }, null, 2), 'utf8');
console.log('\n\n=== 两轮结果 ===\n' + JSON.stringify(summary, null, 2));
