// 农心资料库入库助手（Tier 1 官方文件）
//
// 作用：把 tools/harvest/staging/*.txt（采集暂存）+ tools/harvest/manifest.json（人工登记的来源信息）
//       转成 sources.json 需要的 documents / chunks 两段 JSON，**不直接改知识库**，产物写到
//       tools/harvest/ingested.json 供人工复核后再合并。
//
// 用法：node tools/harvest/ingest.mjs
//
// 切分规则（可复核、可复现）：
//   - 按空行分段；遇到形如「一、」「（一）」「1.」「第X节」的行视为小标题，作为 chunk.heading
//   - 每个片段目标 400–900 字；过短则与后一段合并，避免碎片化
//   - 片段必须带 documentId，正文少于 80 字直接报错退出（防止采集残缺污染资料库）
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const manifestPath = join(here, 'manifest.json');
if (!existsSync(manifestPath)) { console.error('缺少 tools/harvest/manifest.json'); process.exit(1); }

const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
const REQUIRED = ['file', 'id', 'title', 'institution', 'url', 'publishedAt', 'region', 'crops', 'topic'];
const HEADING = /^(?:[一二三四五六七八九十]+、|（[一二三四五六七八九十]+）|\d+[.、]|第[一二三四五六七八九十]+[节部分])/;

/** 正文可能的起点（用于裁掉页面抬头）：据…预测、为做好/为深入…、坚持、当前、近年来、各省、一、 */
const MARKER = /(据[^，。]{0,14}(预测|监测|调度|调查)|为(做好|深入|贯彻|落实|切实|进一步|加强|提升|推进|规范|巩固)|坚持|当前|近年来|各省|一、|（一）)/;

/** 网页导航/页脚等"页面外壳"文字，一律丢弃，避免污染检索 */
const CHROME = /(无障碍浏览|信息员登录|智能问答|新媒体矩阵|当前位置|字号：|打印本页|分享到|网站地图|联系我们|版权|主办单位|承办单位|技术支持|相关新闻|阅览量|文件名称|下载量|扫一扫|关注我们|微信公众号|返回顶部|上一篇|下一篇|来源：本站|责任编辑|星期[一二三四五六日]|明日|今日|搜索)/;
/** 常见 HTML 实体解码（采集时只做了粗粒度去标签） */
const ENTITIES = { '&gt;': '>', '&lt;': '<', '&amp;': '&', '&quot;': '"', '&#39;': "'", '&apos;': "'",
  '&nbsp;': ' ', '&ldquo;': '“', '&rdquo;': '”', '&mdash;': '—', '&middot;': '·', '&hellip;': '…' };

/** 按片段内容识别作物：一份文件可能覆盖多种作物（如省级主推技术指南），
 *  若统统一律标成 manifest 里的首个作物，会污染"按作物过滤"的检索。 */
const CROP_WORDS = {
  水稻: ['水稻', '稻瘟', '稻飞虱', '稻曲', '纹枯', '二化螟', '稻纵卷叶螟', '育秧', '插秧', '齐穗', '穗颈瘟', '再生稻'],
  小麦: ['小麦', '麦田', '赤霉', '条锈', '叶锈', '麦蚜', '麦蜘蛛', '一喷三防', '冬小麦'],
  玉米: ['玉米', '草地贪夜蛾', '大斑病', '小斑病', '玉米螟'],
  油菜: ['油菜', '菌核病', '油菜蚜'],
  大豆: ['大豆', '大豆蚜'],
  马铃薯: ['马铃薯', '晚疫病'],
  蔬菜: ['蔬菜', '番茄', '黄瓜', '辣椒', '白菜', '设施蔬菜'],
  果树: ['果树', '苹果', '柑橘', '梨树', '桃树'],
  茶: ['茶树', '茶园'],
};

function detectCrop(text, fallback) {
  let best = null;
  let bestHits = 0;
  for (const [crop, words] of Object.entries(CROP_WORDS)) {
    const hits = words.reduce((sum, word) => sum + (text.includes(word) ? 1 : 0), 0);
    if (hits > bestHits) { best = crop; bestHits = hits; }
  }
  return bestHits > 0 ? best : fallback;
}

