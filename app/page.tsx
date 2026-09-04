'use client';

import { useEffect, useRef, useState } from 'react';
import { Clock3, CloudSun, Eye, EyeOff, LocateFixed, LoaderCircle, MapPin, RefreshCw, Send, Settings2, Sprout } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';

type ProviderId = 'deepseek' | 'openai' | 'siliconflow' | 'custom';
type ChatMessage = { id: string; role: 'user' | 'assistant'; content: string };
type AiSettings = { provider: ProviderId; model: string; baseUrl: string; apiKey: string };
type LiveContext = {
  method: 'device' | 'manual';
  location: string | null;
  latitude: number;
  longitude: number;
  accuracy: number;
  locatedAt: number;
  timezone: string;
  timezoneAbbreviation: string | null;
  observedAt: string | null;
  weather: {
    temperature: number | null;
    apparentTemperature: number | null;
    humidity: number | null;
    precipitation: number | null;
    weatherCode: number | null;
    windSpeed: number | null;
    windDirection: number | null;
  };
  sources: { weather: string; location: string | null };
};

const providerOptions: Record<ProviderId, { label: string; baseUrl: string; model: string }> = {
  deepseek: { label: 'DeepSeek', baseUrl: 'https://api.deepseek.com/v1', model: 'deepseek-v4-flash-vision-exp' },
  openai: { label: 'OpenAI', baseUrl: 'https://api.openai.com/v1', model: 'gpt-5.2' },
  siliconflow: { label: '硅基流动', baseUrl: 'https://api.siliconflow.cn/v1', model: 'deepseek-ai/DeepSeek-V3.2' },
  custom: { label: '自定义兼容接口', baseUrl: '', model: '' },
};

const initialSettings: AiSettings = {
  provider: 'deepseek',
  model: providerOptions.deepseek.model,
  baseUrl: providerOptions.deepseek.baseUrl,
  apiKey: '',
};

function weatherText(code: number | null) {
  if (code === null) return '天气状况未知';
  if (code === 0) return '晴';
  if ([1, 2].includes(code)) return '少云';
  if (code === 3) return '阴';
  if ([45, 48].includes(code)) return '雾';
  if ([51, 53, 55, 56, 57].includes(code)) return '毛毛雨';
  if ([61, 63, 65, 66, 67, 80, 81, 82].includes(code)) return '雨';
  if ([71, 73, 75, 77, 85, 86].includes(code)) return '雪';
  if ([95, 96, 99].includes(code)) return '雷暴';
  return '天气代码 ' + code;
}

function formatLocalTime(date: Date, timezone: string) {
  try {
    return new Intl.DateTimeFormat('zh-CN', {
      timeZone: timezone,
      month: '2-digit',
      day: '2-digit',
      weekday: 'short',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false,
    }).format(date);
  } catch {
    return '';
  }
}

