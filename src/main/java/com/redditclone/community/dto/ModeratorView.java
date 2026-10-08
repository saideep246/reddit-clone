package com.redditclone.community.dto;

import java.time.Instant;
import java.util.UUID;

// One row of a community's moderator list. `owner` is the community's creator (never editable or removable);
// `permissions` is the CommunityModerator bitmask, sent as-is so the client can show and edit individual bits.
public record ModeratorView(UUID userId, String username, int permissions, boolean owner, Instant addedAt) {
}
