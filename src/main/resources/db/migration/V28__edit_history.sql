-- One row per edit, holding the values as they were BEFORE the edit (so the newest edit's row is the
-- previous revision, and the live row is always the current one). Rows are append-only.
CREATE TABLE post_edits (
  id          UUID PRIMARY KEY,
  post_id     UUID NOT NULL REFERENCES posts(id),
  editor_id   UUID NOT NULL REFERENCES users(id),
  old_title   TEXT,
  old_body    TEXT,
  old_url     TEXT,
  edited_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX post_edits_post_idx ON post_edits (post_id, edited_at DESC);

CREATE TABLE comment_edits (
  id          UUID PRIMARY KEY,
  comment_id  UUID NOT NULL REFERENCES comments(id),
  editor_id   UUID NOT NULL REFERENCES users(id),
  old_body    TEXT,
  edited_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX comment_edits_comment_idx ON comment_edits (comment_id, edited_at DESC);
