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
  if (chunkIds.has(chunk.id)) problems.push(`片段 id 与库内重复：${chunk.id}`);
}
if (problems.length) {
  console.error('合并前校验未通过，未改动知识库：');
  for (const problem of problems) console.error('  - ' + problem);
  process.exit(1);
}

const before = { documents: library.documents.length, chunks: library.chunks.length };
let addedDocuments = 0;
let addedChunks = 0;
for (const doc of incoming.documents) { if (!docIds.has(doc.id)) { library.documents.push(doc); addedDocuments++; } }
for (const chunk of incoming.chunks) { if (!chunkIds.has(chunk.id)) { library.chunks.push(chunk); addedChunks++; } }

writeFileSync(target, JSON.stringify(library, null, 2) + '\n', 'utf8');
console.log(`知识库已更新：文档 ${before.documents} → ${library.documents.length}（新增 ${addedDocuments}）；片段 ${before.chunks} → ${library.chunks.length}（新增 ${addedChunks}）`);
console.log('回退：git checkout -- server/src/main/resources/knowledge/sources.json');
