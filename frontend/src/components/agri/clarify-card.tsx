import { useId, useRef, useState } from 'react';
import { ArrowUp, Check, CircleHelp, LoaderCircle } from 'lucide-react';
import type { ClarifyArgs } from '@/types';
import { formatClarifyReply, getClarifyItems, type ClarifyAnswers } from '@/lib/clarification';

export function ClarifyCard({ clarify, answers, onAnswer, onSubmit, disabled, completed }: {
  clarify: ClarifyArgs;
  answers: ClarifyAnswers;
  onAnswer: (index: number, answer: string) => void;
  onSubmit: (reply: string) => Promise<void>;
  disabled?: boolean;
  completed?: boolean;
}) {
  const id = useId();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');
  const submittingRef = useRef(false);
  const items = getClarifyItems(clarify);
  const answeredCount = items.filter((_, index) => answers[index]?.trim()).length;
  const locked = disabled || completed || submitting;

  async function submit() {
    if (locked || submittingRef.current) return;
    setError('');
    try {
      const reply = formatClarifyReply(clarify, answers);
      submittingRef.current = true;
      setSubmitting(true);
      await onSubmit(reply);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '未能提交，请重试。');
    } finally {
      submittingRef.current = false;
      setSubmitting(false);
    }
  }

  return <section className="nx-clarify">
    <header><CircleHelp size={16} /><b>补充一点情况</b></header>
    {clarify.intro && <p>{clarify.intro}</p>}
    <form onSubmit={event => { event.preventDefault(); void submit(); }}>
      {items.map((item, index) => <fieldset key={index} className="nx-clarify-question" disabled={locked}>
        <legend>{index + 1}. {item.question}</legend>
        {item.hint && <p className="nx-muted">{item.hint}</p>}
        <div className="nx-clarify-options">
          {[...new Set([...item.options, '暂不确定'])].map(option => <label key={option} className={`nx-clarify-option${answers[index] === option ? ' is-selected' : ''}`}>
            <input type="radio" name={`${id}-question-${index}`} value={option} checked={answers[index] === option}
              onChange={() => { setError(''); onAnswer(index, option); }} />
            <span>{option}</span>{answers[index] === option && <Check size={13} aria-hidden="true" />}
          </label>)}
        </div>
        <details className="nx-clarify-custom" open={item.options.length ? undefined : true}>
          <summary>自行填写或补充</summary>
          <label className="sr-only" htmlFor={`${id}-answer-${index}`}>{item.question}的回答</label>
          <textarea id={`${id}-answer-${index}`} rows={2} maxLength={1000} value={answers[index] || ''}
            placeholder="填写你的实际情况，不清楚的可以说明暂不确定。"
            onChange={event => { setError(''); onAnswer(index, event.target.value); }} />
        </details>
        {answers[index]?.trim() && <p className="nx-clarify-answer">当前回答：{answers[index]}</p>}
      </fieldset>)}
      {error && <p className="nx-error" role="alert">{error}</p>}
      <footer className="nx-clarify-footer">
        <p aria-live="polite">{completed ? '已继续对话，请查看下方消息。' : `已回答 ${answeredCount} / ${items.length} 项，提交后统一发送。`}</p>
        <button className="nx-button is-primary" type="submit" disabled={locked || !items.length || answeredCount !== items.length}>
          {completed ? <Check size={15} /> : submitting ? <LoaderCircle size={15} className="spin" /> : <ArrowUp size={15} />}
          {completed ? '已继续对话' : submitting ? '正在提交…' : '提交并继续'}
        </button>
      </footer>
    </form>
  </section>;
}
