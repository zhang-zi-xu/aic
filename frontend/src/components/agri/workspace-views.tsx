import { useMemo, useState, type FormEvent, type ReactNode } from 'react';
import { ArrowRight, BookOpen, CalendarDays, Check, CheckCheck, ClipboardList, Leaf, MessageSquare, Pencil, Plus, Search, Sprout, Trash2 } from 'lucide-react';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { KB_ENTRIES, type KbEntry } from '@/lib/kb-data';
import type { FieldProfile } from '@/types';

export type FarmTask = {
  id: string;
  title: string;
  date: string;
  done: boolean;
  createdAt: string;
  fieldId?: string | null;
  fieldName?: string | null;
  condition?: string | null;
  method?: string | null;
  review?: string | null;
  note?: string | null;
  sourceMessageId?: string | null;
};

export type Task = FarmTask;
export type KnowledgeEntry = KbEntry;
type Mutation<T> = (value: T) => Promise<unknown>;

export type FieldsViewProps = {
  fields: FieldProfile[];
  onSave: Mutation<FieldProfile>;
  onDelete: Mutation<string>;
  onRecord: (id: string, note: string) => Promise<unknown>;
  onAsk: (fieldId: string) => void;
};

export type TasksViewProps = {
  tasks: FarmTask[];
  fields: FieldProfile[];
  onSave: Mutation<FarmTask>;
  onDelete: Mutation<string>;
};

export type KnowledgeViewProps = {
  entries?: KbEntry[];
  onAsk: (question: string) => void;
};

const CROPS = ['水稻', '小麦', '玉米', '油菜', '蔬菜', '果树', '其他'];
const localDate = () => {
  const now = new Date();
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
};
const newId = (prefix: string) => `${prefix}-${crypto.randomUUID()}`;
const errorMessage = (error: unknown) => error instanceof Error ? error.message : '操作未完成，请重试。';
const formatDate = (date: string) => date ? date.replaceAll('-', '.') : '日期待定';

function EmptyState({ icon, title, children, action }: { icon: ReactNode; title: string; children: ReactNode; action?: ReactNode }) {
  return <div className="nx-empty">
    <span className="nx-empty-icon" aria-hidden="true">{icon}</span>
    <h2>{title}</h2>
    <p>{children}</p>
    {action}
  </div>;
}

