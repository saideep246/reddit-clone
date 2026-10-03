package com.redditclone.comment.dto;

import java.time.Instant;
import java.util.UUID;

// A comment hit in search results: enough context (post title, community, author) to render a result row
// that links to the thread without a second round trip.
public record CommentSearchResult(UUID id, UUID postId, String postTitle, String communityName,
                                   String authorUsername, String body, int score, Instant createdAt) {
}
