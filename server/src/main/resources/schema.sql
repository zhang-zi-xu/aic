-- 农心 Agent 数据库结构（SQLite）——目标结构 v5
-- 仅由受控初始化入口用于空库建表，或在已有库完成备份和迁移后补齐配套表。
-- CREATE TABLE IF NOT EXISTS 不能升级已有表；禁止在旧库备份/迁移之前直接运行本脚本。
CREATE TABLE IF NOT EXISTS fields (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  crop TEXT NOT NULL,
  variety TEXT,
  sow_date TEXT NOT NULL,
  area_mu REAL,
  notes TEXT,
  created_at TEXT NOT NULL DEFAULT (datetime('now','localtime')),
  user_id TEXT NOT NULL DEFAULT 'local-owner'
);

CREATE TABLE IF NOT EXISTS field_records (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  field_id TEXT NOT NULL,
  record_date TEXT NOT NULL,
  note TEXT NOT NULL,
  FOREIGN KEY (field_id) REFERENCES fields(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_records_field ON field_records(field_id);

-- 农事任务：状态机 + 方案依据 + 用户执行/复查记录（记录见 task_records）
CREATE TABLE IF NOT EXISTS farm_tasks (
  id TEXT PRIMARY KEY,
  title TEXT NOT NULL,
  task_date TEXT NOT NULL,
  field_id TEXT,
  field_name TEXT NOT NULL DEFAULT '',
  condition_text TEXT NOT NULL DEFAULT '',
  method TEXT NOT NULL DEFAULT '',
  review TEXT NOT NULL DEFAULT '',
  note TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL DEFAULT 'pending',
  time_window TEXT NOT NULL DEFAULT '',
  materials TEXT NOT NULL DEFAULT '',
  risk TEXT NOT NULL DEFAULT '',
  evidence TEXT NOT NULL DEFAULT '[]',
  plan_item_id TEXT,
  source_message_id TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT,
  confirmed_at TEXT,
  executed_at TEXT,
  completed_at TEXT,
  user_id TEXT NOT NULL DEFAULT 'local-owner',
  FOREIGN KEY (field_id) REFERENCES fields(id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_tasks_date ON farm_tasks(task_date);
CREATE INDEX IF NOT EXISTS idx_tasks_status ON farm_tasks(status);
-- 同一句方案里的同一个方案项只能登记一次任务（幂等）
CREATE UNIQUE INDEX IF NOT EXISTS idx_tasks_plan_item
  ON farm_tasks(source_message_id, plan_item_id)
  WHERE plan_item_id IS NOT NULL AND source_message_id IS NOT NULL;

-- 用户提交的执行/复查记录：任务 + 田块 + 来源消息都能追溯到
CREATE TABLE IF NOT EXISTS task_records (
  id TEXT PRIMARY KEY,
  task_id TEXT NOT NULL,
  field_id TEXT,
  kind TEXT NOT NULL,
  record_date TEXT NOT NULL,
  note TEXT NOT NULL,
  outcome TEXT NOT NULL DEFAULT '',
  source_message_id TEXT,
  created_at TEXT NOT NULL,
  FOREIGN KEY (task_id) REFERENCES farm_tasks(id) ON DELETE CASCADE,
  FOREIGN KEY (field_id) REFERENCES fields(id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_task_records_task ON task_records(task_id, created_at);

CREATE TABLE IF NOT EXISTS conversations (
  id TEXT PRIMARY KEY,
  title TEXT NOT NULL,
  field_id TEXT,
  messages_json TEXT NOT NULL,
  created_at TEXT,
  user_id TEXT NOT NULL DEFAULT 'local-owner',
  FOREIGN KEY (field_id) REFERENCES fields(id) ON DELETE SET NULL
);

-- 用户与数据归属（v5）：还没有登录体系时，所有数据归本机所有者；
-- phone / wechat_open_id 位置已留好，将来接手机号或微信登录不用改表结构。
CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY,
  display_name TEXT NOT NULL DEFAULT '',
  phone TEXT,
  phone_verified INTEGER NOT NULL DEFAULT 0,
  wechat_open_id TEXT,
  status TEXT NOT NULL DEFAULT 'active',
  created_at TEXT NOT NULL,
  last_login_at TEXT
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_phone ON users(phone) WHERE phone IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_wechat ON users(wechat_open_id) WHERE wechat_open_id IS NOT NULL;

-- 图片附件：原图存磁盘（data/uploads），库里只登记元数据与引用状态；
-- 聊天记录里只保存 uploads 的 id，绝不把 base64 写进文本字段。
CREATE TABLE IF NOT EXISTS uploads (
  id TEXT PRIMARY KEY,
  mime TEXT NOT NULL,
  ext TEXT NOT NULL,
  bytes INTEGER NOT NULL,
  width INTEGER NOT NULL,
  height INTEGER NOT NULL,
  sha256 TEXT NOT NULL DEFAULT '',
  created_at TEXT NOT NULL,
  referenced_at TEXT,
  field_id TEXT,
  observed_at TEXT,
  note TEXT NOT NULL DEFAULT '',
  task_id TEXT,
  user_id TEXT NOT NULL DEFAULT 'local-owner'
);

CREATE INDEX IF NOT EXISTS idx_uploads_created ON uploads(created_at);
CREATE INDEX IF NOT EXISTS idx_uploads_referenced ON uploads(referenced_at);

-- RAG 向量索引：只存 chunkId → 向量，正文仍以来源库为唯一事实源
CREATE TABLE IF NOT EXISTS kb_vectors (
  chunk_id TEXT PRIMARY KEY,
  model TEXT NOT NULL,
  dim INTEGER NOT NULL,
  vector TEXT NOT NULL,
  updated_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);

-- 服务端演示 Key 的用量计数（按天 × 作用域：ip / global），用于成本护栏
CREATE TABLE IF NOT EXISTS api_usage (
  day TEXT NOT NULL,
  scope TEXT NOT NULL,
  scope_key TEXT NOT NULL,
  count INTEGER NOT NULL DEFAULT 0,
  updated_at TEXT NOT NULL DEFAULT (datetime('now','localtime')),
  PRIMARY KEY (day, scope, scope_key)
);

-- 答案缓存（同问同田块上下文直接复用，降低调用成本）
CREATE TABLE IF NOT EXISTS answer_cache (
  cache_key TEXT PRIMARY KEY,
  reply TEXT NOT NULL,
  plan_json TEXT,
  risk_json TEXT,
  clarify_json TEXT,
  cached_at TEXT NOT NULL DEFAULT (datetime('now','localtime')),
  hit_count INTEGER NOT NULL DEFAULT 0
);

-- 结构版本：新库由本文件直接建到目标版本；老库由迁移服务补记并按版本升级
CREATE TABLE IF NOT EXISTS schema_version (
  version INTEGER PRIMARY KEY,
  applied_at TEXT NOT NULL,
  note TEXT NOT NULL DEFAULT ''
);

-- 调用方必须保证空库或已成功迁移；空版本表本身不能证明结构已达到目标版本。
-- 应用入口在事务内运行脚本和结构校验，失败不会留下新库的部分结构或版本登记。
INSERT INTO schema_version (version, applied_at, note)
SELECT 5, datetime('now','localtime'), 'schema.sql 直接建库（目标结构 v5：含数据归属）'
WHERE NOT EXISTS (SELECT 1 FROM schema_version);

-- 本机所有者：登录体系上线前，所有数据都归它（新库直接建好，老库由迁移 v5 补）
INSERT OR IGNORE INTO users (id, display_name, status, created_at, phone_verified)
VALUES ('local-owner', '本机所有者', 'active', datetime('now','localtime'), 0);

-- 归属索引：必须放在所有表建好之后（表还没建就建索引会报 no such table）
CREATE INDEX IF NOT EXISTS idx_fields_owner ON fields(user_id);
CREATE INDEX IF NOT EXISTS idx_tasks_owner ON farm_tasks(user_id);
CREATE INDEX IF NOT EXISTS idx_conversations_owner ON conversations(user_id);
CREATE INDEX IF NOT EXISTS idx_uploads_owner ON uploads(user_id);
