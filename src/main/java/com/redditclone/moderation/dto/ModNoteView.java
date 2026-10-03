package com.redditclone.moderation.dto;

import java.time.Instant;
import java.util.UUID;

public record ModNoteView(UUID id, UUID userId, UUID authorId, String authorUsername, String note, Instant createdAt) {
}
