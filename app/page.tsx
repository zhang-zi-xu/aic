'use client';

import { useEffect, useRef, useState } from 'react';
import { Eye, EyeOff, LoaderCircle, Send, Settings2, Sprout } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';

type ProviderId = 'deepseek' | 'openai' | 'siliconflow' | 'custom';
type ChatMessage = { id: string; role: 'user' | 'assistant'; content: string };
type AiSettings = { provider: ProviderId; model: string; baseUrl: string; apiKey: string };

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
          <span className="data-state">未接入田块数据</span>
        </div>

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
      </section>
    </main>
  );
}
