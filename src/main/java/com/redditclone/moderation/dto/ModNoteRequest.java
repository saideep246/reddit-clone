package com.redditclone.moderation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record ModNoteRequest(@NotNull UUID userId, @NotBlank @Size(max = 1000) String note) {
}
