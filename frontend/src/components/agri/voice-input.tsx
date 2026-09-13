import { useEffect, useRef, useState } from 'react';
import { LoaderCircle, Mic } from 'lucide-react';

type SpeechCtor = new () => SpeechRecognitionLike;
interface SpeechRecognitionLike {
  lang: string; continuous: boolean; interimResults: boolean;
  start(): void; stop(): void; abort(): void;
  onresult: ((event: SpeechResultEventLike) => void) | null;
  onerror: ((event: { error?: string }) => void) | null;
  onend: (() => void) | null;
}
interface SpeechResultEventLike {
  resultIndex: number;
  results: ArrayLike<{ isFinal: boolean; 0: { transcript: string } } & ArrayLike<unknown>>;
}

/**
 * 语音输入：用浏览器自带的语音识别（Chrome/Edge/安卓 Chrome），不配置任何 ASR Key、不产生费用。
 *
 * 三条原则：
 * 1. **不放假的按钮**——浏览器不支持（iOS Safari、Firefox）时直接不渲染；
 * 2. **识别结果只追加到输入框，绝不自动发送**，用户可以先改错别字再发（方言、药剂名容易听错）；
 * 3. **如实说明音频去向**——声音由浏览器厂商的识别服务处理，不经过本项目的后端。
 */
export function VoiceInput({ onText, disabled }: { onText: (text: string) => void; disabled?: boolean }) {
  const [supported] = useState(() => typeof window !== 'undefined'
    && Boolean((window as unknown as { SpeechRecognition?: SpeechCtor; webkitSpeechRecognition?: SpeechCtor }).SpeechRecognition
      || (window as unknown as { webkitSpeechRecognition?: SpeechCtor }).webkitSpeechRecognition));
  const [listening, setListening] = useState(false);
  const [interim, setInterim] = useState('');
  const [error, setError] = useState('');
  const recognitionRef = useRef<SpeechRecognitionLike | null>(null);
  const finalRef = useRef('');

  useEffect(() => () => { recognitionRef.current?.abort(); }, []);

  if (!supported) return null;

  function stop() {
    recognitionRef.current?.stop();
    setListening(false);
  }

  function start() {
    const ctor = (window as unknown as { SpeechRecognition?: SpeechCtor; webkitSpeechRecognition?: SpeechCtor }).SpeechRecognition
      ?? (window as unknown as { webkitSpeechRecognition?: SpeechCtor }).webkitSpeechRecognition;
    if (!ctor) return;
    setError(''); setInterim(''); finalRef.current = '';
    const recognition = new ctor();
    recognition.lang = 'zh-CN';
    recognition.continuous = true;
    recognition.interimResults = true;
    recognition.onresult = event => {
      let live = '';
      for (let index = event.resultIndex; index < event.results.length; index++) {
        const result = event.results[index];
        const text = String(result[0]?.transcript ?? '');
        if (result.isFinal) finalRef.current += text;
        else live += text;
      }
      setInterim(live);
    };
    recognition.onerror = event => {
      const code = event?.error ?? '';
      if (code === 'aborted') return;
      setError(code === 'not-allowed' || code === 'service-not-allowed'
        ? '没拿到麦克风权限：请在地址栏的权限提示里允许使用麦克风。'
        : code === 'no-speech' ? '没听清，靠近一点再说一遍。'
        : code === 'network' ? '语音识别需要联网，请检查网络后重试。'
        : '语音识别没能启动，改用打字也可以。');
      setListening(false);
    };
    recognition.onend = () => {
      setListening(false); setInterim('');
      const text = finalRef.current.trim();
      if (text) onText(text);
      finalRef.current = '';
    };
    recognitionRef.current = recognition;
    try {
      recognition.start();
      setListening(true);
    } catch {
      setError('语音识别没能启动，改用打字也可以。');
      setListening(false);
    }
  }

  return <>
    <button type="button" className={`nx-icon-button${listening ? ' is-listening' : ''}`} disabled={disabled}
      aria-label={listening ? '停止语音输入' : '语音输入'} aria-pressed={listening}
      title={listening ? '正在听…说完点一下结束' : '语音输入（说完可以改字，不会自动发送）'}
      onClick={() => (listening ? stop() : start())}>
      {listening ? <LoaderCircle size={19} className="spin" /> : <Mic size={19} />}
    </button>
    {listening && <span className="nx-voice-status" role="status">正在听…{interim ? `「${interim.slice(-24)}」` : '说完点一下结束'}</span>}
    {error && <span className="nx-voice-error" role="alert">{error}</span>}
  </>;
}
