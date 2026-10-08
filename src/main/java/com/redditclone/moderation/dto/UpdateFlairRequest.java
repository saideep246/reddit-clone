package com.redditclone.moderation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// Same text/colour rules as FlairRequest; there is no type here because a flair's type can never change.
public record UpdateFlairRequest(
        @NotBlank @Size(max = 64) String text,
        @NotBlank @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String color
) {
}
