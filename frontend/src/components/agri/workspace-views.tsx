import { useEffect, useMemo, useState, type FormEvent, type ReactNode } from 'react';
import { ArrowRight, BellRing, BookOpen, CalendarDays, Camera, CheckCheck, ClipboardList, Leaf, MessageSquare, Pencil, Plus, Search, Sprout, Trash2, X } from 'lucide-react';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { NxSelect } from '@/components/ui/nx-select';
import { api } from '@/lib/api';
import type { AttachedImage, FieldProfile, KnowledgeSource } from '@/types';

export type TaskRecord = {
  id: string;
  taskId: string;
  fieldId?: string | null;
  /** execution = 执行记录；review = 复查记录 */
  kind: string;
  date: string;
  note: string;
  outcome?: string | null;
  sourceMessageId?: string | null;
  createdAt: string;
};

export type FarmTask = {
  id: string;
  title: string;
  date: string;
  /** 待确认 / 待执行 / 已执行待复查 / 已完成 / 取消（与后端 TaskStatus 一致） */
  status: string;
  statusLabel?: string | null;
  createdAt: string;
  fieldId?: string | null;
  fieldName?: string | null;
  condition?: string | null;
  method?: string | null;
  review?: string | null;
  note?: string | null;
  timeWindow?: string | null;
  materials?: string | null;
  risk?: string | null;
  evidence?: string[];
  evidenceCards?: Array<{ id?: string; title?: string; url?: string; reviewStatus?: string }>;
  planItemId?: string | null;
  sourceMessageId?: string | null;
  confirmedAt?: string | null;
  executedAt?: string | null;
  completedAt?: string | null;
  records?: TaskRecord[];
};

/** 状态文案与语气色：以后端返回的 statusLabel 为准，仅在这里补样式。 */
export const OPEN_TASK_STATUSES = ['pending_confirmation', 'pending', 'awaiting_review'];
const TASK_STATUS_TONE: Record<string, string> = {
  pending_confirmation: 'is-draft',
  pending: 'is-open',
  awaiting_review: 'is-review',
  completed: 'is-done',
  cancelled: 'is-cancelled',
};
export const taskStatusLabel = (task: FarmTask) => task.statusLabel || task.status;
export const taskStatusTone = (task: FarmTask) => TASK_STATUS_TONE[task.status] ?? 'is-open';

export type Task = FarmTask;
export type KnowledgeEntry = KnowledgeSource;
type Mutation<T> = (value: T) => Promise<unknown>;

export type FieldsViewProps = {
  fields: FieldProfile[];
  onSave: Mutation<FieldProfile>;
  onDelete: Mutation<string>;
  onRecord: (id: string, note: string) => Promise<unknown>;
  onAsk: (fieldId: string) => void;
  /** 「让农心看最近状况」：带着该田块最近的田间照片开一段对话 */
  onAnalyze?: (fieldId: string) => void;
};

export type TasksViewProps = {
  tasks: FarmTask[];
  fields: FieldProfile[];
  onSave: Mutation<FarmTask>;
  onDelete: Mutation<string>;
  /** 状态流转：确认安排、取消、退回、重新打开（需要记录的两步由 onRecord 推进） */
  onStatus: (id: string, status: string) => Promise<unknown>;
  onRecord: (id: string, record: { kind: string; date: string; note: string; outcome?: string }) => Promise<unknown>;
  onDiscuss: (task: FarmTask) => void;
};

