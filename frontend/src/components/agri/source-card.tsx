import { BookMarked, ExternalLink } from 'lucide-react';
import type { KnowledgeSourceCard } from '@/types';

/** 资料依据卡：只展示后端实际检索命中的来源，模型无法伪造。 */
export function SourceCard({ sources }: { sources: KnowledgeSourceCard[] }) {
  if (!sources.length) return null;
  return <section className="nx-sources">
    <header><BookMarked size={17} /><b>资料依据</b><span>{sources.length} 条</span></header>
    <ol>
      {sources.map(source => <li key={source.id}>
        <div className="nx-source-head">
          <b>{source.title}</b>
          <span className={`nx-badge ${source.status === 'verified' ? 'is-verified' : 'is-draft'}`}>
            {source.status === 'verified' ? '已核验原文' : '本地草稿 · 未核验'}
          </span>
        </div>
        <p className="nx-source-meta">{[
          source.institution,
          source.publishedAt,
          source.region ? `适用地区：${source.region}` : null,
          source.crop ? `适用作物：${source.crop}` : null,
          source.growthStage ? `生育期：${source.growthStage}` : null,
        ].filter(Boolean).join(' · ')}</p>        {source.heading && <p className="nx-source-locator">定位：{source.heading}</p>}
        {source.excerpt && <p className="nx-source-excerpt">{source.excerpt}</p>}
        {source.url
          ? <a className="nx-source-link" href={source.url} target="_blank" rel="noreferrer">查看原文<ExternalLink size={13} /></a>
          : <span className="nx-source-nolink">无原文链接 · 未核验草稿，不能作为官方依据</span>}
      </li>)}
    </ol>
    <p className="nx-source-foot">来源ID：{sources.map(source => source.id).join('、')} · 引用只在本次检索命中的范围内</p>
  </section>;
}
