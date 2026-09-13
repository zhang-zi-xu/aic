import type { AttachedImage } from '@/types';

const MAX_EDGE = 1600;
const MAX_BYTES = 8 * 1024 * 1024;
export const MAX_PHOTOS = 3;
const ACCEPTED = ['image/jpeg', 'image/jpg', 'image/png', 'image/webp'];

/** canvas 重编码：缩到 1600px 内的 JPEG，顺带去掉 EXIF/GPS；环境不支持时退回原图（服务端还会再清一次）。 */
async function shrink(file: File): Promise<Blob> {
  if (typeof document === 'undefined' || typeof createImageBitmap !== 'function') return file;
  try {
    const bitmap = await createImageBitmap(file);
    const scale = Math.min(1, MAX_EDGE / Math.max(bitmap.width, bitmap.height));
    const width = Math.max(1, Math.round(bitmap.width * scale));
    const height = Math.max(1, Math.round(bitmap.height * scale));
    const canvas = document.createElement('canvas');
    canvas.width = width; canvas.height = height;
    const context = canvas.getContext('2d');
    if (!context) return file;
    context.fillStyle = '#fff';
    context.fillRect(0, 0, width, height);
    context.drawImage(bitmap, 0, 0, width, height);
    bitmap.close?.();
    const blob = await new Promise<Blob | null>(resolve => canvas.toBlob(resolve, 'image/jpeg', 0.85));
    return blob && blob.size > 0 ? blob : file;
  } catch { return file; }
}

/** 校验 + 上传单张图片；失败时抛出可直接展示给用户的中文原因。 */
export async function uploadPhoto(file: File, options: { fieldId?: string; observedAt?: string } = {}): Promise<AttachedImage> {
  const type = (file.type || '').toLowerCase();
  if (!ACCEPTED.includes(type)) {
    throw new Error('只支持 JPEG / PNG / WEBP 图片。iPhone 的 HEIC 请先在相册里转存为 JPEG，或截图后再粘贴。');
  }
  if (file.size > MAX_BYTES) throw new Error(`图片超过 ${MAX_BYTES / 1024 / 1024} MB，请先压缩或改小截图。`);
  const blob = await shrink(file);
  const body = new FormData();
  body.append('file', blob, file.name || 'paste.jpg');
  if (options.fieldId) {
    body.append('fieldId', options.fieldId);
    body.append('observedAt', options.observedAt || new Date().toISOString().slice(0, 10));
  }
  const response = await fetch('/api/uploads', { method: 'POST', body });
  const data = await response.json().catch(() => null);
  if (!response.ok) throw new Error(typeof data?.error === 'string' ? data.error : `图片上传失败（${response.status}）`);
  return { id: data.id, url: data.url, mime: data.mime, width: data.width, height: data.height, bytes: data.bytes,
    name: file.name || '粘贴的图片', fieldId: data.fieldId, observedAt: data.observedAt, note: data.note, createdAt: data.createdAt };
}

/** 从粘贴板/拖拽里挑出图片文件。 */
export function imageFilesFrom(list: DataTransferItemList | FileList | null | undefined): File[] {
  if (!list) return [];
  const files: File[] = [];
  if ('length' in list) {
    for (let index = 0; index < list.length; index++) {
      const item = list[index] as DataTransferItem | File;
      if (typeof (item as DataTransferItem).getAsFile === 'function') {
        const dataItem = item as DataTransferItem;
        if (dataItem.kind === 'file' && dataItem.type.startsWith('image/')) {
          const file = dataItem.getAsFile();
          if (file) files.push(file);
        }
      } else if (item instanceof File && item.type.startsWith('image/')) {
        files.push(item);
      }
    }
  }
  return files;
}
