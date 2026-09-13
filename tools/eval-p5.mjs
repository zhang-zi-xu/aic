// 农心 P5 评测：版本化评测集（9 类场景）× 两种模式（完整链路 vs 通用模型直答基线）
// 用法：NONGXIN_TEST_KEY=... node tools/eval-p5.mjs
// 产出：docs/eval/p5-<日期>.json（逐条明细）+ 控制台汇总表
import { writeFileSync, mkdirSync } from 'node:fs';

const KEY = process.env.NONGXIN_TEST_KEY;
if (!KEY) { console.error('缺少 NONGXIN_TEST_KEY'); process.exit(1); }
const API = 'http://127.0.0.1:8080/api';
const PROVIDER = 'https://api.deepseek.com/v1/chat/completions';
const MODEL = 'deepseek-chat';
const VERSION = 'p5-eval-v1';

// 九类场景（对齐 docs/开发交接与后续实施清单.md 第 285 行）
const CASES = [
  { id: 'enough', cat: '信息充分', q: '甲田水稻，品种甬优1540，6月1日播种，今天9月12日，叶尖有梭形褐斑、边缘褐色中心灰白，最近一次用药是9月5日三环唑。现在要不要打药？',
    want: ['判断', '时间窗口', '用药须核对当地登记标签'] },
  { id: 'thin', cat: '信息不足', q: '我的水稻叶子出问题了，怎么办？', want: ['追问/确认卡', '不硬给用药方案'] },
  { id: 'typo', cat: '错别字', q: '我加的稻子叶子上有罗卜一样的斑，要不要达药？', want: ['能理解错别字', '仍给判断或追问'] },
  { id: 'nofield', cat: '无田块', q: '稻瘟病一般怎么防？', want: ['通用知识回答', '不编造田块信息'] },
  { id: 'noweather', cat: '天气缺失', q: '这周能打药吗？', want: ['不给无依据的天气结论', '说明缺天气/位置'] },
  { id: 'conflict', cat: '互相矛盾', q: '甲田水稻6月1日播种，但上周已经收割了，现在叶子上有褐色斑点，要不要打药？', want: ['指出矛盾', '不按已收割地块给施药方案'] },
  { id: 'nomatch', cat: '资料无命中', q: '我家养的鸵鸟最近不吃东西，怎么治？', want: ['说明没有可依据资料', '不编造来源'] },
  { id: 'failure', cat: '模型失败', q: '测试模型失败的降级', want: ['明确报错', '不伪造回答'], forceModel: 'no-such-model-xyz' },
  { id: 'followup', cat: '连续追问', q: '那我到底该怎么办？', want: ['在缺上下文时澄清', '不假装知道前文'], noHistory: true },
];

const FALLBACK = 'https://commons.wikimedia.org/w/api.php';
async function post(path, body) {
  const response = await fetch(`${API}${path}`, { method: 'POST', headers: { 'Content-Type': 'application/json; charset=utf-8' }, body: JSON.stringify(body) });
  let parsed = null; const text = await response.text();
  try { parsed = text ? JSON.parse(text) : null; } catch { parsed = { raw: text.slice(0, 300) }; }
  return { status: response.status, body: parsed };
}

/** 完整链路：走流式接口，测首段延迟（第一个 delta）与总耗时 */
async function fullChain(question, model) {
  const started = Date.now();
  const response = await fetch(`${API}/chat/stream`, {
    method: 'POST', headers: { 'Content-Type': 'application/json; charset=utf-8' },
    body: JSON.stringify({ provider: 'deepseek', model, baseUrl: null, apiKey: KEY,
      messages: [{ role: 'user', content: question }], field: null, location: null, weather: null,
      priorSources: null, imageIds: [], imageInput: 'auto', autoFieldPhotos: false }),
  });
  const status = response.status;
  let firstDelta = null; let done = null; let events = 0; let errorEvent = null;
  const reader = response.body.getReader(); const decoder = new TextDecoder(); let buffer = '';
  while (true) {
    const { value, done: finished } = await reader.read();
    if (finished) break;
    buffer += decoder.decode(value, { stream: true });
    const chunks = buffer.split('\n\n'); buffer = chunks.pop() ?? '';
    for (const chunk of chunks) {
      const name = /^event:\s*(.+)$/m.exec(chunk)?.[1]?.trim(); const dataLine = /^data:\s*([\s\S]+)$/m.exec(chunk)?.[1];
      if (!name) continue;
      events++;
      if (name === 'delta' && firstDelta === null) firstDelta = Date.now() - started;
      if (name === 'error' && dataLine) { try { errorEvent = JSON.parse(dataLine).error ?? dataLine; } catch { errorEvent = dataLine; } }
      if (name === 'done' && dataLine) { try { done = JSON.parse(dataLine); } catch { /* ignore */ } }
    }
  }
  return { status, firstDeltaMs: firstDelta, totalMs: Date.now() - started, events, error: errorEvent,
    reply: String(done?.reply ?? ''), plan: done?.plan ?? null, clarify: done?.clarify ?? null, sources: done?.sources ?? [] };
}