function weatherDetails(weather: LiveContext['weather']) {
  const parts: string[] = [];
  if (weather.humidity !== null) parts.push(`湿度 ${weather.humidity}%`);
  if (weather.apparentTemperature !== null) parts.push(`体感 ${weather.apparentTemperature}°C`);
  if (weather.windSpeed !== null) parts.push(`风速 ${weather.windSpeed} km/h`);
  return parts.join(' · ') || '暂无更多实测数据';
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
  const [liveContext, setLiveContext] = useState<LiveContext | null>(null);
  const [contextStatus, setContextStatus] = useState<'idle' | 'locating' | 'loading' | 'ready' | 'error'>('idle');
  const [contextError, setContextError] = useState('');
  const [clockNow, setClockNow] = useState(() => new Date());
  const [manualLocationOpen, setManualLocationOpen] = useState(false);
  const [manualCity, setManualCity] = useState('');
  const conversationEnd = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const saved = sessionStorage.getItem('nongxin-ai-settings');
    if (!saved) return;
    try {
      const parsed = JSON.parse(saved) as AiSettings;
      if (providerOptions[parsed.provider] && parsed.model && parsed.apiKey) {
        const timer = window.setTimeout(() => {
          setSettings(parsed);
          setSettingsDraft(parsed);
        }, 0);
        return () => window.clearTimeout(timer);
      }
    } catch { /* ignore invalid browser settings */ }
  }, []);

  useEffect(() => {
    conversationEnd.current?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }, [messages, isSending]);

  useEffect(() => {
    if (!liveContext?.timezone) return;
    const timer = window.setInterval(() => setClockNow(new Date()), 1000);
    return () => window.clearInterval(timer);
  }, [liveContext?.timezone]);

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

  const acceptContextResponse = async (response: Response, details: { method: 'device' | 'manual'; accuracy: number; locatedAt: number }) => {
    const data = await response.json() as Omit<LiveContext, 'method' | 'accuracy' | 'locatedAt'> & { error?: string };
    if (!response.ok || data.error) throw new Error(data.error || '实时信息获取失败。');
    setLiveContext({ ...data, ...details });
    setClockNow(new Date());
    setContextStatus('ready');
    setContextError('');
  };

  const loadLiveContext = () => {
    if (!navigator.geolocation) {
      setContextStatus('error');
      setContextError('当前浏览器不支持自动定位，请手动输入城市。');
      setManualLocationOpen(true);
      return;
    }
    setContextStatus('locating');
    setContextError('');
    navigator.geolocation.getCurrentPosition(async (position) => {
      setContextStatus('loading');
      const { latitude, longitude, accuracy } = position.coords;
      try {
        const response = await fetch(`/api/context?lat=${encodeURIComponent(latitude)}&lon=${encodeURIComponent(longitude)}`);
        await acceptContextResponse(response, { method: 'device', accuracy, locatedAt: position.timestamp });
      } catch (error) {
        setContextStatus('error');
        setContextError(error instanceof Error ? error.message : '天气与位置查询失败。');
        setManualLocationOpen(true);
      }
    }, (error) => {
      setContextStatus('error');
      const reason = error.code === error.PERMISSION_DENIED
        ? '定位权限被拒绝，可允许定位后重试，或手动输入城市。'
        : error.code === error.TIMEOUT
          ? '自动定位超时，请重试或手动输入城市。'
          : '暂时无法自动定位，请手动输入城市。';
      setContextError(reason);
      setManualLocationOpen(true);
    }, { enableHighAccuracy: false, timeout: 20000, maximumAge: 300000 });
  };

  const loadManualLocation = async () => {
    const city = manualCity.trim();
    if (!city || contextStatus === 'loading') return;
    setContextStatus('loading');
    setContextError('');
    try {
      const response = await fetch(`/api/context?city=${encodeURIComponent(city)}`);
      await acceptContextResponse(response, { method: 'manual', accuracy: 0, locatedAt: Date.now() });
      setManualLocationOpen(false);
    } catch (error) {
      setContextStatus('error');
      setContextError(error instanceof Error ? error.message : '城市天气查询失败。');
    }
  };

  const submitQuestion = async () => {
    const clean = query.trim();
    if (!clean || isSending) return;
    if (!settings.apiKey) {
      setSettingsDraft(settings);
      setSettingsError('先填写 API 密钥，再开始对话。');
      setSettingsOpen(true);
      return;
    }

    const userMessage: ChatMessage = { id: crypto.randomUUID(), role: 'user', content: clean };
    const history = [...messages, userMessage];
    setMessages(history);
    setQuery('');
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
          messages: history.map(({ role, content }) => ({ role, content })),
          liveContext: liveContext ? {
            ...liveContext,
            localTime: formatLocalTime(clockNow, liveContext.timezone),
          } : null,
        }),
      });
      const data = await response.json() as { reply?: string; error?: string };
      if (!response.ok || !data.reply) throw new Error(data.error || '暂时没有收到回答，请稍后再试。');
      setMessages((current) => [...current, { id: crypto.randomUUID(), role: 'assistant', content: data.reply! }]);
    } catch (error) {
      setChatError(error instanceof Error ? error.message : '对话请求失败，请检查设置后重试。');
    } finally {
      setIsSending(false);
    }
  };

  return (
    <main className="chat-shell">
      <header className="topbar">
        <div className="brand">
          <span className="brand-mark"><Sprout size={21} strokeWidth={2.2} /></span>
          <span className="brand-name">农心</span>
          <span className="brand-sub">农业对话助手</span>
        </div>
        <div className="top-actions">
          <span className={`service-dot ${settings.apiKey ? '' : 'unconfigured'}`}>{settings.apiKey ? `${providerOptions[settings.provider].label} 已配置` : 'AI 待配置'}</span>
          <Dialog open={settingsOpen} onOpenChange={(open) => { setSettingsOpen(open); if (open) { setSettingsDraft(settings); setSettingsError(''); } }}>
            <DialogTrigger render={<Button className="settings-button" />}><Settings2 size={17} />设置</DialogTrigger>
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
                    <button type="button" onClick={() => setShowKey((visible) => !visible)} aria-label={showKey ? '隐藏密钥' : '显示密钥'}>{showKey ? <EyeOff size={17} /> : <Eye size={17} />}</button>
                  </div>
                </div>
                {settingsError && <p className="settings-error">{settingsError}</p>}
              </div>
              <DialogFooter className="settings-footer"><Button onClick={saveSettings} className="save-settings">保存设置</Button></DialogFooter>
            </DialogContent>
          </Dialog>
        </div>
      </header>

      <section className="chat-workspace">
        <div className="chat-heading">
          <div><span className="eyebrow">新对话</span><h1>今天想问什么？</h1></div>
        </div>

        <div className="workspace-grid">
          <aside className="context-panel" aria-label="实时环境信息">
            <div className="context-panel-head">
              <span>实时环境</span>
              <span className={`data-state ${liveContext ? 'connected' : ''}`}>{liveContext ? '已接入' : '未接入'}</span>
            </div>
            <div className="context-item">
              <span className="context-icon"><MapPin size={16} /></span>
              <div><small>{liveContext?.method === 'manual' ? '手动位置' : '当前位置'}</small><b>{liveContext ? (liveContext.location || '已取得当前坐标') : '尚未定位'}</b><p>{liveContext ? (liveContext.method === 'device' ? `精度约 ±${Math.round(liveContext.accuracy)} 米` : '依据输入地区查询') : '仅用于本次查询'}</p></div>
            </div>
            <div className="context-item">
              <span className="context-icon"><CloudSun size={17} /></span>
              <div><small>实时天气</small><b>{liveContext ? `${weatherText(liveContext.weather.weatherCode)} · ${liveContext.weather.temperature ?? '--'}°C` : '等待定位'}</b><p>{liveContext ? weatherDetails(liveContext.weather) : '不使用默认城市'}</p></div>
            </div>
            <div className="context-item">
              <span className="context-icon"><Clock3 size={16} /></span>
              <div><small>当地时间</small><b>{liveContext ? formatLocalTime(clockNow, liveContext.timezone) : '等待定位'}</b><p>{liveContext ? liveContext.timezone : '按所在地更新'}</p></div>
            </div>
            <button className="locate-button" onClick={loadLiveContext} disabled={contextStatus === 'locating' || contextStatus === 'loading'}>
              {contextStatus === 'locating' || contextStatus === 'loading'
                ? <><LoaderCircle size={16} className="spin" />{contextStatus === 'locating' ? '正在定位' : '正在获取天气'}</>
                : liveContext
                  ? <><RefreshCw size={15} />刷新环境</>
                  : <><LocateFixed size={16} />获取实时信息</>}
            </button>
            {contextError && <p className="context-error">{contextError}</p>}
            {manualLocationOpen && <div className="manual-location"><label htmlFor="manual-city">手动输入城市</label><Input id="manual-city" value={manualCity} onChange={(event) => setManualCity(event.target.value)} onKeyDown={(event) => { if (event.key === 'Enter') void loadManualLocation(); }} placeholder="例如：杭州市余杭区" autoComplete="address-level2" /><button onClick={() => void loadManualLocation()} disabled={!manualCity.trim() || contextStatus === 'loading'}>查询</button></div>}
            {!manualLocationOpen && !liveContext && <button className="manual-toggle" onClick={() => setManualLocationOpen(true)}>手动输入城市</button>}
            {liveContext && <p className="context-source">{liveContext.sources.weather} · {new Date(liveContext.locatedAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })}</p>}
          </aside>

          <div className="chat-column">
            <div className="conversation" aria-live="polite">
              <div className="assistant-row">
                <span className="bot-seal">农心</span>
                <div className="message-block">
                  <div className="speaker"><b>农心助手</b><span>{providerOptions[settings.provider].label} · {settings.model}</span></div>
                  <div className="answer-card intro-card">
                    <p>你好。请直接描述你的问题。</p>
                    <p>如果问题与具体田块有关，请告诉我作物、所在地区、生育阶段和你观察到的现象。没有提供的信息，我不会自行假设。</p>
                  </div>
                  {messages.map((message) => message.role === 'user'
                    ? <div className="user-message" key={message.id}>{message.content}</div>
                    : <div className="answer-card chat-answer" key={message.id}>{message.content}</div>)}
                  {isSending && <div className="answer-card loading-answer"><LoaderCircle size={17} className="spin" />正在回答...</div>}
                  {chatError && <div className="chat-error"><span>{chatError}</span><button onClick={() => { setSettingsDraft(settings); setSettingsOpen(true); }}>检查设置</button></div>}
                  <div ref={conversationEnd} />
                </div>
              </div>
            </div>

            <div className="composer-wrap">
              <div className="composer">
                <textarea value={query} onChange={(event) => setQuery(event.target.value)} onKeyDown={(event) => { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); void submitQuestion(); } }} aria-label="向农心提问" placeholder="输入问题，Enter 发送，Shift + Enter 换行" />
                <div className="composer-tools">
                  <span className="composer-model">{providerOptions[settings.provider].label} · {settings.model}</span>
                  <button onClick={() => void submitQuestion()} disabled={isSending || !query.trim()} className="send-button" aria-label="发送">{isSending ? <LoaderCircle size={18} className="spin" /> : <Send size={18} />}</button>
                </div>
              </div>
              <div className="composer-foot"><p>回答仅基于你提供的信息；重要农事请结合现场核实。</p><button onClick={() => { setSettingsDraft(settings); setSettingsOpen(true); }}>切换模型</button></div>
            </div>
          </div>
        </div>
      </section>
    </main>
  );
}
