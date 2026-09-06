import { CalendarPlus, ClipboardList, TriangleAlert } from 'lucide-react';
import type { PlanArgs, RiskArgs } from '@/types';
import { MiniMd } from '@/components/ui/mini-md';
export function PlanCard({ plan, onAdd, disabled }: { plan: PlanArgs; onAdd?: () => void; disabled?: boolean }) {
  return <section className="nx-plan"><header><ClipboardList size={18} /><div><b>{plan.title || '农事建议'}</b><span>AI 生成 · 确认后再执行</span></div></header>
    {plan.summary && <p>{plan.summary}</p>}
    <ol>{(plan.items ?? []).map((item, i) => <li key={i}><span className="nx-plan-index">{String(i + 1).padStart(2, '0')}</span><div><h4>{item.task}</h4>{item.date && <span className="nx-badge">{item.date}</span>}
      {item.method && <p><b>操作</b>{item.method}</p>}{item.dosage && <p><b>用量建议</b>{item.dosage}</p>}{item.condition && <p><b>执行条件</b>{item.condition}</p>}{item.review && <p><b>复查</b>{item.review}</p>}{item.warning && <p className="nx-warning">{item.warning}</p>}
      {!!item.evidence?.length && <p className="nx-plan-source">参考条目（来源待核验）：{item.evidence.join('、')}</p>}
    </div></li>)}</ol>
    <footer><span>用药须核对当地登记标签，不以生成结果代替现场判断。</span>{onAdd && !!plan.items?.length && <button className="nx-button is-small" disabled={disabled} onClick={onAdd}><CalendarPlus size={15} />加入任务</button>}</footer>
  </section>;
}
export function RiskCard({ risk }: { risk: RiskArgs }) {
  // Never infer low risk when tool output is missing or unparsable.
  return <section className="nx-risk"><header><TriangleAlert size={17} /><b>农情数据筛查</b></header><MiniMd text={risk.result || '未收到有效判读结果，无法确定风险。'} /><p className="nx-fine nx-muted">仅对所提供的数据做规则筛查，不是现场诊断或官方预警。</p></section>;
}
