package com.redditclone.post.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record PollVoteRequest(@NotNull UUID optionId) {
}
