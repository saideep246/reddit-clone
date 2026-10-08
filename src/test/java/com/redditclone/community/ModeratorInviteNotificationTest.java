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
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The invitation -> notification/email path through the real outbox and the real NotificationOutboxWorker. Committed
// transactions, not rolled-back ones: the worker inserts notification rows in a separate (REQUIRES_NEW) transaction, which
// cannot see uncommitted users. So this class creates real rows and removes them in @AfterEach. The application's own
// scheduled workers are running too, so assertions wait for the outcome instead of assuming who processed an event first.
@SpringBootTest
class ModeratorInviteNotificationTest {

    private static final int P_BAN = CommunityModerator.PERM_BAN_USERS;

    @Autowired private CommunityService service;
    @Autowired private ModeratorInviteService invites;
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
            jdbc.update("DELETE FROM moderator_invites WHERE community_id = ?", id);
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

    private Community community(UUID owner) {
        Community c = new CommunityDeleteFixtures(jdbc, service, em).community(owner);
        communityIds.add(c.getId());
        return c;
    }

    private String name(UUID id) {
        return jdbc.queryForObject("SELECT username FROM users WHERE id = ?", String.class, id);
    }

    private int events(UUID invitee, String type) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'notification' AND payload->>'userId' = ? AND payload->>'type' = ?",
                Integer.class, invitee.toString(), type);
    }

    private int pendingEvents(UUID invitee) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'notification' AND processed_at IS NULL AND payload->>'userId' = ?",
                Integer.class, invitee.toString());
    }

    private int rows(UUID invitee) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND type = 'mod_invite'", Integer.class, invitee);
    }

    private int emails(UUID invitee) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'email' AND payload->>'type' = 'mod_invite' AND payload->>'to' = (SELECT email FROM users WHERE id = ?)",
                Integer.class, invitee);
    }

    private void setPrefs(UUID user, String notificationPrefsJson, String privacyPrefsJson) {
        jdbc.update("INSERT INTO user_settings (user_id, notification_prefs, privacy_prefs) VALUES (?, ?::jsonb, ?::jsonb)", user, notificationPrefsJson, privacyPrefsJson);
    }

    private void awaitTrue(BooleanSupplier condition, String what) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (condition.getAsBoolean()) {
                return;
            }
            worker.processBatch(); // also drain it ourselves so the test does not depend on the scheduler's timing
            Thread.sleep(100);
        }
        throw new AssertionError("timed out waiting for: " + what);
    }

    @Test
    void sendingOneInvitationWritesExactlyOneEventForTheInviteeWithTheRightSource() {
        UUID owner = user("mn_owner");
        UUID invitee = user("mn_invitee");
        Community c = community(owner);

        ModeratorInvite invite = invites.send(owner, c.getId(), name(invitee), P_BAN);

        assertEquals(1, events(invitee, "mod_invite"));
        Map<String, Object> payload = jdbc.queryForMap("SELECT payload FROM outbox_events WHERE payload->>'userId' = ?", invitee.toString());
        String json = payload.get("payload").toString();
        assertTrue(json.contains(invite.getId().toString()), json);
        assertTrue(json.contains(c.getId().toString()), json);
        assertTrue(json.contains(owner.toString()), json);
        assertFalse(json.contains("permissions"), "the event carries no permission data; the server-held invitation is the only source");
    }

    @Test
    void aRefusedOrDuplicateSendWritesNoExtraEvent() {
        UUID owner = user("mn2_owner");
        UUID invitee = user("mn2_invitee");
        UUID plain = user("mn2_plain");
        Community c = community(owner);

        assertThrows(RuntimeException.class, () -> invites.send(plain, c.getId(), name(invitee), P_BAN)); // not a moderator
        assertEquals(0, events(invitee, "mod_invite"));

        invites.send(owner, c.getId(), name(invitee), P_BAN);
        assertThrows(RuntimeException.class, () -> invites.send(owner, c.getId(), name(invitee), P_BAN)); // duplicate pending
        assertEquals(1, events(invitee, "mod_invite"));
    }

    @Test
    void theWorkerTurnsTheEventIntoOneInboxRowWithTheInviterAndCommunityAndRetriesDoNotDuplicateIt() throws Exception {
        UUID owner = user("mn3_owner");
        UUID invitee = user("mn3_invitee");
        Community c = community(owner);
        ModeratorInvite invite = invites.send(owner, c.getId(), name(invitee), P_BAN);

        awaitTrue(() -> rows(invitee) == 1, "the inbox row");
        assertEquals(0, pendingEvents(invitee));
        for (int i = 0; i < 3; i++) {
            worker.processBatch(); // a retry tick finds nothing left to claim
        }
        assertEquals(1, rows(invitee));

        List<Notification> inbox = notifications.listForUser(invitee);
        assertEquals(1, inbox.size());
        Notification n = inbox.get(0);
        assertEquals("mod_invite", n.getType());
        assertEquals(c.getName(), n.getCommunityName());
        assertEquals(name(owner), n.getActorUsername());
        assertTrue(n.getSource().contains(invite.getId().toString()));
        assertEquals(0, notifications.listForUser(owner).size(), "only the invitee is notified");
    }

    @Test
    void aMutedInviteTypeCreatesNoInboxRowButTheInvitationStaysDiscoverableAndAcceptable() throws Exception {
        UUID owner = user("mn4_owner");
        UUID invitee = user("mn4_invitee");
        Community c = community(owner);
        setPrefs(invitee, "{\"mod_invite\": false}", "{}");

        ModeratorInvite invite = invites.send(owner, c.getId(), name(invitee), P_BAN);

        awaitTrue(() -> pendingEvents(invitee) == 0, "the muted event to be consumed");
        assertEquals(0, rows(invitee), "muting suppresses the notification row");
        assertEquals(0, notifications.listForUser(invitee).size());

        assertEquals(List.of(invite.getId()), invites.listMine(invitee).stream().map(v -> v.id()).toList(), "the pending-invitations list is independent of the mute");
        invites.accept(invitee, invite.getId());
        assertEquals(Integer.valueOf(P_BAN), jdbc.queryForObject("SELECT permissions FROM community_moderators WHERE community_id = ? AND user_id = ?", Integer.class, c.getId(), invitee));
    }

    @Test
    void mutingOtherTypesDoesNotMuteInvites() throws Exception {
        UUID owner = user("mn5_owner");
        UUID invitee = user("mn5_invitee");
        Community c = community(owner);
        setPrefs(invitee, "{\"reply\": false, \"mention\": false, \"chat_message\": false}", "{}");
        invites.send(owner, c.getId(), name(invitee), P_BAN);
        awaitTrue(() -> rows(invitee) == 1, "the inbox row");
    }

    @Test
    void emailFollowsTheExistingOptInRule() throws Exception {
        UUID owner = user("mn6_owner");
        UUID optedIn = user("mn6_in");
        UUID notOptedIn = user("mn6_out");
        Community c = community(owner);
        setPrefs(optedIn, "{}", "{\"emailNotifications\": true}");

        invites.send(owner, c.getId(), name(optedIn), P_BAN);
        invites.send(owner, c.getId(), name(notOptedIn), P_BAN);

        awaitTrue(() -> rows(optedIn) == 1 && rows(notOptedIn) == 1, "both inbox rows");
        awaitTrue(() -> emails(optedIn) == 1, "the opted-in user's email event");
        assertEquals(0, emails(notOptedIn));
        assertEquals(1, emails(optedIn), "exactly one email event");
    }

    @Test
    void aMutedTypeIsNeverEmailedEvenForAnOptedInUser() throws Exception {
        UUID owner = user("mn7_owner");
        UUID invitee = user("mn7_invitee");
        Community c = community(owner);
        setPrefs(invitee, "{\"mod_invite\": false}", "{\"emailNotifications\": true}");
        invites.send(owner, c.getId(), name(invitee), P_BAN);
        awaitTrue(() -> pendingEvents(invitee) == 0, "the event to be consumed");
        assertEquals(0, emails(invitee));
    }
}
