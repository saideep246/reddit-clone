package com.redditclone.post.dto;

import jakarta.validation.constraints.Size;

// PATCH semantics: null = leave unchanged, but a present field must not be blank, and at least one field
// must be present. title/url are only editable inside a short grace window (see PostService.edit).
public record EditPostRequest(@Size(max = 40000) String body, @Size(max = 300) String title, @Size(max = 2000) String url) {
}
