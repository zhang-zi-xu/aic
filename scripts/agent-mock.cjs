// 强制工具轮测试：mock LLM 验证 tool_choice 锁定 submit_clarify 与 submissions 采集
const { ToolRegistry } = require('../.smoke-out/tools.js');
const { runAgent } = require('../.smoke-out/run.js');

let passed = 0;
let failed = 0;
function check(name, cond, detail = '') {
  if (cond) { passed++; console.log(`  OK ${name}`); }
  else { failed++; console.log(`  FAIL ${name} ${detail}`); }
}

const registry = new ToolRegistry();
registry.register({
  name: 'submit_clarify',
  description: 'test',
  parameters: { type: 'object', properties: { intro: { type: 'string' }, items: { type: 'array' } }, required: ['intro', 'items'] },
  executor: (args) => `已登记确认清单（${(args.items ?? []).length} 项）`,
});
registry.register({
  name: 'get_field_context',
  description: 'test',
  parameters: { type: 'object', properties: {} },
  executor: () => '田块：1号田，水稻',
});

const seenBodies = [];
const fakeFetch = async (url, opts) => {
  const body = JSON.parse(opts.body);
  seenBodies.push(body);
  // 第一轮：强制调用 submit_clarify；第二轮：最终文字
  if (!body.tool_choice) {
    return { ok: true, json: async () => ({ choices: [{ message: { content: '先稳一稳，我列了几点你确认下。', tool_calls: null } }] }) };
  }
  return {
    ok: true,
    json: async () => ({
      choices: [{
        message: {
          content: '好的，请确认。',
          tool_calls: [{ id: 'call_1', type: 'function', function: { name: 'submit_clarify', arguments: JSON.stringify({ intro: '帮我确认一下', items: [{ question: '病斑什么形状？', options: ['梭形', '云纹'] }, { question: '生育期？', options: ['孕穗', '灌浆'] }] }) } }],
        },
      }],
    }),
  };
};

(async () => {
  global.fetch = fakeFetch;
  const result = await runAgent({
    model: 'test-model',
    endpoint: 'https://example.com/v1/chat/completions',
    apiKey: 'test-key-not-secret',
    systemPrompt: 'sys',
    tools: registry,
    toolCtx: { userId: 't', extra: {} },
    maxRounds: 3,
    forceTool: 'submit_clarify',
  }, [{ role: 'user', content: '稻叶有褐斑怎么办' }]);

  check('结果无错误', !('error' in result));
  check('submissions 捕获确认卡', result.submissions.length === 1 && result.submissions[0].name === 'submit_clarify');
  const args = result.submissions[0]?.args ?? {};
  check('确认卡含 2 项', Array.isArray(args.items) && args.items.length === 2);
  check('选项完整', args.items?.[0]?.options?.length === 2);
  check('强制轮携带 required', seenBodies.some((b) => b.tool_choice === 'required'));
  check('强制轮 tools 只含目标工具', seenBodies.filter((b) => b.tool_choice === 'required').every((b) => b.tools.length === 1 && b.tools[0].function.name === 'submit_clarify'));
  check('最终回复为文字', typeof result.reply === 'string' && result.reply.length > 0);

  // 对照组：无 forceTool 不应有 tool_choice
  seenBodies.length = 0;
  global.fetch = async (url, opts) => ({ ok: true, json: async () => ({ choices: [{ message: { content: '直接回复', tool_calls: null } }] }) });
  const result2 = await runAgent({
    model: 'test-model',
    endpoint: 'https://example.com/v1/chat/completions',
    apiKey: 'test-key-not-secret',
    systemPrompt: 'sys',
    tools: registry,
    toolCtx: { userId: 't', extra: {} },
    maxRounds: 2,
  }, [{ role: 'user', content: '你好' }]);
  check('无 forceTool 时无 tool_choice', seenBodies.every((b) => !b.tool_choice));
  check('无强制时正常返回', !('error' in result2) && result2.submissions.length === 0);

  console.log(`\n结果：${passed} 通过 / ${failed} 失败`);
  if (failed > 0) process.exit(1);
})();
