package com.redditclone.community.dto;

import java.time.Instant;
import java.util.UUID;

// One row of a restricted community's approved-posters list: who, who approved them, and when.
public record ApprovedSubmitterView(UUID userId, String username, String approvedByUsername, Instant approvedAt) {
}
