-- Server-side drafts: a free-form JSON payload (whatever the submit form held — kind, title, body, url, poll
-- options...) plus an optional target community name. Media is not carried in drafts (uploads expire).
CREATE TABLE post_drafts (
  id              UUID PRIMARY KEY,
  user_id         UUID NOT NULL REFERENCES users(id),
  community_name  TEXT,
  payload         JSONB NOT NULL,
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX post_drafts_user_idx ON post_drafts (user_id, updated_at DESC);

-- A post queued for later: `payload` is the exact CreatePostRequest the author would have submitted, replayed
-- by job.ScheduledPostJob at publish_at (idempotency key "scheduled-<id>" makes a retried publish harmless).
CREATE TABLE scheduled_posts (
  id            UUID PRIMARY KEY,
  author_id     UUID NOT NULL REFERENCES users(id),
  community_id  UUID NOT NULL REFERENCES communities(id),
  payload       JSONB NOT NULL,
  publish_at    TIMESTAMPTZ NOT NULL,
  status        TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','published','failed','cancelled')),
  error         TEXT,
  post_id       UUID,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX scheduled_posts_due_idx ON scheduled_posts (publish_at) WHERE status = 'pending';
CREATE INDEX scheduled_posts_author_idx ON scheduled_posts (author_id, created_at DESC);
