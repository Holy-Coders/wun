CREATE TABLE wun_conn_state (
  user_id BIGINT PRIMARY KEY,
  state_edn JSONB NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
)
