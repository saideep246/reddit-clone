package com.redditclone.community;

import com.redditclone.post.FeedCacheService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Feed-cache eviction after a community delete. The eviction runs AFTER COMMIT, so this class cannot use rolled-back test
// transactions: it commits real rows (removed in @AfterEach) and uses the real local Redis. Shared Spring context, as always.
@SpringBootTest
class CommunityFeedCacheEvictionTest {

    @Autowired private CommunityService service;
    @Autowired private FeedCacheService feedCache;
    @Autowired private StringRedisTemplate redis;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager em;
    @Autowired private PlatformTransactionManager txManager;

    private final List<UUID> communityIds = new ArrayList<>();
    private final List<UUID> userIds = new ArrayList<>();
    private final List<String> keys = new ArrayList<>();

    private CommunityDeleteFixtures fx() {
        return new CommunityDeleteFixtures(jdbc, service, em);
    }

    private UUID owner() {
        return user("fce_owner");
    }

    private UUID user(String prefix) {
        UUID id = fx().user(prefix);
        userIds.add(id);
        return id;
    }

    private Community community(UUID owner) {
        Community c = fx().community(owner);
        communityIds.add(c.getId());
        return c;
    }

    // Seeds the cached page in both layers (putHotPage writes Redis and the 5s in-memory cache).
    private void seed(String name) {
        keys.add("feed:hot:" + name);
        feedCache.putHotPage(name, "{\"page\":\"" + name + "\"}");
        assertTrue(feedCache.getHotPage(name).isPresent());
        assertTrue(redis.hasKey("feed:hot:" + name));
    }

    private boolean cachedAnywhere(String name) {
        return feedCache.getHotPage(name).isPresent() || Boolean.TRUE.equals(redis.hasKey("feed:hot:" + name));
    }

    @AfterEach
    void cleanUp() {
        for (String k : keys) {
            redis.delete(k);
        }
        for (UUID id : communityIds) {
            jdbc.update("DELETE FROM moderation_actions WHERE community_id = ?", id);
            jdbc.update("DELETE FROM memberships WHERE community_id = ?", id);
            jdbc.update("DELETE FROM community_moderators WHERE community_id = ?", id);
            jdbc.update("DELETE FROM communities WHERE id = ?", id);
        }
        for (UUID id : userIds) {
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
    }

    @Test
    void deletingACommunityEvictsItsFeedAndTheRAllFeedFromBothLayers() {
        UUID owner = owner();
        Community c = community(owner);
        seed(c.getName());
        seed("all");

        service.deleteCommunity(owner, c.getId(), c.getName());

        assertFalse(cachedAnywhere(c.getName()), "community feed:hot:<name> evicted");
        assertFalse(cachedAnywhere("all"), "feed:hot:all evicted");
    }

    @Test
    void theInMemoryLayerIsEvictedToo() {
        UUID owner = owner();
        Community c = community(owner);
        seed(c.getName());
        redis.delete("feed:hot:" + c.getName()); // Redis copy gone; only the 5s in-memory copy can still answer
        assertTrue(feedCache.getHotPage(c.getName()).isPresent(), "control: the in-memory layer still serves it");

        service.deleteCommunity(owner, c.getId(), c.getName());

        assertTrue(feedCache.getHotPage(c.getName()).isEmpty(), "in-memory copy evicted");
    }

    @Test
    void unrelatedCommunityCachesSurvive() {
        UUID owner = owner();
        Community target = community(owner);
        Community other = community(owner);
        seed(target.getName());
        seed(other.getName());

        service.deleteCommunity(owner, target.getId(), target.getName());

        assertFalse(cachedAnywhere(target.getName()));
        assertEquals("{\"page\":\"" + other.getName() + "\"}", feedCache.getHotPage(other.getName()).orElseThrow());
        assertTrue(redis.hasKey("feed:hot:" + other.getName()));
    }

    @Test
    void aFailedDeletionEvictsNothing() {
        UUID owner = owner();
        Community c = community(owner);
        seed(c.getName());
        seed("all");

        assertThrows(RuntimeException.class, () -> service.deleteCommunity(owner, c.getId(), "wrong-name"));
        assertThrows(RuntimeException.class, () -> service.deleteCommunity(user("fce_other"), c.getId(), c.getName()));

        assertTrue(feedCache.getHotPage(c.getName()).isPresent());
        assertTrue(redis.hasKey("feed:hot:" + c.getName()));
        assertTrue(feedCache.getHotPage("all").isPresent());
        assertTrue(redis.hasKey("feed:hot:all"));
    }

    @Test
    void aDeletionThatRollsBackAfterSucceedingEvictsNothing() {
        UUID owner = owner();
        Community c = community(owner);
        seed(c.getName());
        seed("all");

        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            service.deleteCommunity(owner, c.getId(), c.getName()); // succeeds inside the transaction and publishes the event...
            status.setRollbackOnly();                                // ...but the transaction never commits
        });

        assertFalse(fx().isDeleted(c.getId()), "rolled back");
        assertTrue(feedCache.getHotPage(c.getName()).isPresent());
        assertTrue(redis.hasKey("feed:hot:" + c.getName()));
        assertTrue(feedCache.getHotPage("all").isPresent());
    }
}
