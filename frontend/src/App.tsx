import { useCallback, useEffect, useRef, useState, type ClipboardEvent as ReactClipboardEvent, type CSSProperties, type KeyboardEvent as ReactKeyboardEvent, type PointerEvent as ReactPointerEvent } from 'react';
import { ArrowRight, ArrowUp, ArrowUpRight, BookOpen, Check, CheckCheck, ChevronDown, ClipboardList, Download, Leaf, LoaderCircle, Menu, MessageSquare, Plus, RefreshCw, Settings2, ShieldCheck, Smartphone, Sprout, Square, Tractor, X } from 'lucide-react';
import { SettingsDialog } from '@/components/agri/settings-dialog';
import { MobileDialog } from '@/components/agri/mobile-dialog';
import { ContextPanel } from '@/components/agri/context-panel';
import { DataAttach } from '@/components/agri/data-attach';
import { ImageAttach, ImageStrip } from '@/components/agri/image-attach';
import { VoiceInput } from '@/components/agri/voice-input';
import { PlanCard, RiskCard } from '@/components/agri/plan-card';
import { SourceCard } from '@/components/agri/source-card';
import { ClarifyCard } from '@/components/agri/clarify-card';
import { ConversationActions } from '@/components/agri/conversation-actions';
import { FieldsView, TasksView, KnowledgeView, type FarmTask } from '@/components/agri/workspace-views';
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog';
import { NxSelect } from '@/components/ui/nx-select';
import { MiniMd } from '@/components/ui/mini-md';
import { api, errorText, normalizeContext, readSettings, SETTINGS_KEY, uid, type AiSettings, type Conversation } from '@/lib/api';
import { hasClarifyFollowUp, setClarifyAnswer, type ClarifyAnswers } from '@/lib/clarification';
import { captureContext, requestHistory, retryMessages, streamChat } from '@/lib/chat';
import { imageFilesFrom, uploadPhoto } from '@/lib/images';
import type { AttachedImage, ChatMessage, FieldProfile, KnowledgeSource, LiveContext } from '@/types';