/**
 * 按片段内容提取关键词。
 * 教训：早期把"文档级关键词"复制到该文档的每个片段上，导致一份 145 片段的省级主推技术指南
 * 每个片段都带「玉米」，把真正讲玉米倒伏的片段挤出了检索前五。关键词必须逐片段从正文里取。
 */
const KEY_TERMS = ['稻瘟病', '稻曲病', '纹枯病', '白叶枯病', '细菌性条斑病', '稻飞虱', '二化螟', '稻纵卷叶螟', '三化螟', '穗颈瘟',
  '赤霉病', '条锈病', '叶锈病', '茎基腐病', '麦蚜', '麦蜘蛛', '地下害虫', '蛴螬', '金针虫', '草地贪夜蛾', '玉米螟',
  '菌核病', '晚疫病', '病毒病', '白粉病', '蚜虫', '红蜘蛛', '蓟马', '斜纹夜蛾',
  '倒伏', '控旺', '一喷三防', '一喷多促', '药剂拌种', '拌种', '种子包衣', '破口期', '齐穗', '抽穗', '灌浆', '孕穗', '分蘖', '苗期', '返青', '拔节',
  '施肥', '追肥', '测土配方', '有机肥', '化肥减量', '灌溉', '排水', '晒田', '断水', '育秧', '插秧', '机插', '直播', '抛秧', '再生稻',
  '农药', '安全间隔期', '农药残留', '绿色防控', '生物防治', '理化诱控', '统防统治', '抗药性', '抗性品种', '植保无人飞机', '无人机',
  '收获', '收割', '烘干', '贮藏', '秸秆还田', '高标准农田'];

function detectKeywords(text) {
  const found = KEY_TERMS.filter(term => text.includes(term));
  return found.slice(0, 8);
}

