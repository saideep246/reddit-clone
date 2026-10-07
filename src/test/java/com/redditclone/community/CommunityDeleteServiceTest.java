package com.redditclone.community;

import com.redditclone.common.exception.BadRequestException;
import com.redditclone.common.exception.ConflictException;
import com.redditclone.common.exception.ForbiddenException;
import com.redditclone.common.exception.NotFoundException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// CommunityService.deleteCommunity against the real local PostgreSQL (start the docker compose stack first). Each test runs
// in a rolled-back transaction, so nothing is left behind. These tests cover the deletion OPERATION only: making a deleted
// community disappear from reads and refuse writes is later work, so nothing here asserts on that.
@SpringBootTest
@Transactional
class CommunityDeleteServiceTest {

    @Autowired
    private CommunityService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager em;

    private CommunityDeleteFixtures fx;
    private UUID creator;
    private Community community;

    @BeforeEach
    void setUp() {
        fx = new CommunityDeleteFixtures(jdbc, service, em);
        creator = fx.user("cdowner");
        community = fx.community(creator);
    }

    // ---- who may delete ----

    @Test
    void theCreatorCanDeleteAndTheRowIsMarkedNotRemoved() {
        service.deleteCommunity(creator, community.getId(), community.getName());

        assertTrue(fx.isDeleted(community.getId()));
        assertEquals(creator, fx.deletedBy(community.getId()));
        assertNotNull(jdbc.queryForObject("SELECT id FROM communities WHERE id = ?", UUID.class, community.getId()),
                "soft delete: the row must still exist");
    }

    @Test
    void aSettingsOnlyModeratorCannotDelete() {
        UUID mod = fx.user("cdmod");
        fx.moderator(community.getId(), mod, CommunityDeleteFixtures.SETTINGS_ONLY, creator);

        assertThrows(ForbiddenException.class, () -> service.deleteCommunity(mod, community.getId(), community.getName()));

        assertFalse(fx.isDeleted(community.getId()));
        assertEquals(0, fx.auditRows(community.getId()));
    }

    @Test
    void aModeratorHoldingEveryPermissionBitIsStillNotTheOwner() {
        UUID coOwner = fx.user("cdcoowner");
        fx.moderator(community.getId(), coOwner, CommunityDeleteFixtures.ALL_BITS, creator);

        assertThrows(ForbiddenException.class, () -> service.deleteCommunity(coOwner, community.getId(), community.getName()));

        assertFalse(fx.isDeleted(community.getId()));
        assertEquals(0, fx.auditRows(community.getId()));
    }

    @Test
    void anOutsiderCannotDelete() {
        UUID outsider = fx.user("cdoutsider");

        assertThrows(ForbiddenException.class, () -> service.deleteCommunity(outsider, community.getId(), community.getName()));

        assertFalse(fx.isDeleted(community.getId()));
    }

    @Test
    void aSiteAdminWhoIsNotTheCreatorCannotDeleteHere() {
        // Site-admin status is not consulted by any community permission check in this codebase; a takedown belongs in
        // AdminController. This pins that decision so an override cannot appear by accident.
        UUID admin = fx.siteAdmin();

        assertThrows(ForbiddenException.class, () -> service.deleteCommunity(admin, community.getId(), community.getName()));

        assertFalse(fx.isDeleted(community.getId()));
    }

    @Test
    void aCreatorWhoseAccountIsNoLongerActiveCannotDelete() {
        UUID bannedCreator = fx.bannedUser();
        Community theirs = fx.community(bannedCreator);

        assertThrows(ForbiddenException.class, () -> service.deleteCommunity(bannedCreator, theirs.getId(), theirs.getName()));

        assertFalse(fx.isDeleted(theirs.getId()));
    }

    // ---- confirmation ----

    @Test
    void aWrongConfirmationNameIsABadRequestAndChangesNothing() {
        assertThrows(BadRequestException.class, () -> service.deleteCommunity(creator, community.getId(), "not-the-name"));

        assertFalse(fx.isDeleted(community.getId()));
        assertEquals(0, fx.auditRows(community.getId()));
    }

    @Test
    void theConfirmationMustMatchExactlyIncludingCase() {
        String shouted = community.getName().toUpperCase();
        assertTrue(!shouted.equals(community.getName()), "sanity: the fixture name has lowercase letters");

        assertThrows(BadRequestException.class, () -> service.deleteCommunity(creator, community.getId(), shouted));
        assertThrows(BadRequestException.class, () -> service.deleteCommunity(creator, community.getId(), community.getName() + " "));
        assertThrows(BadRequestException.class, () -> service.deleteCommunity(creator, community.getId(), null));

        assertFalse(fx.isDeleted(community.getId()));
    }

