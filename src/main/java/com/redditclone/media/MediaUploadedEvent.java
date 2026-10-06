package com.redditclone.media;

// Published (inside the completeUpload transaction) when an upload is marked 'uploaded'. The processing workers
// listen for it AFTER commit so they start immediately instead of waiting for their next scheduled poll — cutting
// up to a poll interval (2s images, 10s videos) off the time before a file is ready. The scheduled poll stays as the
// safety net (a missed event, a restart).
public record MediaUploadedEvent(String mediaType) {
}
