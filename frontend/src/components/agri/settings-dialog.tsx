import { useState } from 'react';
import { Check, Eye, EyeOff, ShieldCheck } from 'lucide-react';
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog';
import { NxSelect } from '@/components/ui/nx-select';
import { api, errorText, PROVIDERS, type AiSettings } from '@/lib/api';

export function SettingsDialog({ open, onOpenChange, settings, onSave }: {
  open: boolean; onOpenChange: (open: boolean) => void; settings: AiSettings; onSave: (settings: AiSettings) => void;
}) {
  return <Dialog open={open} onOpenChange={onOpenChange}><DialogContent className="nx-dialog nx-settings">
    <DialogTitle>连接你的模型</DialogTitle><DialogDescription>选择供应商，使用自己的 API 进行对话。</DialogDescription>
    {open && <SettingsForm settings={settings} onSave={onSave} />}
  </DialogContent></Dialog>;
}
function SettingsForm({ settings, onSave }: { settings: AiSettings; onSave: (settings: AiSettings) => void }) {
  const [draft, setDraft] = useState({ ...settings }); const [showKey, setShowKey] = useState(false);
  const [error, setError] = useState(''); const [testing, setTesting] = useState(false); const [success, setSuccess] = useState(false);
  function valid() {
    if (!draft.model.trim() || !draft.apiKey.trim()) throw new Error('请填写模型名称和 API Key。');
    if (draft.provider === 'custom') {
      let url: URL;
      try { url = new URL(draft.baseUrl); } catch { throw new Error('请填写完整的 HTTPS API 地址。'); }
      if (url.protocol !== 'https:' || url.username || url.password) throw new Error('API 地址须为 HTTPS，且不能包含账号密码。');
    }
    return { ...draft, apiKey: draft.apiKey.trim(), model: draft.model.trim(), baseUrl: draft.baseUrl.trim() };
  }
  async function test() {
    setError(''); setSuccess(false);
    try {
      const value = valid(); setTesting(true);
      const result = await api<{ reply: string }>('/chat', { method: 'POST', body: JSON.stringify({ ...value, messages: [{ role: 'user', content: '这是一条连接测试。请只回复：连接成功。' }] }) });
      if (!result.reply) throw new Error('接口已返回，但未收到有效回答。请检查模型名称及接口兼容性。');
      setSuccess(true);
    } catch (e) { setError(errorText(e)); } finally { setTesting(false); }
  }
  return <form className="nx-form" onSubmit={e => { e.preventDefault(); try { onSave(valid()); } catch (err) { setError(errorText(err)); } }}>
    <label className="nx-form-field">供应商<NxSelect ariaLabel="供应商" value={draft.provider} disabled={testing} options={PROVIDERS.map(p => ({ value: p.id, label: p.label }))} onValueChange={id => {
      const p = PROVIDERS.find(p => p.id === id)!;
      setDraft({ provider: p.id, baseUrl: p.baseUrl, model: p.model, apiKey: '' }); setSuccess(false); setError('');
    }} /></label>
    <label className="nx-form-field">API 地址<input type="url" autoComplete="off" value={draft.baseUrl} disabled={draft.provider !== 'custom' || testing} placeholder="https://你的服务商/v1" onChange={e => { setDraft({ ...draft, baseUrl: e.target.value }); setSuccess(false); }} /></label>
    <label className="nx-form-field">模型名称<input autoComplete="off" disabled={testing} value={draft.model} placeholder="填写供应商支持的完整模型 ID" maxLength={200} onChange={e => { setDraft({ ...draft, model: e.target.value }); setSuccess(false); }} /></label>
    <label className="nx-form-field">API Key<div className="nx-secret-input"><input autoComplete="off" spellCheck={false} disabled={testing} type={showKey ? 'text' : 'password'} value={draft.apiKey} placeholder="粘贴你的 API Key" onChange={e => { setDraft({ ...draft, apiKey: e.target.value }); setSuccess(false); }} /><button type="button" className="nx-icon-button" aria-label={showKey ? '隐藏密钥' : '显示密钥'} onClick={() => setShowKey(!showKey)}>{showKey ? <EyeOff size={17} /> : <Eye size={17} />}</button></div></label>
    <label className="nx-form-field">图片输入<NxSelect ariaLabel="图片输入" value={draft.vision ?? 'auto'} disabled={testing} options={[
      { value: 'auto', label: '自动判断（推荐）', hint: '按模型名单' },
      { value: 'on', label: '支持看图', hint: '我的模型支持' },
      { value: 'off', label: '不支持', hint: '纯文本模型' },
    ]} onValueChange={value => { setDraft({ ...draft, vision: value as 'auto' | 'on' | 'off' }); setSuccess(false); }} /></label>
    <div className="nx-security-note"><ShieldCheck size={18} /><p>图片输入按模型名单自动判断；名单里没有的模型会被明确拒绝，不会把照片发给不看图的模型。照片在浏览器里压缩并去除 GPS 等 EXIF 信息，服务端会再清理一次。</p></div>
    <div className="nx-security-note"><ShieldCheck size={18} /><p>密钥仅在当前浏览器会话中保存。请求经 Java 后端转发到所选供应商，不写入数据库。切换供应商会清空密钥。</p></div>
    {error && <p role="alert" className="nx-error">{error}</p>}{success && <p role="status" className="nx-success"><Check size={15} />已收到模型回答，连接可用。</p>}
    <div className="nx-dialog-actions"><button className="nx-button" type="button" disabled={testing} onClick={() => void test()}>{testing ? '正在测试…' : '测试连接'}</button><button className="nx-button is-primary" disabled={testing} type="submit">保存设置</button></div>
    <p className="nx-muted nx-fine">测试会向所选供应商发送一条简短请求，可能产生少量 API 费用。模型名称请以供应商控制台为准。</p>
  </form>;
}
