import './setup';
import { afterEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import App from '../src/App';
import type { FieldProfile } from '../src/types';

// 侧边栏宽度：拖动 / 键盘 / 双击复位 / 记住上次的选择。
// 只涉及界面偏好，不连接任何真实服务。
afterEach(() => { cleanup(); sessionStorage.clear(); localStorage.clear(); });

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const fieldA: FieldProfile = { id: 'field-a', name: '测试甲田', crop: '水稻', sowDate: '2026-06-01' };

async function renderApp() {
  const original = fetch;
  sessionStorage.setItem('nongxin-ai-settings', JSON.stringify({ provider: 'custom', baseUrl: 'https://example.test/v1', model: 'mock', apiKey: 'test-only-not-a-real-credential' }));
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url === '/api/health') return json({ status: 'ok' });
    if (url === '/api/fields') return json([fieldA]);
    if (url === '/api/tasks' || url === '/api/knowledge' || url === '/api/conversations') return json([]);
    if (url.startsWith('/api/context?')) return json({});
    throw new Error(`unexpected URL ${url}`);
  };
  render(<App />); await screen.findByText('Java 服务已连接');
  return { restore: () => { globalThis.fetch = original; } };
}

const resizer = () => screen.getByRole('separator', { name: '调整侧边栏宽度' });
const railResizer = () => screen.getByRole('separator', { name: '调整右侧栏宽度' });
// 页面上有两个 aside（导航侧栏 + 右侧背景栏），这里按类名取导航侧栏；
// 宽度以 CSS 变量下发，窄屏抽屉/单列布局的媒体查询才能覆盖它。
const sidebar = () => document.querySelector('.nx-sidebar') as HTMLElement;
const sidebarWidth = () => sidebar().style.getPropertyValue('--nx-sidebar-width');
const railWidth = () => (document.querySelector('.nx-chat-layout') as HTMLElement).style.getPropertyValue('--nx-rail-width');
const dragOn = (handle: HTMLElement, from: number, to: number) => {
  // JSDOM 没有 PointerEvent 构造器：用 MouseEvent 充当指针事件（clientX 才是被读取的字段）。
  // 拖动监听挂在 window 上，dispatch 不在 React 事件里，需要用 act 包住才能同步看到重渲染。
  const pointer = (type: string, clientX?: number) => new window.MouseEvent(type, { clientX, bubbles: true, cancelable: true });
  act(() => { fireEvent(handle, pointer('pointerdown', from)); });
  act(() => { window.dispatchEvent(pointer('pointermove', to)); });
  act(() => { window.dispatchEvent(pointer('pointerup')); });
};
const drag = (from: number, to: number) => dragOn(resizer(), from, to);

test('sidebar starts at the default width and exposes an accessible resize handle', async () => {
  const f = await renderApp();
  try {
    assert.equal(sidebarWidth(), '230px');
    assert.equal(resizer().getAttribute('aria-valuenow'), '230');
    assert.equal(resizer().getAttribute('aria-valuemin'), '180');
    assert.equal(resizer().getAttribute('aria-valuemax'), '460');
    assert.equal(resizer().getAttribute('aria-orientation'), 'vertical');
  } finally { f.restore(); }
});

test('dragging the handle resizes the sidebar and clamps inside the allowed range', async () => {
  const f = await renderApp();
  try {
    drag(230, 330);
    assert.equal(sidebarWidth(), '330px');
    assert.equal(resizer().getAttribute('aria-valuenow'), '330');

    drag(330, 120);   // 拖过头 → 卡在下限
    assert.equal(sidebarWidth(), '180px');
    drag(180, 900);   // 拖到屏幕外 → 卡在上限
    assert.equal(sidebarWidth(), '460px');

    // 松手后再移动鼠标不应继续改变宽度
    act(() => { window.dispatchEvent(new window.MouseEvent('pointermove', { clientX: 200 })); });
    assert.equal(sidebarWidth(), '460px');
  } finally { f.restore(); }
});

test('keyboard adjusts the width, Home/Escape restores the default', async () => {
  const f = await renderApp();
  try {
    fireEvent.keyDown(resizer(), { key: 'ArrowRight' });
    assert.equal(sidebarWidth(), '246px', '方向键每次调整 16px');
    fireEvent.keyDown(resizer(), { key: 'ArrowRight', shiftKey: true });
    assert.equal(sidebarWidth(), '294px', '按住 Shift 调整 48px');
    fireEvent.keyDown(resizer(), { key: 'ArrowLeft' });
    assert.equal(sidebarWidth(), '278px');
    fireEvent.keyDown(resizer(), { key: 'Home' });
    assert.equal(sidebarWidth(), '230px');
    fireEvent.keyDown(resizer(), { key: 'ArrowLeft' });
    fireEvent.keyDown(resizer(), { key: 'Escape' });
    assert.equal(sidebarWidth(), '230px');
  } finally { f.restore(); }
});

