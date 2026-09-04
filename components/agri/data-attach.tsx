'use client';

import { useState } from 'react';
import { ClipboardPaste, FileUp, X } from 'lucide-react';

/** 农情数据挂载：粘贴或上传 CSV/文本，附加到提问中 */
export function DataAttach({ onAttach, attached, onClear }: {
  onAttach: (data: string) => void;
  attached: string | null;
  onClear: () => void;
}) {
  const [open, setOpen] = useState(false);
  const [paste, setPaste] = useState('');

  const attachText = () => {
    const clean = paste.trim();
    if (!clean) return;
    onAttach(clean);
    setPaste('');
    setOpen(false);
  };

  const handleFile = async (file: File | undefined) => {
    if (!file) return;
    if (file.size > 512 * 1024) {
      alert('文件过大，请控制在 512KB 以内（初赛演示建议直接粘贴数据）。');
      return;
    }
    const text = await file.text();
    if (!text.trim()) return;
    onAttach(text.slice(0, 4000));
    setOpen(false);
  };

  if (attached) {
    return (
      <div className="data-attach-bar">
        <span className="data-attach-type"><ClipboardPaste size={13} />农情数据已挂载</span>
        <span className="data-attach-preview">{attached.slice(0, 90)}{attached.length > 90 ? '…' : ''}</span>
        <button onClick={onClear} aria-label="移除数据"><X size={14} /></button>
      </div>
    );
  }

  return (
    <div className="data-attach-wrap">
      {open && (
        <div className="data-attach-pop">
          <div className="data-attach-head">
            <b>挂载农情数据</b>
            <button onClick={() => setOpen(false)} aria-label="关闭"><X size={14} /></button>
          </div>
          <p className="data-attach-hint">粘贴时间序列或指标数值（示例：6月1日 湿度 92%，6月2日 降水 45mm），或上传 CSV 文件。</p>
          <textarea value={paste} onChange={(e) => setPaste(e.target.value)} rows={4} placeholder={'6月1日 湿度 92%\n6月2日 降水量 45mm\n6月3日 温度 28°C'} />
          <div className="data-attach-actions">
            <button onClick={attachText} disabled={!paste.trim()}>粘贴内容</button>
            <label className="data-file-btn">
              <FileUp size={13} />上传文件
              <input type="file" accept=".csv,.txt,.tsv" onChange={(e) => void handleFile(e.target.files?.[0])} />
            </label>
          </div>
        </div>
      )}
      <button className="data-attach-trigger" onClick={() => setOpen((v) => !v)}>
        <ClipboardPaste size={14} />农情数据
      </button>
    </div>
  );
}
