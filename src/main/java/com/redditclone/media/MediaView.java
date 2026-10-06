package com.redditclone.media;

import java.math.BigDecimal;
import java.util.UUID;

// Built from app.media.public-base-url + the stored keys — never the raw r2Key. The original upload (up to 20 MB, may
// carry EXIF metadata) is only ever read internally by the processing workers; a client only sees the processed,
// intentionally public renditions: thumbnailUrl (256px, for feeds/cards) and displayUrl (1280px, for detail views).
// Both are null until processingStatus is 'ready'. `id` lets the website poll GET /api/media/{id} while it isn't.
public record MediaView(UUID id, String thumbnailUrl, String displayUrl, Integer width, Integer height,
                         BigDecimal durationSeconds, String processingStatus) {
}
