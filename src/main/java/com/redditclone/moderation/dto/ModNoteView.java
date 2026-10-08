package com.redditclone.moderation.dto;

import java.time.Instant;
import java.util.UUID;

// userId/subjectUsername identify the user the note is ABOUT; authorId/authorUsername the moderator who wrote it.
public record ModNoteView(UUID id, UUID userId, String subjectUsername, UUID authorId, String authorUsername, String note, Instant createdAt) {
}
