-- Community soft delete, step 1 (schema only): who deleted a community and when. A community is deleted exactly when
-- deleted_at IS NOT NULL; there is deliberately no separate status column that could disagree with it. Nothing reads or
-- writes these columns yet.
--
-- Rows are never physically removed: fifteen tables reference communities(id) with NO ACTION foreign keys (posts,
-- memberships, moderators, reports and moderation_actions with all their monthly partitions, ...), so a hard delete
-- would be blocked or would have to cascade through audit data. The partitioned tables are not touched here.
--
-- Both columns are nullable with no default, so on PostgreSQL this is a metadata-only change that does not rewrite the
-- table. The CHECK keeps the pair consistent: either both are set (a deleted community and who deleted it) or neither.
ALTER TABLE communities
  ADD COLUMN deleted_at TIMESTAMPTZ,
  ADD COLUMN deleted_by UUID REFERENCES users(id),
  ADD CONSTRAINT communities_deleted_consistency CHECK ((deleted_at IS NULL) = (deleted_by IS NULL));
