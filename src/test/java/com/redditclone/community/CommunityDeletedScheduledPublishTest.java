package com.redditclone.community;

import com.redditclone.post.ScheduledPostService;
import jakarta.persistence.EntityManager;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The scheduled-post worker commits in its own REQUIRES_NEW transactions, so it can only see rows that are really committed:
// this class cannot use rolled-back test transactions. It commits real rows and removes them in @AfterEach. Shared Spring context.
// Timeline under test: a post is scheduled, the community is deleted, THEN the scheduler runs.
//
// Ownership of the scheduler: the app's own ScheduledPostJob is guarded by ShedLock ("scheduledPostJob", rows in the shared
// `shedlock` table), and the local app container, which runs an image WITHOUT this code, polls the same database every 30s, as
// does this JVM's own copy of the job. The test therefore takes that ShedLock before it inserts anything and keeps it until
// cleanup, so no scheduler instance can start a run and claim the row; the test then invokes the real publishDue() itself
// (a direct call, which does not go through ShedLock). Nothing in production code or timing is changed.
@SpringBootTest
class CommunityDeletedScheduledPublishTest {

    private static final String PAYLOAD = "{\"kind\":\"text\",\"title\":\"scheduled\",\"body\":\"b\"}";

    @Autowired private CommunityService service;
    @Autowired private ScheduledPostService scheduled;
    @Autowired private LockProvider lockProvider;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager em;

    private final List<UUID> communityIds = new ArrayList<>();
    private final List<UUID> userIds = new ArrayList<>();
    private final List<UUID> scheduledIds = new ArrayList<>();

    private static final String JOB_LOCK = "scheduledPostJob"; // @SchedulerLock name on job.ScheduledPostJob
    private static final String OWNER = "CommunityDeletedScheduledPublishTest-" + UUID.randomUUID();
    private boolean ownsScheduler;

    // One atomic statement, in the same UTC-timestamp terms ShedLock's own JDBC provider uses (usingDbTime). The lock is taken only if
    // it is free or merely in its post-run "lockAtLeastFor" tail; a run that is actually IN FLIGHT holds it for lockAtMostFor
    // (2 minutes), which the condition refuses. In that case the test fails loudly rather than guess (runs last milliseconds, so
    // this is vanishingly rare). A run that began before we took the lock is therefore impossible, and one that begins after
    // is blocked until we release.
    private void ownScheduler() {
        int taken = jdbc.update("""
                INSERT INTO shedlock (name, lock_until, locked_at, locked_by)
                VALUES (?, timezone('utc', now()) + interval '10 minutes', timezone('utc', now()), ?)
                ON CONFLICT (name) DO UPDATE
                   SET lock_until = EXCLUDED.lock_until, locked_at = EXCLUDED.locked_at, locked_by = EXCLUDED.locked_by
                 WHERE shedlock.lock_until <= timezone('utc', now())
                    OR shedlock.lock_until - shedlock.locked_at < interval '1 minute'
                """, JOB_LOCK, OWNER);
        assertEquals(1, taken, "a scheduledPostJob run is in flight right now; the test cannot own the scheduler, rerun it");
        ownsScheduler = true;
        // Proof that the guard works: the very acquisition path the scheduler uses is now refused.
        assertTrue(lockProvider.lock(new LockConfiguration(Instant.now(), JOB_LOCK, Duration.ofMinutes(2), Duration.ZERO)).isEmpty(),
                "ShedLock must refuse any other scheduler while the test holds it");
    }

    private CommunityDeleteFixtures fx() {
        return new CommunityDeleteFixtures(jdbc, service, em);
    }

    private UUID scheduledRow(UUID author, UUID communityId, String publishAtSql) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO scheduled_posts (id, author_id, community_id, payload, publish_at) VALUES (?, ?, ?, ?::jsonb, " + publishAtSql + ")",
                id, author, communityId, PAYLOAD);
        scheduledIds.add(id);
        return id;
    }

    @AfterEach
    void cleanUp() {
        for (UUID id : scheduledIds) {
            jdbc.update("DELETE FROM scheduled_posts WHERE id = ?", id);
            redis.delete("idempotency:" + userIds.get(0) + ":scheduled-" + id);
        }
        for (UUID id : communityIds) {
            jdbc.update("DELETE FROM posts WHERE community_id = ?", id);
            jdbc.update("DELETE FROM moderation_actions WHERE community_id = ?", id);
            jdbc.update("DELETE FROM memberships WHERE community_id = ?", id);
            jdbc.update("DELETE FROM community_moderators WHERE community_id = ?", id);
            jdbc.update("DELETE FROM communities WHERE id = ?", id);
        }
        for (UUID id : userIds) {
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
        if (ownsScheduler) { // release last, only our own hold (never clobber a lock someone else now has)
            jdbc.update("UPDATE shedlock SET lock_until = timezone('utc', now()) WHERE name = ? AND locked_by = ?", JOB_LOCK, OWNER);
        }
    }

    @Test
    void aDuePostScheduledBeforeTheDeletionIsNotPublished() {
        ownScheduler(); // before any row exists
        UUID owner = fx().user("sp_owner");
        userIds.add(owner);
        Community c = fx().community(owner);
        communityIds.add(c.getId());
        UUID due = scheduledRow(owner, c.getId(), "now() - interval '1 minute'");   // 10:00 scheduled, already due
        UUID later = scheduledRow(owner, c.getId(), "now() + interval '1 day'");    // not due yet

        service.deleteCommunity(owner, c.getId(), c.getName());                      // 10:05 community deleted
        scheduled.publishDue();                                                      // 10:06 scheduler runs

        assertEquals("failed", jdbc.queryForObject("SELECT status FROM scheduled_posts WHERE id = ?", String.class, due));
        assertEquals("community was deleted", jdbc.queryForObject("SELECT error FROM scheduled_posts WHERE id = ?", String.class, due),
                "refused by the worker's own re-check, with a specific reason");
        assertNull(jdbc.queryForObject("SELECT post_id FROM scheduled_posts WHERE id = ?", UUID.class, due));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM posts WHERE community_id = ?", Integer.class, c.getId()), "no post created");
        assertFalse(Boolean.TRUE.equals(redis.hasKey("idempotency:" + owner + ":scheduled-" + due)), "no idempotency claim left behind");

        // history untouched: the not-yet-due row is neither published nor modified; the community row is the only thing that changed
        assertEquals("pending", jdbc.queryForObject("SELECT status FROM scheduled_posts WHERE id = ?", String.class, later));
        assertTrue(fx().isDeleted(c.getId()));
    }
}