test('double click restores the default width', async () => {
  const f = await renderApp();
  try {
    drag(230, 400);
    assert.equal(sidebarWidth(), '400px');
    fireEvent.doubleClick(resizer());
    assert.equal(sidebarWidth(), '230px');
  } finally { f.restore(); }
});

test('the chosen width is remembered for the next visit', async () => {
  const f = await renderApp();
  try {
    fireEvent.keyDown(resizer(), { key: 'ArrowRight', shiftKey: true });
    await waitFor(() => assert.equal(localStorage.getItem('nongxin-sidebar-width'), '278'));
  } finally { f.restore(); }

  const second = await renderApp();
  try {
    assert.equal(sidebarWidth(), '278px', '刷新后沿用上次的宽度');
  } finally { second.restore(); }
});

test('a broken or out-of-range stored value falls back to a usable width', async () => {
  localStorage.setItem('nongxin-sidebar-width', 'oops');
  const f = await renderApp();
  try { assert.equal(sidebarWidth(), '230px'); } finally { f.restore(); }
  cleanup();

  localStorage.setItem('nongxin-sidebar-width', '9999');
  const second = await renderApp();
  try { assert.equal(sidebarWidth(), '460px', '超出上限时收敛到上限而不是照搬'); } finally { second.restore(); }
});

test('the weather module sits in the right rail instead of crowding the conversation list', async () => {
  const f = await renderApp();
  try {
    assert.equal(document.querySelector('.nx-sidebar .nx-weather'), null, '左侧栏不再放天气模块');
    assert.ok(document.querySelector('.nx-context-rail .nx-weather'), '天气模块移到右侧栏');
    assert.ok(screen.getByRole('button', { name: '设置天气位置' }), '天气交互仍可用');
    assert.ok(screen.getByText('当地天气'));
  } finally { f.restore(); }
});

test('the weather module falls back to the left sidebar when the rail is hidden on narrow screens', async () => {
  const original = window.matchMedia;
  (window as unknown as { matchMedia: unknown }).matchMedia = (query: string) => ({
    matches: false, media: query, onchange: null,
    addEventListener: () => {}, removeEventListener: () => {},
    addListener: () => {}, removeListener: () => {}, dispatchEvent: () => false,
  });
  const f = await renderApp();
  try {
    assert.ok(document.querySelector('.nx-sidebar .nx-weather'), '窄屏时左侧栏必须仍有天气入口');
    assert.equal(document.querySelector('.nx-context-rail .nx-weather'), null);
  } finally {
    (window as unknown as { matchMedia: unknown }).matchMedia = original;
    f.restore();
  }
});

test('the right rail can be resized, clamped and remembered', async () => {
  const f = await renderApp();
  try {
    assert.equal(railWidth(), '250px');
    assert.equal(railResizer().getAttribute('aria-valuenow'), '250');
    assert.equal(railResizer().getAttribute('aria-valuemin'), '200');
    assert.equal(railResizer().getAttribute('aria-valuemax'), '420');
    assert.equal(railResizer().getAttribute('aria-orientation'), 'vertical');

    // 手柄在右栏的左边缘：向左拖变宽、向右拖变窄
    dragOn(railResizer(), 1000, 900);
    assert.equal(railWidth(), '350px');
    dragOn(railResizer(), 900, 1000);
    assert.equal(railWidth(), '250px');
    dragOn(railResizer(), 1000, 1500);
    assert.equal(railWidth(), '200px', '拖过头收敛到下限');
    dragOn(railResizer(), 1000, 100);
    assert.equal(railWidth(), '420px', '拖到屏幕外收敛到上限');

    fireEvent.keyDown(railResizer(), { key: 'ArrowRight' });
    assert.equal(railWidth(), '404px', '右侧栏按 → 是变窄（分隔条跟着按键方向走）');
    fireEvent.keyDown(railResizer(), { key: 'ArrowLeft', shiftKey: true });
    assert.equal(railWidth(), '420px', '一次加 48px，超过上限就收敛到上限');
    fireEvent.keyDown(railResizer(), { key: 'Home' });
    assert.equal(railWidth(), '250px');
    fireEvent.doubleClick(railResizer());
    assert.equal(railWidth(), '250px');

    dragOn(railResizer(), 1000, 940);
    await waitFor(() => assert.equal(localStorage.getItem('nongxin-rail-width'), '310'));
  } finally { f.restore(); }

  const second = await renderApp();
  try { assert.equal(railWidth(), '310px', '刷新后沿用上次的右栏宽度'); } finally { second.restore(); }
});
