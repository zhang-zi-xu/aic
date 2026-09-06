-- 农心 Agent 数据库结构（SQLite）
CREATE TABLE IF NOT EXISTS fields (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  crop TEXT NOT NULL,
  variety TEXT,
  sow_date TEXT NOT NULL,
  area_mu REAL,
  notes TEXT,
  created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);

CREATE TABLE IF NOT EXISTS field_records (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  field_id TEXT NOT NULL,
  record_date TEXT NOT NULL,
  note TEXT NOT NULL,
  FOREIGN KEY (field_id) REFERENCES fields(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_records_field ON field_records(field_id);

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
  done INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL,
  source_message_id TEXT,
  FOREIGN KEY (field_id) REFERENCES fields(id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_tasks_date ON farm_tasks(task_date);

CREATE TABLE IF NOT EXISTS conversations (
  id TEXT PRIMARY KEY,
  title TEXT NOT NULL,
  field_id TEXT,
  messages_json TEXT NOT NULL,
  created_at TEXT NOT NULL,
  FOREIGN KEY (field_id) REFERENCES fields(id) ON DELETE SET NULL
);
