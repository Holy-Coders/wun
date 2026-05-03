CREATE TABLE wun_conn_state (
  user_id INTEGER PRIMARY KEY,
  state_edn TEXT NOT NULL,
  updated_at TEXT NOT NULL DEFAULT (datetime('now'))
)
