'use client';

import { useEffect, useRef, useState } from 'react';
import { QRCodeSVG } from 'qrcode.react';
import { Camera, ChevronRight, CloudSun, FileUp, Leaf, MapPin, MessageCircle, Mic, MoreHorizontal, QrCode, Send, Sprout } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle, DialogTrigger } from '@/components/ui/dialog';
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

const answers: Record<string, { lead: string; body: string; actions: string[] }> = {
  '这周怎么追肥？': {
    lead: '这周只安排一次追肥，放在明天上午。',
    body: '东棚番茄正处于二穗果膨大期，结合基质 EC 2.1 mS/cm 和未来两天无强降雨，建议采用高钾配方，避免继续偏施氮肥。',
    actions: ['高钾水溶肥 4 kg / 亩', '滴灌 25 分钟', '两天后复测 EC'],
  },
  '帮我看叶片病斑': {
    lead: '可以，先拍下部叶正反面和整株环境。',
    body: '最好在自然光下拍 3 张：病斑近照、叶片正反面、整株与棚内环境。农心会先判断是否具备典型特征，再给出复查或处置步骤。',
    actions: ['避开强反光', '叶片占画面 2/3', '同时记录棚内湿度'],
  },
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
  const [submitted, setSubmitted] = useState('');
  const [reply, setReply] = useState<(typeof answers)[string] | null>(null);
  const [uploadName, setUploadName] = useState('');
  const [planConcern, setPlanConcern] = useState('高湿病害风险与二穗果水肥管理');
  const fileInput = useRef<HTMLInputElement>(null);
  const chatInput = useRef<HTMLTextAreaElement>(null);
  const [scanUrl, setScanUrl] = useState('https://nongxin-agent.site/?channel=wechat');

  const submitQuestion = (text = query) => {
    const clean = text.trim();
    if (!clean) return;
    setSubmitted(clean);
    setReply(answers[clean] ?? {
      lead: '这件事需要结合东棚数据分两步处理。',
      body: '我已把你的问题和当前作物生育期、近 7 天棚内温湿度以及本地农技资料放在一起核对。先完成现场确认，再生成用量和时间都明确的处置单。',
      actions: ['核对田间现状', '匹配本地规程', '生成可执行清单'],
    });
    setQuery('');
  };

  const chooseQuickAsk = (text: string) => {
    if (text.includes('7 天')) {
      setPlanConcern('未来 7 天病害预防与水肥安排');
      setActiveTab('plan');
      return;
    }
    submitQuestion(text);
  };

  const handleUpload = async (event: React.ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    if (!file) return;
    setUploadName(file.name);
    setSubmitted(`已上传农情数据：${file.name}`);
    let body = '系统已将文件加入东棚本次分析，并结合田块档案重新计算风险。正式接入后可继续解析 Excel、PDF 检测单与田间图片。';
    let actions = ['完成文件校验', '关联东棚档案', '加入 7 天方案'];
    if (file.name.toLowerCase().endsWith('.csv')) {
      const rows = (await file.text()).trim().split(/\r?\n/).map((line) => line.split(','));
      const humidityIndex = rows[0]?.findIndex((cell) => cell.trim() === 'humidity_pct') ?? -1;
      const humidityValues = humidityIndex >= 0 ? rows.slice(1).map((row) => Number(row[humidityIndex])).filter(Number.isFinite) : [];
      const highHours = humidityValues.filter((value) => value > 85).length;
      const peak = humidityValues.length ? Math.max(...humidityValues) : 0;
      body = humidityValues.length ? `共读取 ${humidityValues.length} 条湿度记录，其中 ${highHours} 条高于 85%，峰值 ${peak}%。这与东棚叶片结露记录吻合，我已将持续高湿计入病害风险判断。` : 'CSV 已读取，但没有找到 humidity_pct 列；请核对表头后再上传。';
      actions = humidityValues.length ? [`读取 ${humidityValues.length} 条记录`, `高湿 ${highHours} 小时`, '加入 7 天方案'] : ['检查 CSV 表头', '保留原田块判断', '等待重新上传'];
    }
    setReply({
      lead: '农情文件已读取，风险判断同步更新。',
      body,
      actions,
    });
  };

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
          <span className="service-dot">服务正常</span>
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
                    <div className="speaker"><b>农心助手</b><span>结合东棚近 7 天数据</span></div>
                    <div className="answer-card">
                      <p className="answer-lead">林师傅，东棚番茄今天先控湿，不急着打药。</p>
                      <p>昨晚棚内湿度连续 5 小时高于 85%，叶面有结露，早疫病风险正在上升。你发来的叶片照片里，斑点还没出现同心轮纹，暂不能直接判定病害。</p>
                      <div className="action-strip"><span>今天先做</span><strong>通风 40 分钟</strong><i /><strong>暂停叶面喷水</strong><i /><strong>傍晚再拍 3 张</strong></div>
                      <button className="source-link">依据 4 条本地农技资料生成 <ChevronRight size={15} /></button>
                    </div>
                    {submitted && reply && <>
                      <div className="user-message">{submitted}</div>
                      <div className="answer-card followup-answer">
                        <p className="answer-lead">{reply.lead}</p>
                        <p>{reply.body}</p>
                        <div className="action-strip"><span>接下来</span>{reply.actions.map((action, index) => <span className="action-piece" key={action}>{action}{index < reply.actions.length - 1 && <i />}</span>)}</div>
                        <button className="source-link">已核对田块档案与本地资料 <ChevronRight size={15} /></button>
                      </div>
                    </>}
                  </div>
                </div>
              </div>
              {uploadName && <div className="upload-chip"><FileUp size={14} />已读取 {uploadName}</div>}
              <div className="quick-asks"><button onClick={() => chooseQuickAsk('这周怎么追肥？')}>这周怎么追肥？</button><button onClick={() => chooseQuickAsk('帮我看叶片病斑')}>帮我看叶片病斑</button><button onClick={() => chooseQuickAsk('生成 7 天农事安排')}>生成 7 天农事安排</button></div>
              <div className="composer-wrap">
                <div className="composer">
                  <textarea ref={chatInput} value={query} onChange={(event) => setQuery(event.target.value)} onKeyDown={(event) => { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); submitQuestion(); } }} aria-label="向农心提问" placeholder="说说田里遇到的事，也可以上传照片、检测表……" />
                  <input ref={fileInput} type="file" accept=".csv,.xlsx,.xls,.pdf,image/*" onChange={handleUpload} hidden />
                  <div className="composer-tools"><div><button onClick={() => fileInput.current?.click()} aria-label="上传农情文件"><FileUp size={19} /></button><button onClick={() => { setQuery('请帮我识别这张叶片照片里的病斑'); chatInput.current?.focus(); }} aria-label="拍照"><Camera size={19} /></button><button onClick={() => { setQuery('语音输入：东棚番茄叶子有褐色小斑点，该怎么处理？'); chatInput.current?.focus(); }} aria-label="语音输入"><Mic size={19} /></button></div><button onClick={() => submitQuestion()} className="send-button" aria-label="发送"><Send size={18} /></button></div>
                </div>
                <div className="composer-foot"><p>重要农事请结合田间实际确认，农药使用以当地登记标签为准。</p><a href="/sample-sensor.csv" download>下载示例农情数据</a></div>
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
