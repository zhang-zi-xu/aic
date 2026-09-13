import { Component, type ErrorInfo, type ReactNode } from 'react';

/**
 * 兜底错误边界：任何渲染期异常都不该让用户看到一片白屏。
 * 这里显示可读原因 + 两个恢复动作（重新加载 / 清掉本地界面设置），并把详情留给控制台。
 */
export class ErrorBoundary extends Component<{ children: ReactNode }, { error: Error | null }> {
  state = { error: null as Error | null };

  static getDerivedStateFromError(error: Error) { return { error }; }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('[农心] 界面渲染失败：', error, info.componentStack);
  }

  private resetLocalState = () => {
    try {
      localStorage.removeItem('nongxin-sidebar-width');
      localStorage.removeItem('nongxin-rail-width');
      sessionStorage.removeItem('nongxin-ai-settings');
    } catch { /* 存储不可用时忽略 */ }
    location.reload();
  };

  render() {
    if (!this.state.error) return this.props.children;
    return <div className="nx-crash" role="alert">
      <h1>界面加载出错了</h1>
      <p>数据都还在服务端，没有丢失。先试重新加载；如果反复出现，请把下面这行信息发给我们。</p>
      <pre>{this.state.error.message}</pre>
      <div className="nx-crash-actions">
        <button type="button" className="nx-button is-primary" onClick={() => location.reload()}>重新加载</button>
        <button type="button" className="nx-button" onClick={this.resetLocalState}>清掉本地界面设置后重试</button>
      </div>
    </div>;
  }
}
