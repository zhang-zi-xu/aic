import { useEffect, useState } from 'react';
import { QRCodeSVG } from 'qrcode.react';
import { Copy, Smartphone, TriangleAlert } from 'lucide-react';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { api } from '@/lib/api';

type Address = { address: string; interface: string; label?: string; private: boolean; virtual?: boolean; recommended?: boolean; remote?: boolean };

/**
 * 手机访问：把本机的局域网地址做成二维码，扫码就能在手机上打开。
 * 二维码里只有 http 地址，不含密钥或聊天内容；服务端只枚举内网 IPv4。
 */
export function MobileDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const [addresses, setAddresses] = useState<Address[]>([]);
  const [hint, setHint] = useState('');
  const [error, setError] = useState('');
  const [copied, setCopied] = useState('');
  const port = typeof window === 'undefined' ? '3000' : (window.location.port || '3000');

  useEffect(() => {
    if (!open) return;
    const controller = new AbortController();
    setError('');
    void api<{ addresses: Address[]; hint: string }>('/network', { signal: controller.signal })
      .then(result => { setAddresses(result.addresses ?? []); setHint(result.hint ?? ''); })
      .catch(cause => { if (!controller.signal.aborted) setError(cause instanceof Error ? cause.message : '读取局域网地址失败'); });
    return () => controller.abort();
  }, [open]);

  const url = (address: string) => `http://${address}:${port}/`;
  async function copy(link: string) {
    try {
      await navigator.clipboard.writeText(link);
      setCopied(link);
      setTimeout(() => setCopied(''), 2000);
    } catch { setCopied(''); }
  }

  return <Dialog open={open} onOpenChange={onOpenChange}>
    <DialogContent className="nx-dialog">
      <DialogHeader>
        <DialogTitle><Smartphone size={18} /> 手机访问</DialogTitle>
        <DialogDescription>手机与电脑连同一个 Wi-Fi，扫码或输入地址即可打开。电脑上的服务要保持运行。</DialogDescription>
      </DialogHeader>
      {error && <p className="nx-error" role="alert">{error}</p>}
      {!error && addresses.length === 0 && <p className="nx-muted">{hint || '正在读取局域网地址…'}</p>}
      {addresses.map(item => <div className={`nx-mobile-item${item.remote ? ' is-remote' : item.recommended ? ' is-recommended' : ''}${item.virtual ? ' is-virtual' : ''}`} key={item.interface + item.address}>
        <QRCodeSVG value={url(item.address)} size={132} marginSize={1} aria-label={`手机访问 ${url(item.address)} 的二维码`} />
        <div className="nx-mobile-meta">
          <b>{url(item.address)}{item.remote ? <span className="nx-mobile-tag is-remote">远程访问 · 演示用</span> : item.recommended && <span className="nx-mobile-tag">同一 Wi-Fi 用这个</span>}</b>
          <span className="nx-muted">{item.label || item.interface}{item.remote ? ' · 需在手机上也装同一套组网工具' : item.virtual ? ' · 代理/VPN 用，手机连不上' : item.private ? ' · 局域网内可用' : ''}</span>
          <button type="button" className="nx-button is-small" onClick={() => void copy(url(item.address))}>
            <Copy size={14} />{copied === url(item.address) ? '已复制' : '复制链接'}
          </button>
        </div>
      </div>)}
      <div className="nx-mobile-notes">
        <TriangleAlert size={16} />
        <ul>
          <li>手机和电脑<b>连同一个 Wi-Fi</b>，扫上面的二维码就能用：拍照问问题、看任务、记复查都可以。</li>
          <li>电脑要<b>开着</b>，关机后手机就打不开。</li>
          <li>万一打不开：先看手机是不是连了别的网（比如用了流量）；再看第一次运行时电脑上的防火墙提示有没有点「允许」。这个地址别随便发给别人。</li>
        </ul>
      </div>
    </DialogContent>
  </Dialog>;
}
