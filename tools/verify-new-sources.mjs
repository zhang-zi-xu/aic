// 验证新入库的官方文件能否被正确检索与引用。
// 用法：NONGXIN_TEST_KEY=... node tools/verify-new-sources.mjs
// 判定：① 回复里出现的来源 ID 必须都在本次检索白名单内；② 是否命中新增的四篇来源之一。
const KEY = process.env.NONGXIN_TEST_KEY;
if (!KEY) { console.error('缺少 NONGXIN_TEST_KEY'); process.exit(1); }
const API = 'http://127.0.0.1:8080/api';

const NEW_DOCS = ['doc-natesc-rice-pest-2026', 'doc-natesc-wheat-spring-2026', 'doc-natesc-wheat-yipensanfang-2026', 'doc-moa-precise-pesticide-2026'];

const CASES = [
  { id: 'rice-2026', q: '2026年水稻主要防哪些病虫害？稻瘟病该在什么时候打药？' },
  { id: 'pesticide', q: '打农药要注意什么？安全间隔期是怎么回事？' },
  { id: 'wheat', q: '小麦一喷三防是打什么、什么时候打？' },
];

async function ask(question) {
  const started = Date.now();
  const response = await fetch(`${API}/chat`, { method: 'POST', headers: { 'Content-Type': 'application/json; charset=utf-8' },
    body: JSON.stringify({ provider: 'deepseek', model: 'deepseek-chat', baseUrl: null, apiKey: KEY,
      messages: [{ role: 'user', content: question }], field: null, location: { label: '浙江杭州' }, weather: null,
      priorSources: null, imageIds: [], imageInput: 'auto', autoFieldPhotos: false }) });
  const body = await response.json().catch(() => null);
  return { status: response.status, ms: Date.now() - started, reply: String(body?.reply ?? body?.error ?? ''),
    sources: (body?.sources ?? []).map(source => source.id), plan: body?.plan ?? null, clarify: body?.clarify ?? null };
}

let hitNew = 0;
const rows = [];
for (const item of CASES) {
  const answer = await ask(item.q);
  const cited = [...answer.reply.matchAll(/chunk-[a-z0-9-]+/gi)].map(match => match[0]);
  const allowed = new Set(answer.sources);
  const compliant = cited.every(id => allowed.has(id));
  const newHits = answer.sources.filter(id => NEW_DOCS.some(doc => id.includes(doc.replace(/^doc-/, '').replace(/-20\d\d$/, ''))) || id.includes('2026'));
  if (newHits.length) hitNew++;
  rows.push({ ...item, ...answer, cited, compliant, newHits });
  console.log(`\n[${item.id}] ${item.q}`);
  console.log(`  状态=${answer.status} 耗时=${(answer.ms / 1000).toFixed(1)}s 检索来源=${answer.sources.length} 引用=${cited.length} 引用合规=${compliant ? '✅' : '❌'}`);
  console.log(`  命中新来源：${newHits.length ? newHits.join(', ') : '（无）'}`);
  console.log(`  回复首句：${answer.reply.split('\n').map(l => l.trim()).filter(Boolean)[0]?.slice(0, 90) ?? '（空）'}`);
}

console.log('\n=== 汇总 ===');
console.log(`命中新增官方文件的问题数：${hitNew}/${CASES.length}`);
console.log(`引用全部合规：${rows.every(row => row.compliant) ? '是' : '否'}`);
