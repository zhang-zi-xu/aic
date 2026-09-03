'use client';

import { useEffect, useRef, useState } from 'react';
import { QRCodeSVG } from 'qrcode.react';
import { CloudSun, Eye, EyeOff, Leaf, LoaderCircle, MapPin, MessageCircle, MoreHorizontal, QrCode, Send, Settings2, Sprout } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger } from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';

const tasks = [
  { time: '今天', title: '棚内通风', note: '10:30 前完成，控制湿度低于 75%', tone: 'urgent' },
  { time: '明天', title: '二穗果追肥', note: '高钾水溶肥 4 kg / 亩，滴灌 25 分钟', tone: 'normal' },
  { time: '9 月 6 日', title: '复查叶片', note: '固定机位补拍 3 张，判断斑点是否扩散', tone: 'normal' },
];

const weekPlan = [
  ['今天', '降湿控病', '上午分段通风 2 次，每次 20 分钟；暂停叶面喷水。'],
  ['明天', '水肥一体化', '土壤含水率低于 62% 时启动滴灌，高钾水溶肥 4 kg / 亩。'],
  ['周六', '整枝打杈', '晴天上午摘除第一花序下侧枝，工具逐株消毒。'],
  ['周日', '病斑复查', '固定 3 个点位拍照，与本周基线图对比。'],
  ['下周一', '授粉与巡棚', '10:00 前完成熊蜂箱检查，记录落花率。'],
];

type ProviderId = 'deepseek' | 'openai' | 'siliconflow' | 'custom';

type ChatMessage = {
  id: string;
  role: 'user' | 'assistant';
  content: string;
};