export type KnowledgeViewProps = {
  entries?: KnowledgeSource[];
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

export function FieldsView({ fields, onSave, onDelete, onRecord, onAsk, onAnalyze }: FieldsViewProps) {
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
  // 田间照片档案：按时间倒序的影像时间轴
  const [photos, setPhotos] = useState<AttachedImage[]>([]);
  const [photoUsage, setPhotoUsage] = useState({ photos: 0, bytes: 0 });
  const [photoError, setPhotoError] = useState('');
  const [preview, setPreview] = useState<AttachedImage | null>(null);
  const [photosLoading, setPhotosLoading] = useState(false);
  useEffect(() => {
    if (!selectedId) { setPhotos([]); setPhotoUsage({ photos: 0, bytes: 0 }); return; }
    const controller = new AbortController();
    setPhotosLoading(true); setPhotoError('');
    void api<{ photos: AttachedImage[]; usage: { photos: number; bytes: number } }>(`/uploads/field/${selectedId}`, { signal: controller.signal })
      .then(result => { setPhotos(result.photos ?? []); setPhotoUsage(result.usage ?? { photos: 0, bytes: 0 }); })
      .catch((cause) => { if (!controller.signal.aborted) setPhotoError(errorMessage(cause)); })
      .finally(() => { if (!controller.signal.aborted) setPhotosLoading(false); });
    return () => controller.abort();
  }, [selectedId]);
  async function removePhoto(photo: AttachedImage) {
    setPhotoError('');
    try {
      await api(`/uploads/${photo.id}`, { method: 'DELETE' });
      setPhotos(current => current.filter(item => item.id !== photo.id));
      setPhotoUsage(current => ({ photos: Math.max(0, current.photos - 1), bytes: Math.max(0, current.bytes - photo.bytes) }));
      setPreview(null);
    } catch (cause) { setPhotoError(errorMessage(cause)); }
  }
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
          <div className="nx-form-grid"><label className="nx-form-field">作物 *<NxSelect ariaLabel="作物" value={editor.draft.crop} placeholder="请选择作物" options={Array.from(new Set([...CROPS, editor.draft.crop])).filter(Boolean).map((crop) => ({ value: crop, label: crop }))} onValueChange={(value) => updateDraft('crop', value)} /></label><label className="nx-form-field">品种<input maxLength={120} placeholder="选填" value={editor.draft.variety} onChange={(event) => updateDraft('variety', event.target.value)} /></label></div>
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
            <div className="nx-field-photos">
              <div className="nx-section-heading"><h3>田间照片</h3><span className="nx-muted">{[photoUsage.photos || photos.length, photoUsage.bytes ? `${(photoUsage.bytes / 1024 / 1024).toFixed(1)} MB` : ''].filter(Boolean).join(' 张 · ')}{photoUsage.bytes ? '' : ' 张'}</span></div>
              <header>
                <b>{photosLoading ? '正在读取…' : photos.length ? `最近一次 ${formatDate((photos[0].observedAt || photos[0].createdAt || '').slice(0, 10))}` : '还没有照片'}</b>
                <div className="nx-inline-actions">
                  {onAnalyze && <button className="nx-text-button" onClick={() => { setSelectedId(null); onAnalyze(selected.id); }}><Camera size={14} />让农心看最近状况 <ArrowRight size={13} /></button>}
                </div>
              </header>
              {photoError && <p className="nx-error" role="alert">{photoError}</p>}
              {photos.length > 0 && <ul className="nx-photo-grid">{photos.map(photo => <li key={photo.id}>
                <img src={photo.url} alt={`${photo.observedAt || ''} 拍摄的田间照片`} onClick={() => setPreview(photo)} />
                <figcaption><b>{formatDate((photo.observedAt || photo.createdAt || '').slice(0, 10))}</b>{photo.note ? <span className="nx-photo-note">{photo.note}</span> : <span className="nx-photo-note">暂无备注</span>}</figcaption>
                <button type="button" aria-label={`删除 ${photo.observedAt || ''} 的照片`} onClick={() => void removePhoto(photo)}><X size={12} /></button>
              </li>)}</ul>}
              {!photosLoading && photos.length === 0 && <p className="nx-inline-empty">在对话里给这块田拍照提问，照片就会按日期存到这里；以后可以对比不同时间的叶色与病斑变化。</p>}
            </div>
            <div className="nx-section-heading"><h3>种植记录</h3><span className="nx-muted">{selected.records?.length ?? 0} 条</span></div>
            {selected.records?.length ? <ol className="nx-record-list">{[...selected.records].reverse().map((record, index) => <li key={`${record.date}-${index}`}><time>{formatDate(record.date)}</time><p>{record.note}</p></li>)}</ol> : <p className="nx-inline-empty">还没有记录。把今天看到的长势、做过的农活记下来。</p>}
            <form className="nx-form" onSubmit={(event) => void saveRecord(event)}><label className="nx-form-field">添加今天的观察<textarea required rows={3} maxLength={8000} placeholder="例如：今天查看了排水沟，南侧田角有少量积水。" value={recordNote} onChange={(event) => setRecordNote(event.target.value)} /></label>{recordError && <p className="nx-error" role="alert">{recordError}</p>}<button className="nx-button is-primary" disabled={recordBusy || !recordNote.trim()} type="submit"><Plus size={16} />{recordBusy ? '正在保存…' : '保存记录'}</button></form>
          </div>
          <div className="nx-dialog-actions"><button className="nx-button" disabled={recordBusy} onClick={() => { setSelectedId(null); edit(selected); }}><Pencil size={16} />编辑田块</button><button className="nx-button is-primary" disabled={recordBusy} onClick={() => { setSelectedId(null); onAsk(selected.id); }}><MessageSquare size={16} />聊聊这块田</button></div>
        </>}
      </DialogContent>
    </Dialog>
    <Dialog open={!!preview} onOpenChange={(open) => { if (!open) setPreview(null); }}>
      <DialogContent className="nx-dialog nx-dialog-wide">
        {preview && <>
          <DialogHeader><DialogTitle>{formatDate((preview.observedAt || preview.createdAt || '').slice(0, 10))} 的田间照片</DialogTitle><DialogDescription>{preview.width}×{preview.height} · {Math.round(preview.bytes / 1024)} KB{preview.note ? ` · ${preview.note}` : ''}</DialogDescription></DialogHeader>
          <img className="nx-photo-large" src={preview.url} alt="田间照片大图" />
          <div className="nx-dialog-actions"><button className="nx-button" onClick={() => setPreview(null)}>关闭</button><button className="nx-button is-danger" onClick={() => void removePhoto(preview)}><Trash2 size={15} />删除这张</button></div>
        </>}
      </DialogContent>
    </Dialog>
    <ConfirmDelete open={!!deleteTarget} title={`删除“${deleteTarget?.name ?? ''}”？`} description="该田块的档案与种植记录将被删除，删除后无法恢复。" onClose={() => setDeleteTarget(null)} onConfirm={async () => { if (deleteTarget) { await onDelete(deleteTarget.id); if (selectedId === deleteTarget.id) setSelectedId(null); setStatus('田块已删除。'); } }} />
  </section>;
}

