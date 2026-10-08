package com.redditclone.community;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Real races against the local PostgreSQL, each call in its own committed transaction (no rolled-back test transaction),
// so this class creates real rows and removes them in @AfterEach. Same approach as CommunityDeleteConcurrencyTest.
@SpringBootTest
class ModeratorInviteConcurrencyTest {

    private static final int THREADS = 8;

    @Autowired private CommunityService service;
    @Autowired private ModeratorInviteService invites;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager em;

    private final List<UUID> communityIds = new ArrayList<>();
    private final List<UUID> userIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (UUID id : communityIds) {
            jdbc.update("DELETE FROM moderation_actions WHERE community_id = ?", id);
            jdbc.update("DELETE FROM moderator_invites WHERE community_id = ?", id);
            jdbc.update("DELETE FROM memberships WHERE community_id = ?", id);
            jdbc.update("DELETE FROM community_moderators WHERE community_id = ?", id);
            jdbc.update("DELETE FROM communities WHERE id = ?", id);
        }
        for (UUID id : userIds) {
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
    }

    private List<Object> race(List<Callable<Object>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CyclicBarrier go = new CyclicBarrier(tasks.size());
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> t : tasks) {
                futures.add(pool.submit(() -> {
                    go.await(10, TimeUnit.SECONDS);
                    try {
                        return t.call();
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            List<Object> out = new ArrayList<>();
            for (Future<Object> f : futures) {
                out.add(f.get(30, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    private UUID newUser(String prefix) {
        UUID id = new CommunityDeleteFixtures(jdbc, service, em).user(prefix);
        userIds.add(id);
        return id;
    }

    @Test
    void manySimultaneousAcceptsGrantExactlyOnce() throws Exception {
        UUID owner = newUser("mic_owner");
        UUID invitee = newUser("mic_invitee");
        Community c = new CommunityDeleteFixtures(jdbc, service, em).community(owner);
        communityIds.add(c.getId());
        ModeratorInvite invite = invites.send(owner, c.getId(), jdbc.queryForObject("SELECT username FROM users WHERE id = ?", String.class, invitee), CommunityModerator.PERM_BAN_USERS);

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(() -> {
                invites.accept(invitee, invite.getId());
                return "ok";
            });
        }
        List<Object> outcomes = race(tasks);

        assertEquals(1, outcomes.stream().filter("ok"::equals).count(), "exactly one accept wins: " + outcomes);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM community_moderators WHERE community_id = ? AND user_id = ?", Integer.class, c.getId(), invitee));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM memberships WHERE community_id = ? AND user_id = ?", Integer.class, c.getId(), invitee));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM moderation_actions WHERE community_id = ? AND action = 'accept_moderator_invite'", Integer.class, c.getId()));
        assertEquals(2, jdbc.queryForObject("SELECT subscriber_count FROM communities WHERE id = ?", Integer.class, c.getId()), "owner + invitee");
    }

    @Test
    void manySimultaneousSendsCreateExactlyOnePendingInvitation() throws Exception {
        UUID owner = newUser("mics_owner");
        UUID invitee = newUser("mics_invitee");
        Community c = new CommunityDeleteFixtures(jdbc, service, em).community(owner);
        communityIds.add(c.getId());
        String name = jdbc.queryForObject("SELECT username FROM users WHERE id = ?", String.class, invitee);

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(() -> {
                invites.send(owner, c.getId(), name, CommunityModerator.PERM_BAN_USERS);
                return "ok";
            });
        }
        List<Object> outcomes = race(tasks);

        assertEquals(1, outcomes.stream().filter("ok"::equals).count(), "exactly one send wins: " + outcomes);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM moderator_invites WHERE community_id = ? AND status = 'pending'", Integer.class, c.getId()));
        outcomes.stream().filter(o -> !"ok".equals(o)).forEach(o ->
                assertEquals(com.redditclone.common.exception.ConflictException.class, o.getClass(), "losers get a clean 409, not a 500: " + o));
    }
}
