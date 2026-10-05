-- Community icon/banner are stored as media ids (resolved to public URLs on GET /r/{name}/about) rather than
-- raw URLs, so they go through the same ownership/readiness validation as post media.
ALTER TABLE communities ADD COLUMN icon_media_id UUID REFERENCES media(id);
ALTER TABLE communities ADD COLUMN banner_media_id UUID REFERENCES media(id);