type TaskFilter = 'todo' | 'pending_confirmation' | 'awaiting_review' | 'completed' | 'all';
const TASK_FILTERS: Array<{ id: TaskFilter; label: string }> = [{ id: 'todo', label: '进行中' }, { id: 'pending_confirmation', label: '待确认' }, { id: 'awaiting_review', label: '待复查' }, { id: 'completed', label: '已完成' }, { id: 'all', label: '全部' }];
const REVIEW_OUTCOMES: Array<{ value: string; label: string }> = [{ value: 'resolved', label: '问题已解决' }, { value: 'improved', label: '明显好转' }, { value: 'unchanged', label: '没有变化' }, { value: 'worse', label: '反而变差' }, { value: 'other', label: '其他情况' }];
const recordKindLabel = (kind: string) => (kind === 'review' ? '复查记录' : '执行记录');
const outcomeText = (outcome?: string | null) => REVIEW_OUTCOMES.find((item) => item.value === outcome)?.label ?? '';

export function TasksView({ tasks, fields, onSave, onDelete, onStatus, onRecord, onDiscuss }: TasksViewProps) {
  const [filter, setFilter] = useState<TaskFilter>('todo');
  const [fieldFilter, setFieldFilter] = useState('');
  const [query, setQuery] = useState('');
  const [editor, setEditor] = useState<FarmTask | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<FarmTask | null>(null);
  const [recordTarget, setRecordTarget] = useState<{ task: FarmTask; kind: 'execution' | 'review' } | null>(null);
  const [recordDraft, setRecordDraft] = useState({ date: '', note: '', outcome: '' });
  const [expanded, setExpanded] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [actingIds, setActingIds] = useState<string[]>([]);
  const [error, setError] = useState('');
  const [listError, setListError] = useState('');
  const [status, setStatus] = useState('');
  const today = localDate();
  const open = tasks.filter((task) => OPEN_TASK_STATUSES.includes(task.status));
  const toReview = tasks.filter((task) => task.status === 'awaiting_review');
  const waiting = open.filter((task) => task.status !== 'awaiting_review');
  const overdue = waiting.filter((task) => !!task.date && task.date < today);
  const dueToday = waiting.filter((task) => task.date === today);
  const filtered = useMemo(() => tasks.filter((task) => {
    if (fieldFilter && task.fieldId !== fieldFilter) return false;
    if (filter === 'todo' && !OPEN_TASK_STATUSES.includes(task.status)) return false;
    if (filter === 'pending_confirmation' && task.status !== 'pending_confirmation') return false;
    if (filter === 'awaiting_review' && task.status !== 'awaiting_review') return false;
    if (filter === 'completed' && task.status !== 'completed') return false;
    return `${task.title} ${task.note ?? ''} ${task.fieldName ?? ''}`.toLowerCase().includes(query.trim().toLowerCase());
  }).sort((a, b) => Number(!OPEN_TASK_STATUSES.includes(a.status)) - Number(!OPEN_TASK_STATUSES.includes(b.status))
    || a.date.localeCompare(b.date) || b.createdAt.localeCompare(a.createdAt)), [tasks, filter, fieldFilter, query]);
  /** 先按田块分组，组内按时间（逾期→今天→以后→待定）；组之间按最早日期排，顺序即执行顺序 */
  const taskGroups = useMemo(() => {
    const grouped = new Map<string, { key: string; label: string; hint: string; items: FarmTask[]; dueText: string }>();
    for (const task of filtered) {
      const key = task.fieldId ?? '__none__';
      let group = grouped.get(key);
      if (!group) {
        const field = fields.find((item) => item.id === task.fieldId);
        group = { key, label: field?.name || task.fieldName || '未关联田块', hint: field?.crop || (task.fieldId ? '' : '通用咨询'), items: [], dueText: '' };
        grouped.set(key, group);
      }
      group.items.push(task);
    }
    const groups = [...grouped.values()];
    for (const group of groups) {
      const dated = group.items.filter((task) => !!task.date).sort((a, b) => a.date.localeCompare(b.date));
      group.dueText = dated.length ? formatDate(dated[0].date) : '';
      // 组内：待确认的排最前（它挡着后面的事），其余按日期升序、同日期按创建时间
      group.items.sort((a, b) => Number(b.status === 'pending_confirmation') - Number(a.status === 'pending_confirmation')
        || (a.date || '9999-12-31').localeCompare(b.date || '9999-12-31') || a.createdAt.localeCompare(b.createdAt));
    }
    return groups.sort((a, b) => (a.items[0]?.date || '9999-12-31').localeCompare(b.items[0]?.date || '9999-12-31'));
  }, [filtered, fields]);
  const titles = (list: FarmTask[]) => `${list.slice(0, 3).map((task) => task.title).join('、')}${list.length > 3 ? '…' : ''}`;
  const edit = (task?: FarmTask) => {
    setError('');
    setEditor(task ? { ...task } : { id: newId('t'), title: '', date: today, status: 'pending', createdAt: new Date().toISOString(), fieldId: '', fieldName: '', condition: '', method: '', review: '', note: '', timeWindow: '', materials: '', risk: '' });
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
        condition: editor.condition?.trim() || '', method: editor.method?.trim() || '', review: editor.review?.trim() || '', note: editor.note?.trim() || '',
        timeWindow: editor.timeWindow?.trim() || '', materials: editor.materials?.trim() || '', risk: editor.risk?.trim() || '' });
      setEditor(null); setStatus('任务已保存。');
    } catch (cause) { setError(errorMessage(cause)); }
    finally { setBusy(false); }
  };
  const act = async (task: FarmTask, next: string, message: string) => {
    if (actingIds.includes(task.id)) return;
    setActingIds((ids) => [...ids, task.id]); setListError('');
    try { await onStatus(task.id, next); setStatus(message); }
    catch (cause) { setListError(errorMessage(cause)); }
    finally { setActingIds((ids) => ids.filter((id) => id !== task.id)); }
  };
  const openRecord = (task: FarmTask, kind: 'execution' | 'review') => {
    setError(''); setRecordDraft({ date: today, note: '', outcome: '' }); setRecordTarget({ task, kind });
  };
  const submitRecord = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!recordTarget || busy) return;
    if (!recordDraft.note.trim()) { setError(recordTarget.kind === 'review' ? '请写下复查看到的情况。' : '请写下实际做了什么。'); return; }
    setBusy(true); setError('');
    try {
      await onRecord(recordTarget.task.id, { kind: recordTarget.kind, date: recordDraft.date || today, note: recordDraft.note.trim(), outcome: recordTarget.kind === 'review' ? recordDraft.outcome : '' });
      setStatus(recordTarget.kind === 'review' ? '复查记录已提交，任务标记为已完成。' : '执行记录已提交，任务进入待复查。');
      setRecordTarget(null);
    } catch (cause) { setError(errorMessage(cause)); }
    finally { setBusy(false); }
  };
  const toggleRecords = (id: string) => setExpanded((ids) => (ids.includes(id) ? ids.filter((item) => item !== id) : [...ids, id]));

  return <section className="nx-view nx-tasks-view" aria-labelledby="nx-tasks-title">
    <header className="nx-view-head"><div><p className="nx-eyebrow">FARM ROUTINE / 农事安排</p><h1 id="nx-tasks-title">把想做的事，落到每一天。</h1><p className="nx-muted">方案确认后成为任务；执行完提交记录，复查后再决定下一步。</p></div><button className="nx-button is-primary" onClick={() => edit()}><Plus size={18} />新建任务</button></header>
    <div className="nx-view-summary"><span><strong>{open.length}</strong> 项进行中</span><span><strong>{toReview.length}</strong> 项待复查</span><span><strong>{overdue.length}</strong> 项已逾期</span><span className="nx-muted">完成状态只由你提交的记录推进</span></div>
    {(overdue.length > 0 || dueToday.length > 0 || toReview.length > 0) && <div className="nx-reminders" role="status" aria-label="到期与待复查提醒">
      <b><BellRing size={16} />今天要留意</b>
      <ul>
        {overdue.length > 0 && <li><button type="button" onClick={() => setFilter('todo')}>已逾期 {overdue.length} 项：{titles(overdue)}</button></li>}
        {dueToday.length > 0 && <li><button type="button" onClick={() => setFilter('todo')}>今天到期 {dueToday.length} 项：{titles(dueToday)}</button></li>}
        {toReview.length > 0 && <li><button type="button" onClick={() => setFilter('awaiting_review')}>执行完等复查 {toReview.length} 项：{titles(toReview)}</button></li>}
      </ul>
      <p className="nx-fine nx-muted">只在页面内提醒，不会发送短信或推送。</p>
    </div>}
    <p className="nx-status" role="status">{status}</p>
    {listError && <p className="nx-error" role="alert">{listError}</p>}
    {tasks.length === 0 ? <EmptyState icon={<ClipboardList size={30} />} title="给下一件农事留个位置" action={<button className="nx-button is-primary" onClick={() => edit()}><Plus size={17} />记下第一件事</button>}>巡田、灌溉、复查，都可以从一条简单的计划开始；也可以让农心给出方案后点「加入任务」。</EmptyState> : <>
      <div className="nx-toolbar nx-task-toolbar"><div className="nx-filter-tabs" aria-label="按任务状态筛选">{TASK_FILTERS.map((item) => <button key={item.id} className={filter === item.id ? 'is-active' : ''} aria-pressed={filter === item.id} onClick={() => setFilter(item.id)}>{item.label}</button>)}</div><NxSelect ariaLabel="按田块筛选任务" className="nx-filter-trigger" value={fieldFilter} options={[{ value: '', label: '全部田块' }, ...fields.map((field) => ({ value: field.id, label: field.name, hint: field.crop }))]} onValueChange={setFieldFilter} /></div>
      <label className="nx-search nx-task-search"><Search size={18} aria-hidden="true" /><input aria-label="搜索任务" placeholder="搜索任务名称或备注" value={query} onChange={(event) => setQuery(event.target.value)} /></label>
      {filtered.length === 0 ? <EmptyState icon={filter === 'completed' ? <CheckCheck size={28} /> : <ClipboardList size={28} />} title={filter === 'todo' && !query && !fieldFilter ? '当前没有进行中的任务' : '这里还没有任务'} action={<button className="nx-button" onClick={() => { setFilter('all'); setFieldFilter(''); setQuery(''); }}>查看全部任务</button>}>可以调整筛选条件，或新建一项农事安排。</EmptyState> : <div className="nx-task-list">
        {/* 先按田块分组，组内按时间（逾期→今天→以后→待定），顺序就是执行顺序 */}
        {taskGroups.map(group => <section className="nx-task-group" key={group.key}>
          <header className="nx-task-group-head">
            <div><b>{group.label}</b>{group.hint && <span>{group.hint}</span>}</div>
            <span className="nx-muted">{group.items.length} 项{group.dueText ? ` · 最早 ${group.dueText}` : ''}</span>
          </header>
          {group.items.map((task, index) => {
          const pastDue = OPEN_TASK_STATUSES.includes(task.status) && task.status !== 'awaiting_review' && !!task.date && task.date < today;
          const field = fields.find((item) => item.id === task.fieldId);
          const records = task.records ?? [];
          const cards = task.evidenceCards ?? [];
          const evidenceIds = task.evidence ?? [];
          const acting = actingIds.includes(task.id);
          const isOpen = expanded.includes(task.id);
          return <article key={task.id} className={`nx-task-row is-${task.status}`}>
            <div className="nx-task-state"><span className="nx-task-order">{index + 1}</span><span className={`nx-badge ${taskStatusTone(task)}`}>{taskStatusLabel(task)}</span></div>
            <div className="nx-task-main">
              <button className="nx-task-title" onClick={() => edit(task)}>{task.title}</button>
              <div className="nx-task-meta"><span className={pastDue ? 'is-overdue' : ''}><CalendarDays size={14} />{formatDate(task.date)}{pastDue ? ' · 已逾期' : task.date === today && OPEN_TASK_STATUSES.includes(task.status) ? ' · 今天' : ''}</span>{field?.name ? <span><Sprout size={14} />{field.name}</span> : task.fieldName ? <span><Sprout size={14} />{task.fieldName}（原关联田块）</span> : <span>未关联田块</span>}{task.sourceMessageId && <span><MessageSquare size={14} />来自对话建议</span>}{task.completedAt && <span>完成于 {formatDate(task.completedAt.slice(0, 10))}</span>}</div>
              {task.timeWindow && <p className="nx-task-condition">时间窗口：{task.timeWindow}</p>}
              {task.condition && <p className="nx-task-condition">执行条件：{task.condition}</p>}
              {task.method && <p className="nx-task-note">操作方法：{task.method}</p>}
              {task.materials && <p className="nx-task-note">所需物料：{task.materials}</p>}
              {task.risk && <p className="nx-task-note nx-warning">风险与禁忌：{task.risk}</p>}
              {task.review && <p className="nx-task-note">复查要点：{task.review}</p>}
              {cards.length > 0
                ? <p className="nx-task-evidence">依据：{cards.map((card) => card.url
                  ? <a key={card.id ?? card.title} href={card.url} target="_blank" rel="noreferrer">{card.title || card.id}{card.reviewStatus === 'unverified' ? '（未核验草稿）' : ''}</a>
                  : <span key={card.id ?? card.title}>{card.title || card.id}（无原文链接）</span>)}</p>
                : evidenceIds.length > 0
                  ? <p className="nx-task-evidence">依据来源ID：{evidenceIds.join('、')}</p>
                  : task.sourceMessageId ? <p className="nx-task-evidence nx-muted">依据：这条方案没有附来源ID</p> : null}
              {task.note && <p className="nx-task-note">{task.note}</p>}
              {records.length > 0 && <>
                <button type="button" className="nx-task-records-toggle" aria-expanded={isOpen} onClick={() => toggleRecords(task.id)}>执行与复查记录（{records.length}）</button>
                {isOpen && <ol className="nx-task-records">{records.map((record) => <li key={record.id}>
                  <b>{recordKindLabel(record.kind)}</b><time>{formatDate(record.date)}</time>
                  {outcomeText(record.outcome) && <span className="nx-badge is-verified">{outcomeText(record.outcome)}</span>}
                  <p>{record.note}</p>
                </li>)}</ol>}
              </>}
              <div className="nx-task-actions">
                {task.status === 'pending_confirmation' && <button className="nx-button is-small is-primary" disabled={acting} onClick={() => void act(task, 'pending', '已确认安排，任务进入待执行。')}>确认安排</button>}
                {task.status === 'pending' && <button className="nx-button is-small is-primary" disabled={acting} onClick={() => openRecord(task, 'execution')}>提交执行记录</button>}
                {task.status === 'awaiting_review' && <button className="nx-button is-small is-primary" disabled={acting} onClick={() => openRecord(task, 'review')}>提交复查记录</button>}
                {task.status === 'awaiting_review' && <button className="nx-button is-small" disabled={acting} onClick={() => void act(task, 'pending', '已退回待执行，可继续执行或调整安排。')}>退回待执行</button>}
                {(task.status === 'completed' || task.status === 'cancelled') && <button className="nx-button is-small" disabled={acting} onClick={() => void act(task, 'pending', '任务已重新打开，回到待执行。')}>重新打开</button>}
                {(task.status === 'pending' || task.status === 'pending_confirmation') && <button className="nx-button is-small" disabled={acting} onClick={() => void act(task, 'cancelled', '任务已取消，历史记录保留。')}>取消</button>}
                <button className="nx-text-button" onClick={() => onDiscuss(task)}><MessageSquare size={14} />在对话里讨论</button>
              </div>
            </div>
            <div className="nx-inline-actions"><button className="nx-icon-button" aria-label={`编辑${task.title}`} onClick={() => edit(task)}><Pencil size={16} /></button><button className="nx-icon-button" disabled={acting} aria-label={`删除${task.title}`} onClick={() => setDeleteTarget(task)}><Trash2 size={16} /></button></div>
          </article>;
        })}
        </section>)}
      </div>}
    </>}
    <Dialog open={!!editor} onOpenChange={(next) => { if (!next && !busy) setEditor(null); }}>
      <DialogContent className="nx-dialog" showCloseButton={!busy}>
        <DialogHeader><DialogTitle>{editor && tasks.some((task) => task.id === editor.id) ? '编辑农事任务' : '记下一件农事'}</DialogTitle><DialogDescription>计划日期用于整理任务；状态由执行与复查记录推进，不能在这里直接改完成。</DialogDescription></DialogHeader>
        {editor && <form className="nx-form" onSubmit={(event) => void save(event)}>
          <label className="nx-form-field">任务名称 *<input autoFocus required maxLength={200} placeholder="例如：检查南侧田块排水沟" value={editor.title} onChange={(event) => update('title', event.target.value)} /></label>
          <div className="nx-form-grid"><label className="nx-form-field">计划日期（可留空待定）<input type="date" min="1900-01-01" max="2100-12-31" value={editor.date} onChange={(event) => update('date', event.target.value)} /></label><label className="nx-form-field">关联田块<NxSelect ariaLabel="关联田块" value={fields.some((field) => field.id === editor.fieldId) ? editor.fieldId ?? '' : ''} options={[{ value: '', label: '不关联田块' }, ...fields.map((field) => ({ value: field.id, label: field.name, hint: field.crop }))]} onValueChange={(value) => update('fieldId', value)} emptyText="还没有田块档案" /></label></div>
          <label className="nx-form-field">时间窗口<input maxLength={200} placeholder="例如：破口前 3—5 天；不清楚写「待确认」" value={editor.timeWindow ?? ''} onChange={(event) => update('timeWindow', event.target.value)} /></label>
          <label className="nx-form-field">执行条件<input maxLength={8000} placeholder="例如：雨停后、田间可以安全进入时" value={editor.condition ?? ''} onChange={(event) => update('condition', event.target.value)} /></label>
          <label className="nx-form-field">操作方法<textarea rows={2} maxLength={8000} placeholder="选填，记录具体怎么做" value={editor.method ?? ''} onChange={(event) => update('method', event.target.value)} /></label>
          <label className="nx-form-field">所需物料<input maxLength={2000} placeholder="选填，例如：背负式喷雾器、清水；不清楚写「待确认」" value={editor.materials ?? ''} onChange={(event) => update('materials', event.target.value)} /></label>
          <label className="nx-form-field">风险与禁忌<input maxLength={4000} placeholder="选填，例如：避开高温时段与蜜蜂活动区" value={editor.risk ?? ''} onChange={(event) => update('risk', event.target.value)} /></label>
          <label className="nx-form-field">复查要点<input maxLength={8000} placeholder="选填，何时再看、观察什么" value={editor.review ?? ''} onChange={(event) => update('review', event.target.value)} /></label>
          <label className="nx-form-field">备注<textarea rows={2} maxLength={8000} placeholder="记录执行情况，或下次需要留意的事" value={editor.note ?? ''} onChange={(event) => update('note', event.target.value)} /></label>
          <p className="nx-fine nx-muted">当前状态：{taskStatusLabel(editor)}。要标记完成，请在任务上提交执行记录与复查记录。</p>
          {error && <p className="nx-error" role="alert">{error}</p>}
          <div className="nx-dialog-actions"><button type="button" className="nx-button" disabled={busy} onClick={() => setEditor(null)}>取消</button><button type="submit" className="nx-button is-primary" disabled={busy}>{busy ? '正在保存…' : '保存任务'}</button></div>
        </form>}
      </DialogContent>
    </Dialog>
    <Dialog open={!!recordTarget} onOpenChange={(next) => { if (!next && !busy) setRecordTarget(null); }}>
      <DialogContent className="nx-dialog nx-dialog-compact" showCloseButton={!busy}>
        <DialogHeader><DialogTitle>{recordTarget?.kind === 'review' ? '提交复查记录' : '提交执行记录'}</DialogTitle><DialogDescription>{recordTarget?.kind === 'review' ? '写下复查看到的情况与结论；提交后任务标记为已完成，农心会据此调整后续建议。' : '写下实际做了什么、什么时候做的；提交后任务进入待复查。'}</DialogDescription></DialogHeader>
        {recordTarget && <form className="nx-form" onSubmit={(event) => void submitRecord(event)}>
          <p className="nx-record-task">{recordTarget.task.title}</p>
          <label className="nx-form-field">日期<input type="date" min="1900-01-01" max="2100-12-31" value={recordDraft.date} onChange={(event) => setRecordDraft((draft) => ({ ...draft, date: event.target.value }))} /></label>
          <label className="nx-form-field">{recordTarget.kind === 'review' ? '复查看到什么 *' : '实际做了什么 *'}<textarea autoFocus required rows={4} maxLength={8000} placeholder={recordTarget.kind === 'review' ? '例如：施药后 5 天查看，病斑没有扩展，新叶干净' : '例如：上午按方案喷施，风力 2 级，用时 1.5 小时'} value={recordDraft.note} onChange={(event) => setRecordDraft((draft) => ({ ...draft, note: event.target.value }))} /></label>
          {recordTarget.kind === 'review' && <div className="nx-form-field">复查结论<div className="nx-clarify-options">{REVIEW_OUTCOMES.map((item) => <button key={item.value} type="button" className={`nx-clarify-option${recordDraft.outcome === item.value ? ' is-selected' : ''}`} aria-pressed={recordDraft.outcome === item.value} onClick={() => setRecordDraft((draft) => ({ ...draft, outcome: draft.outcome === item.value ? '' : item.value }))}>{item.label}</button>)}</div></div>}
          {error && <p className="nx-error" role="alert">{error}</p>}
          <div className="nx-dialog-actions"><button type="button" className="nx-button" disabled={busy} onClick={() => setRecordTarget(null)}>取消</button><button type="submit" className="nx-button is-primary" disabled={busy}>{busy ? '正在提交…' : '提交记录'}</button></div>
        </form>}
      </DialogContent>
    </Dialog>
    <ConfirmDelete open={!!deleteTarget} title={`删除“${deleteTarget?.title ?? ''}”？`} description="这项任务和它的执行/复查记录都会被删除，删除后无法恢复。" onClose={() => setDeleteTarget(null)} onConfirm={async () => { if (deleteTarget) { await onDelete(deleteTarget.id); setStatus('任务已删除。'); } }} />
  </section>;
}

