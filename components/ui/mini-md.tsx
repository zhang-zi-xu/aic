'use client';

// 轻量 Markdown 渲染（安全：全 React 节点，不用 dangerouslySetInnerHTML）
// 支持：**加粗** `代码` - 列表 - 数字列表 > 引用 - 段落与换行

import type { ReactNode } from 'react';

function inline(text: string, keyPrefix: string): ReactNode[] {
  const parts = text.split(/(\*\*[^*]+\*\*|`[^`]+`)/g);
  return parts.map((part, i) => {
    if (part.startsWith('**') && part.endsWith('**') && part.length > 4) {
      return <strong key={`${keyPrefix}-b${i}`}>{part.slice(2, -2)}</strong>;
    }
    if (part.startsWith('`') && part.endsWith('`') && part.length > 2) {
      return <code key={`${keyPrefix}-c${i}`}>{part.slice(1, -1)}</code>;
    }
    return part;
  });
}

export function MiniMd({ text }: { text: string }) {
  const blocks: ReactNode[] = [];
  const lines = text.split('\n');
  let list: string[] = [];
  let quote: string[] = [];
  let para: string[] = [];

  const flushList = () => {
    if (list.length) {
      const items = list.slice();
      blocks.push(<ul key={`ul-${blocks.length}`}>{items.map((l, i) => <li key={i}>{inline(l, `ul${blocks.length}${i}`)}</li>)}</ul>);
      list = [];
    }
  };
  const flushQuote = () => {
    if (quote.length) {
      const items = quote.slice();
      blocks.push(<blockquote key={`bq-${blocks.length}`}>{items.map((l, i) => <p key={i}>{inline(l, `bq${blocks.length}${i}`)}</p>)}</blockquote>);
      quote = [];
    }
  };
  const flushPara = () => {
    if (para.length) {
      const items = para.slice();
      blocks.push(<p key={`p-${blocks.length}`}>{inline(items.join('\n'), `p${blocks.length}`)}</p>);
      para = [];
    }
  };

  for (const raw of lines) {
    const line = raw.trimEnd();
    if (/^[-•]\s+/.test(line)) { flushPara(); flushQuote(); list.push(line.replace(/^[-•]\s+/, '')); }
    else if (/^\d+[.)]\s+/.test(line)) { flushPara(); flushQuote(); list.push(line.replace(/^\d+[.)]\s+/, '')); }
    else if (line.startsWith('>')) { flushPara(); flushList(); quote.push(line.replace(/^>\s?/, '')); }
    else if (!line.trim()) { flushList(); flushQuote(); flushPara(); }
    else { flushList(); flushQuote(); para.push(line); }
  }
  flushList();
  flushQuote();
  flushPara();

  return <div className="mini-md">{blocks}</div>;
}