/** 清洗正文：解码实体 → 丢弃页面外壳行 → 去掉行首日期/来源等版式痕迹 → 压缩空白 */
function cleanBody(text, title) {
  let out = text;
  for (const [from, to] of Object.entries(ENTITIES)) out = out.split(from).join(to);
  out = out.replace(/&#x?[0-9a-fA-F]+;/g, ' ').replace(/&[a-zA-Z]{2,8};/g, ' ');   // 未列举的实体一律清掉，别留在正文里
  const kept = [];
  for (const line of out.split(/\r?\n/)) {
    let value = line.replace(/\s+/g, ' ').trim();
    if (!value) continue;
    if (CHROME.test(value)) continue;
    // 纯导航行（大量竖线分隔的短词）也丢掉
    if (value.length < 60 && (value.split('|').length >= 3 || value.split('>').length >= 3)) continue;
    // 去掉行首的版式痕迹：日期、来源、字号等
    value = value.replace(/^(日期：|来源：|作者：|发布时间：)\s*/, '');
    if (value === title || value === title.replace(/_.*$/, '')) continue;
    kept.push(value);
  }
  const joined = kept.join('\n').trim().replace(/\s*[0-9A-Z]{12,}\s*/g, ' ');   // 去掉文号/索引这类长串编码
  // 页面抬头（标题/栏目/机构/文号/成文日期/日历条）会粘在正文前。做法：在前 300 字内找所有"可能的正文起点"，
  // 取其中最靠后的一个作为切点——既能去掉抬头，又不会像"只看日期"那样把正文开头截掉。
  const head = joined.slice(0, 300);
  const cuts = [];
  const addCut = (match) => { if (match) cuts.push(match.index + match[0].length); };
  addCut(/(\d{4}年\d{1,2}月\d{1,2}日)/.exec(head));                                  // 2026年02月26日
  addCut(/(20\d{2}[-/]\d{1,2}[-/]\d{1,2}(?:\s+\d{1,2}:\d{2}(?::\d{2})?)?)/.exec(head)); // 2026-3-18 17:28:58
  const marker = head.search(MARKER);
  if (marker > 0) cuts.push(marker);                                                  // 据…预测 / 为做好 / 坚持 / 一、
  const cut = cuts.length ? Math.max(...cuts) : 0;
  if (cut > 0 && joined.length - cut > 200) {
    return joined.slice(cut).replace(/^[\s：:。、,，]+/, '').trim();
  }
  return joined;
}

const documents = [];
const chunks = [];
const problems = [];

for (const entry of manifest) {
  for (const key of REQUIRED) {
    if (!entry[key] || (Array.isArray(entry[key]) && entry[key].length === 0)) problems.push(`${entry.id ?? entry.file}: 缺少字段 ${key}`);
  }
  const path = join(here, 'staging', entry.file);
  if (!existsSync(path)) { problems.push(`${entry.id}: 找不到暂存文件 ${entry.file}`); continue; }

  const raw = readFileSync(path, 'utf8');
  const body = cleanBody(raw.split(/\r?\n/).filter(line => !line.trim().startsWith('#')).join('\n'), entry.title);
  if (body.length < 80) { problems.push(`${entry.id}: 正文仅 ${body.length} 字，疑似采集残缺`); continue; }

  documents.push({
    id: entry.id,
    title: entry.title,
    institution: entry.institution,
    url: entry.url,
    publishedAt: entry.publishedAt,
    fetchedAt: new Date().toISOString().slice(0, 10),
    region: entry.region,
    crops: entry.crops,
    topic: entry.topic,
    version: entry.version ?? `${entry.publishedAt} 发布版`,
    license: entry.license ?? '政府网站公开信息（行政性公开文件），转载须注明来源',
    reviewStatus: 'verified',
    reviewNote: entry.reviewNote ?? '按采集脚本抓取原文，人工复核后逐条登记；片段为原文摘录。',
  });

  // 分段并合并成 400–900 字的片段
  const paragraphs = body.split(/\n{1,}/).map(line => line.trim()).filter(line => line.length > 0);
  const keepCrops = entry.keepCrops ?? null;   // 可限定只保留某些作物的片段（如省级综合指南只留水稻/小麦）
  let index = 0;
  let current = null;
  let dropped = 0;
  const flush = () => {
    if (!current) return;
    const text = current.lines.join('');
    if (text.length >= 80) {
      const crop = detectCrop(text, entry.crops.length === 1 ? entry.crops[0] : '通用');
      if (keepCrops && !keepCrops.includes(crop)) { dropped++; current = null; return; }
      index += 1;
      chunks.push({
        id: `chunk-${entry.id.replace(/^doc-/, '')}-${index}`,
        documentId: entry.id,
        heading: current.heading || entry.title,
        locator: `正文片段 ${index}`,
        crop,
        region: entry.region,
        growthStage: current.growthStage ?? '',
        topic: entry.topic,
        text,
        keywords: detectKeywords(text),
      });
    }
    current = null;
  };
  for (const paragraph of paragraphs) {
    const isHeading = HEADING.test(paragraph) && paragraph.length <= 40;
    if (isHeading) { flush(); current = { heading: paragraph, lines: [], growthStage: '' }; continue; }
    if (!current) current = { heading: entry.title, lines: [], growthStage: '' };
    const stage = /(苗期|分蘖期|拔节|孕穗|破口|抽穗|扬花|灌浆|齐穗|返青|越冬)/.exec(paragraph);
    if (stage && !current.growthStage) current.growthStage = stage[1];
    current.lines.push(paragraph);
    if (current.lines.join('').length >= 900) flush();
    else if (current.lines.join('').length >= 400 && /[。；]$/.test(paragraph)) flush();
  }
  flush();
  if (dropped > 0) console.log(`  （${entry.id}：按 keepCrops 丢弃 ${dropped} 个非目标作物片段）`);
}

if (problems.length) {
  console.error('入库前检查未通过：');
  for (const problem of problems) console.error('  - ' + problem);
  process.exit(1);
}

const out = { documents, chunks };
writeFileSync(join(here, 'ingested.json'), JSON.stringify(out, null, 2), 'utf8');
console.log(`准备入库：${documents.length} 篇文档 / ${chunks.length} 个片段 → tools/harvest/ingested.json`);
for (const doc of documents) {
  const count = chunks.filter(chunk => chunk.documentId === doc.id).length;
  const chars = chunks.filter(chunk => chunk.documentId === doc.id).reduce((sum, chunk) => sum + chunk.text.length, 0);
  console.log(`  ${doc.id}  ${count} 片段 / ${chars} 字  ${doc.title.slice(0, 30)}`);
}
console.log('复核无误后，再把 documents 与 chunks 合并进 server/src/main/resources/knowledge/sources.json');