function ConfirmDelete({ open, title, description, onClose, onConfirm }: {
  open: boolean;
  title: string;
  description: string;
  onClose: () => void;
  onConfirm: () => Promise<unknown>;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const remove = async () => {
    setError('');
    setBusy(true);
    try { await onConfirm(); onClose(); }
    catch (cause) { setError(errorMessage(cause)); }
    finally { setBusy(false); }
  };
  return <Dialog open={open} onOpenChange={(value) => { if (!value && !busy) { setError(''); onClose(); } }}>
    <DialogContent className="nx-dialog nx-dialog-compact" showCloseButton={!busy}>
      <DialogHeader><DialogTitle>{title}</DialogTitle><DialogDescription>{description}</DialogDescription></DialogHeader>
      {error && <p className="nx-error" role="alert">{error}</p>}
      <div className="nx-dialog-actions">
        <button className="nx-button" disabled={busy} onClick={onClose}>取消</button>
        <button className="nx-button is-danger" disabled={busy} onClick={() => void remove()}>{busy ? '正在删除…' : '确认删除'}</button>
      </div>
    </DialogContent>
  </Dialog>;
}

type FieldDraft = { name: string; crop: string; variety: string; sowDate: string; areaMu: string; notes: string };
const fieldDraft = (field?: FieldProfile): FieldDraft => ({
  name: field?.name ?? '', crop: field?.crop ?? '', variety: field?.variety ?? '',
  sowDate: field?.sowDate ?? '', areaMu: field?.areaMu == null ? '' : String(field.areaMu), notes: field?.notes ?? '',
});

export function FieldsView({ fields, onSave, onDelete, onRecord, onAsk }: FieldsViewProps) {
  const [query, setQuery] = useState('');
  const [editor, setEditor] = useState<{ existing?: FieldProfile; draft: FieldDraft } | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<FieldProfile | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [status, setStatus] = useState('');
  const [recordNote, setRecordNote] = useState('');
  const [recordBusy, setRecordBusy] = useState(false);
  const [recordError, setRecordError] = useState('');
  const selected = fields.find((field) => field.id === selectedId);
  const visible = fields.filter((field) => `${field.name} ${field.crop} ${field.variety ?? ''}`.toLowerCase().includes(query.trim().toLowerCase()));
  const area = fields.reduce((total, field) => total + (field.areaMu ?? 0), 0);
  const measuredFields = fields.filter((field) => field.areaMu != null).length;

  const edit = (field?: FieldProfile) => {
    setError('');
    setEditor({ existing: field, draft: fieldDraft(field) });
  };
  const updateDraft = (key: keyof FieldDraft, value: string) => setEditor((current) => current && ({ ...current, draft: { ...current.draft, [key]: value } }));
  const save = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!editor || busy) return;
    const draft = editor.draft;
    if (!draft.name.trim() || !draft.crop.trim() || !draft.sowDate) { setError('请填写田块名称、作物和播种日期。'); return; }
    const areaMu = draft.areaMu === '' ? undefined : Number(draft.areaMu);
    if (areaMu !== undefined && (!Number.isFinite(areaMu) || areaMu <= 0)) { setError('面积需要填写大于 0 的数字。'); return; }
    setBusy(true); setError('');
    try {
      await onSave({ ...editor.existing, id: editor.existing?.id ?? newId('f'), name: draft.name.trim(), crop: draft.crop,
        variety: draft.variety.trim() || undefined, sowDate: draft.sowDate, areaMu, notes: draft.notes.trim() || undefined,
        records: editor.existing?.records ?? [] });
      setEditor(null); setStatus(editor.existing ? '田块信息已更新。' : '田块已建立。');
    } catch (cause) { setError(errorMessage(cause)); }
    finally { setBusy(false); }
  };
  const saveRecord = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!selected || !recordNote.trim() || recordBusy) return;
    setRecordBusy(true); setRecordError('');
    try { await onRecord(selected.id, recordNote.trim()); setRecordNote(''); setStatus('种植记录已保存。'); }
    catch (cause) { setRecordError(errorMessage(cause)); }
    finally { setRecordBusy(false); }
  };

  return <section className="nx-view nx-fields-view" aria-labelledby="nx-fields-title">
    <header className="nx-view-head">
      <div><p className="nx-eyebrow">FIELD NOTEBOOK / 田间档案</p><h1 id="nx-fields-title">每一块田，都有自己的故事。</h1><p className="nx-muted">把作物、播期和日常观察记在一起，农事讨论更有依据。</p></div>
      <button className="nx-button is-primary" onClick={() => edit()}><Plus size={18} />新建田块</button>
    </header>
    <div className="nx-view-summary">
      <span><strong>{fields.length}</strong> 块田块</span>
      <span><strong>{measuredFields ? area.toLocaleString('zh-CN', { maximumFractionDigits: 2 }) : '—'}</strong> 亩已登记面积</span>
      <span className="nx-muted">手动记录 · 尚未接入田间监测</span>
    </div>
    <p className="nx-status" role="status">{status}</p>
    {fields.length === 0 ? <EmptyState icon={<Sprout size={30} />} title="从第一块田开始" action={<button className="nx-button is-primary" onClick={() => edit()}><Plus size={17} />建立田块档案</button>}>记录种什么、何时播种，以及这块田值得记住的事。</EmptyState> : <>
      <div className="nx-toolbar"><label className="nx-search"><Search size={18} aria-hidden="true" /><input aria-label="搜索田块" placeholder="搜索田块、作物或品种" value={query} onChange={(event) => setQuery(event.target.value)} /></label><span className="nx-muted">{visible.length} 块田块</span></div>
      {visible.length === 0 ? <EmptyState icon={<Search size={26} />} title="没有找到这块田" action={<button className="nx-button" onClick={() => setQuery('')}>清除搜索</button>}>试试田块名称或作物名称。</EmptyState> : <div className="nx-grid nx-field-grid">
        {visible.map((field) => <article className="nx-card nx-field-card" key={field.id}>
          <div className={`nx-field-cover nx-crop-${CROPS.indexOf(field.crop) % 3}`} aria-hidden="true"><span className="nx-field-contour" /><Sprout size={36} /><span>{field.crop}</span></div>
          <div className="nx-card-body">
            <div className="nx-card-topline"><span className="nx-badge">{field.crop}</span><div className="nx-inline-actions"><button className="nx-icon-button" aria-label={`编辑${field.name}`} onClick={() => edit(field)}><Pencil size={15} /></button><button className="nx-icon-button" aria-label={`删除${field.name}`} onClick={() => setDeleteTarget(field)}><Trash2 size={15} /></button></div></div>
            <button className="nx-card-title-button" onClick={() => { setSelectedId(field.id); setRecordNote(''); setRecordError(''); }}><h2>{field.name}</h2><ArrowRight size={18} /></button>
            <p className="nx-muted">{field.variety || '品种未填写'}<span aria-hidden="true"> · </span>{field.areaMu == null ? '面积未填写' : `${field.areaMu} 亩`}</p>
            <div className="nx-field-meta"><CalendarDays size={15} /><span>{formatDate(field.sowDate)} 播种</span></div>
            <div className="nx-card-footer"><span>{field.records?.length ?? 0} 条种植记录</span><button className="nx-text-button" onClick={() => onAsk(field.id)}>聊聊这块田 <ArrowRight size={14} /></button></div>
          </div>
        </article>)}
      </div>}
    </>}

    <Dialog open={editor !== null} onOpenChange={(open) => { if (!open && !busy) setEditor(null); }}>
      <DialogContent className="nx-dialog" showCloseButton={!busy}>
        <DialogHeader><DialogTitle>{editor?.existing ? '编辑田块' : '建立一块田的档案'}</DialogTitle><DialogDescription>带 * 的项目为必填，其余信息可以之后补充。</DialogDescription></DialogHeader>
        {editor && <form className="nx-form" onSubmit={(event) => void save(event)}>
          <label className="nx-form-field">田块名称 *<input autoFocus required maxLength={120} placeholder="例如：河边一号田" value={editor.draft.name} onChange={(event) => updateDraft('name', event.target.value)} /></label>
          <div className="nx-form-grid"><label className="nx-form-field">作物 *<select required value={editor.draft.crop} onChange={(event) => updateDraft('crop', event.target.value)}><option value="" disabled>请选择作物</option>{Array.from(new Set([...CROPS, editor.draft.crop])).filter(Boolean).map((crop) => <option key={crop}>{crop}</option>)}</select></label><label className="nx-form-field">品种<input maxLength={120} placeholder="选填" value={editor.draft.variety} onChange={(event) => updateDraft('variety', event.target.value)} /></label></div>
          <div className="nx-form-grid"><label className="nx-form-field">播种日期 *<input type="date" required min="1900-01-01" max="2100-12-31" value={editor.draft.sowDate} onChange={(event) => updateDraft('sowDate', event.target.value)} /></label><label className="nx-form-field">面积（亩）<input type="number" min="0.001" step="any" placeholder="选填" value={editor.draft.areaMu} onChange={(event) => updateDraft('areaMu', event.target.value)} /></label></div>
          <label className="nx-form-field">田块备注<textarea rows={3} maxLength={8000} placeholder="土质、灌溉方式，或其他值得留意的情况" value={editor.draft.notes} onChange={(event) => updateDraft('notes', event.target.value)} /></label>
          {error && <p className="nx-error" role="alert">{error}</p>}
          <div className="nx-dialog-actions"><button type="button" className="nx-button" disabled={busy} onClick={() => setEditor(null)}>取消</button><button type="submit" className="nx-button is-primary" disabled={busy}>{busy ? '正在保存…' : '保存田块'}</button></div>
        </form>}
      </DialogContent>
    </Dialog>

    <Dialog open={!!selected} onOpenChange={(open) => { if (!open && !recordBusy) setSelectedId(null); }}>
      <DialogContent className="nx-dialog nx-dialog-wide" showCloseButton={!recordBusy}>
        {selected && <>
          <DialogHeader><DialogTitle>{selected.name}</DialogTitle><DialogDescription>{selected.crop} · {selected.variety || '品种未填'} · {formatDate(selected.sowDate)} 播种</DialogDescription></DialogHeader>
          <div className="nx-field-detail">
            <div className="nx-detail-facts"><span>面积 <strong>{selected.areaMu == null ? '未填写' : `${selected.areaMu} 亩`}</strong></span><span>数据来源 <strong>手动记录</strong></span></div>
            {selected.notes && <p className="nx-note">{selected.notes}</p>}
            <div className="nx-section-heading"><h3>种植记录</h3><span className="nx-muted">{selected.records?.length ?? 0} 条</span></div>
            {selected.records?.length ? <ol className="nx-record-list">{[...selected.records].reverse().map((record, index) => <li key={`${record.date}-${index}`}><time>{formatDate(record.date)}</time><p>{record.note}</p></li>)}</ol> : <p className="nx-inline-empty">还没有记录。把今天看到的长势、做过的农活记下来。</p>}
            <form className="nx-form" onSubmit={(event) => void saveRecord(event)}><label className="nx-form-field">添加今天的观察<textarea required rows={3} maxLength={8000} placeholder="例如：今天查看了排水沟，南侧田角有少量积水。" value={recordNote} onChange={(event) => setRecordNote(event.target.value)} /></label>{recordError && <p className="nx-error" role="alert">{recordError}</p>}<button className="nx-button is-primary" disabled={recordBusy || !recordNote.trim()} type="submit"><Plus size={16} />{recordBusy ? '正在保存…' : '保存记录'}</button></form>
          </div>
          <div className="nx-dialog-actions"><button className="nx-button" disabled={recordBusy} onClick={() => { setSelectedId(null); edit(selected); }}><Pencil size={16} />编辑田块</button><button className="nx-button is-primary" disabled={recordBusy} onClick={() => { setSelectedId(null); onAsk(selected.id); }}><MessageSquare size={16} />聊聊这块田</button></div>
        </>}
      </DialogContent>
    </Dialog>
    <ConfirmDelete open={!!deleteTarget} title={`删除“${deleteTarget?.name ?? ''}”？`} description="该田块的档案与种植记录将被删除，删除后无法恢复。" onClose={() => setDeleteTarget(null)} onConfirm={async () => { if (deleteTarget) { await onDelete(deleteTarget.id); if (selectedId === deleteTarget.id) setSelectedId(null); setStatus('田块已删除。'); } }} />
  </section>;
}