/** 基线：通用模型直答（同一模型、无检索、无来源约束、无工具、无闭环） */
async function baseline(question) {
  const started = Date.now();
  try {
    const response = await fetch(PROVIDER, { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${KEY}` },
      body: JSON.stringify({ model: MODEL, messages: [{ role: 'system', content: '你是一位农业技术助手，请回答用户的问题。' }, { role: 'user', content: question }], temperature: 0.7 }) });
    const data = await response.json().catch(() => null);
    return { status: response.status, totalMs: Date.now() - started, reply: String(data?.choices?.[0]?.message?.content ?? '') };
  } catch (error) { return { status: 0, totalMs: Date.now() - started, reply: `调用失败：${error.message}` }; }
}

const VERDICT = /最可能|很可能|大概率|判断|应该是|更像|倾向于|可能是|我怀疑|问题出在/;
const DOSAGE = /(\d+\s*(毫升|ml|克|g|千克|kg)\s*\/?\s*(亩|公顷|升)?|\d+\s*倍液|稀释\s*\d+)/i;
const ASK = /请|能否|需要|确认|麻烦|告诉我|补充/;

function judge(item, mode) {
  const reply = item.reply ?? '';
  const cited = [...reply.matchAll(/chunk-[a-z0-9-]+/gi)].map(m => m[0]);
  const allowed = new Set((item.sources ?? []).map(s => s.id ?? s.chunkId ?? ''));
  return {
    // 只统计可自动判定的项，主观项留给人工
    verdictFirst: VERDICT.test(reply.split('\n').map(l => l.trim()).filter(Boolean)[0] ?? ''),
    citationIds: cited,
    citationCompliant: mode === 'baseline' ? null : cited.every(id => allowed.has(id)),
    dosageWithoutSource: DOSAGE.test(reply) && (mode === 'baseline' ? true : cited.length === 0),
    askedBack: ASK.test(reply) || Boolean(item.clarify),
    hasPlan: Boolean(item.plan),
    chars: reply.length,
  };
}

const results = [];
for (const item of CASES) {
  process.stdout.write(`\n[${item.id}] ${item.cat} … `);
  const model = item.forceModel ?? MODEL;
  const full = await fullChain(item.q, model);
  const base = await baseline(item.q);
  const row = { id: item.id, cat: item.cat, question: item.q, want: item.want,
    full: { ...full, reply: full.reply.slice(0, 1200), judgment: judge(full, 'full') },
    baseline: { ...base, reply: base.reply.slice(0, 1200), judgment: judge(base, 'baseline') } };
  results.push(row);
  process.stdout.write(`完整链路 ${full.status}/${full.totalMs}ms 首段${full.firstDeltaMs ?? '-'}ms 引用${row.full.judgment.citationIds.length} 判断句${row.full.judgment.verdictFirst ? '✓' : '✗'} | 基线 ${base.status}/${base.totalMs}ms 剂量无源${row.baseline.judgment.dosageWithoutSource ? '有' : '无'}`);
}

const summary = {
  version: VERSION, ranAt: new Date().toISOString(), model: MODEL, cases: CASES.length,
  fullChain: {
    avgFirstDeltaMs: Math.round(results.filter(r => r.full.firstDeltaMs).reduce((s, r) => s + r.full.firstDeltaMs, 0) / Math.max(1, results.filter(r => r.full.firstDeltaMs).length)),
    avgTotalMs: Math.round(results.reduce((s, r) => s + r.full.totalMs, 0) / results.length),
    verdictFirstRate: `${results.filter(r => r.full.judgment.verdictFirst).length}/${results.length}`,
    citationCompliantRate: `${results.filter(r => r.full.judgment.citationCompliant !== false).length}/${results.length}`,
    dosageWithoutSource: results.filter(r => r.full.judgment.dosageWithoutSource).length,
    askedBack: results.filter(r => r.full.judgment.askedBack).length,
    plans: results.filter(r => r.full.judgment.hasPlan).length,
    avgChars: Math.round(results.reduce((s, r) => s + r.full.judgment.chars, 0) / results.length),
  },
  baseline: {
    avgTotalMs: Math.round(results.reduce((s, r) => s + r.baseline.totalMs, 0) / results.length),
    dosageWithoutSource: results.filter(r => r.baseline.judgment.dosageWithoutSource).length,
    askedBack: results.filter(r => r.baseline.judgment.askedBack).length,
    avgChars: Math.round(results.reduce((s, r) => s + r.baseline.judgment.chars, 0) / results.length),
  },
};

mkdirSync('docs/eval', { recursive: true });
const stamp = new Date().toISOString().slice(0, 10);
writeFileSync(`docs/eval/p5-${stamp}.json`, JSON.stringify({ summary, results }, null, 2), 'utf8');
console.log('\n\n=== 汇总 ===');
console.log(JSON.stringify(summary, null, 2));
