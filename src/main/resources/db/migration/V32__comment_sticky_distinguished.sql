-- sticky: a moderator-pinned top-level comment, shown first in its thread regardless of sort.
-- distinguished: 'moderator' marks a comment a moderator posted in an official capacity.
ALTER TABLE comments ADD COLUMN sticky BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE comments ADD COLUMN distinguished TEXT CHECK (distinguished IN ('moderator'));
CREATE INDEX comments_sticky_idx ON comments (post_id) WHERE sticky;
