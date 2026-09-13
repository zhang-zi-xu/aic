import { JSDOM } from 'jsdom';
import { after } from 'node:test';

// Isolated component-test DOM. No browser, real API, user storage or database is used.
// pretendToBeVisual enables JSDOM's requestAnimationFrame implementation (used by the
// Base UI dialog transitions) and fires the visual callbacks on real timers.
const dom = new JSDOM('<!doctype html><html><body></body></html>', {
  url: 'http://localhost:3000/',
  pretendToBeVisual: true,
});
Object.defineProperties(globalThis, {
  window: { value: dom.window, configurable: true },
  document: { value: dom.window.document, configurable: true },
  navigator: { value: dom.window.navigator, configurable: true },
  HTMLElement: { value: dom.window.HTMLElement, configurable: true },
  Element: { value: dom.window.Element, configurable: true },
  Node: { value: dom.window.Node, configurable: true },
  MutationObserver: { value: dom.window.MutationObserver, configurable: true },
  sessionStorage: { value: dom.window.sessionStorage, configurable: true },
  localStorage: { value: dom.window.localStorage, configurable: true },
  getComputedStyle: { value: dom.window.getComputedStyle.bind(dom.window), configurable: true },
  requestAnimationFrame: { value: dom.window.requestAnimationFrame.bind(dom.window), configurable: true },
  cancelAnimationFrame: { value: dom.window.cancelAnimationFrame.bind(dom.window), configurable: true },
  IS_REACT_ACT_ENVIRONMENT: { value: true, writable: true, configurable: true },
});
dom.window.HTMLElement.prototype.scrollIntoView = () => {};

// JSDOM 没有 matchMedia。测试环境按"宽屏"回答（右侧栏可见，天气模块在右侧栏），
// 窄屏分支由用例临时替换 window.matchMedia 来验证。
if (typeof dom.window.matchMedia !== 'function') {
  dom.window.matchMedia = ((query: string) => ({
    matches: true, media: query, onchange: null,
    addEventListener: () => {}, removeEventListener: () => {},
    addListener: () => {}, removeListener: () => {}, dispatchEvent: () => false,
  })) as unknown as typeof dom.window.matchMedia;
}

// jsdom 26 的 nwsapi 把 :modal / :fullscreen 实现成“回调再调用 element.matches”的循环，
// 而 @floating-ui 计算弹层位置时会调用 isTopLayer() → element.matches(':modal')，
// 于是每次打开自绘下拉都会递归到栈溢出（异常被 nwsapi 吞掉后继续重试），单次用例要卡 1~3 分钟。
// 测试环境里不存在顶层弹层（popover/dialog top layer），直接在这里短路这两个伪类。
const originalMatches = dom.window.Element.prototype.matches;
dom.window.Element.prototype.matches = function matches(this: Element, selector: string) {
  if (/:modal|:fullscreen/i.test(selector)) return false;
  return originalMatches.call(this, selector);
};
after(() => dom.window.close());
