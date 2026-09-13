import './setup';
import { afterEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import App from '../src/App';
import { PlanCard } from '../src/components/agri/plan-card';
import { captureContext, requestHistory, retryMessages, streamChat } from '../src/lib/chat';
import { normalizeContext, type Conversation } from '../src/lib/api';
import type { ChatMessage, FieldProfile } from '../src/types';

afterEach(() => { cleanup(); sessionStorage.clear(); });
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const event = (name: string, body: unknown) => `event: ${name}\ndata: ${JSON.stringify(body)}\n\n`;
const complete = (reply: string) => new Response(event('done', { reply }), { headers: { 'Content-Type': 'text/event-stream' } });
const fieldA: FieldProfile = { id: 'field-a', name: '测试甲田', crop: '水稻', sowDate: '2026-06-01' };
const fieldB: FieldProfile = { id: 'field-b', name: '测试乙田', crop: '小麦', sowDate: '2026-05-01' };
const conversation = (id: string, fieldId: string | null = null): Conversation => ({ id, title: id, fieldId, createdAt: '2026-09-01T00:00:00Z', messages: [{ id: `${id}-u`, role: 'user', content: `只属于${id}的问题` }, { id: `${id}-a`, role: 'assistant', content: `只属于${id}的回答` }] });

test('context snapshot is independent; interrupted text excluded and structured cards preserved', () => {
  const field = structuredClone(fieldA);
  const weather = normalizeContext({ location: '测试城市', observedAt: '2026-09-07T10:00' }, 'manual');
  const snapshot = captureContext(field, weather); field.crop = '改后作物'; weather.location = '改后城市';
  assert.equal(snapshot.field?.crop, '水稻'); assert.equal(snapshot.weather?.location, '测试城市');
  const messages: ChatMessage[] = [{ id: 'u', role: 'user', content: '问题' }, { id: 'partial', role: 'assistant', content: '不可作为完整回答', status: 'interrupted' }];
  assert.equal(retryMessages(messages)?.length, 1); assert.equal(requestHistory(messages).length, 1);
  const history = requestHistory([{ id: 'a', role: 'assistant', content: '', clarify: { items: [{ question: '作物？' }] } }]);
  assert.match(history[0].content, /作物/);
  assert.throws(() => requestHistory([{ id: 'too-big', role: 'user', content: '字'.repeat(20001) }]), /没有被截断/);
});

test('stream parser handles byte-split Chinese, CRLF, comments and done', async () => {
  const original = fetch; const bytes = new TextEncoder().encode(': keepalive\r\n\r\n' + event('delta', { text: '稻田🌱' }).replaceAll('\n', '\r\n') + event('done', { reply: '完整回答' }));
  globalThis.fetch = async () => new Response(new ReadableStream({ start(c) { for (const byte of bytes) c.enqueue(new Uint8Array([byte])); c.close(); } }), { headers: { 'Content-Type': 'text/event-stream' } });
  try {
    const deltas: string[] = [];
    const result = await streamChat({}, new AbortController().signal, e => { if (e.event === 'delta') deltas.push(String(e.data.text)); });
    assert.deepEqual(deltas, ['稻田🌱']); assert.equal(result.reply, '完整回答');
  } finally { globalThis.fetch = original; }
});

test('stream EOF and error events never count as completed answers', async () => {
  const original = fetch;
  try {
    for (const [body, error] of [[event('delta', { text: '半截' }), /连接中断/], [event('error', { error: '供应商限流' }), /供应商限流/], ['event: done\ndata: {broken}\n\n', /格式不完整/]] as const) {
      globalThis.fetch = async () => new Response(body, { headers: { 'Content-Type': 'text/event-stream' } });
      await assert.rejects(() => streamChat({}, new AbortController().signal, () => {}), error);
    }
  } finally { globalThis.fetch = original; }
});

type Request = { messages: Array<{ role: string; content: string }>; field: FieldProfile | null; weather: { location: string } | null };
async function fixture(initial: Conversation[] = [], responder: (body: Request, init: RequestInit, call: number) => Response | Promise<Response> = () => complete('测试完整答案')) {
  const original = fetch; const rows = new Map(initial.map(c => [c.id, structuredClone(c)])); const requests: Request[] = [];
  let saveFailure = false, metadataFailure = false, puts = 0;
  let putGate: Promise<void> | null = null;
  sessionStorage.setItem('nongxin-ai-settings', JSON.stringify({ provider: 'custom', baseUrl: 'https://example.test/v1', model: 'mock', apiKey: 'test-only-not-a-real-credential' }));
  globalThis.fetch = async (input, init = {}) => {
    const url = String(input);
    if (url === '/api/health') return json({ status: 'ok' });
    if (url === '/api/fields') return json([fieldA, fieldB]);
    if (url === '/api/tasks' || url === '/api/knowledge') return json([]);
    if (url === '/api/conversations') return json([...rows.values()]);
    if (url.startsWith('/api/context?')) return json({ location: '测试城市', observedAt: '2026-09-07T10:00', sources: { weather: 'mock', location: 'mock' } });
    if (url.startsWith('/api/conversations/')) {
      const id = url.split('/').at(-1)!;
      if (init.method === 'PUT') {
        puts++;
        if (saveFailure) return json({ error: '模拟保存失败' }, 500);
        if (putGate) await putGate;
        const c = JSON.parse(String(init.body)); rows.set(id, c); return json(c);
      }
      if (metadataFailure) return json({ error: '模拟操作失败' }, 500);
      if (init.method === 'PATCH') { const c = { ...rows.get(id)!, title: JSON.parse(String(init.body)).title }; rows.set(id, c); return json(c); }
      if (init.method === 'DELETE') { rows.delete(id); return json({ deleted: true }); }
    }
    if (url === '/api/chat/stream') { const body = JSON.parse(String(init.body)); requests.push(body); return responder(body, init, requests.length); }
    throw new Error(`unexpected URL ${url}`);
  };
  render(<App />); await screen.findByText('Java 服务已连接');
  return { rows, requests, puts: () => puts,
    holdSaves: () => { let release!: () => void; putGate = new Promise<void>(resolve => { release = resolve; }); return () => { putGate = null; release(); }; },
    failSave: (value: boolean) => { saveFailure = value; }, failMetadata: (value: boolean) => { metadataFailure = value; }, restore: () => { globalThis.fetch = original; } };
}
function ask(text: string) { fireEvent.change(screen.getByRole('textbox', { name: '向农心提问' }), { target: { value: text } }); fireEvent.click(screen.getByRole('button', { name: '发送问题' })); }
async function idle() { await waitFor(() => assert.equal((screen.getByRole('textbox', { name: '向农心提问' }) as HTMLTextAreaElement).disabled, false)); }
/** 自绘下拉：先点触发器，再点选项（原生 select 的 change 事件不再适用）。
 *  Base UI 的选项只在 pointerdown 之后接受鼠标提交（防止展开时光标恰好落在选项上误选），
 *  所以这里补一次 pointerdown，等同于真实浏览器 pointerdown → pointerup → click 的顺序。 */
async function pickField(name: RegExp) {
  fireEvent.click(screen.getByRole('combobox', { name: '本次对话关联田块' }));
  const option = await screen.findByRole('option', { name });
  fireEvent.pointerDown(option);
  fireEvent.click(option);
}
// Node's assert builds a Myers diff between the inspected values on failure. Inspecting a
// JSDOM element with assert's deep options dumps the whole document, so element-vs-null
// comparisons must be reduced to a boolean first (same assertion, no multi-megabyte diff).
const gone = (query: () => unknown) => query() === null;

test('partial output is visible before done; stop persists interruption; retry sends only one original question', async () => {
  let stream!: ReadableStreamDefaultController<Uint8Array>;
  const f = await fixture([], (_body, init, call) => call > 1 ? complete('重试后完整答案') : new Response(new ReadableStream({
    start(c) { stream = c; init.signal?.addEventListener('abort', () => c.error(new DOMException('stopped', 'AbortError')), { once: true }); },
  }), { headers: { 'Content-Type': 'text/event-stream' } }));
  try {
    ask('只发送一次的问题'); await waitFor(() => assert.equal(f.requests.length, 1));
    await act(async () => stream.enqueue(new TextEncoder().encode(event('delta', { text: '先观察叶片' }))));
    assert.ok(screen.getByText('先观察叶片'));
    assert.equal((screen.getByRole('combobox', { name: '本次对话关联田块' }) as HTMLButtonElement).disabled, true);
    fireEvent.click(screen.getAllByRole('button', { name: '停止回答' })[0]); await idle();
    const stopped = [...f.rows.values()][0];
    assert.equal(stopped.messages.at(-1)?.status, 'interrupted'); assert.equal(stopped.messages.at(-1)?.content, '先观察叶片');
    fireEvent.click(screen.getByRole('button', { name: '重试这条问题' })); await screen.findByText('重试后完整答案'); await idle();
    assert.deepEqual(f.requests[1], f.requests[0]);
    assert.equal([...f.rows.values()][0].messages.filter(m => m.role === 'user').length, 1);
    assert.equal([...f.rows.values()][0].messages.at(-1)?.status, undefined);
  } finally { f.restore(); }
});

test('degraded answers keep tool results, are marked, and retry sends only the original question', async () => {
  const degraded = new Response(event('done', { reply: '对话处理超时，已生成的工具结果保留在下方。', degraded: true, plan: { title: '排水方案', items: [{ task: '检查排水沟' }] } }), { headers: { 'Content-Type': 'text/event-stream' } });
  const f = await fixture([], (_body, _init, call) => call > 1 ? complete('重试后完整答案') : degraded);
  try {
    ask('只发送一次的问题'); await screen.findByText('检查排水沟');
    assert.ok(screen.getByText(/回答部分完成/));
    fireEvent.click(screen.getByRole('button', { name: '重试这条问题' })); await screen.findByText('重试后完整答案'); await idle();
    assert.equal(f.requests.length, 2);
    assert.deepEqual(f.requests[1], f.requests[0]);
    const saved = [...f.rows.values()][0].messages;
    assert.equal(saved.filter(m => m.role === 'user').length, 1);
    assert.equal(saved.at(-1)?.degraded, undefined);
    assert.equal(requestHistory([{ id: 'd', role: 'assistant', content: 'x', degraded: true }]).length, 0);
  } finally { f.restore(); }
});

test('history rename persists, deletion requires confirmation, failed metadata operations retain records', async () => {
  const f = await fixture([conversation('历史甲'), conversation('历史乙')]);
  try {
    fireEvent.click(screen.getByRole('button', { name: '历史甲' }));
    fireEvent.click(screen.getByRole('button', { name: '重命名对话：历史甲' }));
    fireEvent.change(screen.getByRole('textbox', { name: '对话标题' }), { target: { value: '新的标题' } });
    fireEvent.click(screen.getByRole('button', { name: '保存标题' }));
    await screen.findByRole('button', { name: '新的标题' });
    assert.equal(f.rows.get('历史甲')?.messages.length, 2);
    fireEvent.click(screen.getByRole('button', { name: '删除对话：新的标题' }));
    assert.equal(f.rows.size, 2); fireEvent.click(screen.getByRole('button', { name: '取消' }));
    await waitFor(() => assert.equal(gone(() => screen.queryByRole('dialog')), true));
    fireEvent.click(screen.getByRole('button', { name: '删除对话：新的标题' })); f.failMetadata(true);
    fireEvent.click(screen.getByRole('button', { name: '确认删除对话' })); await screen.findByText('模拟操作失败'); assert.equal(f.rows.size, 2);
    f.failMetadata(false); fireEvent.click(screen.getByRole('button', { name: '确认删除对话' }));
    await waitFor(() => assert.equal(f.rows.size, 1));
    assert.ok(screen.getByRole('button', { name: '历史乙' })); assert.equal(gone(() => screen.queryByText('只属于历史甲的回答')), true);
  } finally { f.restore(); }
});

test('recent conversations are grouped by field, and each group collapses independently', async () => {
  const f = await fixture([
    conversation('甲一', fieldA.id), conversation('甲二', fieldA.id),
    conversation('乙一', fieldB.id), conversation('通用一'),
  ]);
  try {
    const list = screen.getByRole('region', { name: '最近对话列表' });
    const heads = within(list).getAllByRole('button', { name: /的对话（\d+ 段）/ });
    assert.deepEqual(heads.map(head => head.getAttribute('aria-label')),
      ['测试甲田的对话（2 段）', '测试乙田的对话（1 段）', '未关联田块的对话（1 段）']);
    assert.ok(within(list).getByRole('button', { name: '甲一' }));
    assert.ok(within(list).getByText('水稻'));

    fireEvent.click(heads[0]);
    assert.equal(heads[0].getAttribute('aria-expanded'), 'false');
    assert.equal(screen.queryByRole('button', { name: '甲一' }), null, '收起后组内会话隐藏');
    assert.ok(screen.getByRole('button', { name: '乙一' }), '其他组不受影响');
    fireEvent.click(heads[0]);
    assert.ok(screen.getByRole('button', { name: '甲一' }), '再次展开恢复');
  } finally { f.restore(); }
});

test('field picker is a custom popup: lists fields with crop hints and offers field management', async () => {
  const f = await fixture([conversation('已有对话')]);
  try {
    const trigger = screen.getByRole('combobox', { name: '本次对话关联田块' });
    assert.match(trigger.textContent || '', /不关联田块/);
    fireEvent.click(trigger);
    const options = await screen.findAllByRole('option');
    assert.deepEqual(options.map(option => option.textContent?.trim()),
      ['不关联田块通用咨询', '测试甲田水稻', '测试乙田小麦']);

    // 「管理田块档案」在右侧背景栏也有一处（未关联田块时出现），这里限定在弹层内点击
    const popup = screen.getByRole('listbox');
    fireEvent.click(within(popup).getByRole('button', { name: /管理田块档案/ }));
    await waitFor(() => assert.equal(gone(() => screen.queryByRole('textbox', { name: '向农心提问' })), true, '应切换到我的田块页面'));
  } finally { f.restore(); }
});

test('changing fields starts isolated history; switching conversations keeps drafts and never carries other history', async () => {
  const f = await fixture([conversation('历史甲', fieldA.id), conversation('历史乙', fieldB.id)]);
  try {
    fireEvent.click(screen.getByRole('button', { name: '历史甲' }));
    fireEvent.change(screen.getByRole('textbox', { name: '向农心提问' }), { target: { value: '甲草稿' } });
    fireEvent.click(screen.getByRole('button', { name: '历史乙' }));
    assert.equal((screen.getByRole('textbox', { name: '向农心提问' }) as HTMLTextAreaElement).value, '');
    ask('乙的新问题'); await screen.findByText('测试完整答案'); await idle();
    assert.equal(f.requests[0].field?.id, fieldB.id); assert.doesNotMatch(JSON.stringify(f.requests[0].messages), /历史甲/);
    fireEvent.click(screen.getByRole('button', { name: '历史甲' }));
    assert.equal((screen.getByRole('textbox', { name: '向农心提问' }) as HTMLTextAreaElement).value, '甲草稿');
    await pickField(/测试乙田/);
    ask('新田块问题'); await screen.findByText('测试完整答案'); await idle();
    assert.equal(f.requests[1].messages.length, 1); assert.equal(f.requests[1].field?.id, fieldB.id);
  } finally { f.restore(); }
});

test('weather is opt-in and reset for other conversations; reloaded interruption retries its original snapshot', async () => {
  const original = conversation('上次中断', fieldA.id);
  original.messages = [{ id: 'old-user', role: 'user', content: '原始问题', requestContext: captureContext({ ...fieldA, name: '发送时的田块' }, null) }, { id: 'old-partial', role: 'assistant', content: '未完成', status: 'interrupted', error: '连接中断' }];
  const f = await fixture([original, conversation('另一个对话')]);
  try {
    fireEvent.click(screen.getByRole('button', { name: '设置天气位置' }));
    fireEvent.change(screen.getByRole('textbox', { name: '天气城市' }), { target: { value: '测试城市' } });
    fireEvent.click(screen.getByRole('button', { name: '查询天气' }));
    const weather = await screen.findByRole('checkbox', { name: /随问题发送测试城市/ });
    assert.equal((weather as HTMLInputElement).checked, false); fireEvent.click(weather);
    ask('带天气的问题'); await screen.findByText('测试完整答案'); await idle(); assert.equal(f.requests[0].weather?.location, '测试城市');
    fireEvent.click(screen.getByRole('button', { name: '上次中断' })); assert.equal((weather as HTMLInputElement).checked, false);
    fireEvent.click(screen.getByRole('button', { name: '重试这条问题' })); await screen.findByText('测试完整答案'); await idle();
    assert.equal(f.requests[1].field?.name, '发送时的田块'); assert.equal(f.requests[1].weather, null); assert.equal(f.requests[1].messages.length, 1);
  } finally { f.restore(); }
});

test('saves are serialised so a late response cannot overwrite newer messages', async () => {
  const f = await fixture([conversation('已有对话')]);
  try {
    f.failSave(true); ask('暂未保存的问题'); await idle();
    await screen.findByRole('button', { name: '重试保存' });
    f.failSave(false); const release = f.holdSaves(); const base = f.puts();
    fireEvent.click(screen.getByRole('button', { name: '重试保存' }));
    await waitFor(() => assert.equal(f.puts(), base + 1));
    fireEvent.click(screen.getByRole('button', { name: '重试保存' }));
    await new Promise(resolve => setTimeout(resolve, 80));
    assert.equal(f.puts(), base + 1, '第二次保存必须排队，不能与第一次并发发出');
    release();
    await waitFor(() => assert.equal(f.puts(), base + 2));
    await waitFor(() => assert.equal(gone(() => screen.queryByRole('button', { name: '重试保存' })), true));
    assert.equal([...f.rows.values()][0].messages.filter(m => m.role === 'user').length, 1);
  } finally { f.restore(); }
});

test('retrieved sources show status, applicability and the original link; none are invented', async () => {
  const withSources = new Response(event('done', { reply: '按资料执行。', sources: [
    { id: 'chunk-pest-rice-blast', title: '2025年粮食作物重大病虫害防控技术方案', institution: '全国农技推广服务中心', url: 'https://www.moa.gov.cn/example', publishedAt: '2025-02-28', region: '全国', crop: '水稻', growthStage: '苗期至齐穗期', heading: '（4）稻瘟病', status: 'verified', excerpt: '穗瘟在破口前3—5天预防施药。' },
    { id: 'chunk-draft-rice-blast', title: '稻瘟病（叶瘟与穗颈瘟）', institution: '本地整理草稿（未核验）', crop: '水稻', status: 'unverified', excerpt: '症状：叶片出现梭形病斑。' },
  ] }), { headers: { 'Content-Type': 'text/event-stream' } });
  const f = await fixture([], () => withSources);
  try {
    ask('稻瘟病怎么防'); await screen.findByText('资料依据');
    const card = screen.getByText('资料依据').closest('.nx-sources') as HTMLElement;
    assert.ok(within(card).getByText('已核验原文'));
    assert.ok(within(card).getByText('本地草稿 · 未核验'));
    assert.equal(within(card).getByRole('link', { name: /查看原文/ }).getAttribute('href'), 'https://www.moa.gov.cn/example');
    assert.ok(within(card).getByText(/适用地区：全国/));
    assert.ok(within(card).getByText(/来源ID：chunk-pest-rice-blast/));
    assert.ok(within(card).getByText(/无原文链接/));
    assert.equal([...f.rows.values()][0].messages.at(-1)?.sources?.length, 2, '来源卡随回答一起保存，刷新后仍可核对');
  } finally { f.restore(); }
});

test('answers without retrieved material show no source card', async () => {
  const f = await fixture([], () => complete('没有查到可引用的依据。'));
  try {
    ask('某种未知情况'); await screen.findByText('没有查到可引用的依据。');
    assert.equal(screen.queryByText('资料依据'), null);
  } finally { f.restore(); }
});

test('plan card tolerates non-array evidence instead of crashing the page', () => {
  render(<PlanCard plan={{ title: '方案', items: [
    { task: '第一项', evidence: 'chunk-fake-string' as unknown as string[] },
    { task: '第二项', evidence: ['chunk-pest-rice-blast', 42 as unknown as string] },
  ] }} />);
  assert.ok(screen.getByText('第一项'));
  assert.ok(screen.getByText('第二项'));
  assert.ok(screen.getByText(/参考来源：chunk-pest-rice-blast/));
  assert.equal(screen.queryByText(/chunk-fake-string/), null);
});

test('unsaved questions block model call and navigation until saved; retry does not duplicate', async () => {
  const f = await fixture([conversation('已有对话')]);
  try {
    f.failSave(true); ask('暂未保存的问题'); await idle();
    await screen.findByRole('button', { name: '重试保存' });
    assert.equal(f.requests.length, 0); assert.equal((screen.getByRole('button', { name: '已有对话' }) as HTMLButtonElement).disabled, true);
    f.failSave(false); fireEvent.click(screen.getByRole('button', { name: '重试保存' }));
    await waitFor(() => assert.equal(gone(() => screen.queryByRole('button', { name: '重试保存' })), true));
    fireEvent.click(screen.getByRole('button', { name: '重试这条问题' })); await screen.findByText('测试完整答案');
    assert.equal(f.requests.length, 1); assert.equal(f.requests[0].messages.length, 1);
  } finally { f.restore(); }
});
