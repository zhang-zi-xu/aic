import './setup';
import { afterEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { useState } from 'react';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { ClarifyCard } from '../src/components/agri/clarify-card';
import { formatClarifyReply, getClarifyItems, hasClarifyFollowUp, setClarifyAnswer, type ClarifyAnswers } from '../src/lib/clarification';
import type { ChatMessage, ClarifyArgs } from '../src/types';
import App from '../src/App';

const clarify: ClarifyArgs = { intro: '请补充这几项实际情况。', items: [
  { question: '种植什么作物？', options: ['水稻', '小麦'] },
  { question: '田间是否积水？', options: ['有积水', '没有积水'] },
  { question: '问题出现多久了？', options: ['今天刚发现', '持续三天'] },
] };

afterEach(() => { cleanup(); sessionStorage.clear(); });

function Harness({ onSubmit, data = clarify, completed = false }: { onSubmit: (reply: string) => Promise<void>; data?: ClarifyArgs; completed?: boolean }) {
  const [answers, setAnswers] = useState<ClarifyAnswers>({});
  return <ClarifyCard clarify={data} answers={answers} onAnswer={(index, answer) => setAnswers(previous => setClarifyAnswer(previous, index, answer))} onSubmit={onSubmit} completed={completed} />;
}

function choose(name: string) { fireEvent.click(screen.getByRole('radio', { name })); }

test('changing one answer preserves other questions and formats every question/answer in order', () => {
  const first = setClarifyAnswer({}, 0, '水稻');
  const second = setClarifyAnswer(first, 1, '有积水');
  const changed = setClarifyAnswer(second, 0, '小麦');
  assert.deepEqual(first, { 0: '水稻' });
  assert.deepEqual(changed, { 0: '小麦', 1: '有积水' });
  assert.equal(formatClarifyReply(clarify, { ...changed, 2: '  持续三天  ' }),
    '我补充的信息如下：\n\n1. 问题：种植什么作物？\n回答：小麦\n\n2. 问题：田间是否积水？\n回答：有积水\n\n3. 问题：问题出现多久了？\n回答：持续三天\n\n请结合以上信息继续分析，并给出下一步建议。');
});

test('missing answers and oversized replies are rejected, never invented or truncated', () => {
  assert.throws(() => formatClarifyReply(clarify, { 0: '水稻' }), /逐题/);
  assert.throws(() => formatClarifyReply({}, {}), /没有可回答/);
  assert.throws(() => formatClarifyReply(clarify, { 0: '水稻', 1: '有积水', 2: '观测'.repeat(2500) }), /4,000/);
  assert.equal(getClarifyItems({ items: [{ question: '  ' }, { question: '作物？', options: ['水稻', '水稻', ''] }] }).length, 1);
});

test('multiple selections stay selected and submit as one complete reply', async () => {
  const submissions: string[] = [];
  render(<Harness onSubmit={async reply => { submissions.push(reply); }} />);
  const submit = screen.getByRole('button', { name: '提交并继续' }) as HTMLButtonElement;
  assert.equal(submit.disabled, true);
  choose('水稻'); choose('有积水');
  assert.equal((screen.getByRole('radio', { name: '水稻' }) as HTMLInputElement).checked, true);
  assert.equal((screen.getByRole('radio', { name: '有积水' }) as HTMLInputElement).checked, true);
  assert.equal(submissions.length, 0, 'selecting options must not send requests');
  assert.equal(submit.disabled, true);
  choose('持续三天'); choose('小麦');
  assert.equal((screen.getByRole('radio', { name: '水稻' }) as HTMLInputElement).checked, false);
  assert.equal((screen.getByRole('radio', { name: '有积水' }) as HTMLInputElement).checked, true);
  fireEvent.click(submit);
  await waitFor(() => assert.equal(submissions.length, 1));
  assert.equal(submissions[0], formatClarifyReply(clarify, { 0: '小麦', 1: '有积水', 2: '持续三天' }));
});

test('self-written and uncertain answers can be submitted without forcing a guessed option', async () => {
  let sent = '';
  render(<Harness data={{ items: [{ question: '补充观察？' }, { question: '是什么品种？', options: ['品种甲'] }] }} onSubmit={async reply => { sent = reply; }} />);
  fireEvent.change(screen.getByLabelText('补充观察？的回答'), { target: { value: '只在靠近沟渠的两行发现问题' } });
  const variety = screen.getByRole('group', { name: '2. 是什么品种？' });
  fireEvent.click(within(variety).getByRole('radio', { name: '暂不确定' }));
  fireEvent.click(screen.getByRole('button', { name: '提交并继续' }));
  await waitFor(() => assert.match(sent, /回答：只在靠近沟渠的两行发现问题/));
  assert.match(sent, /回答：暂不确定/);
});

test('a fourth question is not silently dropped', () => {
  const extended = { items: [...clarify.items!, { question: '所在城市？', options: ['杭州'] }] };
  render(<Harness data={extended} onSubmit={async () => {}} />);
  assert.ok(screen.getByRole('radio', { name: '杭州' }));
  assert.ok(screen.getByText('已回答 0 / 4 项，提交后统一发送。'));
});

test('duplicate clicks are blocked while submitting; failed submission keeps every answer', async () => {
  let reject!: (reason: Error) => void;
  let calls = 0;
  render(<Harness onSubmit={async () => { calls++; await new Promise<void>((_, failure) => { reject = failure; }); }} />);
  choose('水稻'); choose('有积水'); choose('持续三天');
  const submit = screen.getByRole('button', { name: '提交并继续' });
  fireEvent.click(submit); fireEvent.click(submit);
  assert.equal(calls, 1);
  assert.equal((screen.getByRole('button', { name: '正在提交…' }) as HTMLButtonElement).disabled, true);
  await act(async () => reject(new Error('测试连接失败')));
  assert.match(screen.getByRole('alert').textContent || '', /测试连接失败/);
  for (const name of ['水稻', '有积水', '持续三天']) assert.equal((screen.getByRole('radio', { name }) as HTMLInputElement).checked, true);
  assert.equal((screen.getByRole('button', { name: '提交并继续' }) as HTMLButtonElement).disabled, false);
});

test('historical cards are read-only after a user follow-up, including after reloading stored history', () => {
  const messages: ChatMessage[] = [{ id: 'card', role: 'assistant', content: '', clarify }];
  assert.equal(hasClarifyFollowUp(messages, 'card'), false);
  messages.push({ id: 'reply', role: 'user', content: formatClarifyReply(clarify, { 0: '水稻', 1: '有积水', 2: '持续三天' }) });
  assert.equal(hasClarifyFollowUp(JSON.parse(JSON.stringify(messages)), 'card'), true);
  assert.equal(hasClarifyFollowUp(messages, 'missing'), false);
  render(<Harness completed onSubmit={async () => { throw new Error('must not send'); }} />);
  assert.equal((screen.getByRole('button', { name: '已继续对话' }) as HTMLButtonElement).disabled, true);
  assert.equal((screen.getByRole('radio', { name: '水稻' }) as HTMLInputElement).closest('fieldset')?.disabled, true);
});

type SentRequest = { messages: Array<{ role: string; content: string }>; [key: string]: unknown };

async function renderChat(failFollowUp = false) {
  sessionStorage.setItem('nongxin-ai-settings', JSON.stringify({ provider: 'custom', baseUrl: 'https://example.test/v1', model: 'mock-model', apiKey: 'test-only-not-a-real-credential' }));
  const requests: SentRequest[] = [];
  const saved: Array<{ messages: ChatMessage[] }> = [];
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (input, options) => {
    const url = String(input);
    const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
    if (url === '/api/health') return json({ status: 'ok' });
    if (options?.method === 'PUT' && url.startsWith('/api/conversations/')) {
      const body = JSON.parse(String(options.body)); saved.push(body); return json(body);
    }
    if (url === '/api/chat/stream') {
      requests.push(JSON.parse(String(options?.body)));
      const complete = (body: unknown) => new Response(`event: done\ndata: ${JSON.stringify(body)}\n\n`, { headers: { 'Content-Type': 'text/event-stream' } });
      if (requests.length === 1) return complete({ reply: '先补充几项信息。', clarify });
      if (failFollowUp && requests.length === 2) return json({ error: '模拟供应商暂不可用' }, 502);
      return complete({ reply: '已结合你补充的情况，下面继续讨论下一步。' });
    }
    if (['/api/fields', '/api/tasks', '/api/conversations', '/api/knowledge'].includes(url)) return json([]);
    throw new Error(`Unexpected test request: ${url}`);
  };
  try {
    render(<App />);
    await screen.findByText('Java 服务已连接');
    const composer = screen.getByRole('textbox', { name: '向农心提问' });
    fireEvent.change(composer, { target: { value: '帮我排查田间问题' } });
    fireEvent.click(screen.getByRole('button', { name: '发送问题' }));
    await screen.findByRole('button', { name: '提交并继续' });
    return { requests, saved, composer, restore: () => { globalThis.fetch = originalFetch; } };
  } catch (error) { globalThis.fetch = originalFetch; throw error; }
}

test('app sends all answers directly as one user turn, preserves draft, and continues with the agent response', async () => {
  const fixture = await renderChat();
  try {
    fireEvent.change(fixture.composer, { target: { value: '这份草稿先不要发送' } });
    choose('水稻'); choose('有积水'); choose('持续三天');
    assert.equal((fixture.composer as HTMLTextAreaElement).value, '这份草稿先不要发送');
    assert.equal(fixture.requests.length, 1);
    fireEvent.click(screen.getByRole('button', { name: '提交并继续' }));
    await screen.findByText('已结合你补充的情况，下面继续讨论下一步。');
    assert.equal(fixture.requests.length, 2);
    const expected = formatClarifyReply(clarify, { 0: '水稻', 1: '有积水', 2: '持续三天' });
    assert.deepEqual(fixture.requests[1].messages.at(-1), { role: 'user', content: expected });
    assert.equal((fixture.composer as HTMLTextAreaElement).value, '这份草稿先不要发送');
    const conversation = fixture.saved.at(-1)!;
    assert.equal(conversation.messages.filter(message => message.role === 'user' && message.content === expected).length, 1);
    assert.equal(conversation.messages.at(-1)?.role, 'assistant');
  } finally { fixture.restore(); }
});

test('agent failure can retry the same combined reply without adding a duplicate user message', async () => {
  const fixture = await renderChat(true);
  try {
    choose('水稻'); choose('有积水'); choose('持续三天');
    fireEvent.click(screen.getByRole('button', { name: '提交并继续' }));
    await screen.findByText('模拟供应商暂不可用');
    const retry = screen.getByRole('button', { name: '重试这条问题' });
    await waitFor(() => assert.equal((retry as HTMLButtonElement).disabled, false));
    fireEvent.click(retry);
    await screen.findByText('已结合你补充的情况，下面继续讨论下一步。');
    assert.equal(fixture.requests.length, 3);
    assert.deepEqual(fixture.requests[2].messages, fixture.requests[1].messages);
    assert.equal(fixture.saved.at(-1)!.messages.filter(message => message.role === 'user').length, 2);
  } finally { fixture.restore(); }
});
