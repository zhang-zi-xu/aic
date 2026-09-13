import { CalendarPlus, ClipboardList, TriangleAlert } from 'lucide-react';
import type { PlanArgs, PlanItem, RiskArgs } from '@/types';
import { MiniMd } from '@/components/ui/mini-md';

/** 依据可能来自模型输出，运行时形状不可信：只渲染字符串数组。 */
function evidenceIds(item: PlanItem): string[] {
  if (!Array.isArray(item.evidence)) return [];
  return item.evidence.filter((id): id is string => typeof id === 'string' && !!id.trim());
}

/**
 * AI 方案卡：这里是草稿，点「加入任务」才登记成任务（待确认），
 * 同一方案项重复点击不会产生第二条任务（服务端按来源消息 + 方案项 ID 幂等）。
 */
export function PlanCard({ plan, onAdd, disabled, addedItemIds = [], superseded = false }: { plan: PlanArgs; onAdd?: () => void; disabled?: boolean; addedItemIds?: string[]; superseded?: boolean }) {
  const items = plan.items ?? [];
  const added = new Set(addedItemIds);
  const itemKey = (item: PlanItem, index: number) => item.itemId || `p${index + 1}`;
  const addedCount = items.filter((item, index) => added.has(itemKey(item, index))).length;
  const allAdded = items.length > 0 && addedCount === items.length;
  return <section className="nx-plan"><header><ClipboardList size={18} /><div><b>{plan.title || '农事建议'}</b><span>AI 生成 · 确认后再执行</span></div>{addedCount > 0 && <span className="nx-badge is-verified">已加入 {addedCount}/{items.length}</span>}</header>
    {plan.summary && <p>{plan.summary}</p>}
    <ol>{items.map((item, i) => <li key={i}><span className="nx-plan-index">{String(i + 1).padStart(2, '0')}</span><div><h4>{item.task}{added.has(itemKey(item, i)) && <span className="nx-badge is-verified">已加入任务</span>}</h4>{item.date && <span className="nx-badge">{item.date}</span>}
      {item.window && <p><b>时间窗口</b>{item.window}</p>}{item.method && <p><b>操作</b>{item.method}</p>}{item.dosage && <p><b>用量建议</b>{item.dosage}</p>}{item.materials && <p><b>所需物料</b>{item.materials}</p>}{item.condition && <p><b>执行条件</b>{item.condition}</p>}{item.review && <p><b>复查</b>{item.review}</p>}{item.warning && <p className="nx-warning">{item.warning}</p>}
      {evidenceIds(item).length > 0 && <p className="nx-plan-source">参考来源：{evidenceIds(item).join('、')}（核验状态见下方资料依据）</p>}
    </div></li>)}</ol>
    <footer><span>{superseded ? '这版方案已被后面的新方案取代：下面的按钮不再加入任务，需要哪一项请从最新那张卡片加入。' : allAdded ? '这些方案项都已登记为任务，重复点击不会再加一条。' : '用药须核对当地登记标签，不以生成结果代替现场判断。'}</span>{onAdd && items.length > 0 && !superseded && <button className="nx-button is-small" disabled={disabled} onClick={onAdd}><CalendarPlus size={15} />{allAdded ? '已加入任务' : addedCount > 0 ? `加入剩余 ${items.length - addedCount} 项` : '加入任务'}</button>}{superseded && <span className="nx-badge is-cancelled">已过期</span>}</footer>
  </section>;
}
export function RiskCard({ risk }: { risk: RiskArgs }) {
  // Never infer low risk when tool output is missing or unparsable.
  return <section className="nx-risk"><header><TriangleAlert size={17} /><b>农情数据筛查</b></header><MiniMd text={risk.result || '未收到有效判读结果，无法确定风险。'} /><p className="nx-fine nx-muted">仅对所提供的数据做规则筛查，不是现场诊断或官方预警。</p></section>;
}