type TaskFilter = 'pending' | 'today' | 'overdue' | 'done' | 'all';
const TASK_FILTERS: Array<{ id: TaskFilter; label: string }> = [{ id: 'pending', label: '待完成' }, { id: 'today', label: '今天' }, { id: 'overdue', label: '已逾期' }, { id: 'done', label: '已完成' }, { id: 'all', label: '全部' }];

export function TasksView({ tasks, fields, onSave, onDelete }: TasksViewProps) {
  const [filter, setFilter] = useState<TaskFilter>('pending');
  const [fieldFilter, setFieldFilter] = useState('');
  const [query, setQuery] = useState('');
  const [editor, setEditor] = useState<FarmTask | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<FarmTask | null>(null);
  const [busy, setBusy] = useState(false);
  const [togglingIds, setTogglingIds] = useState<string[]>([]);
  const [error, setError] = useState('');
  const [listError, setListError] = useState('');
  const [status, setStatus] = useState('');
  const today = localDate();
  const pending = tasks.filter((task) => !task.done).length;
  const overdue = tasks.filter((task) => !task.done && !!task.date && task.date < today).length;
  const filtered = useMemo(() => tasks.filter((task) => {
    if (fieldFilter && task.fieldId !== fieldFilter) return false;
    if (filter === 'pending' && task.done || filter === 'done' && !task.done || filter === 'today' && (task.date !== today || task.done) || filter === 'overdue' && (!task.date || task.date >= today || task.done)) return false;
    return `${task.title} ${task.note ?? ''} ${task.fieldName ?? ''}`.toLowerCase().includes(query.trim().toLowerCase());
  }).sort((a, b) => Number(a.done) - Number(b.done) || a.date.localeCompare(b.date) || b.createdAt.localeCompare(a.createdAt)), [tasks, filter, fieldFilter, query, today]);
  const edit = (task?: FarmTask) => {
    setError(''); setEditor(task ? { ...task } : { id: newId('t'), title: '', date: today, done: false, createdAt: new Date().toISOString(), fieldId: '', fieldName: '', condition: '', method: '', review: '', note: '' });
  };
  const update = <K extends keyof FarmTask>(key: K, value: FarmTask[K]) => setEditor((current) => current && ({ ...current, [key]: value }));
  const save = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!editor || busy) return;
    if (!editor.title.trim()) { setError('请填写任务名称。'); return; }
    setBusy(true); setError('');
    try {
      const field = fields.find((item) => item.id === editor.fieldId);
      await onSave({ ...editor, title: editor.title.trim(), fieldId: field?.id ?? null, fieldName: field?.name ?? null,
        condition: editor.condition?.trim() || '', method: editor.method?.trim() || '', review: editor.review?.trim() || '', note: editor.note?.trim() || '' });
      setEditor(null); setStatus('任务已保存。');
    } catch (cause) { setError(errorMessage(cause)); }
    finally { setBusy(false); }
  };
  const toggle = async (task: FarmTask) => {
    if (togglingIds.includes(task.id)) return;
    setTogglingIds((ids) => [...ids, task.id]); setListError('');
    try { await onSave({ ...task, done: !task.done }); setStatus(task.done ? '任务已恢复为待完成。' : '任务已标记完成。'); }
    catch (cause) { setListError(errorMessage(cause)); }
    finally { setTogglingIds((ids) => ids.filter((id) => id !== task.id)); }
  };

  return <section className="nx-view nx-tasks-view" aria-labelledby="nx-tasks-title">
    <header className="nx-view-head"><div><p className="nx-eyebrow">FARM ROUTINE / 农事安排</p><h1 id="nx-tasks-title">把想做的事，落到每一天。</h1><p className="nx-muted">记下安排、执行条件与复查要点，完成一件，划去一件。</p></div><button className="nx-button is-primary" onClick={() => edit()}><Plus size={18} />新建任务</button></header>
    <div className="nx-view-summary"><span><strong>{pending}</strong> 项待完成</span><span><strong>{overdue}</strong> 项已逾期</span><span className="nx-muted">完成状态由你手动标记</span></div>
    <p className="nx-status" role="status">{status}</p>
    {listError && <p className="nx-error" role="alert">{listError}</p>}
    {tasks.length === 0 ? <EmptyState icon={<ClipboardList size={30} />} title="给下一件农事留个位置" action={<button className="nx-button is-primary" onClick={() => edit()}><Plus size={17} />记下第一件事</button>}>巡田、灌溉、复查，都可以从一条简单的计划开始。</EmptyState> : <>
      <div className="nx-toolbar nx-task-toolbar"><div className="nx-filter-tabs" aria-label="按任务状态筛选">{TASK_FILTERS.map((item) => <button key={item.id} className={filter === item.id ? 'is-active' : ''} aria-pressed={filter === item.id} onClick={() => setFilter(item.id)}>{item.label}</button>)}</div><select className="nx-filter-select" aria-label="按田块筛选任务" value={fieldFilter} onChange={(event) => setFieldFilter(event.target.value)}><option value="">全部田块</option>{fields.map((field) => <option key={field.id} value={field.id}>{field.name}</option>)}</select></div>
      <label className="nx-search nx-task-search"><Search size={18} aria-hidden="true" /><input aria-label="搜索任务" placeholder="搜索任务名称或备注" value={query} onChange={(event) => setQuery(event.target.value)} /></label>
      {filtered.length === 0 ? <EmptyState icon={filter === 'done' ? <CheckCheck size={28} /> : <ClipboardList size={28} />} title={filter === 'pending' && !query && !fieldFilter ? '当前任务都已完成' : '这里还没有任务'} action={<button className="nx-button" onClick={() => { setFilter('all'); setFieldFilter(''); setQuery(''); }}>查看全部任务</button>}>可以调整筛选条件，或新建一项农事安排。</EmptyState> : <div className="nx-task-list">
        {filtered.map((task) => {
          const pastDue = !task.done && !!task.date && task.date < today;
          const field = fields.find((item) => item.id === task.fieldId);
          return <article key={task.id} className={`nx-task-row ${task.done ? 'is-done' : ''}`}>
            <button className="nx-task-check" role="checkbox" aria-checked={task.done} aria-label={`${task.done ? '恢复待完成' : '标记完成'}：${task.title}`} disabled={togglingIds.includes(task.id)} onClick={() => void toggle(task)}>{task.done && <Check size={16} />}</button>
            <div className="nx-task-main"><button className="nx-task-title" onClick={() => edit(task)}>{task.title}</button><div className="nx-task-meta"><span className={pastDue ? 'is-overdue' : ''}><CalendarDays size={14} />{formatDate(task.date)}{pastDue ? ' · 已逾期' : task.date === today && !task.done ? ' · 今天' : ''}</span>{field?.name ? <span><Sprout size={14} />{field.name}</span> : task.fieldName ? <span><Sprout size={14} />{task.fieldName}（原关联田块）</span> : <span>未关联田块</span>}{task.sourceMessageId && <span>来自对话</span>}</div>{task.condition && <p className="nx-task-condition">执行条件：{task.condition}</p>}{task.note && <p className="nx-task-note">{task.note}</p>}</div>
            <div className="nx-inline-actions"><button className="nx-icon-button" aria-label={`编辑${task.title}`} onClick={() => edit(task)}><Pencil size={16} /></button><button className="nx-icon-button" disabled={togglingIds.includes(task.id)} aria-label={`删除${task.title}`} onClick={() => setDeleteTarget(task)}><Trash2 size={16} /></button></div>
          </article>;
        })}
      </div>}
    </>}
    <Dialog open={!!editor} onOpenChange={(open) => { if (!open && !busy) setEditor(null); }}>
      <DialogContent className="nx-dialog" showCloseButton={!busy}>
        <DialogHeader><DialogTitle>{editor && tasks.some((task) => task.id === editor.id) ? '编辑农事任务' : '记下一件农事'}</DialogTitle><DialogDescription>计划日期用于整理任务；执行前请再核对田间情况。</DialogDescription></DialogHeader>
        {editor && <form className="nx-form" onSubmit={(event) => void save(event)}>
          <label className="nx-form-field">任务名称 *<input autoFocus required maxLength={200} placeholder="例如：检查南侧田块排水沟" value={editor.title} onChange={(event) => update('title', event.target.value)} /></label>
          <div className="nx-form-grid"><label className="nx-form-field">计划日期（可留空待定）<input type="date" min="1900-01-01" max="2100-12-31" value={editor.date} onChange={(event) => update('date', event.target.value)} /></label><label className="nx-form-field">关联田块<select value={fields.some((field) => field.id === editor.fieldId) ? editor.fieldId ?? '' : ''} onChange={(event) => update('fieldId', event.target.value)}><option value="">不关联田块</option>{fields.map((field) => <option key={field.id} value={field.id}>{field.name}</option>)}</select></label></div>
          <label className="nx-form-field">执行条件<input maxLength={8000} placeholder="例如：雨停后、田间可以安全进入时" value={editor.condition ?? ''} onChange={(event) => update('condition', event.target.value)} /></label>
          <label className="nx-form-field">操作方法<textarea rows={2} maxLength={8000} placeholder="选填，记录具体怎么做" value={editor.method ?? ''} onChange={(event) => update('method', event.target.value)} /></label>
          <label className="nx-form-field">复查要点<input maxLength={8000} placeholder="选填，何时再看、观察什么" value={editor.review ?? ''} onChange={(event) => update('review', event.target.value)} /></label>
          <label className="nx-form-field">备注<textarea rows={2} maxLength={8000} placeholder="记录执行情况，或下次需要留意的事" value={editor.note ?? ''} onChange={(event) => update('note', event.target.value)} /></label>
          <label className="nx-checkbox-label"><input type="checkbox" checked={editor.done} onChange={(event) => update('done', event.target.checked)} />已完成这项任务</label>
          {error && <p className="nx-error" role="alert">{error}</p>}
          <div className="nx-dialog-actions"><button type="button" className="nx-button" disabled={busy} onClick={() => setEditor(null)}>取消</button><button type="submit" className="nx-button is-primary" disabled={busy}>{busy ? '正在保存…' : '保存任务'}</button></div>
        </form>}
      </DialogContent>
    </Dialog>
    <ConfirmDelete open={!!deleteTarget} title={`删除“${deleteTarget?.title ?? ''}”？`} description="这项任务和任务备注将被删除，删除后无法恢复。" onClose={() => setDeleteTarget(null)} onConfirm={async () => { if (deleteTarget) { await onDelete(deleteTarget.id); setStatus('任务已删除。'); } }} />
  </section>;
}