type AiSettings = {
  provider: ProviderId;
  model: string;
  baseUrl: string;
  apiKey: string;
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

declare global {
  interface Document {
    modelContext?: {
      registerTool: (tool: Record<string, unknown>, options?: { signal?: AbortSignal }) => void | Promise<void>;
    };
  }
}

export default function Home() {
  const [activeTab, setActiveTab] = useState('ask');
  const [query, setQuery] = useState('');
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [isSending, setIsSending] = useState(false);
  const [chatError, setChatError] = useState('');
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [settings, setSettings] = useState<AiSettings>(initialSettings);
  const [settingsDraft, setSettingsDraft] = useState<AiSettings>(initialSettings);
  const [settingsError, setSettingsError] = useState('');
  const [showKey, setShowKey] = useState(false);
  const [planConcern, setPlanConcern] = useState('高湿病害风险与二穗果水肥管理');
  const chatInput = useRef<HTMLTextAreaElement>(null);
  const conversationEnd = useRef<HTMLDivElement>(null);
  const [scanUrl, setScanUrl] = useState('https://nongxin-agent.site/?channel=wechat');

  const submitQuestion = async (text = query) => {
    const clean = text.trim();
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

  const changeProvider = (provider: ProviderId) => {
    const preset = providerOptions[provider];
    if (!preset) return;
    setSettingsDraft((current) => ({ ...current, provider, baseUrl: preset.baseUrl, model: preset.model }));
  };

  const saveSettings = () => {
    const next = {
      ...settingsDraft,
      model: settingsDraft.model.trim(),
      baseUrl: settingsDraft.baseUrl.trim(),
      apiKey: settingsDraft.apiKey.trim(),
    };
    if (!next.model) return setSettingsError('请填写模型名称。');
    if (!next.apiKey) return setSettingsError('请填写 API 密钥。');
    if (next.provider === 'custom' && !next.baseUrl.startsWith('https://')) return setSettingsError('自定义 API 地址需要以 https:// 开头。');
    setSettings(next);
    sessionStorage.setItem('nongxin-ai-settings', JSON.stringify(next));
    setSettingsError('');
    setSettingsOpen(false);
    setChatError('');
  };

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
    } catch { /* ignore invalid local preferences */ }
  }, []);

  useEffect(() => {
    conversationEnd.current?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }, [messages, isSending]);

  useEffect(() => {
    const context = document.modelContext;
    if (!context?.registerTool) return;
    const lifecycle = new AbortController();
    const tool = {
      name: 'generate_field_plan',
      title: '生成田块农事方案',
      description: '为指定田块和作物生成可执行的七天农事方案，并在页面中打开方案。',
      inputSchema: {
        type: 'object',
        properties: {
          plot: { type: 'string', description: '田块名称，如东棚' },
          crop: { type: 'string', description: '作物名称，如番茄' },
          concern: { type: 'string', description: '当前最关心的问题' },
        },
        required: ['plot', 'crop', 'concern'],
        additionalProperties: false,
      },
      annotations: { readOnlyHint: false, untrustedContentHint: false },
      execute: async (input: unknown) => {
        const value = input as { plot?: unknown; crop?: unknown; concern?: unknown };
        if (typeof value?.plot !== 'string' || typeof value?.crop !== 'string' || typeof value?.concern !== 'string') throw new Error('plot、crop 和 concern 必须是文本');
        setPlanConcern(value.concern);
        setActiveTab('plan');
        return { status: 'generated', plot: value.plot, crop: value.crop, days: 7 };
      },
    };
    try { void Promise.resolve(context.registerTool(tool, { signal: lifecycle.signal })); } catch { /* optional browser capability */ }
    return () => lifecycle.abort();
  }, []);

  return (
    <main className="shell">
      <header className="topbar">
        <div className="brand"><span className="brand-mark"><Sprout size={21} strokeWidth={2.2} /></span><span className="brand-name">农心</span><span className="brand-sub">田间决策助手</span></div>
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
                    <SelectContent>
                      {Object.entries(providerOptions).map(([id, item]) => <SelectItem key={id} value={id}>{item.label}</SelectItem>)}
                    </SelectContent>
                  </Select>
                </div>
                <label htmlFor="model-name">
                  <span>模型</span>
                  <Input id="model-name" value={settingsDraft.model} onChange={(event) => setSettingsDraft((current) => ({ ...current, model: event.target.value }))} placeholder="填写模型名称" autoComplete="off" />
                </label>
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
              <DialogFooter className="settings-footer">
                <Button onClick={saveSettings} className="save-settings">保存设置</Button>
              </DialogFooter>
            </DialogContent>
          </Dialog>
          <Dialog>
            <DialogTrigger onClick={() => setScanUrl(`${window.location.origin}/?channel=wechat`)} render={<Button className="wechat-button" />}><QrCode size={17} />微信接入</DialogTrigger>
            <DialogContent className="qr-dialog">
              <DialogHeader><DialogTitle>用微信扫一扫</DialogTitle><DialogDescription>把农心带到田里，拍照、发语音都能问。</DialogDescription></DialogHeader>
              <div className="qr-placeholder" aria-label="微信接入二维码"><QRCodeSVG value={scanUrl} size={150} bgColor="#fffefa" fgColor="#173f2c" level="M" marginSize={1} /></div>
              <p className="qr-tip">扫码会打开移动端接入演示 · 农场档案自动同步</p>
            </DialogContent>
          </Dialog>
          <button className="avatar" aria-label="个人中心">林</button>
        </div>
      </header>

      <div className="workspace">
        <aside className="farm-rail">
          <div className="farm-heading"><p>当前农场</p><button aria-label="更多农场操作"><MoreHorizontal size={19} /></button></div>
          <h1>青禾家庭农场</h1>
          <div className="location"><MapPin size={14} />浙江·浦江·白马镇</div>
          <div className="weather-card"><CloudSun size={30} /><div><strong>28°</strong><span>多云 · 东南风 2 级</span></div></div>
          <div className="rail-section">
            <div className="section-label"><span>田块</span><button>管理</button></div>
            <button className="plot active"><span className="plot-icon">01</span><span><b>东棚 · 番茄</b><small>4.2 亩 · 花果期</small></span></button>
            <button className="plot"><span className="plot-icon">02</span><span><b>西棚 · 黄瓜</b><small>3.8 亩 · 结瓜期</small></span></button>
            <button className="plot"><span className="plot-icon">03</span><span><b>露地 · 水稻</b><small>4.6 亩 · 灌浆期</small></span></button>
          </div>
          <div className="knowledge-note"><Leaf size={17} /><div><b>知识库已更新</b><span>收录 2026 年浙江植保意见</span></div></div>
        </aside>

        <section className="main-panel">
          <Tabs value={activeTab} onValueChange={setActiveTab} className="content-tabs">
            <div className="panel-head">
              <TabsList className="tabs-list"><TabsTrigger value="ask">问农事</TabsTrigger><TabsTrigger value="plan">农事方案</TabsTrigger><TabsTrigger value="archive">田块档案</TabsTrigger></TabsList>
              <span className="last-sync">数据更新于 18:26</span>
            </div>
            <TabsContent value="ask" className="ask-view">
              <div className="conversation">
                <div className="date-divider"><span>今天</span></div>
                <div className="assistant-row">
                  <span className="bot-seal">农心</span>
                  <div className="message-block">
                    <div className="speaker"><b>农心助手</b><span>{providerOptions[settings.provider].label} · {settings.model}</span></div>
                    <div className="answer-card">
                      <p className="answer-lead">林师傅，我在。</p>
                      <p>直接说说田里遇到的情况，我会先给判断，再把建议整理成能照着做的步骤。信息不够时，我会明确问你补充什么。</p>
                    </div>
                    {messages.map((message) => message.role === 'user'
                      ? <div className="user-message" key={message.id}>{message.content}</div>
                      : <div className="answer-card followup-answer chat-answer" key={message.id}>{message.content}</div>)}
                    {isSending && <div className="answer-card followup-answer loading-answer"><LoaderCircle size={17} className="spin" />农心正在斟酌...</div>}
                    {chatError && <div className="chat-error"><span>{chatError}</span><button onClick={() => { setSettingsDraft(settings); setSettingsOpen(true); }}>检查设置</button></div>}
                    <div ref={conversationEnd} />
                  </div>
                </div>
              </div>
              <div className="quick-asks"><button onClick={() => void submitQuestion('这周怎么追肥？')}>这周怎么追肥？</button><button onClick={() => void submitQuestion('番茄叶片有褐色病斑，应该先确认什么？')}>叶片病斑怎么判断？</button><button onClick={() => void submitQuestion('请帮我列一个简洁的 7 天番茄农事安排，需要我先补充哪些信息？')}>生成 7 天农事安排</button></div>
              <div className="composer-wrap">
                <div className="composer">
                  <textarea ref={chatInput} value={query} onChange={(event) => setQuery(event.target.value)} onKeyDown={(event) => { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); void submitQuestion(); } }} aria-label="向农心提问" placeholder="说说田里遇到的事……" />
                  <div className="composer-tools"><span className="composer-model">{providerOptions[settings.provider].label} · {settings.model}</span><button onClick={() => submitQuestion()} disabled={isSending || !query.trim()} className="send-button" aria-label="发送">{isSending ? <LoaderCircle size={18} className="spin" /> : <Send size={18} />}</button></div>
                </div>
                <div className="composer-foot"><p>重要农事请结合田间实际确认，农药使用以当地登记标签为准。</p><button onClick={() => { setSettingsDraft(settings); setSettingsOpen(true); }}>切换模型</button></div>
              </div>
            </TabsContent>
            <TabsContent value="plan" className="plan-view">
              <div className="plan-title"><div><span>方案编号 NX-0903-01</span><h2>东棚番茄 · 7 天农事方案</h2><p>重点：{planConcern}</p></div><button onClick={() => window.print()}>打印处置单</button></div>
              <div className="plan-summary"><div><span>风险判断</span><b>早疫病 · 中风险</b></div><div><span>水肥策略</span><b>控氮稳钾 · 1 次追肥</b></div><div><span>预计投入</span><b>约 186 元 / 亩</b></div></div>
              <div className="plan-timeline">{weekPlan.map(([day, title, detail], index) => <div className="plan-row" key={day}><span className="plan-index">{String(index + 1).padStart(2, '0')}</span><div><small>{day}</small><b>{title}</b><p>{detail}</p></div><span className="plan-status">{index === 0 ? '待执行' : '已排程'}</span></div>)}</div>
              <div className="plan-basis"><Leaf size={18} /><div><b>方案依据可追溯</b><p>田块传感器 4 项、本地气象 2 项、《浙江省 2026 年番茄病虫害绿色防控意见》等 4 条资料。</p></div></div>
            </TabsContent>
            <TabsContent value="archive" className="archive-view">
              <div className="archive-title"><span>东棚 · 番茄</span><h2>一块田，一本连续生长的档案</h2><p>从定植到采收，数据、图片和每次建议都留有出处。</p></div>
              <div className="archive-grid"><div className="archive-card"><span>生育期</span><b>二穗果膨大期</b><p>定植第 48 天 · 预计首采还有 19 天</p></div><div className="archive-card"><span>环境记录</span><b>1,284 条</b><p>温度、湿度、基质 EC、土壤含水率</p></div><div className="archive-card"><span>田间影像</span><b>36 组</b><p>固定点位对比，最近更新于今天 17:42</p></div><div className="archive-card"><span>农事记录</span><b>21 条</b><p>用肥、用药、整枝、授粉与采收</p></div></div>
              <div className="knowledge-list"><h3>已接入的本地知识</h3><div><span>地方规程</span><b>浦江县设施番茄绿色生产技术要点</b><small>2026-06 更新</small></div><div><span>植保意见</span><b>浙江省番茄主要病虫害绿色防控意见</b><small>2026-03 更新</small></div><div><span>农药登记</span><b>中国农药信息网登记标签索引</b><small>按周校验</small></div></div>
            </TabsContent>
          </Tabs>
        </section>

        <aside className="task-rail">
          <div className="task-head"><div><span>东棚 · 番茄</span><h2>今日农事</h2></div><span className="task-count">3 项</span></div>
          <div className="risk-card"><div className="risk-top"><span>病害风险</span><strong>中</strong></div><div className="risk-meter"><i /></div><p>持续高湿，重点留意下部叶片</p></div>
          <div className="task-list">{tasks.map((task, index) => <div className={`task-item ${task.tone}`} key={task.title}><span className="task-check">{index === 0 ? '!' : ''}</span><div><small>{task.time}</small><b>{task.title}</b><p>{task.note}</p></div></div>)}</div>
          <Button onClick={() => { setActiveTab('ask'); setQuery('把明天的追肥改到后天，其他安排顺延'); setTimeout(() => chatInput.current?.focus(), 0); }} variant="outline" className="plan-button"><MessageCircle size={17} />让农心调整计划</Button>
          <div className="trace-note"><span>本次建议使用</span><b>气象 2 项 · 传感器 4 项 · 农技资料 4 条</b></div>
        </aside>
      </div>
    </main>
  );
}
