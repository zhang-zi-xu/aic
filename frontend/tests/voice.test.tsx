import './setup';
import { afterEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import App from '../src/App';
import type { FieldProfile } from '../src/types';

// 语音输入：只做两件能被自动化验证的事——
// ① 浏览器不支持时不渲染假按钮；② 支持时识别结果只追加到输入框、绝不自动发送。
afterEach(() => { cleanup(); sessionStorage.clear(); });

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const event = (name: string, body: unknown) => `event: ${name}\ndata: ${JSON.stringify(body)}\n\n`;
const fieldA: FieldProfile = { id: 'field-a', name: '测试甲田', crop: '水稻', sowDate: '2026-06-01' };

async function renderApp() {
  const original = fetch;
  const requests: Array<Record<string, unknown>> = [];
  sessionStorage.setItem('nongxin-ai-settings', JSON.stringify({ provider: 'custom', baseUrl: 'https://example.test/v1', model: 'mock', apiKey: 'test-only-not-a-real-credential' }));
  globalThis.fetch = async (input, init = {}) => {
    const url = String(input);
    if (url.startsWith('/api/chat/vision')) return json({ supported: true });
    if (url === '/api/health') return json({ status: 'ok' });
    if (url === '/api/fields') return json([fieldA]);
    if (url === '/api/tasks' || url === '/api/knowledge' || url === '/api/conversations') return json([]);
    if (url.startsWith('/api/context?')) return json({});
    if (url === '/api/chat/stream') { requests.push(JSON.parse(String(init.body))); return new Response(event('done', { reply: '收到。' }), { headers: { 'Content-Type': 'text/event-stream' } }); }
    throw new Error(`unexpected ${url}`);
  };
  render(<App />); await screen.findByText('Java 服务已连接');
  return { requests, restore: () => { globalThis.fetch = original; } };
}

test('no fake microphone button when the browser has no speech recognition', async () => {
  const f = await renderApp();
  try {
    assert.equal(screen.queryByRole('button', { name: '语音输入' }), null, '不支持时不渲染按钮');
  } finally { f.restore(); }
});

test('recognised speech is appended to the composer and never sent automatically', async () => {
  class FakeRecognition {
    lang = ''; continuous = false; interimResults = false;
    onresult: ((event: unknown) => void) | null = null;
    onerror: ((event: unknown) => void) | null = null;
    onend: (() => void) | null = null;
    start() { FakeRecognition.started = true; }
    stop() { this.onend?.(); }
    abort() {}
    static started = false;
    static emit(text: string) {
      this.instance?.onresult?.({ resultIndex: 0, results: Object.assign([{ isFinal: true, 0: { transcript: text } }], { length: 1 }) });
    }
    static instance: FakeRecognition | null = null;
    constructor() { FakeRecognition.instance = this; }
  }
  (window as unknown as { SpeechRecognition: unknown }).SpeechRecognition = FakeRecognition;

  const f = await renderApp();
  try {
    fireEvent.change(screen.getByRole('textbox', { name: '向农心提问' }), { target: { value: '水稻' } });
    const mic = await screen.findByRole('button', { name: '语音输入' });
    fireEvent.click(mic);
    await waitFor(() => assert.equal(FakeRecognition.started, true));
    assert.equal(screen.getByRole('button', { name: '停止语音输入' }).getAttribute('aria-pressed'), 'true');

    act(() => { FakeRecognition.emit('叶子上有褐斑怎么办'); });
    fireEvent.click(screen.getByRole('button', { name: '停止语音输入' }));

    await waitFor(() => assert.equal((screen.getByRole('textbox', { name: '向农心提问' }) as HTMLTextAreaElement).value, '水稻 叶子上有褐斑怎么办'));
    assert.equal(f.requests.length, 0, '识别结果不会自动发送');
  } finally {
    delete (window as unknown as { SpeechRecognition?: unknown }).SpeechRecognition;
    f.restore();
  }
});
