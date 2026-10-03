-- Full-text search over comment bodies, same english tsvector approach as posts.search_vector. A generated
-- column keeps it in sync with edits and with the body wipe on delete with no trigger.
ALTER TABLE comments ADD COLUMN search_vector tsvector
  GENERATED ALWAYS AS (to_tsvector('english', coalesce(body, ''))) STORED;
CREATE INDEX comments_search_idx ON comments USING gin (search_vector);
