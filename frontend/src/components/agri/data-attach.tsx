import { useState } from 'react';
import { FileText, Paperclip, Upload, X } from 'lucide-react';
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog';
export function DataAttach({ onAttach, attached, onClear, disabled }: { onAttach: (data: string) => void; attached: string | null; onClear: () => void; disabled?: boolean }) {
  const [open, setOpen] = useState(false); const [paste, setPaste] = useState(''); const [error, setError] = useState('');
  async function fileChanged(file?: File) {
    if (!file) return;
    if (file.size > 64 * 1024) { setError('文件超过 64 KB，请先提取需要分析的数据。'); return; }
    try {
      const text = await file.text();
      if (text.length > 3000) { setError('本次最多支持 3,000 字符，请选取需要分析的片段。内容未被截断或发送。'); return; }
      setPaste(text); setError('');
    } catch { setError('无法读取文件，请尝试粘贴文字。'); }
  }
  return <>
    {attached ? <span className="nx-attached"><FileText size={14} /><button disabled={disabled} onClick={() => { setPaste(attached); setOpen(true); }}>农情数据 · {attached.length} 字符</button><button disabled={disabled} aria-label="移除农情数据" onClick={onClear}><X size={14} /></button></span> : <button className="nx-icon-button" disabled={disabled} title="附加农情数据" aria-label="附加农情数据" onClick={() => setOpen(true)}><Paperclip size={19} /></button>}
    <Dialog open={open} onOpenChange={setOpen}><DialogContent className="nx-dialog"><DialogTitle>附加农情数据</DialogTitle><DialogDescription>粘贴文字或导入 CSV / TXT。此版本暂不分析图片。</DialogDescription>
      <textarea className="nx-data-input" value={paste} rows={8} maxLength={3000} onChange={e => setPaste(e.target.value)} placeholder="粘贴实际记录，尽量包含采集日期、指标名称、数值和单位。" aria-label="农情数据内容" />
      <span className="nx-muted nx-fine">{paste.length} / 3,000 字符 · 数据将随下一条问题发送给所选模型</span>
      {error && <p role="alert" className="nx-error">{error}</p>}
      <div className="nx-dialog-actions"><label className="nx-button nx-upload"><Upload size={16} />导入文本<input type="file" accept=".csv,.txt,.tsv" onChange={e => void fileChanged(e.target.files?.[0])} /></label><button className="nx-button is-primary" disabled={!paste.trim() || disabled} onClick={() => { onAttach(paste.trim()); setOpen(false); }}>确认附加</button></div>
    </DialogContent></Dialog>
  </>;
}
