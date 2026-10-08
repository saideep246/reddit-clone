package com.redditclone.community.dto;

import java.time.Instant;
import java.util.UUID;

// The recipient's view of an invitation addressed to them (their own data: community name, who invited, what is offered).
public record MyModeratorInviteView(UUID id, String communityName, String inviterUsername, int permissions,
                                    Instant createdAt, Instant expiresAt) {
}
