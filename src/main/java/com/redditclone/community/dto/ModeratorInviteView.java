package com.redditclone.community.dto;

import java.time.Instant;
import java.util.UUID;

// Moderators-tab row: a pending invitation as the community's mods see it.
public record ModeratorInviteView(UUID id, UUID inviteeId, String inviteeUsername, String inviterUsername,
                                  int permissions, String status, Instant createdAt, Instant expiresAt) {
}
