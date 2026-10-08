package com.redditclone.community;

import com.redditclone.auth.JwtService;
import com.redditclone.auth.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Moderator invitations (Phase A): send / list / cancel / accept / decline over HTTP, against the shared Spring context
// with rolled-back transactions. Every write is flushed after each call so the JDBC assertions can see it.
@SpringBootTest
@Transactional
class ModeratorInviteTest {

    private static final int P_CONTENT = CommunityModerator.PERM_REMOVE_CONTENT;
    private static final int P_BAN = CommunityModerator.PERM_BAN_USERS;
    private static final int P_MODS = CommunityModerator.PERM_MANAGE_MODERATORS;

    @Autowired private WebApplicationContext webContext;
    @Autowired private CommunityService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager em;
    @Autowired private JwtService jwt;
    @Autowired private UserRepository users;

    private MockMvc mvc;
    private CommunityDeleteFixtures fx;
    private UUID owner;
    private UUID inviteMod;   // MANAGE_MODERATORS + BAN_USERS
    private UUID banOnlyMod;  // BAN_USERS only
    private UUID invitee;
    private UUID outsider;
    private Community community;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        fx = new CommunityDeleteFixtures(jdbc, service, em);
        owner = fx.user("mi_owner");
        inviteMod = fx.user("mi_invmod");
        banOnlyMod = fx.user("mi_banmod");
        invitee = fx.user("mi_invitee");
        outsider = fx.user("mi_outsider");
        community = fx.community(owner);
        service.addModerator(owner, community.getId(), inviteMod, P_MODS | P_BAN);
        service.addModerator(owner, community.getId(), banOnlyMod, P_BAN);
        em.flush();
    }

    private String token(UUID id) {
        return "Bearer " + jwt.generateAccessToken(users.findById(id).orElseThrow());
    }

    private String username(UUID id) {
        return users.findById(id).orElseThrow().getUsername();
    }

    private ResultActions send(UUID actor, UUID target, int perms) throws Exception {
        ResultActions r = mvc.perform(post("/r/{n}/mod/moderator-invites", community.getName()).header("Authorization", token(actor))
                .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"" + username(target) + "\",\"permissions\":" + perms + "}"));
        em.flush();
        return r;
    }

    private UUID sendOk(UUID actor, UUID target, int perms) throws Exception {
        String id = com.jayway.jsonpath.JsonPath.read(send(actor, target, perms).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.id");
        return UUID.fromString(id);
    }

    private ResultActions respond(UUID user, UUID inviteId, String verb) throws Exception {
        ResultActions r = mvc.perform(post("/api/moderator-invites/{id}/" + verb, inviteId).header("Authorization", token(user)));
        em.flush();
        em.clear();
        return r;
    }

    private String inviteStatus(UUID inviteId) {
        return jdbc.queryForObject("SELECT status FROM moderator_invites WHERE id = ?", String.class, inviteId);
    }

    private int actions(String action) {
        return jdbc.queryForObject("SELECT count(*) FROM moderation_actions WHERE community_id = ? AND action = ?", Integer.class, community.getId(), action);
    }

    private boolean isMember(UUID user) {
        return jdbc.queryForObject("SELECT count(*) FROM memberships WHERE community_id = ? AND user_id = ?", Integer.class, community.getId(), user) > 0;
    }

    private Integer modPerms(UUID user) {
        var rows = jdbc.queryForList("SELECT permissions FROM community_moderators WHERE community_id = ? AND user_id = ?", Integer.class, community.getId(), user);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ---------- sending ----------

    @Test
    void ownerAndManageModeratorsModCanInviteAndItIsLoggedButNothingIsGrantedYet() throws Exception {
        UUID byOwner = sendOk(owner, invitee, P_CONTENT);
        assertEquals("pending", inviteStatus(byOwner));
        assertEquals(1, actions("invite_moderator"));
        assertEquals(null, modPerms(invitee), "an invitation grants nothing until accepted");
        assertFalse(isMember(invitee));

        UUID other = fx.user("mi_other");
        em.flush();
        sendOk(inviteMod, other, P_BAN);
        assertEquals(2, actions("invite_moderator"));
    }

    @Test
    void unauthorizedCallersAreRefused() throws Exception {
        send(banOnlyMod, invitee, P_BAN).andExpect(status().isForbidden());
        send(outsider, invitee, P_BAN).andExpect(status().isForbidden());
        mvc.perform(post("/r/{n}/mod/moderator-invites", community.getName()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"x\",\"permissions\":1}")).andExpect(status().isUnauthorized());
    }

    @Test
    void escalationBeyondTheSendersOwnPermissionsIsBlocked() throws Exception {
        send(inviteMod, invitee, P_CONTENT).andExpect(status().isForbidden()); // inviteMod lacks REMOVE_CONTENT
        send(inviteMod, invitee, P_BAN | CommunityModerator.PERM_MANAGE_FLAIRS).andExpect(status().isForbidden());
        send(inviteMod, invitee, P_BAN).andExpect(status().isOk());
    }

    @Test
    void invalidPermissionBitsAreRejected() throws Exception {
        send(owner, invitee, Integer.MAX_VALUE).andExpect(status().isBadRequest());
        send(owner, invitee, -1).andExpect(status().isBadRequest());
        send(owner, invitee, 1 << 20).andExpect(status().isBadRequest());
    }

    @Test
    void ownerExistingModeratorsAndUnknownOrInactiveUsersCannotBeInvited() throws Exception {
        send(inviteMod, owner, P_BAN).andExpect(status().isBadRequest());
        send(owner, banOnlyMod, P_BAN).andExpect(status().isConflict());
        mvc.perform(post("/r/{n}/mod/moderator-invites", community.getName()).header("Authorization", token(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"nobody_here_zz\",\"permissions\":1}")).andExpect(status().isNotFound());
        UUID banned = fx.bannedUser();
        em.flush();
        send(owner, banned, P_BAN).andExpect(status().isNotFound());
    }

    @Test
    void aSecondPendingInvitationIsRejectedAndTheDatabaseIndexBacksItUp() throws Exception {
        sendOk(owner, invitee, P_CONTENT);
        send(owner, invitee, P_CONTENT).andExpect(status().isConflict());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM moderator_invites WHERE community_id = ? AND invitee_id = ?", Integer.class, community.getId(), invitee));
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DuplicateKeyException.class, () ->
                jdbc.update("INSERT INTO moderator_invites (id, community_id, invitee_id, inviter_id, permissions, expires_at) VALUES (?, ?, ?, ?, 1, now() + interval '1 day')",
                        UUID.randomUUID(), community.getId(), invitee, owner));
    }

    @Test
    void aLapsedInvitationCanBeReissued() throws Exception {
        UUID first = sendOk(owner, invitee, P_CONTENT);
        jdbc.update("UPDATE moderator_invites SET expires_at = now() - interval '1 minute' WHERE id = ?", first);
        em.clear();
        UUID second = sendOk(owner, invitee, P_BAN);
        assertEquals("expired", inviteStatus(first));
        assertEquals("pending", inviteStatus(second));
    }

    @Test
    void invitationsAreScopedToTheirCommunity() throws Exception {
        sendOk(owner, invitee, P_CONTENT);
        Community other = fx.community(fx.user("mi_otherowner"));
        em.flush();
        mvc.perform(get("/r/{n}/mod/moderator-invites", other.getName()).header("Authorization", token(owner))).andExpect(status().isForbidden());
        mvc.perform(get("/r/{n}/mod/moderator-invites", other.getName()).header("Authorization", token(other.getCreatorId())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aDeletedCommunityRefusesInvitations() throws Exception {
        jdbc.update("UPDATE communities SET deleted_at = now(), deleted_by = ? WHERE id = ?", owner, community.getId());
        em.clear();
        mvc.perform(post("/r/{n}/mod/moderator-invites", community.getName()).header("Authorization", token(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"" + username(invitee) + "\",\"permissions\":1}")).andExpect(status().isNotFound());
    }

    @Test
    void aModeratorCanFindSomeoneThroughSharedSearchAndInviteThem() throws Exception {
        String body = mvc.perform(get("/api/users/search?q={q}", username(invitee).substring(0, 9)).header("Authorization", token(inviteMod)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        java.util.List<String> found = com.jayway.jsonpath.JsonPath.read(body, "$[*].username");
        assertTrue(found.contains(username(invitee)), body);
        assertFalse(found.contains(username(inviteMod)), "the searcher never finds themselves");
        send(inviteMod, invitee, P_BAN).andExpect(status().isOk());
        mvc.perform(get("/r/{n}/mod/moderator-invites", community.getName()).header("Authorization", token(owner)))
                .andExpect(jsonPath("$[0].inviterUsername").value(username(inviteMod)));
    }

    @Test
    void searchGivesANormalUserNoInvitePower() throws Exception {
        mvc.perform(get("/api/users/search?q={q}", username(invitee).substring(0, 9)).header("Authorization", token(outsider))).andExpect(status().isOk());
        send(outsider, invitee, P_BAN).andExpect(status().isForbidden());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM moderator_invites WHERE community_id = ?", Integer.class, community.getId()));
    }

    // ---------- listing / cancelling ----------

    @Test
    void anyModeratorSeesPendingInvitationsAndTheInviteeSeesTheirOwn() throws Exception {
        sendOk(owner, invitee, P_CONTENT);
        mvc.perform(get("/r/{n}/mod/moderator-invites", community.getName()).header("Authorization", token(banOnlyMod)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].inviteeUsername").value(username(invitee)))
                .andExpect(jsonPath("$[0].inviterUsername").value(username(owner)))
                .andExpect(jsonPath("$[0].permissions").value(P_CONTENT));
        mvc.perform(get("/r/{n}/mod/moderator-invites", community.getName()).header("Authorization", token(outsider))).andExpect(status().isForbidden());
        mvc.perform(get("/api/moderator-invites").header("Authorization", token(invitee)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].communityName").value(community.getName()));
        mvc.perform(get("/api/moderator-invites").header("Authorization", token(outsider)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/moderator-invites")).andExpect(status().isUnauthorized());
    }

    @Test
    void aLapsedInvitationIsNotListedForModeratorsOrTheInviteeAndCannotBeAccepted() throws Exception {
        UUID id = sendOk(owner, invitee, P_CONTENT);
        mvc.perform(get("/r/{n}/mod/moderator-invites", community.getName()).header("Authorization", token(owner))).andExpect(jsonPath("$.length()").value(1));
        jdbc.update("UPDATE moderator_invites SET expires_at = now() - interval '1 second' WHERE id = ?", id);
        em.clear();
        mvc.perform(get("/r/{n}/mod/moderator-invites", community.getName()).header("Authorization", token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/moderator-invites").header("Authorization", token(invitee))).andExpect(jsonPath("$.length()").value(0));
        respond(invitee, id, "accept").andExpect(status().isConflict());
        assertFalse(isMember(invitee));
    }

    @Test
    void cancelWorksIsIdempotentAndLogged() throws Exception {
        UUID id = sendOk(owner, invitee, P_CONTENT);
        mvc.perform(delete("/r/{n}/mod/moderator-invites/{id}", community.getName(), id).header("Authorization", token(owner))).andExpect(status().isOk());
        em.flush();
        assertEquals("cancelled", inviteStatus(id));
        mvc.perform(delete("/r/{n}/mod/moderator-invites/{id}", community.getName(), id).header("Authorization", token(owner))).andExpect(status().isOk());
        assertEquals(1, actions("cancel_moderator_invite"));
        respond(invitee, id, "accept").andExpect(status().isConflict());
        assertFalse(isMember(invitee));
    }

    @Test
    void cancelNeedsManageModeratorsAndCannotCancelBeyondOneOwnBits() throws Exception {
        UUID id = sendOk(owner, invitee, P_CONTENT);
        mvc.perform(delete("/r/{n}/mod/moderator-invites/{id}", community.getName(), id).header("Authorization", token(banOnlyMod))).andExpect(status().isForbidden());
        mvc.perform(delete("/r/{n}/mod/moderator-invites/{id}", community.getName(), id).header("Authorization", token(inviteMod))).andExpect(status().isForbidden()); // grants REMOVE_CONTENT, which inviteMod lacks
        Community other = fx.community(fx.user("mi_oc"));
        em.flush();
        mvc.perform(delete("/r/{n}/mod/moderator-invites/{id}", other.getName(), id).header("Authorization", token(other.getCreatorId()))).andExpect(status().isNotFound());
        assertEquals("pending", inviteStatus(id));
    }

    // ---------- accepting ----------

    @Test
    void acceptJoinsTheCommunityAndGrantsExactlyTheStoredPermissionsAndIsLogged() throws Exception {
        UUID id = sendOk(owner, invitee, P_CONTENT | P_BAN);
        int before = jdbc.queryForObject("SELECT subscriber_count FROM communities WHERE id = ?", Integer.class, community.getId());
        respond(invitee, id, "accept").andExpect(status().isOk());
        assertEquals("accepted", inviteStatus(id));
        assertTrue(isMember(invitee));
        assertEquals(P_CONTENT | P_BAN, modPerms(invitee));
        assertEquals(before + 1, jdbc.queryForObject("SELECT subscriber_count FROM communities WHERE id = ?", Integer.class, community.getId()));
        assertEquals(owner, jdbc.queryForObject("SELECT added_by FROM community_moderators WHERE community_id = ? AND user_id = ?", UUID.class, community.getId(), invitee));
        assertEquals(1, actions("accept_moderator_invite"));
        mvc.perform(get("/r/{n}/mod/moderators", community.getName()).header("Authorization", token(invitee))).andExpect(status().isOk());
    }

    @Test
    void acceptingWhileAlreadyAMemberDoesNotDoubleCountTheMembership() throws Exception {
        service.join(invitee, community.getId());
        em.flush();
        int before = jdbc.queryForObject("SELECT subscriber_count FROM communities WHERE id = ?", Integer.class, community.getId());
        UUID id = sendOk(owner, invitee, P_BAN);
        respond(invitee, id, "accept").andExpect(status().isOk());
        assertEquals(before, jdbc.queryForObject("SELECT subscriber_count FROM communities WHERE id = ?", Integer.class, community.getId()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM memberships WHERE community_id = ? AND user_id = ?", Integer.class, community.getId(), invitee));
    }

    @Test
    void acceptWorksForAPrivateCommunityWithoutAJoinRequest() throws Exception {
        jdbc.update("UPDATE communities SET type = 'private' WHERE id = ?", community.getId());
        em.clear();
        UUID id = sendOk(owner, invitee, P_BAN);
        respond(invitee, id, "accept").andExpect(status().isOk());
        assertTrue(isMember(invitee));
        assertEquals(P_BAN, modPerms(invitee));
    }

    @Test
    void onlyTheInviteeCanRespondAndOthersCannotTellTheInvitationExists() throws Exception {
        UUID id = sendOk(owner, invitee, P_BAN);
        respond(outsider, id, "accept").andExpect(status().isNotFound());
        respond(owner, id, "accept").andExpect(status().isNotFound());
        respond(outsider, id, "decline").andExpect(status().isNotFound());
        respond(invitee, UUID.randomUUID(), "accept").andExpect(status().isNotFound());
        mvc.perform(post("/api/moderator-invites/{id}/accept", id)).andExpect(status().isUnauthorized());
        assertEquals("pending", inviteStatus(id));
        assertEquals(null, modPerms(invitee));
    }

    @Test
    void theRequestBodyCannotChangeWhatAcceptanceGrants() throws Exception {
        UUID id = sendOk(owner, invitee, P_BAN);
        mvc.perform(post("/api/moderator-invites/{id}/accept", id).header("Authorization", token(invitee))
                .contentType(MediaType.APPLICATION_JSON).content("{\"permissions\":2147483647,\"communityId\":\"" + UUID.randomUUID() + "\"}")).andExpect(status().isOk());
        em.flush();
        assertEquals(P_BAN, modPerms(invitee));
    }

    @Test
    void acceptingTwiceIsRejectedAndChangesNothing() throws Exception {
        UUID id = sendOk(owner, invitee, P_BAN);
        respond(invitee, id, "accept").andExpect(status().isOk());
        respond(invitee, id, "accept").andExpect(status().isConflict());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM community_moderators WHERE community_id = ? AND user_id = ?", Integer.class, community.getId(), invitee));
        assertEquals(1, actions("accept_moderator_invite"));
    }

    @Test
    void anExpiredInvitationCannotBeAcceptedAndIsMarkedExpired() throws Exception {
        UUID id = sendOk(owner, invitee, P_BAN);
        jdbc.update("UPDATE moderator_invites SET expires_at = now() - interval '1 second' WHERE id = ?", id);
        em.clear();
        respond(invitee, id, "accept").andExpect(status().isConflict());
        assertEquals("expired", inviteStatus(id));
        assertFalse(isMember(invitee));
        mvc.perform(get("/api/moderator-invites").header("Authorization", token(invitee))).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aBannedUserCannotAcceptButCanOnceTheBanIsLifted() throws Exception {
        UUID id = sendOk(owner, invitee, P_BAN);
        service.issueBan(owner, community.getId(), invitee, "spam", null);
        em.flush();
        respond(invitee, id, "accept").andExpect(status().isForbidden());
        assertEquals("pending", inviteStatus(id));
        assertFalse(isMember(invitee));
        assertEquals(null, modPerms(invitee));
        service.liftBan(owner, community.getId(), invitee, "appeal");
        em.flush();
        respond(invitee, id, "accept").andExpect(status().isOk());
        assertEquals(P_BAN, modPerms(invitee));
    }

    @Test
    void aDeletedCommunityCannotBeJoinedThroughAnInvitation() throws Exception {
        UUID id = sendOk(owner, invitee, P_BAN);
        jdbc.update("UPDATE communities SET deleted_at = now(), deleted_by = ? WHERE id = ?", owner, community.getId());
        em.clear();
        respond(invitee, id, "accept").andExpect(status().isNotFound());
        assertFalse(isMember(invitee));
        assertEquals(null, modPerms(invitee));
        mvc.perform(get("/api/moderator-invites").header("Authorization", token(invitee))).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void anInviteeDeactivatedMeanwhileCannotAccept() throws Exception {
        UUID id = sendOk(owner, invitee, P_BAN);
        jdbc.update("UPDATE users SET status = 'deleted' WHERE id = ?", invitee);
        em.clear();
        respond(invitee, id, "accept").andExpect(status().is4xxClientError());
        assertEquals(null, modPerms(invitee));
    }

    @Test
    void anInviteeWhoBecameAModeratorAnotherWayKeepsThoseExactPermissions() throws Exception {
        UUID id = sendOk(owner, invitee, P_BAN);
        service.addModerator(owner, community.getId(), invitee, P_CONTENT);
        em.flush();
        respond(invitee, id, "accept").andExpect(status().isOk());
        assertEquals(P_CONTENT, modPerms(invitee), "acceptance must not overwrite an existing moderator row");
        assertEquals("accepted", inviteStatus(id));
    }

    // The sender losing their rights afterwards does not void an already-authorized delegation.
    @Test
    void anInvitationSurvivesTheSenderBeingDemoted() throws Exception {
        UUID id = sendOk(inviteMod, invitee, P_BAN);
        service.removeModerator(owner, community.getId(), inviteMod);
        em.flush();
        respond(invitee, id, "accept").andExpect(status().isOk());
        assertEquals(P_BAN, modPerms(invitee));
    }

    // ---------- declining ----------

    @Test
    void declineMarksItDeclinedIsIdempotentAndBlocksALaterAccept() throws Exception {
        UUID id = sendOk(owner, invitee, P_BAN);
        respond(invitee, id, "decline").andExpect(status().isOk());
        assertEquals("declined", inviteStatus(id));
        respond(invitee, id, "decline").andExpect(status().isOk());
        assertEquals(1, actions("decline_moderator_invite"));
        respond(invitee, id, "accept").andExpect(status().isConflict());
        assertEquals(null, modPerms(invitee));
        assertFalse(isMember(invitee));
        sendOk(owner, invitee, P_BAN); // a declined invitation frees the slot for a new one
    }
}
