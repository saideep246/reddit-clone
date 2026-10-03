package com.redditclone.post.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// myOptionId is only filled by the dedicated GET /posts/{id}/poll endpoint (it is viewer-specific, so it can't
// ride along on the shared, cached feed payloads); null in feeds and for a viewer who hasn't voted.
public record PollView(List<PollOptionView> options, int totalVotes, Instant endsAt, boolean ended, UUID myOptionId) {

    public record PollOptionView(UUID id, String text, int votes) {
    }
}
