'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { CalendarDays, ChevronsRight, Clock3, LoaderCircle, PanelLeft, Send, Settings2, Sprout } from 'lucide-react';
import { useIsMobile } from '@/hooks/use-mobile';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { FieldSidebar } from '@/components/agri/field-sidebar';
import { ContextPanel } from '@/components/agri/context-panel';
import { PlanCard, RiskCard } from '@/components/agri/plan-card';
import { ClarifyCard } from '@/components/agri/clarify-card';
import { MiniMd } from '@/components/ui/mini-md';
import { DataAttach } from '@/components/agri/data-attach';
import type { ChatMessage, FieldProfile, LiveContext } from '@/lib/types';

type ProviderId = 'deepseek' | 'openai' | 'siliconflow' | 'custom';
type AiSettings = { provider: ProviderId; model: string; baseUrl: string; apiKey: string };

const providerOptions: Record<ProviderId, { label: string; baseUrl: string; model: string }> = {
  deepseek: { label: 'DeepSeek', baseUrl: 'https://api.deepseek.com/v1', model: 'deepseek-chat' },
  openai: { label: 'OpenAI', baseUrl: 'https://api.openai.com/v1', model: 'gpt-4o-mini' },
  siliconflow: { label: '硅基流动', baseUrl: 'https://api.siliconflow.cn/v1', model: 'deepseek-ai/DeepSeek-V3.2' },
  custom: { label: '自定义兼容接口', baseUrl: '', model: '' },
};

const initialSettings: AiSettings = {
  provider: 'deepseek',
  model: providerOptions.deepseek.model,
  baseUrl: providerOptions.deepseek.baseUrl,
  apiKey: '',
};

const FIELDS_KEY = 'nongxin-fields';
const ACTIVE_KEY = 'nongxin-active-field';

function loadFields(): FieldProfile[] {
  try {
    const raw = localStorage.getItem(FIELDS_KEY);
    if (!raw) return [];
    return JSON.parse(raw) as FieldProfile[];
  } catch {
    return [];
  }
}

