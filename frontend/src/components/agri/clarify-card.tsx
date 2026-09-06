import { CircleHelp } from 'lucide-react';
import type { ClarifyArgs } from '@/types';
export function ClarifyCard({ clarify, onPick, disabled }: { clarify: ClarifyArgs; onPick: (question: string, option: string) => void; disabled?: boolean }) {
  return <section className="nx-clarify"><header><CircleHelp size={16} /><b>补充一点情况</b></header>{clarify.intro && <p>{clarify.intro}</p>}{(clarify.items ?? []).slice(0, 3).map((item, i) => <div key={i}><h4>{item.question}</h4>{item.hint && <p className="nx-muted">{item.hint}</p>}<div className="nx-clarify-options">{(item.options ?? []).slice(0, 5).map((opt, j) => <button key={j} disabled={disabled} onClick={() => onPick(item.question ?? '', opt)}>{opt}</button>)}</div></div>)}<p className="nx-fine nx-muted">点击选项填入输入框，可补充说明后发送。</p></section>;
}
