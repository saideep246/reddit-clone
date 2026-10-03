package com.redditclone.post.dto;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record DraftView(UUID id, String communityName, JsonNode payload, Instant updatedAt) {
}