type View = 'chat' | 'fields' | 'tasks' | 'knowledge';
const NAV = [{ id: 'chat' as View, label: '问农心', icon: MessageSquare }, { id: 'fields' as View, label: '我的田块', icon: Sprout }, { id: 'tasks' as View, label: '农事任务', icon: ClipboardList }, { id: 'knowledge' as View, label: '农技资料', icon: BookOpen }];
const STARTERS = [
  { icon: Leaf, name: '一起排查问题', detail: '从症状出发，找到下一步', prompt: '我想排查作物叶片出现的问题，应该先观察和记录哪些信息？' },
  { icon: ClipboardList, name: '安排接下来的农事', detail: '把建议变成可执行的任务', prompt: '我想制定接下来一周的农事计划。需要向你提供哪些田块信息？' },
  { icon: Tractor, name: '读懂农情数据', detail: '附上实测记录，寻找线索', prompt: '我准备提供农情数据，请帮我分析，并说明判断依据和缺失的信息。' },
];
const newConversation = (fieldId: string | null = null): Conversation => ({ id: uid(), title: '新对话', fieldId, messages: [], createdAt: new Date().toISOString() });
const dateToday = () => { const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`; };
/** 思考提示只显示一行：取流式文本里最后一行有内容的，压平并截断 */
const lastLine = (text: string) => text.split('\n').map(line => line.trim()).filter(Boolean).at(-1)?.slice(0, 80) ?? '';

// 两侧栏宽度：可拖动/键盘调节，记住上次的选择（纯界面偏好，与业务数据无关）
const SIDEBAR_DEFAULT = 230, SIDEBAR_MIN = 180, SIDEBAR_MAX = 460, SIDEBAR_KEY = 'nongxin-sidebar-width';
const RAIL_DEFAULT = 250, RAIL_MIN = 200, RAIL_MAX = 420, RAIL_KEY = 'nongxin-rail-width';
const clampWidth = (value: number, min: number, max: number) => Math.round(Math.min(max, Math.max(min, value)));
function readPanelWidth(key: string, fallback: number, min: number, max: number) {
  try {
    const stored = Number(localStorage.getItem(key));
    return Number.isFinite(stored) && stored > 0 ? clampWidth(stored, min, max) : fallback;
  } catch { return fallback; }
}
/**
 * 可调节宽度的侧栏。
 * direction = 1 表示面板在左侧（向右拖变宽），-1 表示面板在右侧（向左拖变宽）；
 * 方向键始终让分隔条朝着按键方向移动，与鼠标拖动的手感一致。
 */
function usePanelWidth(key: string, initial: number, min: number, max: number, direction: 1 | -1) {
  const [width, setWidth] = useState(() => readPanelWidth(key, initial, min, max));
  const [resizing, setResizing] = useState(false);
  useEffect(() => {
    try { localStorage.setItem(key, String(width)); } catch { /* 存储不可用时忽略，不影响使用 */ }
  }, [key, width]);
  const apply = (value: number) => setWidth(clampWidth(value, min, max));
  function startResize(event: ReactPointerEvent<HTMLDivElement>) {
    event.preventDefault();
    const startX = event.clientX;
    const startWidth = width;
    setResizing(true);
    const move = (moveEvent: PointerEvent) => apply(startWidth + direction * (moveEvent.clientX - startX));
    const finish = () => {
      setResizing(false);
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', finish);
      window.removeEventListener('pointercancel', finish);
    };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', finish);
    window.addEventListener('pointercancel', finish);
  }
  function onKeyDown(event: ReactKeyboardEvent<HTMLDivElement>) {
    const step = event.shiftKey ? 48 : 16;
    const widenKey = direction === 1 ? 'ArrowRight' : 'ArrowLeft';
    const narrowKey = direction === 1 ? 'ArrowLeft' : 'ArrowRight';
    if (event.key === widenKey) { event.preventDefault(); apply(width + step); }
    else if (event.key === narrowKey) { event.preventDefault(); apply(width - step); }
    else if (event.key === 'Home' || event.key === 'Escape') { event.preventDefault(); apply(initial); }
  }
  return { width, resizing, startResize, onKeyDown, reset: () => apply(initial), min, max };
}
/** 媒体查询订阅：用于按屏宽决定天气模块放在右侧栏还是左侧栏。 */
function useMediaQuery(query: string) {
  const [matches, setMatches] = useState(() => (typeof window !== 'undefined' && typeof window.matchMedia === 'function' ? window.matchMedia(query).matches : true));
  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return;
    const list = window.matchMedia(query);
    const update = () => setMatches(list.matches);
    update();
    list.addEventListener?.('change', update);
    return () => list.removeEventListener?.('change', update);
  }, [query]);
  return matches;
}

function BrandMark({ small = false }: { small?: boolean }) {
  const [imageReady, setImageReady] = useState(false);
  return <span className={`nx-brand-mark${small ? ' is-small' : ''}`}><img src="/brand/nongxin-mark.png" alt="" onLoad={() => setImageReady(true)} onError={() => setImageReady(false)} style={{ display: imageReady ? 'block' : 'none' }} />{!imageReady && <Sprout size={small ? 19 : 26} />}</span>;
}

export default function App() {
  const [view, setView] = useState<View>('chat'); const [mobileNav, setMobileNav] = useState(false);
  const [fields, setFields] = useState<FieldProfile[]>([]); const [tasks, setTasks] = useState<FarmTask[]>([]);
  const [knowledge, setKnowledge] = useState<KnowledgeSource[]>([]); const [conversations, setConversations] = useState<Conversation[]>([]);
  const [current, setCurrent] = useState<Conversation>(() => newConversation());
  const [ready, setReady] = useState(false); const [loading, setLoading] = useState(false); const [loadError, setLoadError] = useState('');
  const [query, setQuery] = useState(''); const [attachment, setAttachment] = useState<string | null>(null);
  const [images, setImages] = useState<AttachedImage[]>([]);
  /** 当前模型是否按"能看图"处理（由后端名单 + 用户设置决定），仅用于提前提示，不阻止发问 */
  const [visionReady, setVisionReady] = useState(true);
  /** 当前田块的田间照片档案 + 是否随问题自动带上最近几张（用户可在作曲区取消） */
  const [fieldPhotos, setFieldPhotos] = useState<AttachedImage[]>([]);
  const [autoFieldPhotos, setAutoFieldPhotos] = useState(true);
  const [imageError, setImageError] = useState('');
  const [imageBusy, setImageBusy] = useState(false);

  /** 选文件 / 粘贴 / 拖入 都走这里：压缩去 EXIF → 上传 → 挂到待发送列表（最多 3 张） */
  async function addPhotos(files: File[]) {
    if (!files.length || sendingRef.current) return;
    setImageError('');
    const room = 3 - images.length;
    if (room <= 0) { setImageError('一次最多 3 张照片：全株、病部近景、健康对照各一张就够定位问题了。'); return; }
    const chosen = files.slice(0, room);
    if (files.length > room) setImageError(`一次最多 3 张，已接收前 ${room} 张。`);
    setImageBusy(true);
    const added: AttachedImage[] = [];
    for (const file of chosen) {
      try {
        added.push(await uploadPhoto(file, { fieldId: activeField?.id, observedAt: dateToday() }));
      } catch (cause) {
        setImageError(cause instanceof Error ? cause.message : '图片上传失败，请重试。文字草稿不会丢。');
      }
    }
    if (added.length) setImages(current => [...current, ...added]);
    setImageBusy(false);
  }
  /** 粘贴图片：输入框内 Ctrl+V 直接贴图（截图后最常用） */
  function handlePaste(event: ReactClipboardEvent<HTMLTextAreaElement>) {
    const files = imageFilesFrom(event.clipboardData?.items);
    if (!files.length) return;   // 纯文字粘贴交给浏览器默认行为
    event.preventDefault();
    void addPhotos(files);
  }
  const [clarifyAnswers, setClarifyAnswers] = useState<Record<string, ClarifyAnswers>>({});
  const [sending, setSending] = useState(false); const sendingRef = useRef(false); const [chatError, setChatError] = useState('');
  const [streamText, setStreamText] = useState(''); const [streamStatus, setStreamStatus] = useState('');
  /** 一行思考提示：上一轮被 reset 掉的文字留一行预览，正文只在最终回答时出现 */
  const [thinkingLine, setThinkingLine] = useState('');
  const [includeWeather, setIncludeWeather] = useState(false);
  const [conversationBusy, setConversationBusy] = useState(false); const conversationBusyRef = useRef(false);
  const drafts = useRef<Record<string, { query: string; attachment: string | null; images?: AttachedImage[] }>>({});
  const [settings, setSettings] = useState<AiSettings>(readSettings); const [settingsOpen, setSettingsOpen] = useState(false);
  const [mobileOpen, setMobileOpen] = useState(false);
  const [context, setContext] = useState<LiveContext | null>(null); const [contextBusy, setContextBusy] = useState(false); const [contextError, setContextError] = useState('');
  const [now, setNow] = useState(() => new Date()); const [notice, setNotice] = useState('');
  const [showArt, setShowArt] = useState(true); const [planTarget, setPlanTarget] = useState<ChatMessage | null>(null); const [planBusy, setPlanBusy] = useState(false); const [planError, setPlanError] = useState('');
  const [saveError, setSaveError] = useState('');
  const sidebar = usePanelWidth(SIDEBAR_KEY, SIDEBAR_DEFAULT, SIDEBAR_MIN, SIDEBAR_MAX, 1);
  const rail = usePanelWidth(RAIL_KEY, RAIL_DEFAULT, RAIL_MIN, RAIL_MAX, -1);
  // 窄屏（≤1000px）时右侧栏整栏隐藏，天气模块必须回到左侧栏，否则手机上完全没有天气入口
  const railVisible = useMediaQuery('(min-width: 1001px)');
  const inputRef = useRef<HTMLTextAreaElement>(null); const abortRef = useRef<AbortController | null>(null); const contextSeq = useRef(0);
  /** 自动定位只尝试一次，避免每次重渲染都请求位置 */
  const locateTried = useRef(false);
  const scrollRef = useRef<HTMLDivElement>(null); const followScroll = useRef(true);
  const activeField = fields.find(f => f.id === current.fieldId) ?? null;
  const pendingTasks = tasks.filter(t => ['pending_confirmation', 'pending', 'awaiting_review'].includes(t.status));
  const lastMessage = current.messages.at(-1);
  /** 只有最新一张方案卡能加入任务：模型每轮都会重排一版方案，旧卡再点就会堆出重复待办 */
  const latestPlanId = [...current.messages].reverse().find(message => message.plan)?.id;
  /** 生成过程只显示一行：优先当前轮的最后一个有内容的行，其次保留上一轮的一行预览 */
  const previewLine = lastLine(streamText) || thinkingLine;
  const [collapsedGroups, setCollapsedGroups] = useState<Record<string, boolean>>({});
  // 最近对话按田块分组：组顺序取"最近有对话的田块在前"，组内保持服务端的时间倒序
  const historyGroups = (() => {
    const groups: Array<{ key: string; label: string; hint: string; items: Conversation[] }> = [];
    const index = new Map<string, number>();
    for (const conversation of conversations) {
      const key = conversation.fieldId ?? '__none__';
      let at = index.get(key);
      if (at === undefined) {
        at = groups.length; index.set(key, at);
        const field = fields.find(f => f.id === conversation.fieldId);
        groups.push({ key, label: field ? field.name : '未关联田块', hint: field ? field.crop : '通用咨询', items: [] });
      }
      groups[at].items.push(conversation);
    }
    return groups;
  })();
  // Interrupted and partially completed answers need different wording: only the latter keeps tool cards.
  const incompleteHint = lastMessage?.error || (lastMessage?.degraded
    ? '这条回答未完整生成，已保留的工具结果可以继续使用，也可以重试获得完整回答。'
    : '这条问题尚未收到完整回答，可重试。');

  const loadWorkspace = useCallback(async () => {
    setLoading(true); setLoadError('');
    try {
      const [health, f, t, c, k] = await Promise.all([api<{ status: string }>('/health'), api<FieldProfile[]>('/fields'), api<FarmTask[]>('/tasks'), api<Conversation[]>('/conversations'), api<KnowledgeSource[]>('/knowledge')]);
      if (health.status !== 'ok' || ![f, t, c, k].every(Array.isArray)) throw new Error('后端接口格式不正确，未载入工作区。');
      setFields(f); setTasks(t); setConversations(c); setKnowledge(k); setReady(true);
    } catch (e) { setLoadError(errorText(e)); setReady(false); } finally { setLoading(false); }
  }, []);
  useEffect(() => { void loadWorkspace(); const clock = window.setInterval(() => setNow(new Date()), 1000); return () => { clearInterval(clock); abortRef.current?.abort(); }; }, [loadWorkspace]);
  useEffect(() => { if (notice) { const timer = setTimeout(() => setNotice(''), 5500); return () => clearTimeout(timer); } }, [notice]);
  useEffect(() => {
    // Follow the newest content instantly: CSS scroll-behavior:smooth would restart its animation on
    // every streaming delta and make the view lag behind. Switching conversations also jumps to the end.
    const el = scrollRef.current;
    if (!el || !followScroll.current) return;
    // Older engines (and the JSDOM test DOM) have no Element.scrollTo; scrollTop is the safe fallback.
    if (typeof el.scrollTo === 'function') el.scrollTo({ top: el.scrollHeight, behavior: 'instant' });
    else el.scrollTop = el.scrollHeight;
  }, [current.id, current.messages.length, sending, streamText, streamStatus]);
  useEffect(() => { if (inputRef.current) { inputRef.current.style.height = 'auto'; inputRef.current.style.height = `${Math.min(inputRef.current.scrollHeight, 180)}px`; } }, [query, view]);

  const go = (next: View) => { setView(next); setMobileNav(false); };
  function fresh(fieldId: string | null = null) {
    if (conversationBusyRef.current) return;
    if (sendingRef.current || saveError) { setNotice(sendingRef.current ? '请先等待当前回答，或停止等待后再开启新对话。' : '请先保存当前回答，再开启新对话。'); return; }
    drafts.current[current.id] = { query, attachment, images };
    setCurrent(newConversation(fieldId)); setQuery(''); setAttachment(null); setImages([]); setChatError(''); setIncludeWeather(false); followScroll.current = true; go('chat');
  }
  function openConversation(conversation: Conversation) {
    if (sendingRef.current || saveError || conversationBusyRef.current) return;
    drafts.current[current.id] = { query, attachment, images };
    const draft = drafts.current[conversation.id];
    setCurrent(conversation); setQuery(draft?.query || ''); setAttachment(draft?.attachment || null); setImages(draft?.images ?? []);
    setChatError(conversation.messages.at(-1)?.error || ''); setIncludeWeather(false); followScroll.current = true; go('chat');
  }
  async function renameConversation(id: string, title: string) {
    if (sendingRef.current || saveError || conversationBusyRef.current) throw new Error('请等待当前操作完成后再重命名。');
    conversationBusyRef.current = true; setConversationBusy(true);
    try {
      const saved = await api<Conversation>(`/conversations/${id}`, { method: 'PATCH', body: JSON.stringify({ title }) });
      setConversations(prev => prev.map(c => c.id === id ? saved : c));
      setCurrent(prev => prev.id === id ? { ...prev, title: saved.title } : prev);
      setNotice('对话标题已保存。');
    } finally { conversationBusyRef.current = false; setConversationBusy(false); }
  }
  async function deleteConversation(id: string) {
    if (sendingRef.current || saveError || conversationBusyRef.current) throw new Error('请等待当前操作完成后再删除。');
    conversationBusyRef.current = true; setConversationBusy(true);
    try {
      await api(`/conversations/${id}`, { method: 'DELETE' });
      setConversations(prev => prev.filter(c => c.id !== id)); delete drafts.current[id];
      const removed = conversations.find(c => c.id === id);
      setClarifyAnswers(prev => Object.fromEntries(Object.entries(prev).filter(([key]) => !removed?.messages.some(m => m.id === key))));
      if (current.id === id) { setCurrent(newConversation()); setQuery(''); setAttachment(null); setImages([]); setChatError(''); setIncludeWeather(false); }
      setNotice('对话已删除，无法撤销。田块与已创建的任务仍然保留。');
    } finally { conversationBusyRef.current = false; setConversationBusy(false); }
  }
  function suggest(prompt: string) { go('chat'); setQuery(prompt); setTimeout(() => inputRef.current?.focus(), 0); }
  // 当前田块的田间照片档案：田块变化时刷新，用于"自动带最近几张"的提示与开关
  useEffect(() => {
    const fieldId = activeField?.id;
    if (!fieldId) { setFieldPhotos([]); return; }
    const controller = new AbortController();
    void api<{ photos: AttachedImage[] }>(`/uploads/field/${fieldId}`, { signal: controller.signal })
      .then(result => setFieldPhotos(Array.isArray(result.photos) ? result.photos : []))
      .catch(() => setFieldPhotos([]));
    return () => controller.abort();
  }, [activeField?.id, current.id]);

  /** 从田块页发起"看最近状况"：新开该田块的对话，并自动带上最近几张照片 */
  function analyzeField(fieldId: string) {
    const field = fields.find(item => item.id === fieldId);
    drafts.current[current.id] = { query, attachment, images };
    setCurrent(newConversation(fieldId)); setQuery(''); setAttachment(null); setImages([]);
    setQuery(`看看${field?.name ?? '这块地'}最近的状况：和之前比，叶色、病斑和长势有哪些变化？接下来该怎么安排？`);
    setAutoFieldPhotos(true);
    go('chat'); setTimeout(() => inputRef.current?.focus(), 0);
  }
  // 当前模型是否按"能看图"处理：名单判定在服务端，这里只用来提前提示，不阻止用户先选图
  useEffect(() => {
    if (!settings.model) { setVisionReady(true); return; }
    const controller = new AbortController();
    void api<{ supported: boolean }>(`/chat/vision?model=${encodeURIComponent(settings.model)}&imageInput=${settings.vision ?? 'auto'}`, { signal: controller.signal })
      .then(result => setVisionReady(result.supported !== false))
      .catch(() => { /* 判定失败不阻塞发问，真正的拒绝由发送时的 415 明确给出 */ });
    return () => controller.abort();
  }, [settings.model, settings.vision]);
  function applySettings(value: AiSettings) {
    setSettings(value); setSettingsOpen(false);
    try { sessionStorage.setItem(SETTINGS_KEY, JSON.stringify(value)); setNotice('模型设置已保存。'); }
    catch { setNotice('浏览器存储不可用，设置仅在本次页面中有效。'); }
  }
  const saveChain = useRef<Promise<unknown>>(Promise.resolve());
  async function persist(conversation: Conversation) {
    // Serialise saves per conversation update order so a late stale response can never overwrite newer messages.
    const run = async () => {
      const saved = await api<Conversation>(`/conversations/${conversation.id}`, { method: 'PUT', body: JSON.stringify(conversation) });
      setConversations(prev => [saved, ...prev.filter(c => c.id !== saved.id)]); setSaveError('');
      return saved;
    };
    const next = saveChain.current.then(run, run);
    saveChain.current = next.catch(() => {});
    return next;
  }

  async function send(retry = false, directReply?: string): Promise<boolean> {
    if (sendingRef.current || conversationBusyRef.current || !ready) return false;
    if (!settings.apiKey || !settings.model) { setSettingsOpen(true); return false; }
    if (saveError) { setChatError('请先重试保存当前对话，避免丢失已有内容。'); return false; }
    const text = (directReply ?? query).trim();
    if (!retry && !text) return false;
    if (current.messages.length >= 490) { setChatError('这段对话较长，请新建对话继续。'); return false; }
    const previous = retry ? retryMessages(current.messages) : current.messages;
    if (!previous) return false;
    // A card submission is its own message: do not overwrite or accidentally send the composer's draft/attachment.
    const withComposer = directReply === undefined && !retry;
    const messages = retry ? previous : [...previous, { id: uid(), role: 'user' as const, content: text,
      requestContext: captureContext(activeField, includeWeather ? context : null),
      ...(withComposer && attachment ? { attachedData: attachment } : {}),
      ...(withComposer && images.length ? { images } : {}) }];
    const sentImageIds = retry ? [] : images.map(image => image.id);
    const requestContext = messages.at(-1)?.requestContext ?? captureContext(activeField, null);
    let history: ReturnType<typeof requestHistory>;
    try { history = requestHistory(messages); } catch (e) { setChatError(errorText(e)); return false; }
    const conversation = { ...current, title: current.messages.length ? current.title : text.slice(0, 36), messages };
    const assistantId = uid(); let partial = '';
    const interrupted = (error: string): Conversation => ({ ...conversation, messages: [...messages, {
      id: assistantId, role: 'assistant', content: partial, status: 'interrupted', error,
    }] });
    sendingRef.current = true; setSending(true); setChatError(''); setStreamText(''); setThinkingLine(''); setStreamStatus('正在思考…'); followScroll.current = true;
    const pending = interrupted('上次回答尚未完成，可重试这条问题。'); setCurrent(pending);
    if (!retry && directReply === undefined) { setQuery(''); setAttachment(null); setImages([]); }
    const controller = new AbortController(); abortRef.current = controller;
    const timer = setTimeout(() => controller.abort('timeout'), 180000);
    try {
      await persist(pending);
      controller.signal.throwIfAborted();
      // 会话内此前各轮已检索命中的来源ID：多轮对话里模型会继续引用它们，后端校验需要这份白名单
      const priorSources = [...new Set(messages.flatMap(m => (m.sources ?? []).map(source => source.id)))];
      const result = await streamChat({ ...settings, messages: history, ...requestContext, priorSources,
        imageIds: sentImageIds, imageInput: settings.vision ?? 'auto',
        autoFieldPhotos: autoFieldPhotos && !!activeField && fieldPhotos.length > 0 }, controller.signal, ({ event, data }) => {
        if (controller.signal.aborted) return;
        // 工具轮会发 reset 清空正文；这里只保留"一行思考提示"，避免出现"文字出现又消失"的观感
        if (event === 'reset') { if (partial.trim()) setThinkingLine(lastLine(partial)); partial = ''; setStreamText(''); }
        if (event === 'delta' && typeof data.text === 'string') { partial += data.text; setStreamText(partial); setStreamStatus('正在思考…'); }
        if (event === 'status' && typeof data.text === 'string') setStreamStatus(data.text);
      });
      controller.signal.throwIfAborted(); clearTimeout(timer);
      const completed = { ...conversation, messages: [...messages, { id: assistantId, role: 'assistant' as const, content: result.reply || '', plan: result.plan, risk: result.risk, clarify: result.clarify, sources: result.sources?.length ? result.sources : undefined, ...(result.degraded === true ? { degraded: true } : {}) }] };
      setStreamText(''); setStreamStatus('正在保存回答…');
      setCurrent(completed);
      try { await persist(completed); } catch (e) { setSaveError(errorText(e)); }
    } catch (e) {
      const error = controller.signal.aborted ? (controller.signal.reason === 'timeout' ? '等待模型响应超时，可稍后重试。' : '已停止回答。供应商可能仍在处理刚才的请求。') : e instanceof TypeError ? '对话连接失败，请检查网络与 Java 服务后重试。' : errorText(e);
      setChatError(error); const failed = interrupted(error); setCurrent(failed);
      try { await persist(failed); } catch (saveFailure) { setSaveError(errorText(saveFailure)); }
    } finally { clearTimeout(timer); sendingRef.current = false; setSending(false); abortRef.current = null; setStreamText(''); setStreamStatus(''); }
    return true;
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
  async function changeTaskStatus(id: string, status: string) {
    const saved = await api<FarmTask>(`/tasks/${id}/status`, { method: 'POST', body: JSON.stringify({ status }) });
    setTasks(prev => prev.map(t => t.id === saved.id ? saved : t)); return saved;
  }
  async function addTaskRecord(id: string, record: { kind: string; date: string; note: string; outcome?: string }) {
    const saved = await api<FarmTask>(`/tasks/${id}/records`, { method: 'POST', body: JSON.stringify(record) });
    setTasks(prev => prev.map(t => t.id === saved.id ? saved : t)); return saved;
  }
  /** 把任务与用户记录显式写进提问草稿：回到对话讨论时，模型看到的是"你做了什么"，而不是把建议当成已执行。 */
  function discussTask(task: FarmTask) {
    const records = task.records ?? [];
    const lines = [
      `关于任务「${task.title}」（当前状态：${task.statusLabel || task.status}${task.fieldName ? `，田块：${task.fieldName}` : ''}${task.date ? `，计划日期：${task.date}` : ''}）想继续讨论：`,
      task.condition ? `- 原定执行条件：${task.condition}` : '',
      task.review ? `- 原定复查要点：${task.review}` : '',
      ...records.map(record => `- ${record.kind === 'review' ? '复查记录' : '执行记录'}（${record.date}）：${record.note}${record.outcome ? `，结论：${record.outcome}` : ''}`),
      records.length === 0 ? '- 目前还没有提交任何执行记录。' : '',
      '',
      '我的问题是：',
    ].filter(line => line !== '');
    go('chat'); setQuery(lines.join('\n')); setTimeout(() => inputRef.current?.focus(), 0);
  }
  async function addPlan() {
    if (!planTarget?.plan?.items) return;
    setPlanBusy(true); setPlanError(''); let count = 0, reused = 0;
    try {
      for (const [index, item] of planTarget.plan.items.entries()) {
        const planItemId = item.itemId || `p${index + 1}`;
        const already = tasks.find(t => t.sourceMessageId === planTarget.id && t.planItemId === planItemId);
        if (already) { reused++; continue; }
        const date = item.date && /^\d{4}-\d{2}-\d{2}$/.test(item.date) ? item.date : '';
        const planned = { id: uid(), title: item.task, date, fieldId: activeField?.id ?? null, fieldName: activeField?.name ?? '',
          status: 'pending_confirmation',
          timeWindow: item.window || (!date && item.date ? `建议时间：${item.date}` : ''),
          materials: item.materials || '',
          risk: item.warning || '',
          evidence: (item.evidence ?? []).filter((id): id is string => typeof id === 'string' && !!id.trim()),
          planItemId,
          condition: item.condition || '',
          method: [item.method, item.dosage ? `用量建议（须核验标签）：${item.dosage}` : ''].filter(Boolean).join('\n'),
          review: item.review || '', note: '来自 AI 农事建议，确认时间与条件后再执行。', createdAt: new Date().toISOString(), sourceMessageId: planTarget.id };
        const saved = await saveTask(planned);
        // 服务端会做两层幂等：命中的是"已有任务"时返回的 id 与我提交的不同，据此区分新增与复用
        if (saved.id === planned.id) count++; else reused++;
      }
      setPlanTarget(null);
      setNotice(count ? `已登记 ${count} 项任务（待确认），可在「农事任务」里确认安排、执行并提交记录。`
        : `这些方案项${reused ? `（${reused} 项）` : ''}已经在待办里了，没有重复添加。`);
    } catch (e) { setPlanError(`${count ? `已登记 ${count} 项，其余未保存。` : ''}${errorText(e)} 再次提交会跳过已登记的项目。`); } finally { setPlanBusy(false); }
  }

  async function fetchContext(params: URLSearchParams, method: 'device' | 'manual', accuracy: number, sequence: number) {
    try {
      const raw = await api<LiveContext>(`/context?${params}&days=7`);
      if (sequence === contextSeq.current) setContext(normalizeContext(raw, method, accuracy));
    } catch (e) { if (sequence === contextSeq.current) setContextError(errorText(e)); }
    finally { if (sequence === contextSeq.current) setContextBusy(false); }
  }
  function searchCity(city: string) {
    setIncludeWeather(false);
    const seq = ++contextSeq.current; setContextBusy(true); setContextError(''); setContext(null);
    void fetchContext(new URLSearchParams({ city }), 'manual', 0, seq);
  }
  // 没位置时自动定位：仅在浏览器已经授权定位的情况下静默获取，
  // 未授权时不弹权限框（一进页面就弹窗很打扰，也可能被浏览器直接拦），交给用户点「使用当前位置」
  useEffect(() => {
    if (context || contextBusy || contextError || locateTried.current) return;
    const permissions = navigator.permissions;
    if (!permissions?.query) return;
    locateTried.current = true;
    try {
      permissions.query({ name: 'geolocation' as PermissionName })
        .then(result => { if (result.state === 'granted') locate(); })
        .catch(() => { /* 查询权限失败就不自动定位，用户仍可手动点 */ });
    } catch { /* 个别浏览器不支持查询该权限名时会同步抛错，绝不能让它把页面搞崩 */ }
  }, [context, contextBusy, contextError]);

  function locate() {    setIncludeWeather(false);
    if (!window.isSecureContext || !navigator.geolocation) { setContextError('当前环境不支持设备定位，请手动选择城市；远程访问需 HTTPS。'); return; }
    const seq = ++contextSeq.current; setContextBusy(true); setContextError(''); setContext(null);
    navigator.geolocation.getCurrentPosition(p => { if (seq === contextSeq.current) void fetchContext(new URLSearchParams({ lat: String(p.coords.latitude), lon: String(p.coords.longitude) }), 'device', p.coords.accuracy, seq); }, e => {
      if (seq !== contextSeq.current) return;
      setContextBusy(false); setContextError(e.code === 1 ? '定位权限未获允许。可在浏览器中开启权限，或手动输入城市。' : '设备定位失败或超时，请手动输入城市查询天气。');
    }, { enableHighAccuracy: false, timeout: 12000, maximumAge: 60000 });
  }
  function exportConversation() {
    // The export doubles as verification evidence: keep source cards and the honesty markers in it.
    const statusLine = (m: ChatMessage) => m.status === 'interrupted'
      ? '\n\n> 状态：回答未完成（中断）\n'
      : m.degraded ? '\n\n> 状态：部分完成（工具结果已保留）\n' : '';
    const sourcesBlock = (m: ChatMessage) => !m.sources?.length ? '' : `\n\n资料依据：\n` + m.sources.map(s => [
      `- 来源ID：${s.id}（${s.status === 'verified' ? '已核验原文' : '本地草稿 · 未核验'}）`,
      `  标题：${s.title}`,
      s.institution ? `  机构：${s.institution}` : '',
      s.publishedAt ? `  发布日期：${s.publishedAt}` : '',
      s.region ? `  适用地区：${s.region}` : '',
      s.crop ? `  适用作物：${s.crop}` : '',
      s.growthStage ? `  生育期：${s.growthStage}` : '',
      s.url ? `  原文链接：${s.url}` : '  原文链接：无（未核验草稿）',
    ].filter(Boolean).join('\n')).join('\n');
    const content = `# ${current.title}\n\n农心 Agent 对话导出 · ${new Date().toLocaleString('zh-CN')}\n\n` + current.messages.map(m => `## ${m.role === 'user' ? '我' : '农心'}\n\n${m.content}${statusLine(m)}${m.attachedData ? `\n\n农情数据：\n${m.attachedData}` : ''}${m.plan ? `\n\n农事建议（AI 生成，待核验）：\n${JSON.stringify(m.plan, null, 2)}` : ''}${m.risk?.result ? `\n\n${m.risk.result}` : ''}${m.clarify ? `\n\n待确认：\n${JSON.stringify(m.clarify, null, 2)}` : ''}${sourcesBlock(m)}`).join('\n\n---\n\n');
    const url = URL.createObjectURL(new Blob([content], { type: 'text/markdown;charset=utf-8' })); const a = document.createElement('a'); a.href = url; a.download = `农心对话-${dateToday()}.md`; a.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  return <div className={`nx-shell${sidebar.resizing || rail.resizing ? ' is-resizing' : ''}`}>
    {mobileNav && <button className="nx-nav-scrim" aria-label="收起导航" onClick={() => setMobileNav(false)} />}
    <aside className={`nx-sidebar${mobileNav ? ' is-open' : ''}`} style={{ '--nx-sidebar-width': `${sidebar.width}px` } as CSSProperties}>
      <a className="nx-brand" href="#" onClick={e => { e.preventDefault(); go('chat'); }}><BrandMark /><span><strong>农心 <i>Agent</i></strong><small>把农事，放在心上。</small></span></a>
      <button className="nx-new-chat" disabled={sending || !!saveError || conversationBusy} onClick={() => fresh()}><Plus size={18} />开启新对话<span className="nx-new-chat-go"><ArrowUpRight size={16} strokeWidth={2.2} /></span></button>
      <div className="nx-nav-label">工作空间</div>
      <nav aria-label="主导航">{NAV.map(n => <button key={n.id} className={view === n.id ? 'is-active' : ''} onClick={() => go(n.id)}><n.icon size={18} /><span>{n.label}</span>{n.id === 'tasks' && pendingTasks.length > 0 && <b>{pendingTasks.length}</b>}</button>)}</nav>
      <section className="nx-history" aria-label="最近对话">
        <div className="nx-nav-label">最近对话 <span>{ready ? conversations.length : '—'}</span></div>
        <div className="nx-history-list" role="region" aria-label="最近对话列表" tabIndex={0}>
          {historyGroups.length ? historyGroups.map(group => {
            const collapsed = !!collapsedGroups[group.key];
            return <section className="nx-history-group" key={group.key}>
              <button type="button" className="nx-history-group-head" aria-expanded={!collapsed}
                aria-label={`${group.label}的对话（${group.items.length} 段）`}
                onClick={() => setCollapsedGroups(prev => ({ ...prev, [group.key]: !prev[group.key] }))}>
                <ChevronDown size={12} className={collapsed ? 'is-collapsed' : ''} />
                <span className="nx-history-group-name">{group.label}</span>
                {group.hint && <span className="nx-history-group-hint">{group.hint}</span>}
                <b>{group.items.length}</b>
              </button>
              {!collapsed && <div className="nx-history-group-rows">
                {group.items.map(c => <div key={c.id} className={`nx-history-row${current.id === c.id ? ' is-active' : ''}`}><button title={c.title} aria-current={current.id === c.id ? 'page' : undefined} disabled={sending || !!saveError || conversationBusy} onClick={() => openConversation(c)}><MessageSquare size={13} /><span>{c.title}</span></button><ConversationActions conversation={c} disabled={sending || !!saveError || conversationBusy} onRename={renameConversation} onDelete={deleteConversation} /></div>)}
              </div>}
            </section>;
          }) : <p>你的对话会保存在这里</p>}
        </div>
      </section>
      {!railVisible && <ContextPanel context={context} now={now} busy={contextBusy} error={contextError} onLocate={locate} onSearch={searchCity} onClear={() => { contextSeq.current++; setContext(null); setContextError(''); setContextBusy(false); }} />}
      <div className="nx-sidebar-bottom"><span className={`nx-connection-dot${ready ? ' is-online' : ''}`} />{loading ? '连接工作区…' : ready ? 'Java 服务已连接' : '工作区未连接'}<span>v1.0</span></div>
    </aside>
    <div className={`nx-resizer${sidebar.resizing ? ' is-active' : ''}`} role="separator" aria-orientation="vertical"
      aria-label="调整侧边栏宽度" aria-valuenow={sidebar.width} aria-valuemin={sidebar.min} aria-valuemax={sidebar.max}
      tabIndex={0} title="拖动调整宽度（←/→ 微调，双击恢复默认）"
      onPointerDown={sidebar.startResize} onKeyDown={sidebar.onKeyDown} onDoubleClick={sidebar.reset} />
    <main className="nx-main">
      <header className="nx-topbar"><div className="nx-topbar-left"><button className="nx-icon-button nx-menu" aria-label="打开导航" onClick={() => setMobileNav(true)}><Menu size={21} /></button><span className="nx-breadcrumb">工作空间 <span>/</span> <b>{NAV.find(n => n.id === view)?.label}</b></span></div><div className="nx-topbar-actions"><span className="nx-model-label">{settings.apiKey ? settings.model : '尚未连接模型'}</span><button className="nx-button is-small" onClick={() => setMobileOpen(true)}><Smartphone size={16} /><span>手机访问</span></button><button className="nx-button is-small" onClick={() => setSettingsOpen(true)}><Settings2 size={16} /><span>模型设置</span></button></div></header>
      {loadError && <div className="nx-global-error" role="alert"><span>{loadError}</span><button disabled={loading} onClick={() => void loadWorkspace()}><RefreshCw size={14} />重新连接</button></div>}
      {notice && <div className="nx-notice" role="status"><Check size={16} />{notice}<button aria-label="关闭提示" onClick={() => setNotice('')}><X size={14} /></button></div>}
      {view === 'chat' ? <div className={`nx-chat-layout${current.messages.length ? ' has-messages' : ''}`} style={{ '--nx-rail-width': `${rail.width}px` } as CSSProperties}>
        <div className="nx-chat-main">
          <div className="nx-conversation-bar"><div className="nx-field-select"><NxSelect
            ariaLabel="本次对话关联田块"
            value={current.fieldId || ''}
            disabled={sending || !!saveError || conversationBusy}
            leading={<Sprout size={15} />}
            options={[{ value: '', label: '不关联田块', hint: '通用咨询' },
              ...fields.map(f => ({ value: f.id, label: f.name, hint: f.crop }))]}
            footer={<button type="button" onClick={() => go('fields')}>管理田块档案 <ArrowRight size={13} /></button>}
            emptyText="还没有田块档案"
            onValueChange={value => {
              const fieldId = value || null;
              if (current.messages.length) { fresh(fieldId); setNotice('已为所选田块开启新对话，避免混用田块信息。'); }
              else setCurrent(prev => ({ ...prev, fieldId }));
            }}
          /></div>{current.messages.length > 0 && <button className="nx-icon-button" title="导出当前对话" aria-label="导出当前对话" onClick={exportConversation}><Download size={17} /></button>}</div>
          <div className="nx-conversation-scroll" ref={scrollRef} onScroll={() => { const el = scrollRef.current; if (el) followScroll.current = el.scrollHeight - el.scrollTop - el.clientHeight < 100; }}>
            {!current.messages.length ? <section className="nx-welcome">
              <div className="nx-welcome-heading"><span className="nx-eyebrow"><span />NONGXIN · YOUR FARMING COMPANION</span><h1>每一份耕耘，<br />都有<span>回应。</span></h1><p>从一个问题开始，和农心一起理清田里的事。</p></div>
              {showArt && <figure className="nx-brand-landscape"><img src="/brand/field-study.png" alt="绿色田垄的品牌概念影像" onError={() => setShowArt(false)} /><figcaption><span>从田间出发，向每一步行动。</span><small>品牌概念影像 · 非实测田块</small></figcaption></figure>}
              <div className="nx-starters">{STARTERS.map(s => <button key={s.name} onClick={() => suggest(s.prompt)}><s.icon size={21} /><b>{s.name}<ArrowRight size={15} /></b><span>{s.detail}</span></button>)}</div>
            </section> : <div className="nx-message-list" role="log" aria-label="对话记录" aria-live="polite">{current.messages.filter(m => !(sending && m.id === current.messages.at(-1)?.id && m.status === 'interrupted')).map(m => <article key={m.id} className={`nx-message is-${m.role}`}>{m.role === 'assistant' && <BrandMark small />}<div className="nx-message-body"><div className="nx-message-name">{m.role === 'assistant' ? '农心' : '我'}{m.role === 'assistant' && <span>AI 助手</span>}</div><div className="nx-message-content">{m.images?.length ? <ul className="nx-image-strip is-message" aria-label="随本条发送的照片">{m.images.map(image => <li key={image.id}><img src={image.url} alt={`拍摄的照片（${image.width}×${image.height}）`} /></li>)}</ul> : null}<MiniMd text={m.content} />{m.status === 'interrupted' && <p className="nx-interrupted">回答未完成 · 这部分内容不会作为完整答案带入后续对话。</p>}{m.degraded && <p className="nx-degraded">回答部分完成 · 工具结果已保留在下方卡片中，可直接使用；也可以重试这条问题获得完整回答。</p>}{m.requestContext && <details className="nx-message-context"><summary>本条使用的信息</summary><p>田块：{m.requestContext.field?.name || '未关联'}；天气：{m.requestContext.weather ? `${m.requestContext.weather.location || '所选位置'} · ${m.requestContext.weather.observedAt || '数据时间未提供'}` : '未附带'}。重试沿用这份快照，不自动替换为之后修改的数据。</p></details>}{m.attachedData && <details className="nx-message-data"><summary>已附加农情数据 · {m.attachedData.length} 字符</summary><pre>{m.attachedData}</pre></details>}</div>{m.plan && <PlanCard plan={m.plan} disabled={sending || planBusy || !ready || m.id !== latestPlanId} superseded={m.id !== latestPlanId} addedItemIds={tasks.filter(t => t.sourceMessageId === m.id && t.planItemId).map(t => t.planItemId as string)} onAdd={() => { setPlanTarget(m); setPlanError(''); }} />}{m.risk && <RiskCard risk={m.risk} />}{m.sources?.length ? <SourceCard sources={m.sources} /> : null}{m.clarify && <ClarifyCard
                clarify={m.clarify}
                answers={clarifyAnswers[m.id] || {}}
                disabled={sending || !ready || !!saveError}
                completed={hasClarifyFollowUp(current.messages, m.id)}
                onAnswer={(index, answer) => setClarifyAnswers(previous => ({
                  ...previous, [m.id]: setClarifyAnswer(previous[m.id] || {}, index, answer),
                }))}
                onSubmit={async reply => {
                  if (hasClarifyFollowUp(current.messages, m.id)) return;
                  const accepted = await send(false, reply);
                  if (!accepted) throw new Error('本次尚未提交，请检查模型设置或连接状态后再试。答案已保留。');
                }}
              />}</div></article>)}{sending && <div className="nx-thinking" role="status"><LoaderCircle size={16} className="spin" /><span>{streamStatus}</span>{previewLine && <span className="nx-thinking-preview">{previewLine}</span>}<button onClick={() => abortRef.current?.abort()}>停止回答</button></div>}</div>}
          </div>
          <div className="nx-composer-wrap">
            {!sending && (chatError || retryMessages(current.messages)) && <div className="nx-chat-error" role="alert"><span>{chatError || incompleteHint}</span>{retryMessages(current.messages) && <button disabled={sending || !!saveError || conversationBusy} onClick={() => void send(true)}>重试这条问题</button>}</div>}
            {saveError && <div className="nx-chat-error" role="alert"><span>回答尚未保存到数据库：{saveError}。请勿刷新页面。</span><button onClick={() => void persist(current).catch(e => setSaveError(errorText(e)))}>重试保存</button></div>}
            <label className="nx-weather-consent"><input type="checkbox" checked={includeWeather && !!context} disabled={sending || !context} onChange={e => setIncludeWeather(e.target.checked)} /><span>{context ? `随问题发送${context.location || '所选位置'}的天气（不代表田块位置）` : '未附带天气 · 可在侧栏获取后勾选'}</span></label>
            <form className="nx-composer" onSubmit={e => { e.preventDefault(); void send(); }} onDrop={e => { const files = imageFilesFrom(e.dataTransfer?.files); if (!files.length) return; e.preventDefault(); void addPhotos(files); }} onDragOver={e => { if (imageFilesFrom(e.dataTransfer?.items).length) e.preventDefault(); }}><ImageStrip images={images} onChange={setImages} disabled={sending} /><label className="sr-only" htmlFor="chat-input">向农心提问</label><textarea id="chat-input" ref={inputRef} value={query} maxLength={850} rows={2} disabled={sending} placeholder={activeField ? `关于${activeField.name}，有什么想聊的？` : '说说田里的情况，或者问一个农业问题…（可 Ctrl+V 粘贴截图）'} onChange={e => setQuery(e.target.value)} onPaste={handlePaste} onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing && e.keyCode !== 229) { e.preventDefault(); void send(); } }} /><div className="nx-composer-tools"><DataAttach attached={attachment} onAttach={setAttachment} onClear={() => setAttachment(null)} disabled={sending} /><VoiceInput disabled={sending || !ready} onText={text => setQuery(previous => (previous.trim() ? `${previous.trim()} ${text}` : text))} /><ImageAttach images={images} disabled={sending || !ready} visionReady={visionReady} onFiles={files => void addPhotos(files)} error={imageError} fieldId={activeField?.id ?? ''} fieldName={activeField?.name ?? ''} /><span className="nx-composer-hint">{imageBusy ? '正在处理图片…' : (attachment || images.length) ? `待发送${attachment ? ' · 农情数据' : ''}${images.length ? ` · ${images.length} 张照片` : ''}` : 'Enter 发送 · Shift + Enter 换行'}</span>{sending ? <button className="nx-send" type="button" aria-label="停止回答" onClick={() => abortRef.current?.abort()}><Square size={16} /></button> : <button className="nx-send" type="submit" aria-label="发送问题" disabled={!query.trim() || !ready || !!saveError || conversationBusy || imageBusy}><ArrowUp size={20} /></button>}
            {activeField && fieldPhotos.length > 0 && <label className="nx-field-photo-toggle"><input type="checkbox" checked={autoFieldPhotos} disabled={sending} onChange={e => setAutoFieldPhotos(e.target.checked)} /><span>带上最近 {Math.min(3, fieldPhotos.length)} 张田间照片（{fieldPhotos.slice(0, 3).map(photo => (photo.observedAt || photo.createdAt || '').slice(5)).join('、')}）</span></label>}
          </div></form>
            <p className="nx-composer-foot"><ShieldCheck size={12} />不把未知当事实。AI 建议供参考，重要农事请结合现场判断。</p>
          </div>
        </div>
        <aside className="nx-context-rail">
          <div className={`nx-resizer is-rail${rail.resizing ? ' is-active' : ''}`} role="separator" aria-orientation="vertical"
            aria-label="调整右侧栏宽度" aria-valuenow={rail.width} aria-valuemin={rail.min} aria-valuemax={rail.max}
            tabIndex={0} title="拖动调整宽度（←/→ 微调，双击恢复默认）"
            onPointerDown={rail.startResize} onKeyDown={rail.onKeyDown} onDoubleClick={rail.reset} />
          {railVisible && <ContextPanel context={context} now={now} busy={contextBusy} error={contextError} onLocate={locate} onSearch={searchCity} onClear={() => { contextSeq.current++; setContext(null); setContextError(''); setContextBusy(false); }} />}
          <div className="nx-rail-section"><span className="nx-eyebrow">CONTEXT</span><h3>下条问题的背景</h3><p>每条问题保存独立快照。较长对话仅携带最近 20 条有效消息。</p><dl><div><dt>关联田块</dt><dd>{activeField?.name || '未关联'}</dd></div><div><dt>种植作物</dt><dd>{activeField?.crop || '未提供'}</dd></div><div><dt>播种日期</dt><dd>{activeField?.sowDate || '未提供'}</dd></div><div><dt>天气背景</dt><dd>{includeWeather && context ? context.location : '不附带'}</dd></div></dl>{!activeField && <button className="nx-text-button" onClick={() => go('fields')}>管理田块档案 <ArrowRight size={14} /></button>}</div>
          <div className="nx-rail-section"><span className="nx-eyebrow">NEXT STEP</span><h3>从建议，到行动</h3><ol className="nx-workflow"><li><span>1</span><div><b>说清情况</b><small>描述问题，按需补充数据</small></div></li><li><span>2</span><div><b>一起判断</b><small>分清已知、依据与待确认</small></div></li><li><span>3</span><div><b>确认后行动</b><small>把建议加入任务，记录复查</small></div></li></ol></div>
          <div className="nx-rail-task"><CheckCheck size={20} /><div><b>{ready ? `${pendingTasks.length} 项待办农事` : '待办尚未载入'}</b><p>{pendingTasks.length ? '留一点时间，看看下一步。' : '有了计划，再从这里开始。'}</p></div><button aria-label="查看农事任务" onClick={() => go('tasks')}><ArrowRight size={16} /></button></div>
        </aside>
      </div> : <div className="nx-page-scroll">{!ready ? <div className="nx-empty"><LoaderCircle size={28} className={loading ? 'spin' : ''} /><h2>{loading ? '正在载入工作区' : '工作区尚未连接'}</h2><p>连接成功后会展示数据库中的真实记录。</p><button className="nx-button" disabled={loading} onClick={() => void loadWorkspace()}>重新连接</button></div> : view === 'fields' ? <FieldsView fields={fields} onSave={saveField} onDelete={removeField} onRecord={addRecord} onAsk={id => fresh(id)} onAnalyze={analyzeField} /> : view === 'tasks' ? <TasksView tasks={tasks} fields={fields} onSave={saveTask} onDelete={removeTask} onStatus={changeTaskStatus} onRecord={addTaskRecord} onDiscuss={discussTask} /> : <KnowledgeView entries={knowledge} onAsk={suggest} />}</div>}
    </main>
    <SettingsDialog open={settingsOpen} onOpenChange={setSettingsOpen} settings={settings} onSave={applySettings} />
    <MobileDialog open={mobileOpen} onOpenChange={setMobileOpen} />
    <Dialog open={!!planTarget} onOpenChange={open => { if (!open && !planBusy) setPlanTarget(null); }}><DialogContent className="nx-dialog" showCloseButton={!planBusy}><DialogTitle>确认加入农事任务</DialogTitle><DialogDescription>这只是建议清单，不代表已执行。日期不明确的项目会保留为“待定日期”，请在任务中核对条件与操作方法。</DialogDescription><ul className="nx-plan-confirm">{planTarget?.plan?.items?.map((item, i) => <li key={i}><Check size={15} /><span>{item.task}</span><small>{item.date || '日期待定'}</small></li>)}</ul><p className="nx-muted">关联田块：{activeField?.name || '不关联田块'}</p>{planError && <p className="nx-error" role="alert">{planError}</p>}<div className="nx-dialog-actions"><button disabled={planBusy} className="nx-button" onClick={() => setPlanTarget(null)}>再想想</button><button disabled={planBusy} className="nx-button is-primary" onClick={() => void addPlan()}>{planBusy ? '正在保存…' : '确认加入任务'}</button></div></DialogContent></Dialog>
  </div>;
}
