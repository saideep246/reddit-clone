package com.redditclone.block;

import java.time.Instant;
import java.util.UUID;

public record BlockedUser(UUID id, String username, Instant blockedAt) {
}
