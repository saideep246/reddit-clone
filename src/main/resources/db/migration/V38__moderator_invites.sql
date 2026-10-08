-- Moderator invitations: a pending, persisted delegation that the invitee accepts or declines. The permission bitmask is
-- stored here and is the ONLY source for what acceptance grants. Expiry is evaluated lazily (expires_at vs now()), so no
-- sweeper job is needed; a stale 'pending' row is flipped to 'expired' the next time it is touched.
CREATE TABLE moderator_invites (
  id            UUID PRIMARY KEY,
  community_id  UUID NOT NULL REFERENCES communities(id),
  invitee_id    UUID NOT NULL REFERENCES users(id),
  inviter_id    UUID NOT NULL REFERENCES users(id),
  permissions   INTEGER NOT NULL,
  status        TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','accepted','declined','cancelled','expired')),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at    TIMESTAMPTZ NOT NULL,
  responded_at  TIMESTAMPTZ
);

-- At most one pending invitation per (community, invitee), enforced by the database so concurrent sends cannot duplicate.
CREATE UNIQUE INDEX moderator_invites_one_pending_idx ON moderator_invites (community_id, invitee_id) WHERE status = 'pending';
CREATE INDEX moderator_invites_invitee_idx ON moderator_invites (invitee_id, status);
CREATE INDEX moderator_invites_community_idx ON moderator_invites (community_id, status, created_at DESC);
