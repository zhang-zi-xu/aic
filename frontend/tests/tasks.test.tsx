import './setup';
import { afterEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import App from '../src/App';
import type { FieldProfile } from '../src/types';

// 农事任务闭环（P2）：状态流转、执行/复查记录、页面内提醒、方案项幂等登记。
// 全程使用模拟后端，不连接真实数据库。
afterEach(() => { cleanup(); sessionStorage.clear(); });

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const fieldA: FieldProfile = { id: 'field-a', name: '测试甲田', crop: '水稻', sowDate: '2026-06-01' };
const today = (() => { const now = new Date(); return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`; })();

type FakeTask = {
  id: string; title: string; date: string; status: string; statusLabel: string; createdAt: string;
  fieldId?: string | null; fieldName?: string | null; condition?: string | null; method?: string | null;
  review?: string | null; note?: string | null; timeWindow?: string | null; materials?: string | null; risk?: string | null;
  evidence?: string[]; evidenceCards?: unknown[]; planItemId?: string | null; sourceMessageId?: string | null;
  completedAt?: string | null; records?: Array<Record<string, unknown>>;
};
const LABELS: Record<string, string> = { pending_confirmation: '待确认', pending: '待执行', awaiting_review: '已执行待复查', completed: '已完成', cancelled: '已取消' };
const task = (id: string, title: string, status: string, extra: Partial<FakeTask> = {}): FakeTask =>
  ({ id, title, date: '2026-09-20', status, statusLabel: LABELS[status], createdAt: '2026-09-11T09:00:00Z', fieldId: 'field-a', fieldName: '测试甲田', records: [], ...extra });

async function fixture(tasks: FakeTask[] = [], conversations: unknown[] = []) {
  const original = fetch;
  const rows = new Map(tasks.map((item) => [item.id, structuredClone(item)]));
  const calls = { status: [] as Array<{ id: string; status: string }>, records: [] as Array<Record<string, unknown>>, created: [] as Array<Record<string, unknown>> };
  let sequence = 0;
  sessionStorage.setItem('nongxin-ai-settings', JSON.stringify({ provider: 'custom', baseUrl: 'https://example.test/v1', model: 'mock', apiKey: 'test-only-not-a-real-credential' }));
  globalThis.fetch = async (input, init = {}) => {
    const url = String(input); const method = init.method ?? 'GET'; const body = init.body ? JSON.parse(String(init.body)) : null;
    if (url === '/api/health') return json({ status: 'ok' });
    if (url === '/api/fields') return json([fieldA]);
    if (url === '/api/knowledge') return json([]);
    if (url.startsWith('/api/context?')) return json({});
    if (url === '/api/conversations') return json(conversations);
    if (url === '/api/tasks' && method === 'GET') return json([...rows.values()]);
    if (url === '/api/tasks' && method === 'POST') {
      const incoming = body as Record<string, unknown>;
      const existing = [...rows.values()].find((item) => item.planItemId && item.planItemId === incoming.planItemId && item.sourceMessageId === incoming.sourceMessageId);
      if (existing) return json(existing, 200);
      calls.created.push(incoming);
      const saved: FakeTask = { ...(incoming as unknown as FakeTask), statusLabel: LABELS[String(incoming.status)] ?? String(incoming.status), records: [] };
      rows.set(saved.id, saved); return json(saved, 201);
    }
    const statusMatch = url.match(/^\/api\/tasks\/([^/]+)\/status$/);
    if (statusMatch && method === 'POST') {
      const id = statusMatch[1]; const saved = rows.get(id)!;
      calls.status.push({ id, status: body.status });
      saved.status = body.status; saved.statusLabel = LABELS[body.status];
      return json(saved);
    }
    const recordMatch = url.match(/^\/api\/tasks\/([^/]+)\/records$/);
    if (recordMatch && method === 'POST') {
      const id = recordMatch[1]; const saved = rows.get(id)!;
      calls.records.push({ taskId: id, ...body });
      saved.records = [...(saved.records ?? []), { id: `r-${++sequence}`, taskId: id, fieldId: saved.fieldId, createdAt: new Date().toISOString(), ...body }];
      const next = body.kind === 'review' ? 'completed' : 'awaiting_review';
      saved.status = next; saved.statusLabel = LABELS[next];
      return json(saved);
    }
    const taskMatch = url.match(/^\/api\/tasks\/([^/]+)$/);
    if (taskMatch && method === 'DELETE') { rows.delete(taskMatch[1]); return json({ deleted: true }); }
    if (taskMatch && method === 'PUT') { const saved = { ...rows.get(taskMatch[1]), ...body } as FakeTask; rows.set(saved.id, saved); return json(saved); }
    throw new Error(`unexpected ${method} ${url}`);
  };
  render(<App />); await screen.findByText('Java 服务已连接');
  return { rows, calls, restore: () => { globalThis.fetch = original; } };
}

async function openTasks() {
  fireEvent.click(screen.getByRole('button', { name: /^农事任务/ }));
  await screen.findByRole('heading', { name: '把想做的事，落到每一天。' });
}

test('task list separates states, keeps records and links evidence', async () => {
  const f = await fixture([
    task('t-confirm', '破口期预防施药', 'pending_confirmation', {
      timeWindow: '破口前 3—5 天', evidence: ['chunk-pest-rice-blast'],
      evidenceCards: [{ id: 'chunk-pest-rice-blast', title: '2025年粮食作物重大病虫害防控技术方案', url: 'https://www.moa.gov.cn/example', reviewStatus: 'verified' }],
    }),
    task('t-pending', '检查排水沟', 'pending'),
    task('t-review', '第一次施药', 'awaiting_review', {
      records: [{ id: 'r1', taskId: 't-review', kind: 'execution', date: '2026-09-12', note: '上午按方案喷施，风力 2 级', createdAt: '2026-09-12T09:00:00Z' }],
    }),
    task('t-done', '全田复查与归档', 'completed', {
      records: [{ id: 'r2', taskId: 't-done', kind: 'review', date: '2026-09-16', note: '病斑没有扩展，新叶干净', outcome: 'improved', createdAt: '2026-09-16T09:00:00Z' }],
    }),
  ]);
  try {
    await openTasks();
    // 默认「进行中」：待确认/待执行/待复查 在列表里，已完成的不在
    assert.ok(screen.getByText('破口期预防施药'));
    assert.ok(screen.getByText('检查排水沟'));
    assert.equal(screen.queryByText('全田复查与归档'), null, '已完成任务不混在进行中');
    assert.ok(screen.getAllByText('待确认').length >= 1);
    assert.ok(screen.getAllByText('已执行待复查').length >= 1);
    assert.ok(screen.getByText((_, element) => element?.tagName === 'P' && (element.textContent ?? '').startsWith('时间窗口：破口前 3—5 天')));
    assert.equal(screen.getByRole('link', { name: /防控技术方案/ }).getAttribute('href'), 'https://www.moa.gov.cn/example');

    // 记录默认收起，点开才显示
    assert.equal(screen.queryByText(/上午按方案喷施/), null);
    fireEvent.click(screen.getByRole('button', { name: /执行与复查记录（1）/ }));
    assert.ok(screen.getByText(/上午按方案喷施/));
    assert.ok(screen.getByText('执行记录', { selector: 'b' }));

    // 切到已完成：看得到复查结论
    fireEvent.click(screen.getByRole('button', { name: '已完成' }));
    assert.ok(screen.getByText('全田复查与归档'));
    fireEvent.click(screen.getByRole('button', { name: /执行与复查记录（1）/ }));
    assert.ok(screen.getByText('明显好转'));
    assert.ok(screen.getByText(/病斑没有扩展/));
  } finally { f.restore(); }
});

test('a task is confirmed, then completed only through execution and review records', async () => {
  const f = await fixture([task('t1', '第一次施药', 'pending_confirmation')]);
  try {
    await openTasks();
    fireEvent.click(screen.getByRole('button', { name: '确认安排' }));
    await waitFor(() => assert.equal(f.calls.status.length, 1));
    assert.deepEqual(f.calls.status[0], { id: 't1', status: 'pending' });

    fireEvent.click(await screen.findByRole('button', { name: '提交执行记录' }));
    fireEvent.change(screen.getByLabelText(/实际做了什么/), { target: { value: '上午完成喷施，风力 2 级' } });
    fireEvent.click(screen.getByRole('button', { name: '提交记录' }));
    await waitFor(() => assert.equal(f.calls.records.length, 1));
    assert.equal(f.calls.records[0].kind, 'execution');
    assert.equal(f.calls.records[0].note, '上午完成喷施，风力 2 级');

    fireEvent.click(await screen.findByRole('button', { name: '提交复查记录' }));
    fireEvent.change(screen.getByLabelText(/复查看到什么/), { target: { value: '病斑没有扩展' } });
    fireEvent.click(screen.getByRole('button', { name: '明显好转' }));
    fireEvent.click(screen.getByRole('button', { name: '提交记录' }));
    await waitFor(() => assert.equal(f.calls.records.length, 2));
    assert.equal(f.calls.records[1].kind, 'review');
    assert.equal(f.calls.records[1].outcome, 'improved');
    assert.equal(f.rows.get('t1')?.status, 'completed', '只有复查记录提交后才算完成');

    fireEvent.click(screen.getByRole('button', { name: '已完成' }));
    assert.ok(screen.getByText('第一次施药'));
    fireEvent.click(screen.getByRole('button', { name: /执行与复查记录（2）/ }));
    assert.ok(screen.getByText('上午完成喷施，风力 2 级'));
    assert.ok(screen.getByText('病斑没有扩展'));
  } finally { f.restore(); }
});

test('in-page reminders list overdue, due-today and awaiting-review tasks', async () => {
  const f = await fixture([
    task('t-over', '检查排水沟', 'pending', { date: '2026-09-01' }),
    task('t-today', '今天补肥', 'pending', { date: today }),
    task('t-review', '第一次施药', 'awaiting_review'),
  ]);
  try {
    await openTasks();
    const banner = screen.getByRole('status', { name: '到期与待复查提醒' });
    assert.match(banner.textContent ?? '', /已逾期 1 项：检查排水沟/);
    assert.match(banner.textContent ?? '', /今天到期 1 项：今天补肥/);
    assert.match(banner.textContent ?? '', /执行完等复查 1 项：第一次施药/);
    assert.match(banner.textContent ?? '', /不会发送短信或推送/);

    // 点提醒切到待复查筛选
    fireEvent.click(screen.getByRole('button', { name: /执行完等复查/ }));
    await waitFor(() => assert.equal(screen.queryByText('检查排水沟'), null));
    assert.ok(screen.getByText('第一次施药'));
  } finally { f.restore(); }
});

test('adding the same plan item twice registers one task and marks the card', async () => {
  const message = {
    id: 'm-plan', role: 'assistant', content: '先按这个顺序安排。',
    plan: { title: '水稻破口期方案', summary: '抓住破口前窗口。', items: [{ itemId: 'p1', task: '预防施药', date: '2026-09-12', window: '破口前 3—5 天', materials: '待确认', condition: '雨停后', review: '5 天后查病斑', evidence: ['chunk-pest-rice-blast'] }] },
  };
  const f = await fixture([], [{ id: 'c1', title: '稻瘟病怎么防', fieldId: 'field-a', createdAt: '2026-09-11T00:00:00Z', messages: [message] }]);
  try {
    fireEvent.click(screen.getByRole('button', { name: '稻瘟病怎么防' }));
    await screen.findByText('水稻破口期方案');
    assert.ok(screen.getByText((_, element) => element?.tagName === 'P' && (element.textContent ?? '').includes('时间窗口') && (element.textContent ?? '').includes('破口前 3—5 天')));

    fireEvent.click(screen.getByRole('button', { name: '加入任务' }));
    await screen.findByText('确认加入农事任务');
    fireEvent.click(screen.getByRole('button', { name: '确认加入任务' }));
    await waitFor(() => assert.equal(f.calls.created.length, 1));
    const created = f.calls.created[0];
    assert.equal(created.planItemId, 'p1');
    assert.equal(created.sourceMessageId, 'm-plan');
    assert.equal(created.status, 'pending_confirmation', 'AI 方案先登记为待确认');
    assert.equal(created.timeWindow, '破口前 3—5 天');
    assert.equal(created.materials, '待确认');
    assert.deepEqual(created.evidence, ['chunk-pest-rice-blast']);

    // 方案卡变成「已加入任务」，再点一次不会产生第二条
    fireEvent.click(await screen.findByRole('button', { name: '已加入任务' }));
    await screen.findByText('确认加入农事任务');
    fireEvent.click(screen.getByRole('button', { name: '确认加入任务' }));
    await screen.findByText(/已经在待办里了/);
    assert.equal(f.calls.created.length, 1, '同一方案项只登记一次');
    assert.equal(f.rows.size, 1);
  } finally { f.restore(); }
});

test('tasks are grouped by field first and ordered by time inside each group', async () => {
  const f = await fixture([
    task('t-late', '断水与收获前准备', 'pending', { date: '2026-09-18' }),
    task('t-confirm', '下田核查穗颈瘟症状', 'pending_confirmation', { date: '2026-09-11' }),
    task('t-early', '全田定级复查', 'pending', { date: '2026-09-11' }),
    task('t-none-1', '整理用药记录', 'pending_confirmation', { date: '', fieldId: null, fieldName: null }),
    task('t-other-field', '齐穗期第二次预防施药', 'pending', { date: '2026-09-15', fieldId: 'field-x', fieldName: '测试乙田' }),
  ]);
  try {
    await openTasks();
    const groups = [...document.querySelectorAll('.nx-task-group')] as HTMLElement[];
    assert.equal(groups.length, 3, '按田块分成三组：甲田 / 测试乙田 / 未关联田块');

    const heads = groups.map(group => group.querySelector('.nx-task-group-head b')?.textContent);
    assert.deepEqual(heads, ['测试甲田', '测试乙田', '未关联田块'], '组之间按最早任务日期排：09-11 → 09-15 → 日期待定');

    const titles = [...groups[0].querySelectorAll('.nx-task-title')].map(node => node.textContent);
    assert.deepEqual(titles, ['下田核查穗颈瘟症状', '全田定级复查', '断水与收获前准备'],
      '组内：待确认优先，其余按日期升序（同日按创建时间）');
    assert.deepEqual([...groups[0].querySelectorAll('.nx-task-order')].map(node => node.textContent), ['1', '2', '3'],
      '行首序号就是执行顺序');
    assert.match(groups[0].querySelector('.nx-task-group-head')?.textContent ?? '', /3 项 · 最早 2026\.09\.11/);
  } finally { f.restore(); }
});

test('discussing a task carries the user records into the question draft', async () => {
  const f = await fixture([
    task('t1', '第一次施药', 'awaiting_review', {
      condition: '雨停后、田面无积水',
      records: [{ id: 'r1', taskId: 't1', kind: 'execution', date: '2026-09-12', note: '上午完成喷施，风力 2 级', createdAt: '2026-09-12T09:00:00Z' }],
    }),
  ]);
  try {
    await openTasks();
    fireEvent.click(screen.getByRole('button', { name: /在对话里讨论/ }));
    await waitFor(() => assert.ok(screen.getByRole('textbox', { name: '向农心提问' })));
    const draft = (screen.getByRole('textbox', { name: '向农心提问' }) as HTMLTextAreaElement).value;
    assert.match(draft, /关于任务「第一次施药」/);
    assert.match(draft, /当前状态：已执行待复查/);
    assert.match(draft, /原定执行条件：雨停后、田面无积水/);
    assert.match(draft, /执行记录（2026-09-12）：上午完成喷施，风力 2 级/);
  } finally { f.restore(); }
});
