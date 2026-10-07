package com.redditclone.media;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Runs the real claim SQL against the local PostgreSQL, like RedditCloneApplicationTests (start the docker compose stack
// first). Every test runs in a transaction that is rolled back, so nothing it inserts is ever visible to, or left behind
// for, the running application: its own workers cannot see (or steal) these rows.
//
// Other 'uploaded' rows may already exist in the development database, so each test claims repeatedly until nothing is
// left and looks only at the order of ITS OWN rows.
@SpringBootTest
@Transactional
class MediaClaimQueryTest {

    @Autowired
    private MediaService mediaService;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID owner;

    @BeforeEach
    void createOwner() {
        owner = UUID.randomUUID();
        String name = "claimtest_" + owner.toString().substring(0, 8);
        jdbc.update("INSERT INTO users (id, username, email, password_hash) VALUES (?, ?, ?, 'x')",
                owner, name, name + "@example.com");
    }

    // ageSeconds: how long ago the row was created. startedAgoSeconds: when the previous attempt began (null = never).
    private UUID insert(String type, String status, int attempts, long ageSeconds, Long startedAgoSeconds) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO media (id, owner_id, r2_key, content_type, byte_size, processing_status, media_type,
                                   attempt_count, created_at, processing_started_at)
                VALUES (?, ?, ?, 'video/mp4', 1000, ?, ?, ?, now() - make_interval(secs => ?),
                        CASE WHEN ?::bigint IS NULL THEN NULL ELSE now() - make_interval(secs => ?::bigint) END)
                """, id, owner, "claim-test/" + id, status, type, attempts, ageSeconds, startedAgoSeconds, startedAgoSeconds);
        return id;
    }

    private UUID uploadedVideo(int attempts, long ageSeconds, Long startedAgoSeconds) {
        return insert("video", "uploaded", attempts, ageSeconds, startedAgoSeconds);
    }

    // Claims video rows one at a time until none is left; returns the ids in claim order.
    private List<UUID> claimAllVideos(int minAgeSeconds) {
        List<UUID> claimed = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            List<ClaimedMedia> next = mediaService.claimNextUploaded("video", minAgeSeconds);
            if (next.isEmpty()) {
                return claimed;
            }
            assertEquals(1, next.size(), "one row per claim");
            claimed.add(next.getFirst().id());
        }
        throw new AssertionError("claim loop did not terminate");
    }

    private static List<UUID> only(List<UUID> claimed, UUID... mine) {
        Set<UUID> set = Set.of(mine);
        return claimed.stream().filter(set::contains).toList();
    }

    private static final long DAY = 86_400;

    @Test
    void freshUploadsComeBeforeRetriesAndAreOldestFirstWithinTheSameAttemptCount() {
        UUID retry2 = uploadedVideo(2, 400 * DAY, 3600L);
        UUID retry1 = uploadedVideo(1, 400 * DAY, 3600L);
        UUID freshNewer = uploadedVideo(0, 100 * DAY, null);
        UUID freshOlder = uploadedVideo(0, 200 * DAY, null);

        List<UUID> order = only(claimAllVideos(0), retry2, retry1, freshNewer, freshOlder);

        // the two retries are OLDER than both fresh uploads, yet go after them (this is the head-of-line fix)
        assertEquals(List.of(freshOlder, freshNewer, retry1, retry2), order);
    }

    @Test
    void aRetryWaitsThirtySecondsPerAttemptAfterItsPreviousAttemptBegan() {
        UUID tooSoon1 = uploadedVideo(1, DAY, 10L);        // needs 30s, only 10s passed
        UUID ready1 = uploadedVideo(1, DAY, 40L);          // 40s >= 30s
        UUID tooSoon3 = uploadedVideo(3, DAY, 60L);        // needs 90s, only 60s passed
        UUID ready3 = uploadedVideo(3, DAY, 120L);         // 120s >= 90s
        UUID neverStarted = uploadedVideo(2, DAY, null);   // e.g. put back by the reaper, which clears the start time

        List<UUID> claimed = only(claimAllVideos(0), tooSoon1, ready1, tooSoon3, ready3, neverStarted);

        assertEquals(Set.of(ready1, ready3, neverStarted), Set.copyOf(claimed));
        assertEquals(3, claimed.size());
    }

    @Test
    void aRetryIsStillServedWhenNothingFreshIsWaiting() {
        UUID retry = uploadedVideo(2, DAY, 3600L);
        assertTrue(only(claimAllVideos(0), retry).contains(retry));
    }

    @Test
    void aClaimTakesOneRowFlipsItToProcessingAndNeverChangesTheAttemptCount() {
        UUID a = uploadedVideo(0, 300 * DAY, null);
        UUID b = uploadedVideo(0, 299 * DAY, null);

        List<ClaimedMedia> first = mediaService.claimNextUploaded("video", 0);

        assertEquals(1, first.size());
        // other 'uploaded' rows may sort before ours in a shared development database; check whichever row was taken
        UUID taken = first.getFirst().id();
        assertEquals("processing", jdbc.queryForObject("SELECT processing_status FROM media WHERE id = ?", String.class, taken));
        assertNotNull(jdbc.queryForObject("SELECT processing_started_at FROM media WHERE id = ?", Object.class, taken));
        assertEquals(first.getFirst().attemptCount(),
                jdbc.queryForObject("SELECT attempt_count FROM media WHERE id = ?", Integer.class, taken),
                "claiming must not touch attempt_count");
        List<UUID> rest = claimAllVideos(0);
        assertTrue(only(rest, a, b).size() + (taken.equals(a) || taken.equals(b) ? 1 : 0) == 2, "both of our rows are eventually claimed, once each");
    }

    @Test
    void theMinimumAgeIsStillHonoured() {
        UUID tooNew = uploadedVideo(0, 5L, null);
        UUID oldEnough = uploadedVideo(0, 60L, null);

        List<UUID> claimed = only(claimAllVideos(30), tooNew, oldEnough);

        assertEquals(List.of(oldEnough), claimed);
    }

    @Test
    void onlyUploadedVideosAreClaimed() {
        UUID image = insert("image", "uploaded", 0, DAY, null);
        UUID processing = insert("video", "processing", 0, DAY, 5L);
        UUID pending = insert("video", "pending", 0, DAY, null);
        UUID ready = insert("video", "ready", 0, DAY, null);
        UUID failed = insert("video", "failed", 5, DAY, 3600L);

        assertTrue(only(claimAllVideos(0), image, processing, pending, ready, failed).isEmpty());
    }

    @Test
    void imageClaimingKeepsItsOriginalOrderingAndHasNoBackoff() {
        // the image worker still uses claimUploadedBatch: oldest first, whatever the attempt count, no retry delay
        UUID retryOlder = insert("image", "uploaded", 1, 200 * DAY, 1L);   // 1s ago: a video retry would still be waiting
        UUID freshNewer = insert("image", "uploaded", 0, 100 * DAY, null);

        List<UUID> claimed = mediaService.claimUploadedBatch("image", 10_000, 0).stream().map(ClaimedMedia::id).toList();

        assertEquals(List.of(retryOlder, freshNewer), only(claimed, retryOlder, freshNewer));
    }
}
