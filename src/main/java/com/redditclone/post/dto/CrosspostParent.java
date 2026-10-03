package com.redditclone.post.dto;

import java.util.UUID;

// A trimmed view of the original post a crosspost points at. `available` is false when the original was
// removed/deleted or now lives in a private community — everything else is then null, so a crosspost never
// leaks content its viewer couldn't otherwise reach.
public record CrosspostParent(UUID id, boolean available, String title, String kind, String body, String url,
                               String authorUsername, String communityName) {

    public static CrosspostParent unavailable(UUID id) {
        return new CrosspostParent(id, false, null, null, null, null, null, null);
    }
}
