package com.redditclone.auth.dto;

import java.util.UUID;

// The whole public surface of a picker search result: who to show, and which account it is. Nothing else about a user
// leaves the server through search.
public record UserSearchHit(UUID id, String username) {
}
