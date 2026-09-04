'use client';

import { useState } from 'react';
import { CircleHelp } from 'lucide-react';
import type { ClarifyArgs } from '@/lib/types';

/** 结构化确认卡：模型生成待确认项，用户点选即补充，避免大段文字追问 */
export function ClarifyCard({ clarify, onPick }: {
  clarify: ClarifyArgs;
  onPick: (question: string, option: string) => void;
}) {
  const [picked, setPicked] = useState<Record<number, boolean>>({});
  const items = (clarify.items ?? []).slice(0, 3);

  const handlePick = (index: number, question: string, option: string) => {
    if (picked[index]) return;
    setPicked((prev) => ({ ...prev, [index]: true }));
    onPick(question, option);
  };

  return (
    <div className="clarify-card">
      <div className="clarify-card-head">
        <span className="clarify-tag"><CircleHelp size={14} />确认几件事</span>
        {clarify.intro && <p>{clarify.intro}</p>}
      </div>
      <div className="clarify-items">
        {items.map((item, i) => (
          <div key={i} className="clarify-item">
            <b className="clarify-question">{i + 1}. {item.question}</b>
            {item.hint && <p className="clarify-hint">{item.hint}</p>}
            {item.options && item.options.length > 0 && (
              <div className="clarify-options">
                {(item.options ?? []).slice(0, 4).map((opt) => (
                  <button
                    key={opt}
                    className={`clarify-option ${picked[i] ? 'clarify-option-picked' : ''}`}
                    onClick={() => handlePick(i, item.question ?? '', opt)}
                    disabled={picked[i]}
                  >
                    {picked[i] ? '已选 ✓ ' : ''}{opt}
                  </button>
                ))}
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
