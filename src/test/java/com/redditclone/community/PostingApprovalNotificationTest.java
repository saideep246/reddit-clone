package com.redditclone.community;

import com.redditclone.notify.Notification;
import com.redditclone.notify.NotificationOutboxWorker;
import com.redditclone.notify.NotificationService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Posting-approval notifications through the real outbox and NotificationOutboxWorker. Committed rows (the worker inserts in a
// separate transaction), cleaned up afterwards; the app's own scheduled worker is running too, so assertions wait for outcomes.
@SpringBootTest
class PostingApprovalNotificationTest {

    @Autowired private CommunityService service;
    @Autowired private PostingApprovalService approvals;
    @Autowired private NotificationOutboxWorker worker;
    @Autowired private NotificationService notifications;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager em;

    private final List<UUID> communityIds = new ArrayList<>();
    private final List<UUID> userIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (UUID u : userIds) {
            jdbc.update("DELETE FROM notifications WHERE user_id = ?", u);
            jdbc.update("DELETE FROM outbox_events WHERE payload->>'userId' = ?", u.toString());
            jdbc.update("DELETE FROM user_settings WHERE user_id = ?", u);
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

    private int rows(UUID user, String type) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND type = ?", Integer.class, user, type);
    }

    private int pending(UUID user) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'notification' AND processed_at IS NULL AND payload->>'userId' = ?", Integer.class, user.toString());
    }

    private void await(BooleanSupplier ok, String what) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (ok.getAsBoolean()) {
                return;
            }
            worker.processBatch();
            Thread.sleep(100);
        }
        throw new AssertionError("timed out waiting for: " + what);
    }

    @Test
    void moderatorsGetTheRequestAndTheRequesterGetsEachDecisionOnceEvenIfTheWorkerRunsAgain() throws Exception {
        UUID owner = user("pn_owner");
        UUID requester = user("pn_req");
        UUID again = user("pn_again");
        Community c = restricted(owner);

        approvals.request(requester, c.getId());
        await(() -> rows(owner, "posting_request") == 1, "the moderator's inbox row");
        for (int i = 0; i < 3; i++) {
            worker.processBatch();
        }
        assertEquals(1, rows(owner, "posting_request"), "retries add nothing");
        List<Notification> inbox = notifications.listForUser(owner);
        assertEquals(1, inbox.size());
        assertEquals(c.getName(), inbox.get(0).getCommunityName());
        assertEquals(jdbc.queryForObject("SELECT username FROM users WHERE id = ?", String.class, requester), inbox.get(0).getActorUsername());
        assertEquals(0, notifications.listForUser(requester).size(), "the requester is not told about their own request");

        approvals.approve(owner, c.getId(), requester);
        await(() -> rows(requester, "posting_decision") == 1, "the approval notice");
        Notification decision = notifications.listForUser(requester).get(0);
        assertEquals(c.getName(), decision.getCommunityName());
        assertTrue(decision.getSource().contains("approved"), decision.getSource());
        assertTrue(decision.getSource().contains("communityId"), decision.getSource());
        assertTrue(!decision.getSource().contains("actorId"), "no moderator identity in the notice");

        approvals.request(again, c.getId());
        approvals.deny(owner, c.getId(), again);
        await(() -> rows(again, "posting_decision") == 1, "the denial notice");
        assertTrue(notifications.listForUser(again).get(0).getSource().contains("denied"));
        for (int i = 0; i < 3; i++) {
            worker.processBatch();
        }
        assertEquals(1, rows(again, "posting_decision"));
        assertEquals(1, rows(requester, "posting_decision"));
    }

    @Test
    void aMutedModeratorGetsNoInboxRowButStillSeesTheRequestInTheList() throws Exception {
        UUID owner = user("pn2_owner");
        UUID requester = user("pn2_req");
        Community c = restricted(owner);
        jdbc.update("INSERT INTO user_settings (user_id, notification_prefs, privacy_prefs) VALUES (?, '{\"posting_request\": false}'::jsonb, '{}'::jsonb)", owner);

        approvals.request(requester, c.getId());
        await(() -> pending(owner) == 0, "the muted event to be consumed");
        assertEquals(0, rows(owner, "posting_request"));
        assertEquals(1, approvals.list(owner, c.getId()).size(), "muting hides the notice, never the request");
    }

    @Test
    void aMutedRequesterGetsNoDecisionRowButTheDecisionStands() throws Exception {
        UUID owner = user("pn3_owner");
        UUID requester = user("pn3_req");
        Community c = restricted(owner);
        jdbc.update("INSERT INTO user_settings (user_id, notification_prefs, privacy_prefs) VALUES (?, '{\"posting_decision\": false}'::jsonb, '{}'::jsonb)", requester);

        approvals.request(requester, c.getId());
        approvals.approve(owner, c.getId(), requester);
        await(() -> pending(requester) == 0, "the muted event to be consumed");
        assertEquals(0, rows(requester, "posting_decision"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM community_approved_submitters WHERE community_id = ? AND user_id = ?", Integer.class, c.getId(), requester));
    }
}
