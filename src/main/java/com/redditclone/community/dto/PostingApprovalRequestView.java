package com.redditclone.community.dto;

import java.time.Instant;
import java.util.UUID;

// A pending request as the community's moderators see it: who asked, and when.
public record PostingApprovalRequestView(UUID userId, String username, String status, Instant requestedAt) {
}
