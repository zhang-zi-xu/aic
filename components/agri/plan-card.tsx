'use client';

import { BookOpen, FileText, TriangleAlert } from 'lucide-react';
import type { PlanArgs, RiskArgs } from '@/lib/types';
import { KB_ENTRIES } from '@/lib/kb/data';

function evidenceTitle(id: string) {
  const entry = KB_ENTRIES.find((e) => e.id === id);
  return entry ? `${entry.crop}·${entry.topic}｜${entry.title}` : id;
}

export function PlanCard({ plan }: { plan: PlanArgs }) {
  const items = (plan.items ?? []).slice(0, 14);
  return (
    <div className="plan-card">
      <div className="plan-card-head">
        <span className="plan-seal">处方</span>
        <div className="plan-title-wrap">
          <b>{plan.title || '农事处方单'}</b>
          <span>{plan.fieldName ? `${plan.fieldName} · ` : ''}{plan.crop ?? ''}{plan.fieldName || plan.crop ? '' : ' · 农心生成'}</span>
        </div>
      </div>
      {plan.summary && <p className="plan-summary">{plan.summary}</p>}
      <ol className="plan-items">
        {items.map((item, i) => (
          <li key={i} className="plan-item">
            <div className="plan-item-head">
              <span className="plan-index">{String(i + 1).padStart(2, '0')}</span>
              <div className="plan-item-title">
                <b>{item.task}</b>
                {item.date && <em>{item.date}</em>}
              </div>
            </div>
            <div className="plan-item-body">
              {item.dosage && <p><span>用量</span>{item.dosage}</p>}
              {item.method && <p><span>方法</span>{item.method}</p>}
              {item.condition && <p><span>条件</span>{item.condition}</p>}
              {item.review && <p className="plan-review"><span>复查</span>{item.review}</p>}
              {item.warning && <p className="plan-warning"><TriangleAlert size={13} />{item.warning}</p>}
              {item.evidence && item.evidence.length > 0 && (
                <div className="plan-evidence">
                  <span className="plan-evidence-label"><BookOpen size={12} />依据</span>
                  {item.evidence.slice(0, 5).map((ev, j) => <code key={j}>{evidenceTitle(ev)}</code>)}
                </div>
              )}
            </div>
          </li>
        ))}
      </ol>
      <p className="plan-foot">本处方基于公开农技资料归纳，药剂用量以当地登记标签与植保部门意见为准。</p>
    </div>
  );
}

export function RiskCard({ risk }: { risk: RiskArgs }) {
  const report = parseRiskResult(risk.result);
  const levelClass = (level: string) => level === '高' ? 'risk-high' : level === '中' ? 'risk-mid' : 'risk-low';
  return (
    <div className="risk-card">
      <div className="risk-card-head">
        <span className="risk-tag">判读</span>
        <b>农情数据风险判读</b>
        {report && <span className={`risk-level ${levelClass(report.overall)}`}>{report.overall}风险</span>}
      </div>
      {report && <p className="risk-summary">{report.summary}</p>}
      {report ? (
        <ul className="risk-items">
          {report.items.map((item, i) => (
            <li key={i} className={`risk-item ${levelClass(item.level)}`}>
              <div className="risk-item-head">
                <span className="risk-item-level">{item.level}</span>
                <b>{item.title}</b>
              </div>
              <p className="risk-reason"><span>依据</span>{item.reason}</p>
              <p className="risk-suggestion"><span>建议</span>{item.suggestion}</p>
              <code className="risk-rule">规则 {item.rule}</code>
            </li>
          ))}
        </ul>
      ) : (
        <p className="risk-raw">{risk.result ?? risk.data ?? '未返回判定结果'}</p>
      )}
      <p className="risk-foot">这是基于你所附数据的规则筛查，不等同于现场诊断或官方预警。</p>
    </div>
  );
}

function parseRiskResult(result?: string) {
  if (!result) return null;
  try {
    // 服务端 riskReportText 输出：首行「风险判定：…」，随后「【高风险】标题/依据：/建议：/规则：」
    const lines = result.split('\n').map((l) => l.trim()).filter(Boolean);
    const head = lines[0] ?? '';
    const overallMatch = head.match(/总体(高|中|低)风险/);
    const overall = (overallMatch?.[1] ?? '低') as '高' | '中' | '低';
    const items: Array<{ level: string; title: string; reason: string; suggestion: string; rule: string }> = [];
    let current: { level: string; title: string; reason: string; suggestion: string; rule: string } | null = null;
    for (const line of lines.slice(1)) {
      const itemMatch = line.match(/^【(高|中|低)风险】(.*)$/);
      if (itemMatch) {
        if (current) items.push(current);
        current = { level: itemMatch[1], title: itemMatch[2], reason: '', suggestion: '', rule: '' };
        continue;
      }
      if (!current) continue;
      if (line.startsWith('依据：')) current.reason = line.slice(3);
      else if (line.startsWith('建议：')) current.suggestion = line.slice(3);
      else if (line.startsWith('规则：')) current.rule = line.slice(3);
    }
    if (current) items.push(current);
    return { overall, summary: head.replace(/^风险判定：总体.{1,3}风险[。.]?/, ''), items };
  } catch {
    return null;
  }
}

export function EvidenceAttach({ evidence }: { evidence: string[] }) {
  return (
    <div className="evidence-attach">
      <FileText size={12} />
      {evidence.map((ev) => <code key={ev}>{evidenceTitle(ev)}</code>)}
    </div>
  );
}
