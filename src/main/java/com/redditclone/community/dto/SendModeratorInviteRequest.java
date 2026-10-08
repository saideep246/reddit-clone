package com.redditclone.community.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SendModeratorInviteRequest(
        @NotBlank String username,
        @NotNull Integer permissions
) {
}
