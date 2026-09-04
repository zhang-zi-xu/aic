'use client';

import { useState } from 'react';
import { ChevronsLeft, Plus, Trash2, Sprout } from 'lucide-react';
import type { FieldProfile } from '@/lib/types';

const CROP_OPTIONS = ['水稻', '小麦', '玉米', '油菜', '蔬菜', '果树'];

export function FieldSidebar({
  fields,
  activeId,
  onSelect,
  onCreate,
  onDelete,
  onCollapse,
}: {
  fields: FieldProfile[];
  activeId: string | null;
  onSelect: (id: string) => void;
  onCreate: (field: FieldProfile) => void;
  onDelete: (id: string) => void;
  onCollapse?: () => void;
}) {
  const [creating, setCreating] = useState(false);
  const [draft, setDraft] = useState({
    name: '', crop: '水稻', variety: '',
    sowY: '', sowM: '', sowD: '', areaMu: '', notes: '',
  });

  const startCreate = () => {
    setDraft({ name: '', crop: '水稻', variety: '', sowY: '', sowM: '', sowD: '', areaMu: '', notes: '' });
    setCreating(true);
  };

  // 年/月/日 → YYYY-MM-DD（带合法性校验）
  const sowDate = (() => {
    const y = draft.sowY.trim();
    const m = draft.sowM.trim();
    const d = draft.sowD.trim();
    if (!y || !m || !d) return null;
    const year = Number(y);
    const month = Number(m);
    const day = Number(d);
    if (!Number.isInteger(year) || year < 1900 || year > 2100) return null;
    if (!Number.isInteger(month) || month < 1 || month > 12 || !Number.isInteger(day) || day < 1) return null;
    const date = new Date(year, month - 1, day);
    const valid = date.getFullYear() === year && date.getMonth() === month - 1 && date.getDate() === day;
    if (!valid) return null;
    return `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
  })();

  const submit = () => {
    if (!draft.name.trim() || !sowDate) return;
    const parsedArea = Number(draft.areaMu);
    const field: FieldProfile = {
      id: `f-${Date.now()}`,
      name: draft.name.trim(),
      crop: draft.crop,
      variety: draft.variety.trim() || undefined,
      sowDate,
      areaMu: draft.areaMu && Number.isFinite(parsedArea) && parsedArea > 0 ? parsedArea : undefined,
      notes: draft.notes.trim() || undefined,
      records: [],
    };
    onCreate(field);
    setCreating(false);
  };

  const pad = (v: string) => v.trim().replace(/[^0-9]/g, '').slice(0, 2);

  return (
    <aside className="field-sidebar">
      <div className="field-sidebar-head">
        <span>我的田块</span>
        <span className="field-head-actions">
          <button className="field-add" onClick={startCreate} aria-label="新建田块"><Plus size={16} /></button>
          {onCollapse && <button className="field-collapse" onClick={onCollapse} aria-label="收起田块栏"><ChevronsLeft size={15} /></button>}
        </span>
      </div>

      {creating ? (
        <div className="field-create">
          <label>田块名称<input value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} placeholder="如：东头大田" /></label>

          <div className="field-create-label">作物<span className="field-create-hint">点击选择</span></div>
          <div className="crop-chips">
            {CROP_OPTIONS.map((crop) => (
              <button
                key={crop}
                type="button"
                className={`crop-chip ${draft.crop === crop ? 'crop-chip-active' : ''}`}
                onClick={() => setDraft({ ...draft, crop })}
              >
                {crop}
              </button>
            ))}
          </div>

          <div className="field-create-label">播期（年月日）</div>
          <div className="sow-date-row">
            <input className="sow-input" inputMode="numeric" value={draft.sowY} onChange={(e) => setDraft({ ...draft, sowY: e.target.value.replace(/[^0-9]/g, '').slice(0, 4) })} placeholder="2025" aria-label="年" />
            <span className="sow-sep">年</span>
            <input className="sow-input" inputMode="numeric" value={draft.sowM} onChange={(e) => setDraft({ ...draft, sowM: pad(e.target.value) })} placeholder="6" aria-label="月" />
            <span className="sow-sep">月</span>
            <input className="sow-input" inputMode="numeric" value={draft.sowD} onChange={(e) => setDraft({ ...draft, sowD: pad(e.target.value) })} placeholder="20" aria-label="日" />
            <span className="sow-sep">日</span>
          </div>

          <div className="field-create-row">
            <label>品种<input value={draft.variety} onChange={(e) => setDraft({ ...draft, variety: e.target.value })} placeholder="可选" /></label>
            <label>面积（亩）<input type="number" min="0" value={draft.areaMu} onChange={(e) => setDraft({ ...draft, areaMu: e.target.value })} placeholder="可选" /></label>
          </div>
          <label>备注<input value={draft.notes} onChange={(e) => setDraft({ ...draft, notes: e.target.value })} placeholder="可选，如：稻虾共作" /></label>
          <div className="field-create-actions">
            <button onClick={submit} disabled={!draft.name.trim() || !sowDate}>建档</button>
            <button onClick={() => setCreating(false)}>取消</button>
          </div>
        </div>
      ) : (
        <div className="field-list">
          {fields.length === 0 && (
            <div className="field-empty">
              <Sprout size={28} />
              <p>还没有田块档案</p>
              <button onClick={startCreate}>建立第一个田块</button>
            </div>
          )}
          {fields.map((field) => (
            <div key={field.id} className={`field-item ${field.id === activeId ? 'field-item-active' : ''}`}>
              <button className="field-item-main" onClick={() => onSelect(field.id)}>
                <span className="field-item-name">{field.name}</span>
                <span className="field-item-meta">{field.crop} · 播期 {field.sowDate}</span>
              </button>
              <button className="field-item-del" onClick={() => onDelete(field.id)} aria-label="删除田块"><Trash2 size={14} /></button>
            </div>
          ))}
        </div>
      )}
    </aside>
  );
}
