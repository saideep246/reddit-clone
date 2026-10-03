package com.redditclone.comment.dto;

import java.time.Instant;
import java.util.UUID;

// One prior revision: the body as it was before the edit made at editedAt.
public record CommentEditView(UUID id, UUID editorId, String body, Instant editedAt) {
}
