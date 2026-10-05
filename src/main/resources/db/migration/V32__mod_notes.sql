-- Private, moderator-only notes about a user within one community (context for future decisions: "warned
-- twice for spam", "ban evasion suspected"). Never exposed to the subject or to non-moderators.
CREATE TABLE mod_notes (
  id            UUID PRIMARY KEY,
  community_id  UUID NOT NULL REFERENCES communities(id),
  user_id       UUID NOT NULL REFERENCES users(id),
  author_id     UUID NOT NULL REFERENCES users(id),
  note          TEXT NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX mod_notes_user_idx ON mod_notes (community_id, user_id, created_at DESC);
