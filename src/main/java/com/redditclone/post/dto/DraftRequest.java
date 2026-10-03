package com.redditclone.post.dto;

import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

// payload is whatever the client's submit form holds; the server only requires a JSON object of bounded size.
public record DraftRequest(String communityName, @NotNull JsonNode payload) {
}