export function KnowledgeView({ entries = KB_ENTRIES, onAsk }: KnowledgeViewProps) {
  const [query, setQuery] = useState('');
  const [crop, setCrop] = useState('全部');
  const [topic, setTopic] = useState('全部');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const selected = entries.find((entry) => entry.id === selectedId);
  const crops = ['全部', ...new Set(entries.map((entry) => entry.crop))];
  const topics = ['全部', ...new Set(entries.map((entry) => entry.topic))];
  const visible = entries.filter((entry) => (crop === '全部' || entry.crop === crop) && (topic === '全部' || entry.topic === topic) && `${entry.title} ${entry.crop} ${entry.topic} ${entry.symptom} ${entry.keywords.join(' ')}`.toLowerCase().includes(query.trim().toLowerCase()));

  return <section className="nx-view nx-knowledge-view" aria-labelledby="nx-knowledge-title">
    <header className="nx-view-head"><div><p className="nx-eyebrow">GROWING LIBRARY / 农技资料</p><h1 id="nx-knowledge-title">好判断，从了解开始。</h1><p className="nx-muted">按作物和问题查阅资料，把不确定的地方带到对话里。</p></div><span className="nx-badge is-draft"><BookOpen size={15} />内置草稿 · 来源待核验</span></header>
    <div className="nx-source-note"><BookOpen size={18} aria-hidden="true" /><p>这些条目是内置参考草稿，尚未补齐可核验的原始出处与审核信息。涉及用药、用量和防治时机，请结合当地登记标签与农技指导确认。</p></div>
    <div className="nx-toolbar"><label className="nx-search"><Search size={18} aria-hidden="true" /><input aria-label="搜索农技资料" placeholder="搜索作物、症状或问题，如：水稻、病斑、排水" value={query} onChange={(event) => setQuery(event.target.value)} /></label><select className="nx-filter-select" aria-label="按资料主题筛选" value={topic} onChange={(event) => setTopic(event.target.value)}>{topics.map((item) => <option value={item} key={item}>{item === '全部' ? '全部主题' : item}</option>)}</select></div>
    <div className="nx-knowledge-filter"><div className="nx-filter-tabs" aria-label="按作物筛选资料">{crops.map((item) => <button key={item} aria-pressed={crop === item} className={crop === item ? 'is-active' : ''} onClick={() => setCrop(item)}>{item}</button>)}</div><span className="nx-muted">{visible.length} 篇参考草稿</span></div>
    {visible.length === 0 ? <EmptyState icon={<BookOpen size={28} />} title={entries.length ? '还没有匹配的资料' : '资料正在整理'} action={entries.length ? <button className="nx-button" onClick={() => { setQuery(''); setCrop('全部'); setTopic('全部'); }}>重置筛选</button> : undefined}>{entries.length ? '试试其他关键词，或切换作物和主题。' : '当前没有可浏览的条目。'}</EmptyState> : <div className="nx-grid nx-knowledge-grid">
      {visible.map((entry) => <button className="nx-card nx-knowledge-card" key={entry.id} onClick={() => setSelectedId(entry.id)}>
        <div className="nx-card-topline"><span className="nx-knowledge-icon" aria-hidden="true"><Leaf size={21} /></span><span className="nx-badge">{entry.crop} · {entry.topic}</span></div>
        <h2>{entry.title}</h2><p className="nx-knowledge-excerpt">{entry.symptom}</p><div className="nx-card-footer"><span>内置草稿 · 来源待核验</span><ArrowRight size={17} /></div>
      </button>)}
    </div>}
    <Dialog open={!!selected} onOpenChange={(open) => { if (!open) setSelectedId(null); }}>
      <DialogContent className="nx-dialog nx-dialog-wide nx-knowledge-dialog">
        {selected && <>
          <DialogHeader><span className="nx-badge is-draft">{selected.crop} · {selected.topic} · 内置草稿</span><DialogTitle>{selected.title}</DialogTitle><DialogDescription>来源待核验 · 以下为参考资料原有内容，尚未经专业审核。</DialogDescription></DialogHeader>
          <article className="nx-knowledge-detail">
            <section><h3>症状与识别</h3><p>{selected.symptom}</p></section>
            <section><h3>判断要点</h3><p>{selected.diagnosis}</p></section>
            <section><h3>参考建议 · 执行前需核验</h3><ul>{selected.advice.map((advice, index) => <li key={index}>{advice}</li>)}</ul></section>
            <section><h3>复查提示</h3><p>{selected.review}</p></section>
            <div className="nx-source-note"><div><strong>原有来源说明</strong><p>{selected.source || '暂未提供原始出处。'}</p><p>条目编号：{selected.id}。编号仅用于定位本站内容，不代表外部文献引用。</p></div></div>
          </article>
          <div className="nx-dialog-actions"><button className="nx-button" onClick={() => setSelectedId(null)}>关闭资料</button><button className="nx-button is-primary" onClick={() => { setSelectedId(null); onAsk(`我看了内置草稿“${selected.title}”（${selected.id}）。我想了解这条资料是否适合我的田块，需要补充哪些情况？请说明仍需核验的依据。`); }}><MessageSquare size={16} />带着问题聊一聊</button></div>
        </>}
      </DialogContent>
    </Dialog>
  </section>;
}
