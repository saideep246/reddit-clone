package com.redditclone.post.dto;

import java.time.Instant;
import java.util.UUID;

// One prior revision: the values as they were before the edit made at editedAt.
public record PostEditView(UUID id, UUID editorId, String title, String body, String url, Instant editedAt) {
}
