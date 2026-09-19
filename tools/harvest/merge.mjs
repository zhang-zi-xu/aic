// 把 tools/harvest/ingested.json 合并进 server/src/main/resources/knowledge/sources.json
//
// 用法：node tools/harvest/merge.mjs
//
// 安全设计：
//   - 合并前校验：文档/片段 id 唯一、每个片段的 documentId 都存在于文档表、片段正文 ≥80 字
//   - 任一项不通过 → 直接退出，不改动知识库
//   - 已存在的 id 跳过（可重复执行，不会写重复条目）
//   - 回退方式：git checkout -- server/src/main/resources/knowledge/sources.json
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const target = join(here, '..', '..', 'server', 'src', 'main', 'resources', 'knowledge', 'sources.json');
const read = (path) => JSON.parse(readFileSync(path, 'utf8').replace(/^\uFEFF/, ''));

const library = read(target);
const incoming = read(join(here, 'ingested.json'));

const problems = [];
const docIds = new Set(library.documents.map((doc) => doc.id));
const chunkIds = new Set(library.chunks.map((chunk) => chunk.id));
const incomingDocIds = new Set();
const incomingChunkIds = new Set();

for (const doc of incoming.documents) {
  for (const field of ['id', 'title', 'institution', 'url', 'publishedAt']) {
    if (!doc[field]) problems.push(`文档 ${doc.id ?? '?'} 缺少 ${field}`);
  }
  if (incomingDocIds.has(doc.id)) problems.push(`待入库文档 id 重复：${doc.id}`);
  incomingDocIds.add(doc.id);
}
for (const chunk of incoming.chunks) {
  if (!incomingDocIds.has(chunk.documentId) && !docIds.has(chunk.documentId)) problems.push(`片段 ${chunk.id} 的 documentId 不存在：${chunk.documentId}`);
  if ((chunk.text ?? '').trim().length < 80) problems.push(`片段 ${chunk.id} 正文过短（${chunk.text?.length ?? 0} 字）`);
  // 库内已有同 id＝重新采集了同一批文件：跳过即可，不算错误（此前误判为错误，导致重复采集直接失败）
  if (incomingChunkIds.has(chunk.id)) problems.push(`本次待入库文件内部 id 重复：${chunk.id}`);
  incomingChunkIds.add(chunk.id);
}
if (problems.length) {
  console.error('合并前校验未通过，未改动知识库：');
  for (const problem of problems) console.error('  - ' + problem);
  process.exit(1);
}

const before = { documents: library.documents.length, chunks: library.chunks.length };
let addedDocuments = 0;
let addedChunks = 0;
let replacedDocuments = 0;
for (const doc of incoming.documents) {
  const existing = library.documents.findIndex(item => item.id === doc.id);
  if (existing >= 0) {
    // 同一来源重新采集：更新元数据并**替换**它的片段（否则旧的错误片段会一直留在库里）
    library.documents[existing] = doc;
    const keep = library.chunks.filter(chunk => chunk.documentId !== doc.id);
    const removed = library.chunks.length - keep.length;
    library.chunks = keep;
    replacedDocuments++;
    if (removed) console.log(`  ${doc.id}：替换时移除旧片段 ${removed} 个`);
  } else {
    library.documents.push(doc);
    addedDocuments++;
  }
}
// 去重集合必须按"替换后的现状"重建：只在替换时删片段、不更新集合，
// 会导致同一来源的片段"删了却加不回来"（此前真实踩到，库从 210 掉到 27）
chunkIds.clear();
for (const chunk of library.chunks) chunkIds.add(chunk.id);
for (const chunk of incoming.chunks) { if (!chunkIds.has(chunk.id)) { library.chunks.push(chunk); chunkIds.add(chunk.id); addedChunks++; } }

writeFileSync(target, JSON.stringify(library, null, 2) + '\n', 'utf8');
console.log(`知识库已更新：文档 ${before.documents} → ${library.documents.length}（新增 ${addedDocuments}）；片段 ${before.chunks} → ${library.chunks.length}（新增 ${addedChunks}）`);
console.log('回退：git checkout -- server/src/main/resources/knowledge/sources.json');
