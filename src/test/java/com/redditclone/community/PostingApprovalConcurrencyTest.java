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
import static org.junit.jupiter.api.Assertions.assertTrue;

// Real races against the local PostgreSQL, each call in its own committed transaction, so this class creates real rows and
// removes them afterwards (same approach as ModeratorInviteConcurrencyTest).
@SpringBootTest
class PostingApprovalConcurrencyTest {

    private static final int THREADS = 8;

    @Autowired private CommunityService service;
    @Autowired private PostingApprovalService approvals;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager em;

    private final List<UUID> communityIds = new ArrayList<>();
    private final List<UUID> userIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (UUID u : userIds) {
            jdbc.update("DELETE FROM outbox_events WHERE payload->>'userId' = ?", u.toString());
        }
        for (UUID id : communityIds) {
            jdbc.update("DELETE FROM moderation_actions WHERE community_id = ?", id);
            jdbc.update("DELETE FROM posting_approval_requests WHERE community_id = ?", id);
            jdbc.update("DELETE FROM community_approved_submitters WHERE community_id = ?", id);
            jdbc.update("DELETE FROM memberships WHERE community_id = ?", id);
            jdbc.update("DELETE FROM community_moderators WHERE community_id = ?", id);
            jdbc.update("DELETE FROM communities WHERE id = ?", id);
        }
        for (UUID u : userIds) {
            jdbc.update("DELETE FROM users WHERE id = ?", u);
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

    private UUID user(String prefix) {
        UUID id = new CommunityDeleteFixtures(jdbc, service, em).user(prefix);
        userIds.add(id);
        return id;
    }

    private Community restricted(UUID owner) {
        Community x = new CommunityDeleteFixtures(jdbc, service, em).community(owner);
        communityIds.add(x.getId());
        jdbc.update("UPDATE communities SET type = 'restricted' WHERE id = ?", x.getId());
        return x;
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private int logged(Community x, String action) {
        return count("SELECT count(*) FROM moderation_actions WHERE community_id = ? AND action = ?", x.getId(), action);
    }

    private int events(UUID recipient, String type) {
        return count("SELECT count(*) FROM outbox_events WHERE payload->>'userId' = ? AND payload->>'type' = ?", recipient.toString(), type);
    }

    private List<Callable<Object>> repeat(Callable<Object> task) {
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(task);
        }
        return tasks;
    }

    @Test
    void manySimultaneousRequestsFromOnePersonLeaveExactlyOnePending() throws Exception {
        UUID owner = user("pc_owner");
        UUID requester = user("pc_req");
        Community c = restricted(owner);

        List<Object> outcomes = race(repeat(() -> {
            approvals.request(requester, c.getId());
            return "ok";
        }));

        assertTrue(outcomes.stream().allMatch("ok"::equals), "a duplicate is a quiet no-op, never an error: " + outcomes);
        assertEquals(1, count("SELECT count(*) FROM posting_approval_requests WHERE community_id = ? AND user_id = ? AND status = 'pending'", c.getId(), requester));
        assertEquals(1, count("SELECT count(*) FROM posting_approval_requests WHERE community_id = ? AND user_id = ?", c.getId(), requester));
        assertEquals(1, logged(c, "posting_approval_requested"), "one log entry, not one per caller");
        assertEquals(1, events(owner, "posting_request"), "one notification, not one per caller");
    }

    @Test
    void manySimultaneousApprovalsByTwoModeratorsApproveExactlyOnce() throws Exception {
        UUID owner = user("pc2_owner");
        UUID second = user("pc2_mod");
        UUID requester = user("pc2_req");
        Community c = restricted(owner);
        service.addModerator(owner, c.getId(), second, CommunityModerator.PERM_MANAGE_ACCESS);
        approvals.request(requester, c.getId());

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            UUID actor = i % 2 == 0 ? owner : second;
            tasks.add(() -> {
                approvals.approve(actor, c.getId(), requester);
                return "ok";
            });
        }
        List<Object> outcomes = race(tasks);

        assertTrue(outcomes.stream().allMatch("ok"::equals), "later approvals are idempotent no-ops: " + outcomes);
        assertEquals(1, count("SELECT count(*) FROM community_approved_submitters WHERE community_id = ? AND user_id = ?", c.getId(), requester));
        assertEquals(1, count("SELECT count(*) FROM posting_approval_requests WHERE community_id = ? AND user_id = ? AND status = 'approved'", c.getId(), requester));
        assertEquals(1, logged(c, "posting_approval_approved"));
        assertEquals(1, events(requester, "posting_decision"));
    }

    @Test
    void approveAndDenyRacingLeaveOneConsistentOutcome() throws Exception {
        UUID owner = user("pc3_owner");
        UUID requester = user("pc3_req");
        Community c = restricted(owner);
        approvals.request(requester, c.getId());

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            boolean approve = i % 2 == 0;
            tasks.add(() -> {
                if (approve) {
                    approvals.approve(owner, c.getId(), requester);
                } else {
                    approvals.deny(owner, c.getId(), requester);
                }
                return approve ? "approve" : "deny";
            });
        }
        List<Object> outcomes = race(tasks);

        int approvedRows = count("SELECT count(*) FROM posting_approval_requests WHERE community_id = ? AND user_id = ? AND status = 'approved'", c.getId(), requester);
        int deniedRows = count("SELECT count(*) FROM posting_approval_requests WHERE community_id = ? AND user_id = ? AND status = 'denied'", c.getId(), requester);
        int submitters = count("SELECT count(*) FROM community_approved_submitters WHERE community_id = ? AND user_id = ?", c.getId(), requester);
        assertEquals(1, approvedRows + deniedRows, "exactly one decision won: " + outcomes);
        assertEquals(approvedRows, submitters, "approved if and only if the person became an approved submitter");
        assertEquals(1, logged(c, "posting_approval_approved") + logged(c, "posting_approval_denied"), "one decision, one log entry");
        assertEquals(1, events(requester, "posting_decision"), "one decision, one notification");
        long clean = outcomes.stream().filter(o -> o instanceof String).count();
        long conflicts = outcomes.stream().filter(o -> o instanceof com.redditclone.common.exception.ConflictException).count();
        assertEquals(THREADS, clean + conflicts, "every loser got a clean 409 or an idempotent no-op, never a 500: " + outcomes);
    }
}
