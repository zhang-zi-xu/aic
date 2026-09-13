import { useRef, useState } from 'react';
import { Pencil, Trash2 } from 'lucide-react';
import { Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/ui/dialog';
import { errorText, type Conversation } from '@/lib/api';

export function ConversationActions({ conversation, disabled, onRename, onDelete }: {
  conversation: Conversation; disabled: boolean;
  onRename: (id: string, title: string) => Promise<void>;
  onDelete: (id: string) => Promise<void>;
}) {
  const [action, setAction] = useState<'rename' | 'delete' | null>(null);
  const [title, setTitle] = useState(''); const [busy, setBusy] = useState(false); const [error, setError] = useState('');
  const busyRef = useRef(false);
  async function submit() {
    if (busyRef.current || disabled || !action || (action === 'rename' && !title.trim())) return;
    busyRef.current = true; setBusy(true); setError('');
    try {
      if (action === 'rename') await onRename(conversation.id, title.trim()); else await onDelete(conversation.id);
      setAction(null);
    } catch (e) { setError(errorText(e)); }
    finally { busyRef.current = false; setBusy(false); }
  }
  return <div className="nx-history-actions">
    <button disabled={disabled} title="重命名对话" aria-label={`重命名对话：${conversation.title}`} onClick={() => { setTitle(conversation.title); setError(''); setAction('rename'); }}><Pencil size={14} /></button>
    <button disabled={disabled} title="删除对话" aria-label={`删除对话：${conversation.title}`} onClick={() => { setError(''); setAction('delete'); }}><Trash2 size={14} /></button>
    <Dialog open={!!action} onOpenChange={open => { if (!open && !busy) setAction(null); }}><DialogContent className="nx-dialog" showCloseButton={!busy}>
      <DialogTitle>{action === 'rename' ? '重命名对话' : '删除这段对话？'}</DialogTitle>
      <DialogDescription>{action === 'rename' ? '换一个便于查找的标题，不会改变对话内容。' : `将删除“${conversation.title}”的聊天记录，无法撤销。已加入的农事任务和田块档案不会删除。需要保留时，请先导出对话。`}</DialogDescription>
      <form onSubmit={e => { e.preventDefault(); void submit(); }} className="nx-form">
        {action === 'rename' && <label className="nx-form-field">对话标题<input autoFocus value={title} maxLength={200} disabled={busy} onChange={e => setTitle(e.target.value)} /></label>}
        {error && <p role="alert" className="nx-error">{error}</p>}
        <div className="nx-dialog-actions"><button type="button" className="nx-button" disabled={busy} onClick={() => setAction(null)}>取消</button><button type="submit" className={`nx-button ${action === 'delete' ? 'is-danger' : 'is-primary'}`} disabled={busy || disabled || (action === 'rename' && !title.trim())}>{busy ? '正在处理…' : action === 'rename' ? '保存标题' : '确认删除对话'}</button></div>
      </form>
    </DialogContent></Dialog>
  </div>;
}
