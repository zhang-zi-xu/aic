import type { ChatMessage, ClarifyArgs } from '../types';

export type ClarifyAnswers = Record<number, string>;

export function getClarifyItems(clarify: ClarifyArgs) {
  return (Array.isArray(clarify.items) ? clarify.items : [])
    .filter(item => item && typeof item.question === 'string' && item.question.trim())
    .map(item => ({
      question: item.question!.trim(),
      hint: typeof item.hint === 'string' ? item.hint : '',
      options: [...new Set((Array.isArray(item.options) ? item.options : [])
        .filter((option): option is string => typeof option === 'string' && !!option.trim())
        .map(option => option.trim()))],
    }));
}

export function setClarifyAnswer(answers: ClarifyAnswers, index: number, answer: string): ClarifyAnswers {
  return { ...answers, [index]: answer };
}

export function formatClarifyReply(clarify: ClarifyArgs, answers: ClarifyAnswers): string {
  const items = getClarifyItems(clarify);
  if (!items.length) throw new Error('这张确认卡没有可回答的问题。');
  if (items.some((_, index) => !answers[index]?.trim())) {
    throw new Error('请逐题选择或填写答案；不清楚的可以选择“暂不确定”。');
  }
  // Assemble only the user's actual answers; do not have a model invent connective facts.
  const reply = '我补充的信息如下：\n\n' + items.map((item, index) =>
    `${index + 1}. 问题：${item.question}\n回答：${answers[index].trim()}`,
  ).join('\n\n') + '\n\n请结合以上信息继续分析，并给出下一步建议。';
  if (reply.length > 4000) throw new Error('补充内容超过 4,000 字符，请精简填写的答案后再提交。');
  return reply;
}

/** Retire a card once the user has continued the conversation, including a failed request awaiting retry. */
export function hasClarifyFollowUp(messages: ChatMessage[], messageId: string): boolean {
  const index = messages.findIndex(message => message.id === messageId);
  return index >= 0 && messages.slice(index + 1).some(message => message.role === 'user');
}