export default function Home() {
  const [query, setQuery] = useState('');
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [isSending, setIsSending] = useState(false);
  const [chatError, setChatError] = useState('');
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [settings, setSettings] = useState<AiSettings>(initialSettings);
  const [settingsDraft, setSettingsDraft] = useState<AiSettings>(initialSettings);
  const [settingsError, setSettingsError] = useState('');
  const [showKey, setShowKey] = useState(false);
  const [fields, setFields] = useState<FieldProfile[]>([]);
  const [activeFieldId, setActiveFieldId] = useState<string | null>(null);
  const [attachedData, setAttachedData] = useState<string | null>(null);
  const [context, setContext] = useState<LiveContext | null>(null);
  const [locating, setLocating] = useState(false);
  const [contextError, setContextError] = useState('');
  const [clockNow, setClockNow] = useState(() => new Date());
  const conversationEnd = useRef<HTMLDivElement>(null);
  const isMobile = useIsMobile();
  const [leftWidth, setLeftWidth] = useState(240);
  const [rightWidth, setRightWidth] = useState(300);
  const dragRef = useRef<{ side: 'left' | 'right'; startX: number; startW: number; latest: number } | null>(null);
  const [leftCollapsed, setLeftCollapsed] = useState(false);
  const [rightCollapsed, setRightCollapsed] = useState(false);

  const activeField = fields.find((f) => f.id === activeFieldId) ?? null;

  useEffect(() => {
    const timer = window.setTimeout(() => {
      const savedFields = loadFields();
      setFields(savedFields);
      const savedLeftWidth = Number(localStorage.getItem('nongxin-left-w'));
      const savedRightWidth = Number(localStorage.getItem('nongxin-right-w'));
      if (savedLeftWidth >= 180 && savedLeftWidth <= 360) setLeftWidth(savedLeftWidth);
      if (savedRightWidth >= 220 && savedRightWidth <= 400) setRightWidth(savedRightWidth);
      const savedActive = localStorage.getItem(ACTIVE_KEY);
      if (savedActive && savedFields.some((f) => f.id === savedActive)) setActiveFieldId(savedActive);
      const saved = sessionStorage.getItem('nongxin-ai-settings');
      if (!saved) return;
      try {
        const parsed = JSON.parse(saved) as AiSettings;
        if (providerOptions[parsed.provider] && parsed.model && parsed.apiKey) {
          setSettings(parsed);
          setSettingsDraft(parsed);
        }
      } catch { /* ignore */ }
    }, 0);
    return () => window.clearTimeout(timer);
  }, []);

  useEffect(() => {
    conversationEnd.current?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }, [messages, isSending]);

  useEffect(() => {
    if (!context?.timezone) return;
    const timer = window.setInterval(() => setClockNow(new Date()), 60000);
    return () => window.clearInterval(timer);
  }, [context?.timezone]);

  const persistFields = (next: FieldProfile[]) => {
    setFields(next);
    try { localStorage.setItem(FIELDS_KEY, JSON.stringify(next)); } catch { /* ignore */ }
  };

  const createField = (field: FieldProfile) => {
    const next = [...fields, field];
    persistFields(next);
    setActiveFieldId(field.id);
    try { localStorage.setItem(ACTIVE_KEY, field.id); } catch { /* ignore */ }
  };

  const deleteField = (id: string) => {
    const target = fields.find((field) => field.id === id);
    if (target && !window.confirm(`确认删除田块“${target.name}”？此操作无法撤销。`)) return;
    const next = fields.filter((f) => f.id !== id);
    persistFields(next);
    if (activeFieldId === id) {
      setActiveFieldId(next[0]?.id ?? null);
      try { localStorage.setItem(ACTIVE_KEY, next[0]?.id ?? ''); } catch { /* ignore */ }
    }
  };

  const selectField = (id: string) => {
    setActiveFieldId(id);
    try { localStorage.setItem(ACTIVE_KEY, id); } catch { /* ignore */ }
  };

  // ---- 定位与天气 ----
  const loadContext = useCallback(async (mode: 'device' | 'manual', city?: string) => {
    setLocating(true);
    setContextError('');
    try {
      let url = '';
      let accuracy = 0;
      if (mode === 'manual' && city) {
        url = `/api/context?city=${encodeURIComponent(city)}&days=7`;
      } else {
        const pos = await new Promise<GeolocationPosition>((resolve, reject) => {
          if (!navigator.geolocation) { reject(new Error('浏览器不支持定位，请改用城市选择。')); return; }
          navigator.geolocation.getCurrentPosition(resolve, reject, { enableHighAccuracy: false, timeout: 15000, maximumAge: 300000 });
        });
        accuracy = pos.coords.accuracy;
        url = `/api/context?lat=${pos.coords.latitude}&lon=${pos.coords.longitude}&days=7`;
      }
      const response = await fetch(url);
      const data = await response.json() as {
        error?: string;
        location?: string | null;
        latitude?: number;
        longitude?: number;
        timezone?: string | null;
        timezoneAbbreviation?: string | null;
        observedAt?: string | null;
        weather?: LiveContext['weather'];
        daily?: LiveContext['daily'];
        dailyText?: string;
        weatherError?: string | null;
        sources?: { weather?: string | null; location?: string | null };
      };
      if (!response.ok) throw new Error(data.error || '获取失败');
      setContext({
        method: mode === 'device' ? 'device' : 'manual',
        location: data.location ?? (mode === 'device' ? `坐标 ${Number(data.latitude).toFixed(4)}, ${Number(data.longitude).toFixed(4)}` : city!),
        latitude: Number(data.latitude) || 0,
        longitude: Number(data.longitude) || 0,
        accuracy,
        locatedAt: Date.now(),
        timezone: data.timezone ?? null,
        timezoneAbbreviation: data.timezoneAbbreviation ?? null,
        observedAt: data.observedAt ?? null,
        weather: data.weather ?? null,
        daily: data.daily ?? [],
        dailyText: data.dailyText ?? '',
        weatherError: data.weatherError ?? null,
        sources: { weather: data.sources?.weather ?? null, location: data.sources?.location ?? null },
      });
      if (mode === 'device') {
        try { localStorage.setItem('nongxin-was-located', '1'); } catch { /* ignore */ }
      }
    } catch (error) {
      setContextError(error instanceof Error ? error.message : '定位失败，请选择城市。');
    } finally {
      setLocating(false);
    }
  }, []);

  const handlePickCity = (city: string) => { void loadContext('manual', city); };

  // 只在用户曾经成功授权过定位时自动刷新；失败时仍可手动查询城市。
  useEffect(() => {
    const timer = window.setTimeout(() => {
      if (localStorage.getItem('nongxin-was-located') === '1') void loadContext('device');
    }, 0);
    return () => window.clearTimeout(timer);
  }, [loadContext]);

  useEffect(() => {
    if (!isMobile) return;
    const timer = window.setTimeout(() => {
      setLeftCollapsed(true);
      setRightCollapsed(true);
    }, 0);
    return () => window.clearTimeout(timer);
  }, [isMobile]);

  // ---- 对话 ----
  const submitQuestion = async (override?: string) => {
    const clean = (override ?? query).trim();
    if (!clean || isSending) return;
    if (!settings.apiKey) {
      setSettingsDraft(settings);
      setSettingsError('先填写 API 密钥，再开始对话。');
      setSettingsOpen(true);
      return;
    }

    const userMessage: ChatMessage = { id: crypto.randomUUID(), role: 'user', content: clean, attachedData: attachedData ?? undefined };
    const history = [...messages, userMessage];
    setMessages(history);
    setQuery('');
    setAttachedData(null);
    setChatError('');
    setIsSending(true);

    try {
      const response = await fetch('/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          provider: settings.provider,
          model: settings.model,
          baseUrl: settings.baseUrl,
          apiKey: settings.apiKey,
          messages: history.map(({ role, content, attachedData: farmData }) => ({ role, content, farmData })),
          field: activeField,
          location: context ? { lat: context.latitude, lon: context.longitude, label: context.location } : null,
          weather: context ? {
            weather: context.weather,
            daily: context.daily.slice(0, 7),
            dailyText: context.dailyText,
            locationText: context.timezone ? `时区 ${context.timezone}` : null,
          } : null,
        }),
      });
      const data = await response.json() as {
        reply?: string; plan?: ChatMessage['plan']; risk?: ChatMessage['risk']; clarify?: ChatMessage['clarify']; error?: string;
      };
      if (!response.ok || !data.reply) throw new Error(data.error || '暂时没有收到回答，请稍后再试。');
      setMessages((current) => [...current, {
        id: crypto.randomUUID(),
        role: 'assistant',
        content: data.reply!,
        plan: data.plan ?? null,
        risk: data.risk ?? null,
        clarify: data.clarify ?? null,
      }]);
    } catch (error) {
      setChatError(error instanceof Error ? error.message : '对话请求失败，请检查设置后重试。');
    } finally {
      setIsSending(false);
    }
  };

  /** 侧栏拖拽调宽（桌面端；双击分隔条恢复默认） */
  const startDrag = (side: 'left' | 'right', e: React.MouseEvent<HTMLButtonElement>) => {
    e.preventDefault();
    dragRef.current = { side, startX: e.clientX, startW: side === 'left' ? leftWidth : rightWidth, latest: side === 'left' ? leftWidth : rightWidth };
    const onMove = (ev: MouseEvent) => {
      const d = dragRef.current;
      if (!d) return;
      const raw = d.side === 'left' ? d.startW + (ev.clientX - d.startX) : d.startW - (ev.clientX - d.startX);
      const next = Math.min(Math.max(raw, d.side === 'left' ? 180 : 220), d.side === 'left' ? 360 : 400);
      d.latest = next;
      if (d.side === 'left') setLeftWidth(next); else setRightWidth(next);
    };
    const onUp = () => {
      const d = dragRef.current;
      if (d) {
        try { localStorage.setItem(d.side === 'left' ? 'nongxin-left-w' : 'nongxin-right-w', String(Math.round(d.latest))); } catch { /* ignore */ }
      }
      dragRef.current = null;
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
    };
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
  };

  const resizeWithKeyboard = (side: 'left' | 'right') => (event: React.KeyboardEvent<HTMLButtonElement>) => {
    if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight' && event.key !== 'Home') return;
    event.preventDefault();
    const isLeft = side === 'left';
    const current = isLeft ? leftWidth : rightWidth;
    const next = event.key === 'Home'
      ? (isLeft ? 240 : 300)
      : current + (event.key === 'ArrowRight' ? (isLeft ? 10 : -10) : (isLeft ? -10 : 10));
    const clamped = Math.min(Math.max(next, isLeft ? 180 : 220), isLeft ? 360 : 400);
    if (isLeft) setLeftWidth(clamped); else setRightWidth(clamped);
    try { localStorage.setItem(isLeft ? 'nongxin-left-w' : 'nongxin-right-w', String(clamped)); } catch { /* ignore */ }
  };
  /** 确认卡点选：补一条信息继续追问 */
  const sendClarify = (question: string, option: string) => {
    if (isSending) return;
    void submitQuestion(`${question} 我这边的情况是：${option}`);
  };
  const quickPlan = () => {
    if (!activeField) {
      setChatError('请先在左侧建立田块档案，再生成七日农事方案。');
      return;
    }
    void submitQuestion(`请为「${activeField.name}」生成未来 7 天的农事方案，结合当前天气与${activeField.crop}（播期 ${activeField.sowDate}）生育期。`);
  };

  const changeProvider = (provider: ProviderId) => {
    const preset = providerOptions[provider];
    if (!preset) return;
    setSettingsDraft((current) => ({ ...current, provider, baseUrl: preset.baseUrl, model: preset.model }));
  };

  const saveSettings = () => {
    const next = { ...settingsDraft, model: settingsDraft.model.trim(), baseUrl: settingsDraft.baseUrl.trim(), apiKey: settingsDraft.apiKey.trim() };
    if (!next.model) return setSettingsError('请填写模型名称。');
    if (!next.apiKey) return setSettingsError('请填写 API 密钥。');
    if (next.provider === 'custom' && !next.baseUrl.startsWith('https://')) return setSettingsError('自定义 API 地址需要以 https:// 开头。');
    setSettings(next);
    sessionStorage.setItem('nongxin-ai-settings', JSON.stringify(next));
    setSettingsError('');
    setSettingsOpen(false);
    setChatError('');
  };

  const localTimeText = context?.timezone
    ? (() => {
        try {
          return new Intl.DateTimeFormat('zh-CN', { timeZone: context.timezone, month: '2-digit', day: '2-digit', weekday: 'short', hour: '2-digit', minute: '2-digit', hour12: false }).format(clockNow);
        } catch {
          return '';
        }
      })()
    : '';

  return (
    <main className="app-shell">
      <header className="topbar">
        <div className="brand">
          <span className="brand-mark"><Sprout size={20} strokeWidth={2.2} /></span>
          <span className="brand-name">农心</span>
          <span className="brand-sub">县域农技站 · 田间决策助手</span>
        </div>
        <div className="top-actions">
          {context && <span className="topbar-clock"><Clock3 size={13} />{localTimeText}</span>}
          <span className={`service-dot ${settings.apiKey ? '' : 'unconfigured'}`}>{settings.apiKey ? `${providerOptions[settings.provider].label} 已接入` : 'AI 待配置'}</span>
          <Dialog open={settingsOpen} onOpenChange={(open) => { setSettingsOpen(open); if (open) { setSettingsDraft(settings); setSettingsError(''); } }}>
            <DialogTrigger render={<Button className="settings-button" />}><Settings2 size={16} />设置</DialogTrigger>
            <DialogContent className="settings-dialog">
              <DialogHeader>
                <DialogTitle>AI 对话设置</DialogTitle>
                <DialogDescription>选择供应商与模型。密钥只保存在当前浏览器会话，关闭浏览器后自动清除。</DialogDescription>
              </DialogHeader>
              <div className="settings-form">
                <div className="settings-field">
                  <span>供应商</span>
                  <Select value={settingsDraft.provider} onValueChange={(value) => changeProvider(value as ProviderId)}>
                    <SelectTrigger className="settings-select"><SelectValue /></SelectTrigger>
                    <SelectContent>{Object.entries(providerOptions).map(([id, item]) => <SelectItem key={id} value={id}>{item.label}</SelectItem>)}</SelectContent>
                  </Select>
                </div>
                <label htmlFor="model-name"><span>模型</span><Input id="model-name" value={settingsDraft.model} onChange={(event) => setSettingsDraft((current) => ({ ...current, model: event.target.value }))} autoComplete="off" /></label>
                <label htmlFor="api-base-url">
                  <span>API 地址</span>
                  <Input id="api-base-url" value={settingsDraft.baseUrl} onChange={(event) => setSettingsDraft((current) => ({ ...current, baseUrl: event.target.value }))} readOnly={settingsDraft.provider !== 'custom'} placeholder="https://example.com/v1" autoComplete="off" />
                  {settingsDraft.provider !== 'custom' && <small>由所选供应商自动填写</small>}
                </label>
                <div className="settings-field">
                  <label htmlFor="api-key">API 密钥</label>
                  <div className="key-field">
                    <Input id="api-key" type={showKey ? 'text' : 'password'} value={settingsDraft.apiKey} onChange={(event) => setSettingsDraft((current) => ({ ...current, apiKey: event.target.value }))} placeholder="粘贴 API Key" autoComplete="off" spellCheck={false} />
                    <button type="button" onClick={() => setShowKey((visible) => !visible)} aria-label={showKey ? '隐藏密钥' : '显示密钥'}>{showKey ? '隐藏' : '显示'}</button>
                  </div>
                </div>
                {settingsError && <p className="settings-error">{settingsError}</p>}
              </div>
              <DialogFooter className="settings-footer"><Button onClick={saveSettings} className="save-settings">保存设置</Button></DialogFooter>
            </DialogContent>
          </Dialog>
        </div>
      </header>

      <div
        className={`app-grid ${isMobile ? '' : 'app-grid-resizable'}`}
        style={isMobile ? undefined : {
          gridTemplateColumns: `${leftCollapsed ? '' : `${leftWidth}px 7px `}minmax(0, 1fr)${rightCollapsed ? '' : ` 7px ${rightWidth}px`}`,
        }}
      >
        {!leftCollapsed && (
          <div className="sidebar-grid-cell">
            <FieldSidebar
              fields={fields}
              activeId={activeFieldId}
              onSelect={selectField}
              onCreate={createField}
              onDelete={deleteField}
              onCollapse={() => setLeftCollapsed(true)}
            />
          </div>
        )}
        {!leftCollapsed && !isMobile && (
          <button
            type="button"
            className="panel-resizer"
            aria-label="调整田块栏宽度，方向键微调，Home 恢复默认"
            onMouseDown={(event) => startDrag('left', event)}
            onKeyDown={resizeWithKeyboard('left')}
            onDoubleClick={() => setLeftWidth(240)}
          />
        )}

        <section className="chat-column">
          <div className="chat-heading">
            <div className="chat-heading-left">
              <span className="eyebrow">{activeField ? `当前田块 · ${activeField.name}` : '工作台'}</span>
              <h1>{activeField ? `${activeField.crop} 农事咨询` : '今天想问点什么？'}</h1>
            </div>
            <div className="chat-heading-actions">
              {leftCollapsed && <button className="panel-expand-btn" onClick={() => setLeftCollapsed(false)}><PanelLeft size={14} />田块</button>}
              {rightCollapsed && <button className="panel-expand-btn" onClick={() => setRightCollapsed(false)}><ChevronsRight size={14} />农情</button>}
              <button className="quick-plan" onClick={quickPlan} disabled={!activeField || isSending}>
                <CalendarDays size={15} />七日农事方案
              </button>
            </div>
          </div>

          {contextError && <div className="context-error-banner"><span>{contextError}</span><button onClick={() => setContextError('')}>知道了</button></div>}

          <div className="conversation" aria-live="polite">
            <div className="assistant-row">
              <span className="bot-seal">农心</span>
              <div className="message-block">
                <div className="speaker"><b>农心助手</b><span>{providerOptions[settings.provider].label} · {settings.model}</span></div>
                <div className="answer-card intro-card">
                  <p>您好。可以直接提问；需要结合具体田块时，再从左侧建立或选择档案。</p>
                  <p className="intro-hint">「水稻叶片出现斑点怎么排查」 · 「结合我的田块做一周安排」 · 挂载实测数据做规则筛查</p>
                </div>
              </div>
            </div>

            {messages.map((message) => message.role === 'user' ? (
              <div className="user-message" key={message.id}>
                <span>{message.content}</span>
                {message.attachedData && <small className="user-attachment">已附农情数据</small>}
              </div>
            ) : (
              <div className="assistant-row msg-row" key={message.id}>
                <span className="bot-seal">农心</span>
                <div className="message-block">
                  <div className="answer-block">
                    {message.plan && <PlanCard plan={message.plan} />}
                    {message.risk && <RiskCard risk={message.risk} />}
                    {message.clarify && <ClarifyCard clarify={message.clarify} onPick={sendClarify} />}
                    <div className="answer-card chat-answer"><MiniMd text={message.content} /></div>
                  </div>
                </div>
              </div>
            ))}

            {isSending && (
              <div className="assistant-row msg-row">
                <span className="bot-seal">农心</span>
                <div className="answer-card loading-answer"><LoaderCircle size={16} className="spin" />正在查阅资料并整理方案…</div>
              </div>
            )}
            {chatError && <div className="chat-error"><span>{chatError}</span><button onClick={() => { setSettingsDraft(settings); setSettingsOpen(true); }}>检查设置</button></div>}
            <div ref={conversationEnd} />
          </div>

          <div className="composer-wrap">
            <DataAttach attached={attachedData} onAttach={setAttachedData} onClear={() => setAttachedData(null)} />
            <div className="composer">
              <textarea value={query} onChange={(event) => setQuery(event.target.value)} onKeyDown={(event) => { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); void submitQuestion(); } }} aria-label="向农心提问" placeholder="描述田里的情况，Enter 发送，Shift + Enter 换行" />
              <div className="composer-tools">
                <span className="composer-model">{activeField ? `${activeField.name} · ${activeField.crop}` : '通用咨询 · 未绑定田块'}</span>
                <button onClick={() => void submitQuestion()} disabled={isSending || !query.trim()} className="send-button" aria-label="发送">{isSending ? <LoaderCircle size={17} className="spin" /> : <Send size={17} />}</button>
              </div>
            </div>
            <div className="composer-foot"><p>回答仅基于你提供的信息与内置资料；用药以当地登记标签为准。</p><button onClick={() => { setSettingsDraft(settings); setSettingsOpen(true); }}>切换模型</button></div>
          </div>
        </section>

        {!rightCollapsed && !isMobile && (
          <button
            type="button"
            className="panel-resizer"
            aria-label="调整农情栏宽度，方向键微调，Home 恢复默认"
            onMouseDown={(event) => startDrag('right', event)}
            onKeyDown={resizeWithKeyboard('right')}
            onDoubleClick={() => setRightWidth(300)}
          />
        )}
        {!rightCollapsed && (
          <div className="sidebar-grid-cell">
            <ContextPanel context={context} onLocate={() => void loadContext('device')} locating={locating} onPickCity={handlePickCity} field={activeField} onCollapse={() => setRightCollapsed(true)} />
          </div>
        )}
      </div>
    </main>
  );
}
