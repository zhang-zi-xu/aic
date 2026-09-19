import './setup';
import { afterEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import App from '../src/App';
import type { FieldProfile } from '../src/types';

// 田块影像档案：时间轴（按日期倒序、显示占用、可删除）与「让农心看最近状况」；
// 以及作曲区「自动带上最近 N 张田间照片」开关是否真的把 autoFieldPhotos 传给后端。
afterEach(() => { cleanup(); sessionStorage.clear(); });

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const event = (name: string, body: unknown) => `event: ${name}\ndata: ${JSON.stringify(body)}\n\n`;
const fieldA: FieldProfile = { id: 'field-a', name: '测试甲田', crop: '水稻', sowDate: '2026-06-01' };
const conversation = { id: 'c-1', title: '稻瘟病怎么防', fieldId: 'field-a', createdAt: '2026-09-11T00:00:00Z', messages: [{ id: 'm-1', role: 'user', content: '稻瘟病怎么防' }] };
const photo = (id: string, observedAt: string, note: string) => ({ id, url: `/api/uploads/${id}`, mime: 'image/jpeg', width: 1600, height: 1200, bytes: 240000, fieldId: 'field-a', observedAt, note, createdAt: `${observedAt}T08:00:00` });

async function fixture(deleteFailures = 0) {
  const original = fetch;
  const requests: Array<Record<string, unknown>> = [];
  const deleted: string[] = [];
  let photos = [photo('img-new', '2026-09-20', '南侧田角，叶尖发黄'), photo('img-old', '2026-09-03', '')];
  sessionStorage.setItem('nongxin-ai-settings', JSON.stringify({ provider: 'custom', baseUrl: 'https://example.test/v1', model: 'vision-model', apiKey: 'test-only-not-a-real-credential' }));
  globalThis.fetch = async (input, init = {}) => {
    const url = String(input); const method = init.method ?? 'GET';
    if (url.startsWith('/api/chat/vision')) return json({ supported: true });
    if (url === '/api/health') return json({ status: 'ok' });
    if (url === '/api/fields') return json([fieldA]);
    if (url === '/api/knowledge' || url === '/api/tasks') return json([]);
    if (url === '/api/conversations') return json([conversation]);
    if (url === '/api/uploads/field/field-a') {
      return json({ fieldId: 'field-a', photos, usage: { photos: photos.length, bytes: photos.reduce((sum, item) => sum + item.bytes, 0) } });
    }
    if (url.startsWith('/api/uploads/') && method === 'DELETE') {
      if (deleteFailures > 0) {
        deleteFailures--;
        return json({ code: 'UPLOAD_DELETE_UNAVAILABLE', error: '图片删除暂时无法确认，请刷新影像列表核对后再重试' }, 503);
      }
      deleted.push(url.split('/').at(-1)!); photos = photos.filter(item => item.id !== url.split('/').at(-1)); return json({ deleted: true });
    }
    if (url.startsWith('/api/context?')) return json({});
    if (url === '/api/chat/stream') { requests.push(JSON.parse(String(init.body))); return new Response(event('done', { reply: '看到了。' }), { headers: { 'Content-Type': 'text/event-stream' } }); }
    if (url.startsWith('/api/conversations/')) return json(conversation);
    throw new Error(`unexpected ${method} ${url}`);
  };
  render(<App />); await screen.findByText('Java 服务已连接');
  return { requests, deleted, restore: () => { globalThis.fetch = original; } };
}

async function openFieldDialog() {
  fireEvent.click(screen.getByRole('button', { name: /^我的田块/ }));
  await screen.findByRole('heading', { name: '每一块田，都有自己的故事。' });
  fireEvent.click(screen.getByRole('button', { name: '测试甲田' }));
  return screen.findByRole('heading', { name: '田间照片' });
}

test('field page shows a photo timeline by date with usage and supports deleting one', async () => {
  const f = await fixture();
  try {
    await openFieldDialog();
    assert.match(screen.getByText(/2 张/).textContent ?? '', /2 张/);

    const grid = await screen.findByRole('list', { name: '' }).catch(() => null) ?? document.querySelector('.nx-photo-grid')!;
    const captions = [...(grid as HTMLElement).querySelectorAll('figcaption b')].map(node => node.textContent);
    assert.deepEqual(captions, ['2026.09.20', '2026.09.03'], '按拍摄日期倒序');
    assert.ok(screen.getByText('南侧田角，叶尖发黄'));
    assert.ok(screen.getByText('暂无备注'));

    fireEvent.click(screen.getByRole('button', { name: '删除 2026-09-20 的照片' }));
    await waitFor(() => assert.deepEqual(f.deleted, ['img-new']));
    await waitFor(() => assert.equal(screen.queryByText('南侧田角，叶尖发黄'), null, '删除后从时间轴消失'));
  } finally { f.restore(); }
});

test('“让农心看最近状况” opens a conversation about that field with the question prefilled', async () => {
  const f = await fixture();
  try {
    await openFieldDialog();
    fireEvent.click(await screen.findByRole('button', { name: /让农心看最近状况/ }));

    const draft = await waitFor(() => {
      const box = screen.getByRole('textbox', { name: '向农心提问' }) as HTMLTextAreaElement;
      assert.match(box.value, /看看测试甲田最近的状况/);
      return box.value;
    });
    assert.match(draft, /叶色、病斑和长势/);
  } finally { f.restore(); }
});

test('a failed photo deletion keeps the timeline and usage until a later request succeeds', async () => {
  const f = await fixture(1);
  try {
    await openFieldDialog();
    const originalUsage = screen.getByText(/2 张/).textContent;
    fireEvent.click(screen.getByRole('button', { name: '删除 2026-09-20 的照片' }));
    await screen.findByText('图片删除暂时无法确认，请刷新影像列表核对后再重试');
    assert.deepEqual(f.deleted, [], '失败不记作已删除');
    assert.ok(screen.getByText('南侧田角，叶尖发黄'), '照片仍在时间轴中');
    assert.equal(screen.getByText(/2 张/).textContent, originalUsage, '张数与占用不提前减少');

    fireEvent.click(screen.getByRole('button', { name: '删除 2026-09-20 的照片' }));
    await waitFor(() => assert.deepEqual(f.deleted, ['img-new']));
    await waitFor(() => assert.equal(screen.queryByText('南侧田角，叶尖发黄'), null));
    assert.ok(screen.getByText(/1 张/));
  } finally { f.restore(); }
});

test('the composer toggle carries the latest field photos, and can be switched off', async () => {
  const f = await fixture();
  try {
    fireEvent.click(await screen.findByRole('button', { name: '稻瘟病怎么防' }));
    // 开关默认勾选，并把日期写给用户看
    const toggle = await screen.findByRole('checkbox', { name: /带上最近 2 张田间照片/ });
    assert.equal((toggle as HTMLInputElement).checked, true);
    assert.match(screen.getByText(/带上最近 2 张田间照片/).textContent ?? '', /09-20、09-03/);

    fireEvent.change(screen.getByRole('textbox', { name: '向农心提问' }), { target: { value: '这周要打药吗' } });
    fireEvent.click(screen.getByRole('button', { name: '发送问题' }));
    await waitFor(() => assert.equal(f.requests.length, 1));
    assert.equal(f.requests[0].autoFieldPhotos, true, '默认随问题带上最近照片');
    assert.equal((f.requests[0].imageIds as string[]).length, 0, '自动带图走 autoFieldPhotos，不占用显式 imageIds');

    // 取消勾选后不再带图
    const box = await screen.findByRole('textbox', { name: '向农心提问' });
    fireEvent.change(box, { target: { value: '再问一次' } });
    fireEvent.click(screen.getByRole('checkbox', { name: /带上最近 2 张田间照片/ }));
    fireEvent.click(screen.getByRole('button', { name: '发送问题' }));
    await waitFor(() => assert.equal(f.requests.length, 2));
    assert.equal(f.requests[1].autoFieldPhotos, false);
  } finally { f.restore(); }
});