export function KnowledgeView({ entries = [], onAsk }: KnowledgeViewProps) {
  const [query, setQuery] = useState('');
  const [crop, setCrop] = useState('全部');
  const [status, setStatus] = useState('全部');
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const selected = entries.find((entry) => entry.id === selectedId);
  const crops = ['全部', ...new Set(entries.flatMap((entry) => entry.crops ?? []))];
  const verifiedCount = entries.filter((entry) => entry.reviewStatus === 'verified').length;
  const visible = entries.filter((entry) => {
    if (crop !== '全部' && !(entry.crops ?? []).includes(crop)) return false;
    if (status === '已核验' && entry.reviewStatus !== 'verified') return false;
    if (status === '未核验' && entry.reviewStatus !== 'unverified') return false;
    const haystack = [entry.title, entry.institution, entry.region, (entry.crops ?? []).join(' '), entry.topic,
      ...(entry.chunks ?? []).map((chunk) => `${chunk.heading ?? ''} ${chunk.text ?? ''}`)].join(' ');
    return haystack.toLowerCase().includes(query.trim().toLowerCase());
  });

  return <section className="nx-view nx-knowledge-view" aria-labelledby="nx-knowledge-title">
    <header className="nx-view-head"><div><p className="nx-eyebrow">SOURCED LIBRARY / 农技资料</p><h1 id="nx-knowledge-title">好判断，从了解开始。</h1><p className="nx-muted">每条资料都登记了机构、日期、适用地区与原文链接，对话中的引用只从这里取。</p></div><span className="nx-badge is-verified"><BookOpen size={15} />已核验原文 {verifiedCount} 篇</span></header>
    <div className="nx-source-note"><BookOpen size={18} aria-hidden="true" /><p>「已核验原文」表示已逐条对照公开原文登记，可点开链接核对；「本地草稿 · 未核验」是整理材料，只能作为线索。涉及用药剂量与登记信息，一律以当地有效登记标签为准。</p></div>
    <div className="nx-toolbar"><label className="nx-search"><Search size={18} aria-hidden="true" /><input aria-label="搜索农技资料" placeholder="搜索作物、病虫害或问题，如：稻瘟病、赤霉病、高温" value={query} onChange={(event) => setQuery(event.target.value)} /></label><NxSelect ariaLabel="按核验状态筛选" className="nx-filter-trigger" value={status} options={['全部', '已核验', '未核验'].map((item) => ({ value: item, label: item === '全部' ? '全部状态' : item }))} onValueChange={setStatus} /></div>
    <div className="nx-knowledge-filter"><div className="nx-filter-tabs" aria-label="按作物筛选资料">{crops.map((item) => <button key={item} aria-pressed={crop === item} className={crop === item ? 'is-active' : ''} onClick={() => setCrop(item)}>{item}</button>)}</div><span className="nx-muted">{visible.length} 篇来源登记</span></div>
    {visible.length === 0 ? <EmptyState icon={<BookOpen size={28} />} title={entries.length ? '还没有匹配的资料' : '资料正在整理'} action={entries.length ? <button className="nx-button" onClick={() => { setQuery(''); setCrop('全部'); setStatus('全部'); }}>重置筛选</button> : undefined}>{entries.length ? '试试其他关键词，或切换作物和状态。' : '当前没有可浏览的来源登记。'}</EmptyState> : <div className="nx-grid nx-knowledge-grid">
      {visible.map((entry) => <button className="nx-card nx-knowledge-card" key={entry.id} onClick={() => setSelectedId(entry.id)}>
        <div className="nx-card-topline"><span className="nx-knowledge-icon" aria-hidden="true"><Leaf size={21} /></span><span className={`nx-badge ${entry.reviewStatus === 'verified' ? 'is-verified' : 'is-draft'}`}>{entry.reviewStatus === 'verified' ? '已核验原文' : '本地草稿 · 未核验'}</span></div>
        <h2>{entry.title}</h2>
        <p className="nx-knowledge-excerpt">{[entry.institution, entry.publishedAt, entry.region ? `适用地区：${entry.region}` : null].filter(Boolean).join(' · ')}</p>
        <div className="nx-card-footer"><span>{(entry.crops ?? []).join('、') || '作物未标注'} · {entry.chunks?.length ?? 0} 条原文片段</span><ArrowRight size={17} /></div>
      </button>)}
    </div>}
    <Dialog open={!!selected} onOpenChange={(open) => { if (!open) setSelectedId(null); }}>
      <DialogContent className="nx-dialog nx-dialog-wide nx-knowledge-dialog">
        {selected && <>
          <DialogHeader><span className={`nx-badge ${selected.reviewStatus === 'verified' ? 'is-verified' : 'is-draft'}`}>{(selected.crops ?? []).join('、') || '作物未标注'} · {selected.reviewStatus === 'verified' ? '已核验原文' : '本地草稿 · 未核验'}</span><DialogTitle>{selected.title}</DialogTitle><DialogDescription>{selected.reviewNote || '来源登记信息如下。'}</DialogDescription></DialogHeader>
          <article className="nx-knowledge-detail">
            <section><h3>来源登记</h3><dl className="nx-source-fields">
              <div><dt>机构</dt><dd>{selected.institution || '未登记'}</dd></div>
              <div><dt>发布日期</dt><dd>{selected.publishedAt || '未登记'}</dd></div>
              <div><dt>抓取日期</dt><dd>{selected.fetchedAt || '未登记'}</dd></div>
              <div><dt>适用地区</dt><dd>{selected.region || '未标注'}</dd></div>
              <div><dt>适用作物</dt><dd>{(selected.crops ?? []).join('、') || '未标注'}</dd></div>
              <div><dt>版本</dt><dd>{selected.version || '未登记'}</dd></div>
              <div><dt>许可说明</dt><dd>{selected.license || '未登记'}</dd></div>
            </dl>
            {selected.url ? <a className="nx-source-link" href={selected.url} target="_blank" rel="noreferrer">查看原文链接</a> : <p className="nx-source-nolink">没有可核验的原文链接：这是本地整理草稿，不能作为官方依据。</p>}
            </section>
            {(selected.chunks ?? []).map((chunk) => <section key={chunk.id}><h3>{chunk.heading || '原文片段'}</h3>
              <p className="nx-source-locator">定位：{chunk.locator || '未标注'} · 适用：{[chunk.crop, chunk.region, chunk.growthStage].filter(Boolean).join(' / ') || '未标注'}</p>
              <p>{chunk.text}</p>
            </section>)}
          </article>
          <div className="nx-dialog-actions"><button className="nx-button" onClick={() => setSelectedId(null)}>关闭资料</button><button className="nx-button is-primary" onClick={() => { setSelectedId(null); onAsk(`我看了资料“${selected.title}”（来源ID：${selected.chunks?.[0]?.id ?? selected.id}）。这条是否适用于我的田块？请说明适用条件，以及还需要我现场核实什么。`); }}><MessageSquare size={16} />带着问题聊一聊</button></div>
        </>}
      </DialogContent>
    </Dialog>
  </section>;
}
