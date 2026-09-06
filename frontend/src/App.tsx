import { useCallback, useEffect, useRef, useState } from 'react';
import { ArrowRight, ArrowUp, BookOpen, Check, CheckCheck, ChevronDown, ClipboardList, Download, Leaf, LoaderCircle, Menu, MessageSquare, Plus, RefreshCw, Settings2, ShieldCheck, Sprout, Square, Tractor, X } from 'lucide-react';
import { SettingsDialog } from '@/components/agri/settings-dialog';
import { ContextPanel } from '@/components/agri/context-panel';
import { DataAttach } from '@/components/agri/data-attach';
import { PlanCard, RiskCard } from '@/components/agri/plan-card';
import { ClarifyCard } from '@/components/agri/clarify-card';
import { FieldsView, TasksView, KnowledgeView, type FarmTask } from '@/components/agri/workspace-views';
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog';
import { MiniMd } from '@/components/ui/mini-md';
import { api, errorText, normalizeContext, readSettings, SETTINGS_KEY, uid, type AiSettings, type Conversation } from '@/lib/api';
import type { KbEntry } from '@/lib/kb-data';
import type { ChatMessage, FieldProfile, LiveContext } from '@/types';

type View = 'chat' | 'fields' | 'tasks' | 'knowledge';
const NAV = [{ id: 'chat' as View, label: '问农心', icon: MessageSquare }, { id: 'fields' as View, label: '我的田块', icon: Sprout }, { id: 'tasks' as View, label: '农事任务', icon: ClipboardList }, { id: 'knowledge' as View, label: '农技资料', icon: BookOpen }];
const STARTERS = [
  { icon: Leaf, name: '一起排查问题', detail: '从症状出发，找到下一步', prompt: '我想排查作物叶片出现的问题，应该先观察和记录哪些信息？' },
  { icon: ClipboardList, name: '安排接下来的农事', detail: '把建议变成可执行的任务', prompt: '我想制定接下来一周的农事计划。需要向你提供哪些田块信息？' },
  { icon: Tractor, name: '读懂农情数据', detail: '附上实测记录，寻找线索', prompt: '我准备提供农情数据，请帮我分析，并说明判断依据和缺失的信息。' },
];
const newConversation = (fieldId: string | null = null): Conversation => ({ id: uid(), title: '新对话', fieldId, messages: [], createdAt: new Date().toISOString() });
const dateToday = () => { const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`; };

function BrandMark({ small = false }: { small?: boolean }) {
  const [imageReady, setImageReady] = useState(false);
  return <span className={`nx-brand-mark${small ? ' is-small' : ''}`}><img src="/brand/nongxin-mark.png" alt="" onLoad={() => setImageReady(true)} onError={() => setImageReady(false)} style={{ display: imageReady ? 'block' : 'none' }} />{!imageReady && <Sprout size={small ? 19 : 26} />}</span>;
}

export default function App() {
  const [view, setView] = useState<View>('chat'); const [mobileNav, setMobileNav] = useState(false);
  const [fields, setFields] = useState<FieldProfile[]>([]); const [tasks, setTasks] = useState<FarmTask[]>([]);
  const [knowledge, setKnowledge] = useState<KbEntry[]>([]); const [conversations, setConversations] = useState<Conversation[]>([]);
  const [current, setCurrent] = useState<Conversation>(() => newConversation());
  const [ready, setReady] = useState(false); const [loading, setLoading] = useState(false); const [loadError, setLoadError] = useState('');
  const [query, setQuery] = useState(''); const [attachment, setAttachment] = useState<string | null>(null);
  const [sending, setSending] = useState(false); const sendingRef = useRef(false); const [chatError, setChatError] = useState('');
  const [settings, setSettings] = useState<AiSettings>(readSettings); const [settingsOpen, setSettingsOpen] = useState(false);
  const [context, setContext] = useState<LiveContext | null>(null); const [contextBusy, setContextBusy] = useState(false); const [contextError, setContextError] = useState('');
  const [now, setNow] = useState(() => new Date()); const [notice, setNotice] = useState('');
  const [showArt, setShowArt] = useState(true); const [planTarget, setPlanTarget] = useState<ChatMessage | null>(null); const [planBusy, setPlanBusy] = useState(false); const [planError, setPlanError] = useState('');
  const [saveError, setSaveError] = useState('');
  const inputRef = useRef<HTMLTextAreaElement>(null); const endRef = useRef<HTMLDivElement>(null); const abortRef = useRef<AbortController | null>(null); const contextSeq = useRef(0);
  const activeField = fields.find(f => f.id === current.fieldId) ?? null;
  const pendingTasks = tasks.filter(t => !t.done);

  const loadWorkspace = useCallback(async () => {
    setLoading(true); setLoadError('');
    try {
      const [health, f, t, c, k] = await Promise.all([api<{ status: string }>('/health'), api<FieldProfile[]>('/fields'), api<FarmTask[]>('/tasks'), api<Conversation[]>('/conversations'), api<KbEntry[]>('/knowledge')]);
      if (health.status !== 'ok' || ![f, t, c, k].every(Array.isArray)) throw new Error('后端接口格式不正确，未载入工作区。');
      setFields(f); setTasks(t); setConversations(c); setKnowledge(k); setReady(true);
    } catch (e) { setLoadError(errorText(e)); setReady(false); } finally { setLoading(false); }
  }, []);
  useEffect(() => { void loadWorkspace(); const clock = window.setInterval(() => setNow(new Date()), 1000); return () => { clearInterval(clock); abortRef.current?.abort(); }; }, [loadWorkspace]);
  useEffect(() => { if (notice) { const timer = setTimeout(() => setNotice(''), 5500); return () => clearTimeout(timer); } }, [notice]);
  useEffect(() => { endRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' }); }, [current.messages.length, sending]);
  useEffect(() => { if (inputRef.current) { inputRef.current.style.height = 'auto'; inputRef.current.style.height = `${Math.min(inputRef.current.scrollHeight, 180)}px`; } }, [query, view]);

  const go = (next: View) => { setView(next); setMobileNav(false); };
  function fresh(fieldId: string | null = null) {
    if (sendingRef.current || saveError) { setNotice(sendingRef.current ? '请先等待当前回答，或停止等待后再开启新对话。' : '请先保存当前回答，再开启新对话。'); return; }
    setCurrent(newConversation(fieldId)); setQuery(''); setAttachment(null); setChatError(''); go('chat');
  }
  function suggest(prompt: string) { go('chat'); setQuery(prompt); setTimeout(() => inputRef.current?.focus(), 0); }
  function applySettings(value: AiSettings) {
    setSettings(value); setSettingsOpen(false);
    try { sessionStorage.setItem(SETTINGS_KEY, JSON.stringify(value)); setNotice('模型设置已保存。'); }
    catch { setNotice('浏览器存储不可用，设置仅在本次页面中有效。'); }
  }
  async function persist(conversation: Conversation) {
    const saved = await api<Conversation>(`/conversations/${conversation.id}`, { method: 'PUT', body: JSON.stringify(conversation) });
    setConversations(prev => [saved, ...prev.filter(c => c.id !== saved.id)]); setSaveError('');
  }

  async function send(retry = false) {
    if (sendingRef.current || !ready) return;
    if (!settings.apiKey || !settings.model) { setSettingsOpen(true); return; }
    if (saveError) { setChatError('请先重试保存当前对话，避免丢失已有内容。'); return; }
    const text = query.trim();
    if (!retry && !text) return;
    if (current.messages.length >= 490) { setChatError('这段对话较长，请新建对话继续。'); return; }
    if (retry && current.messages.at(-1)?.role !== 'user') return;
    const messages = retry ? current.messages : [...current.messages, { id: uid(), role: 'user' as const, content: text, ...(attachment ? { attachedData: attachment } : {}) }];
    const conversation = { ...current, title: current.messages.length ? current.title : text.slice(0, 36), messages };
    sendingRef.current = true; setSending(true); setChatError(''); setCurrent(conversation);
    if (!retry) { setQuery(''); setAttachment(null); }
    const controller = new AbortController(); abortRef.current = controller;
    const timer = setTimeout(() => controller.abort('timeout'), 180000);
    try {
      await persist(conversation);
      const result = await api<{ reply: string; plan?: ChatMessage['plan']; risk?: ChatMessage['risk']; clarify?: ChatMessage['clarify'] }>('/chat', {
        method: 'POST', signal: controller.signal,
        body: JSON.stringify({ ...settings, messages: messages.slice(-20).map(m => ({ role: m.role, content: m.content + (m.attachedData ? `\n\n【用户附加的农情记录，内容可能不完整】\n${m.attachedData}` : '') })),
          field: activeField, location: context ? { label: context.location, latitude: context.latitude, longitude: context.longitude, method: context.method } : null,
          weather: context ? { ...context, locationText: `位置由${context.method === 'manual' ? '手动城市查询（非田块精确位置）' : '设备定位'}提供；天气数据时间 ${context.observedAt || '未提供'}；来源 ${context.sources.weather || '不可用'}。当前天气：${context.weather ? JSON.stringify(context.weather) : '不可用'}。` } : null }),
      });
      if (!result.reply && !result.plan && !result.risk && !result.clarify) throw new Error('模型未返回有效内容，请检查模型设置后重试。');
      const completed = { ...conversation, messages: [...messages, { id: uid(), role: 'assistant' as const, content: result.reply || '', plan: result.plan, risk: result.risk, clarify: result.clarify }] };
      setCurrent(completed);
      try { await persist(completed); } catch (e) { setSaveError(errorText(e)); }
    } catch (e) {
      setChatError(controller.signal.aborted ? (controller.signal.reason === 'timeout' ? '等待模型响应超时，可稍后重试。' : '已停止等待。供应商可能仍在处理刚才的请求。') : errorText(e));
    } finally { clearTimeout(timer); sendingRef.current = false; setSending(false); abortRef.current = null; }
  }

  async function saveField(field: FieldProfile) {
    if (sendingRef.current && current.fieldId === field.id) throw new Error('当前正在使用这个田块进行对话，请等待回答后再编辑。');
    const exists = fields.some(f => f.id === field.id);
    const saved = await api<FieldProfile>(exists ? `/fields/${field.id}` : '/fields', { method: exists ? 'PUT' : 'POST', body: JSON.stringify(field) });
    setFields(prev => [saved, ...prev.filter(f => f.id !== saved.id)]); return saved;
  }
  async function removeField(id: string) {
    if (sendingRef.current && current.fieldId === id) throw new Error('当前正在使用这个田块进行对话，请等待回答后再删除。');
    await api(`/fields/${id}`, { method: 'DELETE' });
    setFields(prev => prev.filter(f => f.id !== id));
    if (current.fieldId === id) setCurrent(prev => ({ ...prev, fieldId: null }));
    setConversations(prev => prev.map(c => c.fieldId === id ? { ...c, fieldId: null } : c));
    setTasks(prev => prev.map(t => t.fieldId === id ? { ...t, fieldId: null } : t));
  }
  async function addRecord(id: string, note: string) {
    const saved = await api<FieldProfile>(`/fields/${id}/records`, { method: 'POST', body: JSON.stringify({ date: dateToday(), note }) });
    setFields(prev => prev.map(f => f.id === id ? saved : f));
  }
  async function saveTask(task: FarmTask) {
    const exists = tasks.some(t => t.id === task.id);
    const saved = await api<FarmTask>(exists ? `/tasks/${task.id}` : '/tasks', { method: exists ? 'PUT' : 'POST', body: JSON.stringify(task) });
    setTasks(prev => [saved, ...prev.filter(t => t.id !== saved.id)]); return saved;
  }
  async function removeTask(id: string) { await api(`/tasks/${id}`, { method: 'DELETE' }); setTasks(prev => prev.filter(t => t.id !== id)); }
  async function addPlan() {
    if (!planTarget?.plan?.items) return;
    setPlanBusy(true); setPlanError(''); let count = 0;
    try {
      for (const [index, item] of planTarget.plan.items.entries()) {
        const source = `${planTarget.id}-${index}`;
        if (tasks.some(t => t.sourceMessageId === source)) continue;
        const date = item.date && /^\d{4}-\d{2}-\d{2}$/.test(item.date) ? item.date : '';
        await saveTask({ id: uid(), title: item.task, date, fieldId: activeField?.id ?? null, fieldName: activeField?.name ?? '',
          condition: [item.condition, !date && item.date ? `建议时间：${item.date}（具体日期待确认）` : '', item.warning].filter(Boolean).join('\n'),
          method: [item.method, item.dosage ? `用量建议（须核验标签）：${item.dosage}` : ''].filter(Boolean).join('\n'),
          review: item.review || '', note: '来自 AI 农事建议，执行前请核对条件。', done: false, createdAt: new Date().toISOString(), sourceMessageId: source }); count++;
      }
      setPlanTarget(null); setNotice(count ? `已保存 ${count} 项任务，可在「农事任务」中调整日期与记录执行情况。` : '这些建议已加入任务，无需重复添加。');
    } catch (e) { setPlanError(`${count ? `已保存 ${count} 项，其余未保存。` : ''}${errorText(e)} 再次提交会跳过已保存的项目。`); } finally { setPlanBusy(false); }
  }

  async function fetchContext(params: URLSearchParams, method: 'device' | 'manual', accuracy: number, sequence: number) {
    try {
      const raw = await api<LiveContext>(`/context?${params}&days=7`);
      if (sequence === contextSeq.current) setContext(normalizeContext(raw, method, accuracy));
    } catch (e) { if (sequence === contextSeq.current) setContextError(errorText(e)); }
    finally { if (sequence === contextSeq.current) setContextBusy(false); }
  }
  function searchCity(city: string) {
    const seq = ++contextSeq.current; setContextBusy(true); setContextError(''); setContext(null);
    void fetchContext(new URLSearchParams({ city }), 'manual', 0, seq);
  }
  function locate() {
    if (!window.isSecureContext || !navigator.geolocation) { setContextError('当前环境不支持设备定位，请手动选择城市；远程访问需 HTTPS。'); return; }
    const seq = ++contextSeq.current; setContextBusy(true); setContextError(''); setContext(null);
    navigator.geolocation.getCurrentPosition(p => { if (seq === contextSeq.current) void fetchContext(new URLSearchParams({ lat: String(p.coords.latitude), lon: String(p.coords.longitude) }), 'device', p.coords.accuracy, seq); }, e => {
      if (seq !== contextSeq.current) return;
      setContextBusy(false); setContextError(e.code === 1 ? '定位权限未获允许。可在浏览器中开启权限，或手动输入城市。' : '设备定位失败或超时，请手动输入城市查询天气。');
    }, { enableHighAccuracy: false, timeout: 12000, maximumAge: 60000 });
  }
  function exportConversation() {
    const content = `# ${current.title}\n\n农心 Agent 对话导出 · ${new Date().toLocaleString('zh-CN')}\n\n` + current.messages.map(m => `## ${m.role === 'user' ? '我' : '农心'}\n\n${m.content}${m.attachedData ? `\n\n农情数据：\n${m.attachedData}` : ''}${m.plan ? `\n\n农事建议（AI 生成，待核验）：\n${JSON.stringify(m.plan, null, 2)}` : ''}${m.risk?.result ? `\n\n${m.risk.result}` : ''}${m.clarify ? `\n\n待确认：\n${JSON.stringify(m.clarify, null, 2)}` : ''}`).join('\n\n---\n\n');
    const url = URL.createObjectURL(new Blob([content], { type: 'text/markdown;charset=utf-8' })); const a = document.createElement('a'); a.href = url; a.download = `农心对话-${dateToday()}.md`; a.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  return <div className="nx-shell">
    {mobileNav && <button className="nx-nav-scrim" aria-label="收起导航" onClick={() => setMobileNav(false)} />}
    <aside className={`nx-sidebar${mobileNav ? ' is-open' : ''}`}>
      <a className="nx-brand" href="#" onClick={e => { e.preventDefault(); go('chat'); }}><BrandMark /><span><strong>农心 <i>Agent</i></strong><small>把农事，放在心上。</small></span></a>
      <button className="nx-new-chat" disabled={sending || !!saveError} onClick={() => fresh()}><Plus size={18} />开启新对话<span>↗</span></button>
      <div className="nx-nav-label">工作空间</div>
      <nav aria-label="主导航">{NAV.map(n => <button key={n.id} className={view === n.id ? 'is-active' : ''} onClick={() => go(n.id)}><n.icon size={18} /><span>{n.label}</span>{n.id === 'tasks' && pendingTasks.length > 0 && <b>{pendingTasks.length}</b>}</button>)}</nav>
      <div className="nx-history"><div className="nx-nav-label">最近对话 <span>{ready ? conversations.length : '—'}</span></div>{conversations.length ? conversations.slice(0, 12).map(c => <button key={c.id} className={current.id === c.id ? 'is-active' : ''} disabled={sending || !!saveError} onClick={() => { setCurrent(c); setQuery(''); setAttachment(null); setChatError(''); go('chat'); }}><MessageSquare size={13} /><span>{c.title}</span></button>) : <p>你的对话会保存在这里</p>}</div>
      <ContextPanel context={context} now={now} busy={contextBusy} error={contextError} onLocate={locate} onSearch={searchCity} onClear={() => { contextSeq.current++; setContext(null); setContextError(''); setContextBusy(false); }} />
      <div className="nx-sidebar-bottom"><span className={`nx-connection-dot${ready ? ' is-online' : ''}`} />{loading ? '连接工作区…' : ready ? 'Java 服务已连接' : '工作区未连接'}<span>v1.0</span></div>
    </aside>
    <main className="nx-main">
      <header className="nx-topbar"><div className="nx-topbar-left"><button className="nx-icon-button nx-menu" aria-label="打开导航" onClick={() => setMobileNav(true)}><Menu size={21} /></button><span className="nx-breadcrumb">工作空间 <span>/</span> <b>{NAV.find(n => n.id === view)?.label}</b></span></div><div className="nx-topbar-actions"><span className="nx-model-label">{settings.apiKey ? settings.model : '尚未连接模型'}</span><button className="nx-button is-small" onClick={() => setSettingsOpen(true)}><Settings2 size={16} /><span>模型设置</span></button></div></header>
      {loadError && <div className="nx-global-error" role="alert"><span>{loadError}</span><button disabled={loading} onClick={() => void loadWorkspace()}><RefreshCw size={14} />重新连接</button></div>}
      {notice && <div className="nx-notice" role="status"><Check size={16} />{notice}<button aria-label="关闭提示" onClick={() => setNotice('')}><X size={14} /></button></div>}
      {view === 'chat' ? <div className={`nx-chat-layout${current.messages.length ? ' has-messages' : ''}`}>
        <div className="nx-chat-main">
          <div className="nx-conversation-bar"><label className="nx-field-select"><Sprout size={15} /><select aria-label="本次对话关联田块" value={current.fieldId || ''} disabled={sending || !!saveError} onChange={e => {
            const fieldId = e.target.value || null;
            if (current.messages.length) { fresh(fieldId); setNotice('已为所选田块开启新对话，避免混用田块信息。'); }
            else setCurrent(prev => ({ ...prev, fieldId }));
          }}><option value="">不关联田块 · 通用咨询</option>{fields.map(f => <option key={f.id} value={f.id}>{f.name} · {f.crop}</option>)}</select><ChevronDown size={13} /></label>{current.messages.length > 0 && <button className="nx-icon-button" title="导出当前对话" aria-label="导出当前对话" onClick={exportConversation}><Download size={17} /></button>}</div>
          <div className="nx-conversation-scroll">
            {!current.messages.length ? <section className="nx-welcome">
              <div className="nx-welcome-heading"><span className="nx-eyebrow"><span />NONGXIN · YOUR FARMING COMPANION</span><h1>每一份耕耘，<br />都有<span>回应。</span></h1><p>从一个问题开始，和农心一起理清田里的事。</p></div>
              {showArt && <figure className="nx-brand-landscape"><img src="/brand/field-study.png" alt="绿色田垄的品牌概念影像" onError={() => setShowArt(false)} /><figcaption><span>从田间出发，向每一步行动。</span><small>品牌概念影像 · 非实测田块</small></figcaption></figure>}
              <div className="nx-starters">{STARTERS.map(s => <button key={s.name} onClick={() => suggest(s.prompt)}><s.icon size={21} /><b>{s.name}<ArrowRight size={15} /></b><span>{s.detail}</span></button>)}</div>
            </section> : <div className="nx-message-list" role="log" aria-label="对话记录" aria-live="polite">{current.messages.map(m => <article key={m.id} className={`nx-message is-${m.role}`}>{m.role === 'assistant' && <BrandMark small />}<div className="nx-message-body"><div className="nx-message-name">{m.role === 'assistant' ? '农心' : '我'}{m.role === 'assistant' && <span>AI 助手</span>}</div><div className="nx-message-content"><MiniMd text={m.content} />{m.attachedData && <details className="nx-message-data"><summary>已附加农情数据 · {m.attachedData.length} 字符</summary><pre>{m.attachedData}</pre></details>}</div>{m.plan && <PlanCard plan={m.plan} disabled={sending || planBusy || !ready} onAdd={() => { setPlanTarget(m); setPlanError(''); }} />}{m.risk && <RiskCard risk={m.risk} />}{m.clarify && <ClarifyCard clarify={m.clarify} disabled={sending} onPick={(q, opt) => suggest(`${q}\n${opt}`)} />}</div></article>)}{sending && <div className="nx-thinking"><LoaderCircle size={16} className="spin" />正在结合你提供的信息思考…<button onClick={() => abortRef.current?.abort()}>停止等待</button></div>}<div ref={endRef} /></div>}
          </div>
          <div className="nx-composer-wrap">
            {chatError && <div className="nx-chat-error" role="alert"><span>{chatError}</span>{current.messages.at(-1)?.role === 'user' && <button disabled={sending} onClick={() => void send(true)}>重试这条问题</button>}</div>}
            {saveError && <div className="nx-chat-error" role="alert"><span>回答尚未保存到数据库：{saveError}。请勿刷新页面。</span><button onClick={() => void persist(current).catch(e => setSaveError(errorText(e)))}>重试保存</button></div>}
            <form className="nx-composer" onSubmit={e => { e.preventDefault(); void send(); }}><label className="sr-only" htmlFor="chat-input">向农心提问</label><textarea id="chat-input" ref={inputRef} value={query} maxLength={850} rows={2} disabled={sending} placeholder={activeField ? `关于${activeField.name}，有什么想聊的？` : '说说田里的情况，或者问一个农业问题…'} onChange={e => setQuery(e.target.value)} onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing && e.keyCode !== 229) { e.preventDefault(); void send(); } }} /><div className="nx-composer-tools"><DataAttach attached={attachment} onAttach={setAttachment} onClear={() => setAttachment(null)} disabled={sending} /><span className="nx-composer-hint">{attachment ? '附加数据将在发送时提交' : 'Enter 发送 · Shift + Enter 换行'}</span>{sending ? <button className="nx-send" type="button" aria-label="停止等待" onClick={() => abortRef.current?.abort()}><Square size={16} /></button> : <button className="nx-send" type="submit" aria-label="发送问题" disabled={!query.trim() || !ready}><ArrowUp size={20} /></button>}</div></form>
            <p className="nx-composer-foot"><ShieldCheck size={12} />不把未知当事实。AI 建议供参考，重要农事请结合现场判断。</p>
          </div>
        </div>
        <aside className="nx-context-rail"><div className="nx-rail-section"><span className="nx-eyebrow">CONTEXT</span><h3>这次对话的依据</h3><p>只带入你提供的信息。</p><dl><div><dt>关联田块</dt><dd>{activeField?.name || '未关联'}</dd></div><div><dt>种植作物</dt><dd>{activeField?.crop || '未提供'}</dd></div><div><dt>播种日期</dt><dd>{activeField?.sowDate || '未提供'}</dd></div><div><dt>天气背景</dt><dd>{context?.weather ? context.location : '未获取'}</dd></div></dl>{!activeField && <button className="nx-text-button" onClick={() => go('fields')}>管理田块档案 <ArrowRight size={14} /></button>}</div>
          <div className="nx-rail-section"><span className="nx-eyebrow">NEXT STEP</span><h3>从建议，到行动</h3><ol className="nx-workflow"><li><span>1</span><div><b>说清情况</b><small>描述问题，按需补充数据</small></div></li><li><span>2</span><div><b>一起判断</b><small>分清已知、依据与待确认</small></div></li><li><span>3</span><div><b>确认后行动</b><small>把建议加入任务，记录复查</small></div></li></ol></div>
          <div className="nx-rail-task"><CheckCheck size={20} /><div><b>{ready ? `${pendingTasks.length} 项待办农事` : '待办尚未载入'}</b><p>{pendingTasks.length ? '留一点时间，看看下一步。' : '有了计划，再从这里开始。'}</p></div><button aria-label="查看农事任务" onClick={() => go('tasks')}><ArrowRight size={16} /></button></div>
        </aside>
      </div> : <div className="nx-page-scroll">{!ready ? <div className="nx-empty"><LoaderCircle size={28} className={loading ? 'spin' : ''} /><h2>{loading ? '正在载入工作区' : '工作区尚未连接'}</h2><p>连接成功后会展示数据库中的真实记录。</p><button className="nx-button" disabled={loading} onClick={() => void loadWorkspace()}>重新连接</button></div> : view === 'fields' ? <FieldsView fields={fields} onSave={saveField} onDelete={removeField} onRecord={addRecord} onAsk={id => fresh(id)} /> : view === 'tasks' ? <TasksView tasks={tasks} fields={fields} onSave={saveTask} onDelete={removeTask} /> : <KnowledgeView entries={knowledge} onAsk={suggest} />}</div>}
    </main>
    <SettingsDialog open={settingsOpen} onOpenChange={setSettingsOpen} settings={settings} onSave={applySettings} />
    <Dialog open={!!planTarget} onOpenChange={open => { if (!open && !planBusy) setPlanTarget(null); }}><DialogContent className="nx-dialog" showCloseButton={!planBusy}><DialogTitle>确认加入农事任务</DialogTitle><DialogDescription>这只是建议清单，不代表已执行。日期不明确的项目会保留为“待定日期”，请在任务中核对条件与操作方法。</DialogDescription><ul className="nx-plan-confirm">{planTarget?.plan?.items?.map((item, i) => <li key={i}><Check size={15} /><span>{item.task}</span><small>{item.date || '日期待定'}</small></li>)}</ul><p className="nx-muted">关联田块：{activeField?.name || '不关联田块'}</p>{planError && <p className="nx-error" role="alert">{planError}</p>}<div className="nx-dialog-actions"><button disabled={planBusy} className="nx-button" onClick={() => setPlanTarget(null)}>再想想</button><button disabled={planBusy} className="nx-button is-primary" onClick={() => void addPlan()}>{planBusy ? '正在保存…' : '确认加入任务'}</button></div></DialogContent></Dialog>
  </div>;
}
