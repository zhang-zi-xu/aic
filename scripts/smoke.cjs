// 核心逻辑烟雾测试（CJS 版，跑编译产物）
const { resolveCity, CITY_TABLE } = require('../.smoke-out/geo/cities.js');
const { searchKnowledge } = require('../.smoke-out/kb/search.js');
const { parseFarmData, extractMetrics, assessRisk } = require('../.smoke-out/fields/risk.js');
const { getPhenology } = require('../.smoke-out/fields/phenology.js');

let passed = 0;
let failed = 0;
function check(name, cond, detail = '') {
  if (cond) { passed++; console.log(`  OK ${name}`); }
  else { failed++; console.log(`  FAIL ${name} ${detail}`); }
}

console.log('--- 城市解析 ---');
check('城市表加载(>300)', CITY_TABLE.length > 300, `实际 ${CITY_TABLE.length}`);
check('杭州市余杭区 -> 杭州市', resolveCity('杭州市余杭区')?.name === '杭州市');
const yuhang = resolveCity('杭州市余杭区');
check('杭州市坐标', yuhang && Math.abs(yuhang.lat - 30.27) < 0.05 && Math.abs(yuhang.lon - 120.16) < 0.05);
check('余杭区(县级不在表内→null,走Photon)', resolveCity('余杭区') === null);
check('浙江杭州 -> 杭州市', resolveCity('浙江杭州')?.name === '杭州市');
check('衡水市 精确', resolveCity('衡水市')?.name === '衡水市');
check('未知城市 -> null', resolveCity('不存在的城市xyz') === null);

console.log('--- 知识库检索 ---');
const q1 = searchKnowledge('水稻叶子有褐色梭形斑怎么办');
check('稻瘟病命中', q1.length > 0 && q1[0].entry.id === 'rice-blast', q1.map((h) => h.entry.id).join(','));
const q2 = searchKnowledge('小麦扬花期下雨怕赤霉病');
check('赤霉病命中', q2.some((h) => h.entry.id === 'wheat-scab'), q2.map((h) => h.entry.id).join(','));
const q3 = searchKnowledge('玉米地倒伏了');
check('玉米倒伏命中', q3.some((h) => h.entry.id === 'corn-lodging'), q3.map((h) => h.entry.id).join(','));
const q4 = searchKnowledge('今天天气如何');
check('天气问题低分/空', q4.length === 0 || q4.every((h) => h.score <= 3));
const q5 = searchKnowledge('用啥药治稻瘟病');
check('稻瘟药问命中', q5.some((h) => h.entry.id === 'rice-blast'));

console.log('--- 风险引擎 ---');
const m1 = assessRisk({ metrics: [{ name: '湿度', value: 92 }], series: [] });
check('湿度92 -> 中R1', m1.overall === '中' && m1.items.some((i) => i.rule === 'R1'));
const m2 = assessRisk({ metrics: [{ name: '降水量', value: 60 }], series: [] });
check('降水60 -> 高R2', m2.overall === '高' && m2.items.some((i) => i.rule === 'R2'));
const m3 = assessRisk({ metrics: [{ name: '温度', value: -2 }], series: [], context: { phaseText: '抽穗扬花期' } });
check('低温+抽穗 -> R5', m3.items.some((i) => i.rule === 'R5'));
const m4 = assessRisk({ metrics: [{ name: '土壤湿度', value: 30 }], series: [] });
check('土壤30% -> R4', m4.items.some((i) => i.rule === 'R4'));
const m5 = assessRisk({ metrics: [], series: [] });
check('无指标 -> R0', m5.items.every((i) => i.rule === 'R0'));

console.log('--- 数据解析 ---');
const csv = '6月1日 湿度 88\n6月2日 降水量 45\n6月3日 温度 30';
const p1 = parseFarmData(csv);
check('文本格式解析3行', p1.series.length === 3, `实际 ${p1.series.length}`);
check('指标识别', p1.metrics.includes('湿度') && p1.metrics.includes('降水量'));
const p2 = parseFarmData('日期,温度,湿度,降水量\n2026-09-01,28.5°C,92%,45mm\n2026-09-02,29°C,88%,3mm');
check('多列 CSV 解析', p2.series.length === 2 && p2.metrics.length === 3, `实际 ${p2.series.length} 行 / ${p2.metrics.length} 个指标`);
check('带单位数值解析', p2.series.some((row) => row.湿度 === 92 && row.降水量 === 45));
const ext = extractMetrics('今天温度28.5度，湿度90%，最近下了55mm雨');
check('自由文本提取', ext.some((m) => m.name === '温度' && m.value === 28.5) && ext.some((m) => m.name === '湿度' && m.value === 90) && ext.some((m) => m.name === '降水量' && m.value === 55));

console.log('--- 生育期 ---');
const ph1 = getPhenology('水稻', '2026-05-20');
check('水稻生育期非空', ph1.phase !== null);
const ph2 = getPhenology('小麦', '2026-09-01', new Date('2026-09-15'));
check('小麦播后15天 -> 出苗分蘖', ph2.phase?.name === '出苗分蘖期');
const ph3 = getPhenology('棉花', '2026-01-01');
check('未知作物 -> null', ph3.phase === null);
const ph4 = getPhenology('水稻', '2026-10-01', new Date('2026-09-15'));
check('未来播期不推算', ph4.phase === null && ph4.note.includes('播期尚未到'));

console.log(`\n结果：${passed} 通过 / ${failed} 失败`);
if (failed > 0) process.exit(1);