    @Test
    void aCallerWhoMayNotDeleteGetsForbiddenEvenWithAWrongConfirmation() {
        UUID outsider = fx.user("cdoutsider2");

        assertThrows(ForbiddenException.class, () -> service.deleteCommunity(outsider, community.getId(), "wrong"));
    }

    // ---- not found / already deleted ----

    @Test
    void anUnknownCommunityIsNotFound() {
        assertThrows(NotFoundException.class, () -> service.deleteCommunity(creator, UUID.randomUUID(), "anything"));
    }

    @Test
    void anAlreadyDeletedCommunityIsNotFoundAndNothingChangesOnTheSecondAttempt() {
        service.deleteCommunity(creator, community.getId(), community.getName());
        UUID firstDeletedBy = fx.deletedBy(community.getId());
        Object firstDeletedAt = jdbc.queryForObject("SELECT deleted_at FROM communities WHERE id = ?", Object.class, community.getId());

        assertThrows(NotFoundException.class, () -> service.deleteCommunity(creator, community.getId(), community.getName()));

        assertEquals(firstDeletedBy, fx.deletedBy(community.getId()));
        assertEquals(firstDeletedAt, jdbc.queryForObject("SELECT deleted_at FROM communities WHERE id = ?", Object.class, community.getId()),
                "the original deletion time must not be overwritten");
        assertEquals(1, fx.auditRows(community.getId()), "one audit row, not two");
    }

    @Test
    void aDeletedCommunityIsNotFoundEvenForSomeoneWhoWouldOtherwiseBeRefused() {
        service.deleteCommunity(creator, community.getId(), community.getName());
        UUID outsider = fx.user("cdoutsider3");

        // 404 comes before 403: nothing about a deleted community is revealed to a caller with no rights over it
        assertThrows(NotFoundException.class, () -> service.deleteCommunity(outsider, community.getId(), community.getName()));
    }

    // ---- audit ----

    @Test
    void successWritesOneAuditRowWithTheActorAndTheCommunity() {
        service.deleteCommunity(creator, community.getId(), community.getName());

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT actor_id, action, target_type, target_id, reason FROM moderation_actions
                WHERE community_id = ? AND action = 'delete_community'""", community.getId());
        assertEquals(creator, row.get("actor_id"));
        assertEquals("delete_community", row.get("action"));
        assertEquals("community", row.get("target_type"));
        assertEquals(community.getId(), row.get("target_id"));
        assertEquals(null, row.get("reason"));
        assertEquals(1, fx.auditRows(community.getId()));
    }

    @Test
    void aRefusedDeleteWritesNoAuditRow() {
        UUID outsider = fx.user("cdoutsider4");
        assertThrows(ForbiddenException.class, () -> service.deleteCommunity(outsider, community.getId(), community.getName()));
        assertThrows(BadRequestException.class, () -> service.deleteCommunity(creator, community.getId(), "wrong"));

        assertEquals(0, fx.auditRows(community.getId()));
    }

    // ---- scope: this step only records the deletion ----

    @Test
    void deletionTouchesNothingButTheCommunityRowAndTheAuditLog() {
        UUID mod = fx.user("cdmod2");
        fx.moderator(community.getId(), mod, CommunityDeleteFixtures.SETTINGS_ONLY, creator);
        int membersBefore = jdbc.queryForObject("SELECT count(*) FROM memberships WHERE community_id = ?", Integer.class, community.getId());
        int moderatorsBefore = jdbc.queryForObject("SELECT count(*) FROM community_moderators WHERE community_id = ?", Integer.class, community.getId());
        int subscribersBefore = jdbc.queryForObject("SELECT subscriber_count FROM communities WHERE id = ?", Integer.class, community.getId());

        service.deleteCommunity(creator, community.getId(), community.getName());

        assertEquals(membersBefore, jdbc.queryForObject("SELECT count(*) FROM memberships WHERE community_id = ?", Integer.class, community.getId()));
        assertEquals(moderatorsBefore, jdbc.queryForObject("SELECT count(*) FROM community_moderators WHERE community_id = ?", Integer.class, community.getId()));
        assertEquals(subscribersBefore, jdbc.queryForObject("SELECT subscriber_count FROM communities WHERE id = ?", Integer.class, community.getId()));
    }

    @Test
    void theNameStaysReservedAfterDeletion() {
        service.deleteCommunity(creator, community.getId(), community.getName());

        UUID someoneElse = fx.user("cdother");
        assertThrows(ConflictException.class, () -> service.create(someoneElse, community.getName(), "taking the name", "public"));
    }
}
