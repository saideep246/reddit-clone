package com.redditclone.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// Shared by both /verify-email/resend and /password-reset/request — each just takes one email address.
public record EmailRequest(
        @NotBlank @Email @Size(max = 254) String email
) {
}
