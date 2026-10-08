-- Restricted communities: a person who may read and join but not post can ask a moderator for posting access. Deliberately its own
-- table, not community_join_requests: that one is membership for PRIVATE communities (one row per community and user, approval
-- creates a membership), while this is posting permission (history is kept, a request can be cancelled, approval creates an
-- approved-submitter row and no membership).
CREATE TABLE posting_approval_requests (
  id            UUID PRIMARY KEY,
  community_id  UUID NOT NULL REFERENCES communities(id),
  user_id       UUID NOT NULL REFERENCES users(id),
  status        TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','approved','denied','cancelled')),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  responded_at  TIMESTAMPTZ,
  responded_by  UUID REFERENCES users(id)
);

-- At most one pending request per person per community, enforced by the database so simultaneous requests cannot duplicate.
CREATE UNIQUE INDEX posting_approval_one_pending_idx ON posting_approval_requests (community_id, user_id) WHERE status = 'pending';
CREATE INDEX posting_approval_community_idx ON posting_approval_requests (community_id, status, created_at);
CREATE INDEX posting_approval_user_idx ON posting_approval_requests (user_id, community_id, created_at DESC);
