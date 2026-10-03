CREATE TABLE user_blocks (
  blocker_id  UUID NOT NULL REFERENCES users(id),
  blocked_id  UUID NOT NULL REFERENCES users(id),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (blocker_id, blocked_id)
);
CREATE INDEX user_blocks_blocked_idx ON user_blocks (blocked_id, blocker_id);
