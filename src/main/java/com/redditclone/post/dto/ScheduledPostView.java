package com.redditclone.post.dto;

import java.time.Instant;
import java.util.UUID;

public record ScheduledPostView(UUID id, String communityName, String title, String kind, Instant publishAt,
                                 String status, String error, UUID postId) {
}
