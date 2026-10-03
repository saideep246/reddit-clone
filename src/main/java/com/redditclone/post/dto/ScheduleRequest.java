package com.redditclone.post.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record ScheduleRequest(@NotNull @Valid CreatePostRequest post, @NotNull Instant publishAt) {
}
