ALTER TABLE posts DROP CONSTRAINT posts_kind_check;
ALTER TABLE posts ADD CONSTRAINT posts_kind_check CHECK (kind IN ('text','link','image','video','gallery','poll','crosspost'));

-- A crosspost points at the ORIGINAL post (never at another crosspost: PostService.create resolves the root).
ALTER TABLE posts ADD COLUMN crosspost_of UUID REFERENCES posts(id);
CREATE INDEX posts_crosspost_of_idx ON posts (crosspost_of) WHERE crosspost_of IS NOT NULL;

CREATE TABLE polls (
  post_id  UUID PRIMARY KEY REFERENCES posts(id),
  ends_at  TIMESTAMPTZ NOT NULL
);

-- votes is a denormalized counter bumped in the same transaction that inserts the poll_votes row.
CREATE TABLE poll_options (
  id        UUID PRIMARY KEY,
  post_id   UUID NOT NULL REFERENCES posts(id),
  text      VARCHAR(100) NOT NULL,
  position  SMALLINT NOT NULL,
  votes     INTEGER NOT NULL DEFAULT 0,
  UNIQUE (post_id, position)
);

-- One vote per user per poll (the primary key), no changing it afterwards — same as Reddit.
CREATE TABLE poll_votes (
  post_id    UUID NOT NULL REFERENCES posts(id),
  user_id    UUID NOT NULL REFERENCES users(id),
  option_id  UUID NOT NULL REFERENCES poll_options(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (post_id, user_id)
);
