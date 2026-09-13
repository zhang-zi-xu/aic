import './setup';
import { afterEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import App from '../src/App';
import type { FieldProfile } from '../src/types';

// 图片排查链路的前端部分：选择 → 校验 → 上传 → 预览/删除 → 随问题发送 → 消息内回显。
// 全程使用模拟后端；不做任何真实图片上传。
afterEach(() => { cleanup(); sessionStorage.clear(); });

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const event = (name: string, body: unknown) => `event: ${name}\ndata: ${JSON.stringify(body)}\n\n`;
const fieldA: FieldProfile = { id: 'field-a', name: '测试甲田', crop: '水稻', sowDate: '2026-06-01' };
const uploaded = { id: 'img-1', url: '/api/uploads/img-1', mime: 'image/jpeg', width: 1600, height: 1200, bytes: 240000 };

async function fixture(visionSupported = true) {
  const original = fetch;
  const requests: Array<Record<string, unknown>> = [];
  const uploadBodies: FormData[] = [];
  sessionStorage.setItem('nongxin-ai-settings', JSON.stringify({ provider: 'custom', baseUrl: 'https://example.test/v1', model: 'vision-model', apiKey: 'test-only-not-a-real-credential' }));
  globalThis.fetch = async (input, init = {}) => {
    const url = String(input);
    if (url.startsWith('/api/chat/vision')) return json({ model: 'vision-model', mode: 'auto', known: true, supported: visionSupported });
    if (url === '/api/health') return json({ status: 'ok' });
    if (url === '/api/fields') return json([fieldA]);
    if (url === '/api/knowledge' || url === '/api/conversations') return json([]);
    if (url === '/api/tasks' && (init.method ?? 'GET') === 'GET') return json([]);
    if (url.startsWith('/api/context?')) return json({});
    if (url === '/api/uploads') { uploadBodies.push(init.body as FormData); return json(uploaded); }
    if (url.startsWith('/api/conversations/')) return json({ id: 'c-1', title: '对话', fieldId: null, messages: [], createdAt: '2026-09-11T00:00:00Z' });
    if (url === '/api/chat/stream') { requests.push(JSON.parse(String(init.body))); return new Response(event('done', { reply: '图上能看到叶缘焦枯，可能是缺钾或盐害。' }), { headers: { 'Content-Type': 'text/event-stream' } }); }
    throw new Error(`unexpected ${url}`);
  };
  render(<App />); await screen.findByText('Java 服务已连接');
  return { requests, uploadBodies, restore: () => { globalThis.fetch = original; } };
}

const photo = (name = 'leaf.jpg', type = 'image/jpeg', size = 1024) => {
  const file = new File([new Uint8Array(size)], name, { type });
  return file;
};

test('a chosen photo is uploaded, previewed and can be removed before sending', async () => {
  const f = await fixture();
  try {
    const picker = screen.getByLabelText('选择照片') as HTMLInputElement;
    fireEvent.change(picker, { target: { files: [photo()] } });

    await waitFor(() => assert.equal(f.uploadBodies.length, 1));
    assert.ok(f.uploadBodies[0].get('file'), '上传时带上了图片文件');
    const strip = await screen.findByRole('list', { name: '待发送的照片' });
    assert.ok(strip.querySelector('img[src="/api/uploads/img-1"]'), '显示缩略图');
    assert.match(screen.getByRole('button', { name: /^移除照片/ }).getAttribute('aria-label') ?? '', /leaf\.jpg/);

    fireEvent.click(screen.getByRole('button', { name: /^移除照片/ }));
    assert.equal(screen.queryByRole('list', { name: '待发送的照片' }), null, '可以删除待发送的照片');
  } finally { f.restore(); }
});

test('unsupported file types and oversized photos are rejected without uploading', async () => {
  const f = await fixture();
  try {
    const picker = screen.getByLabelText('选择照片') as HTMLInputElement;
    fireEvent.change(picker, { target: { files: [photo('leaf.heic', 'image/heic', 1024)] } });
    await screen.findByText(/只支持 JPEG \/ PNG/);
    assert.equal(f.uploadBodies.length, 0);

    fireEvent.change(picker, { target: { files: [photo('big.jpg', 'image/jpeg', 9 * 1024 * 1024)] } });
    await screen.findByText(/超过 8 MB/);
    assert.equal(f.uploadBodies.length, 0);
  } finally { f.restore(); }
});

test('sending carries image ids to the backend and shows the photo in the message', async () => {
  const f = await fixture();
  try {
    fireEvent.change(screen.getByLabelText('选择照片'), { target: { files: [photo()] } });
    await screen.findByRole('list', { name: '待发送的照片' });

    fireEvent.change(screen.getByRole('textbox', { name: '向农心提问' }), { target: { value: '叶子这样了，是什么问题？' } });
    fireEvent.click(screen.getByRole('button', { name: '发送问题' }));

    await waitFor(() => assert.equal(f.requests.length, 1));
    assert.deepEqual(f.requests[0].imageIds, ['img-1'], '只传 id，不传 base64');
    assert.equal(f.requests[0].imageInput, 'auto');
    assert.equal(JSON.stringify(f.requests[0]).includes('base64'), false, '请求正文里不应出现 base64');

    const shown = await screen.findByRole('list', { name: '随本条发送的照片' });
    assert.ok(shown.querySelector('img[src="/api/uploads/img-1"]'), '消息里回显照片');
    assert.equal(screen.queryByRole('list', { name: '待发送的照片' }), null, '发送后草稿里的照片被清空');
  } finally { f.restore(); }
});

test('when the model is not vision capable the composer warns before sending', async () => {
  const f = await fixture(false);
  try {
    fireEvent.change(screen.getByLabelText('选择照片'), { target: { files: [photo()] } });
    await screen.findByRole('list', { name: '待发送的照片' });
    await screen.findByText(/当前模型可能不支持看图/);
    assert.ok(screen.getByText(/换成支持图片的模型/));
  } finally { f.restore(); }
});

test('pasting an image from the clipboard uploads it and shows it inside the composer', async () => {
  const f = await fixture();
  try {
    const composer = document.querySelector('.nx-composer') as HTMLElement;
    const box = screen.getByRole('textbox', { name: '向农心提问' });
    const clipboardData = { items: [{ kind: 'file', type: 'image/png', getAsFile: () => photo('screenshot.png', 'image/png', 2048) }] };
    fireEvent.paste(box, { clipboardData });

    await waitFor(() => assert.equal(f.uploadBodies.length, 1));
    const strip = await screen.findByRole('list', { name: '待发送的照片' });
    assert.ok(composer.contains(strip), '缩略图显示在输入框内（textarea 上方）');
    assert.ok(strip.querySelector('img[src="/api/uploads/img-1"]'));
    assert.equal(screen.queryByText(/只支持 JPEG/), null);
  } finally { f.restore(); }
});

test('pasting plain text keeps the browser default and does not upload anything', async () => {
  const f = await fixture();
  try {
    const box = screen.getByRole('textbox', { name: '向农心提问' });
    fireEvent.paste(box, { clipboardData: { items: [{ kind: 'string', type: 'text/plain', getAsFile: () => null }] } });
    await new Promise(resolve => setTimeout(resolve, 30));
    assert.equal(f.uploadBodies.length, 0, '文字粘贴不触发上传');
    assert.equal(screen.queryByRole('list', { name: '待发送的照片' }), null);
  } finally { f.restore(); }
});

test('the shooting guide explains which photos help and why', async () => {
  const f = await fixture();
  try {
    fireEvent.click(screen.getByRole('button', { name: '拍摄提示' }));
    const guide = screen.getByRole('note');
    assert.match(guide.textContent ?? '', /全株/);
    assert.match(guide.textContent ?? '', /病部近景/);
    assert.match(guide.textContent ?? '', /健康对照/);
    assert.match(guide.textContent ?? '', /环境/);
    assert.match(guide.textContent ?? '', /GPS/);
  } finally { f.restore(); }
});
