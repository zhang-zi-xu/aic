import { useRef, useState } from 'react';
import { Camera, Info, LoaderCircle, X } from 'lucide-react';
import type { AttachedImage } from '@/types';
import { MAX_PHOTOS, imageFilesFrom, uploadPhoto } from '@/lib/images';

/** 待发送图片：显示在输入框内（textarea 上方），可单张移除。 */
export function ImageStrip({ images, onChange, disabled, label = '待发送的照片' }: {
  images: AttachedImage[];
  onChange: (next: AttachedImage[]) => void;
  disabled?: boolean;
  label?: string;
}) {
  if (images.length === 0) return null;
  return <ul className="nx-image-strip is-composer" aria-label={label}>
    {images.map(image => <li key={image.id}>
      <img src={image.url} alt={`待发送照片 ${image.width}×${image.height}`} />
      <button type="button" aria-label={`移除照片 ${image.name ?? image.id}`} disabled={disabled}
        onClick={() => {
          // 先解绑本地缩略图，再尽力通知服务端删除文件；删除失败不影响使用
          onChange(images.filter(item => item.id !== image.id));
          void fetch(`/api/uploads/${image.id}`, { method: 'DELETE' }).catch(() => {});
        }}><X size={12} /></button>
    </li>)}
  </ul>;
}

/**
 * 图片入口：选文件 / 粘贴 / 拖入都走同一条链路（压缩 → 去 EXIF → 上传）。
 * 粘贴与拖拽由 App 统一接管（要在输入框上监听），这里只负责按钮与说明。
 */
export function ImageAttach({ images, disabled, visionReady = true, onFiles, error, fieldId = '', fieldName = '' }: {
  images: AttachedImage[];
  disabled?: boolean;
  /** 当前模型是否按"能看图"处理：false 时只提示，不阻止用户先选图 */
  visionReady?: boolean;
  /** 交给 App：选中的文件统一走上传（与粘贴/拖拽共用） */
  onFiles: (files: File[]) => void;
  error?: string;
  /** 归档目标田块：非空时照片同时存入该田块的田间档案 */
  fieldId?: string;
  fieldName?: string;
}) {
  const inputRef = useRef<HTMLInputElement>(null);
  const busy = false;
  const [guideOpen, setGuideOpen] = useState(false);

  return <>
    <input ref={inputRef} type="file" accept="image/jpeg,image/png,image/webp" multiple className="sr-only"
      aria-label="选择照片" disabled={disabled}
      onChange={event => { onFiles(Array.from(event.target.files ?? [])); if (inputRef.current) inputRef.current.value = ''; }} />
    <button type="button" className="nx-icon-button" disabled={disabled}
      title="添加照片（拍照、选择，或直接 Ctrl+V 粘贴）" aria-label="添加照片"
      onClick={() => inputRef.current?.click()}>
      {busy ? <LoaderCircle size={19} className="spin" /> : <Camera size={19} />}
    </button>
    <button type="button" className="nx-icon-button nx-fine" aria-label="拍摄提示"
      title="怎么拍更有用" onClick={() => setGuideOpen(open => !open)}>
      <Info size={15} />
    </button>
    {images.length > 0 && <p className="nx-image-guide" role="note">
      {fieldId
        ? <>照片会存入 <b>{fieldName || '当前田块'}</b> 的田间档案，按日期排进时间轴；发送前移除会同时从档案删除。</>
        : <>当前对话未关联田块：照片只用于本次问答，<b>不会存入田间档案</b>。想留档请在会话栏选择田块。</>}
    </p>}
    {guideOpen && <p className="nx-image-guide" role="note">
      拍照顺序建议：<b>全株</b>（看长势与分布）→ <b>病部近景</b>（看斑点、霉层、虫体）→ <b>健康对照</b>（同一块地的正常植株）→ <b>环境</b>（积水、遮阴、周边作物）。
      一次一至三张就够，拍清楚比拍多更重要；带 GPS 的原始信息会在压缩时清除。截图可以直接 Ctrl+V 粘贴。
    </p>}
    {!visionReady && images.length > 0 && <p className="nx-image-guide is-warning" role="status">
      当前模型可能不支持看图：发送前请在「模型设置」里换成支持图片的模型，或把「图片输入」设为「支持」。
    </p>}
    {error && <p className="nx-error nx-fine" role="alert">{error}</p>}
  </>;
}

export { MAX_PHOTOS, imageFilesFrom, uploadPhoto };
