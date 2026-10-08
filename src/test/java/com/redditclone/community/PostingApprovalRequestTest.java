package com.redditclone.community;

import com.jayway.jsonpath.JsonPath;
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

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// "Request posting approval" for restricted communities: state rules, permissions, audit entries and notification events. Shared
// Spring context, rolled-back transactions; every write is flushed so the JDBC assertions see it. Real concurrency and the real
// notification worker are in PostingApprovalConcurrencyTest / PostingApprovalNotificationTest (they need committed rows).
@SpringBootTest
@Transactional
class PostingApprovalRequestTest {

    @Autowired private WebApplicationContext webContext;
    @Autowired private CommunityService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager em;
    @Autowired private JwtService jwt;
    @Autowired private UserRepository users;

    private MockMvc mvc;
    private CommunityDeleteFixtures fx;
    private UUID owner;
    private UUID accessMod;  // MANAGE_ACCESS
    private UUID banMod;     // BAN_USERS only
    private UUID member;     // the requester
    private UUID other;
    private Community c;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        fx = new CommunityDeleteFixtures(jdbc, service, em);
        owner = fx.user("pa_owner");
        accessMod = fx.user("pa_accessmod");
        banMod = fx.user("pa_banmod");
        member = fx.user("pa_member");
        other = fx.user("pa_other");
        c = restricted();
    }

    private Community restricted() {
        Community x = fx.community(owner);
        service.addModerator(owner, x.getId(), accessMod, CommunityModerator.PERM_MANAGE_ACCESS);
        service.addModerator(owner, x.getId(), banMod, CommunityModerator.PERM_BAN_USERS);
        jdbc.update("UPDATE communities SET type = 'restricted' WHERE id = ?", x.getId());
        em.flush();
        em.clear();
        return x;
    }

    private String token(UUID id) {
        return "Bearer " + jwt.generateAccessToken(users.findById(id).orElseThrow());
    }

    private String username(UUID id) {
        return users.findById(id).orElseThrow().getUsername();
    }

    private ResultActions flushed(ResultActions r) {
        em.flush();
        em.clear();
        return r;
    }

    private ResultActions request(UUID who, Community x) throws Exception {
        return flushed(mvc.perform(post("/r/{n}/posting-requests", x.getName()).header("Authorization", token(who))));
    }

    private ResultActions cancel(UUID who, Community x) throws Exception {
        return flushed(mvc.perform(delete("/r/{n}/posting-requests", x.getName()).header("Authorization", token(who))));
    }

    private ResultActions approve(UUID who, Community x, UUID target) throws Exception {
        return flushed(mvc.perform(post("/r/{n}/mod/posting-requests/{u}/approve", x.getName(), target).header("Authorization", token(who))));
    }

    private ResultActions deny(UUID who, Community x, UUID target) throws Exception {
        return flushed(mvc.perform(post("/r/{n}/mod/posting-requests/{u}/deny", x.getName(), target).header("Authorization", token(who))));
    }

    private ResultActions list(UUID who, Community x) throws Exception {
        return mvc.perform(get("/r/{n}/mod/posting-requests", x.getName()).header("Authorization", token(who)));
    }

    private ResultActions submit(UUID who, Community x) throws Exception {
        return flushed(mvc.perform(post("/r/{n}/submit", x.getName()).header("Authorization", token(who))
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"text\",\"title\":\"hello\",\"body\":\"body\"}")));
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private int rows(Community x, UUID user, String status) {
        return count("SELECT count(*) FROM posting_approval_requests WHERE community_id = ? AND user_id = ? AND status = ?", x.getId(), user, status);
    }

    private int logged(Community x, String action) {
        return count("SELECT count(*) FROM moderation_actions WHERE community_id = ? AND action = ?", x.getId(), action);
    }

    private int events(UUID recipient, String type) {
        return count("SELECT count(*) FROM outbox_events WHERE event_type = 'notification' AND payload->>'userId' = ? AND payload->>'type' = ?", recipient.toString(), type);
    }

    private boolean approvedSubmitter(Community x, UUID user) {
        return count("SELECT count(*) FROM community_approved_submitters WHERE community_id = ? AND user_id = ?", x.getId(), user) > 0;
    }

    private boolean isMember(Community x, UUID user) {
        return count("SELECT count(*) FROM memberships WHERE community_id = ? AND user_id = ?", x.getId(), user) > 0;
    }

    // ================= creating =================

    @Test
    void askingCreatesOnePendingRequestAndNeverAMembership() throws Exception {
        request(member, c).andExpect(status().isOk());
        assertEquals(1, rows(c, member, "pending"));
        assertFalse(isMember(c, member), "asking for posting approval is not joining");
        assertFalse(approvedSubmitter(c, member));
        assertEquals(1, logged(c, "posting_approval_requested"));
        submit(member, c).andExpect(status().isForbidden());
    }

    @Test
    void askingTwiceWhilePendingChangesNothing() throws Exception {
        request(member, c).andExpect(status().isOk());
        request(member, c).andExpect(status().isOk());
        assertEquals(1, rows(c, member, "pending"));
        assertEquals(1, count("SELECT count(*) FROM posting_approval_requests WHERE community_id = ? AND user_id = ?", c.getId(), member));
        assertEquals(1, logged(c, "posting_approval_requested"), "a repeat writes no second log entry");
        assertEquals(1, events(owner, "posting_request"), "and no second notification");
    }

    @Test
    void theDatabaseItselfRefusesASecondPendingRow() throws Exception {
        request(member, c).andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DuplicateKeyException.class, () ->
                jdbc.update("INSERT INTO posting_approval_requests (id, community_id, user_id) VALUES (?, ?, ?)", UUID.randomUUID(), c.getId(), member));
    }

    @Test
    void moderatorsWhoCanActAreToldAndOthersAreNot() throws Exception {
        request(member, c).andExpect(status().isOk());
        assertEquals(1, events(owner, "posting_request"));
        assertEquals(1, events(accessMod, "posting_request"));
        assertEquals(0, events(banMod, "posting_request"), "a moderator without Manage access is not notified");
        assertEquals(0, events(member, "posting_request"));
        String payload = jdbc.queryForObject("SELECT payload::text FROM outbox_events WHERE payload->>'userId' = ? AND payload->>'type' = 'posting_request'", String.class, owner.toString());
        assertTrue(payload.contains(c.getId().toString()) && payload.contains(member.toString()), payload);
    }

    @Test
    void peopleWhoCanAlreadyPostCannotAsk() throws Exception {
        request(owner, c).andExpect(status().isConflict());
        request(banMod, c).andExpect(status().isConflict());
        jdbc.update("INSERT INTO community_approved_submitters (community_id, user_id, approved_by) VALUES (?, ?, ?)", c.getId(), member, owner);
        request(member, c).andExpect(status().isConflict());
        assertEquals(0, count("SELECT count(*) FROM posting_approval_requests WHERE community_id = ?", c.getId()));
    }

    @Test
    void onlyRestrictedCommunitiesTakeRequests() throws Exception {
        Community open = fx.community(owner);
        Community priv = fx.community(owner);
        jdbc.update("UPDATE communities SET type = 'private' WHERE id = ?", priv.getId());
        em.flush();
        em.clear();
        request(member, open).andExpect(status().isBadRequest());
        request(member, priv).andExpect(status().isBadRequest());
    }

    @Test
    void bannedAndInactiveRequestersAreRefused() throws Exception {
        service.issueBan(owner, c.getId(), member, "spam", null);
        em.flush();
        request(member, c).andExpect(status().isForbidden());
        UUID inactive = fx.bannedUser();
        em.flush();
        request(inactive, c).andExpect(status().isForbidden());
        assertEquals(0, count("SELECT count(*) FROM posting_approval_requests WHERE community_id = ?", c.getId()));
    }

    @Test
    void anonymousCallersAreRefused() throws Exception {
        mvc.perform(post("/r/{n}/posting-requests", c.getName())).andExpect(status().isUnauthorized());
        mvc.perform(delete("/r/{n}/posting-requests", c.getName())).andExpect(status().isUnauthorized());
        mvc.perform(get("/r/{n}/mod/posting-requests", c.getName())).andExpect(status().isUnauthorized());
    }

    @Test
    void theTenthRequestCycleIsFineAndTheEleventhIsRateLimited() throws Exception {
        for (int i = 0; i < 10; i++) {
            request(member, c).andExpect(status().isOk());
            cancel(member, c).andExpect(status().isOk());
        }
        request(member, c).andExpect(status().isTooManyRequests());
    }

    // ================= listing =================

    @Test
    void theOwnerAndManageAccessModeratorsSeePendingRequestsAndNobodyElseDoes() throws Exception {
        request(member, c).andExpect(status().isOk());
        for (UUID viewer : List.of(owner, accessMod)) {
            list(viewer, c).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].userId").value(member.toString()))
                    .andExpect(jsonPath("$[0].username").value(username(member)))
                    .andExpect(jsonPath("$[0].status").value("pending"));
        }
        list(banMod, c).andExpect(status().isForbidden());
        list(member, c).andExpect(status().isForbidden());
        list(other, c).andExpect(status().isForbidden());
        String body = list(owner, c).andReturn().getResponse().getContentAsString();
        java.util.Map<String, Object> row = JsonPath.read(body, "$[0]");
        assertEquals(java.util.Set.of("userId", "username", "status", "requestedAt"), row.keySet(), "no internal fields leave the server");
    }

    @Test
    void decidedRequestsLeaveTheList() throws Exception {
        request(member, c).andExpect(status().isOk());
        request(other, c).andExpect(status().isOk());
        approve(owner, c, member).andExpect(status().isOk());
        deny(owner, c, other).andExpect(status().isOk());
        list(owner, c).andExpect(jsonPath("$.length()").value(0));
    }

    // ================= approving =================

    @Test
    void theOwnerApprovingMakesThemAnApprovedSubmitterWhoCanPostAndNothingElse() throws Exception {
        request(member, c).andExpect(status().isOk());
        approve(owner, c, member).andExpect(status().isOk());
        assertEquals(1, rows(c, member, "approved"));
        assertEquals(owner, jdbc.queryForObject("SELECT responded_by FROM posting_approval_requests WHERE user_id = ?", UUID.class, member));
        assertTrue(count("SELECT count(*) FROM posting_approval_requests WHERE user_id = ? AND responded_at IS NOT NULL", member) == 1);
        assertTrue(approvedSubmitter(c, member));
        assertEquals(owner, jdbc.queryForObject("SELECT approved_by FROM community_approved_submitters WHERE user_id = ?", UUID.class, member));
        assertFalse(isMember(c, member), "approval grants posting, not membership");
        assertEquals(0, count("SELECT count(*) FROM community_moderators WHERE community_id = ? AND user_id = ?", c.getId(), member), "and never moderator status");
        submit(member, c).andExpect(status().isOk());
        assertEquals(1, logged(c, "posting_approval_approved"));
        assertEquals(1, events(member, "posting_decision"));
    }

    @Test
    void aManageAccessModeratorCanApproveAndOthersCannot() throws Exception {
        request(member, c).andExpect(status().isOk());
        approve(banMod, c, member).andExpect(status().isForbidden());
        approve(member, c, member).andExpect(status().isForbidden());
        approve(other, c, member).andExpect(status().isForbidden());
        deny(banMod, c, member).andExpect(status().isForbidden());
        assertEquals(1, rows(c, member, "pending"));
        assertFalse(approvedSubmitter(c, member));
        approve(accessMod, c, member).andExpect(status().isOk());
        assertTrue(approvedSubmitter(c, member));
        assertEquals(accessMod, jdbc.queryForObject("SELECT responded_by FROM posting_approval_requests WHERE user_id = ?", UUID.class, member));
    }

    @Test
    void aModeratorWhoLosesManageAccessCanNoLongerDecide() throws Exception {
        request(member, c).andExpect(status().isOk());
        service.addModerator(owner, c.getId(), accessMod, CommunityModerator.PERM_BAN_USERS);
        em.flush();
        approve(accessMod, c, member).andExpect(status().isForbidden());
        deny(accessMod, c, member).andExpect(status().isForbidden());
        assertEquals(1, rows(c, member, "pending"));
        service.removeModerator(owner, c.getId(), banMod);
        em.flush();
        approve(banMod, c, member).andExpect(status().isForbidden());
    }

    @Test
    void approvingARequestFromSomeoneAlreadyApprovedJustClosesIt() throws Exception {
        request(member, c).andExpect(status().isOk());
        flushed(mvc.perform(post("/r/{n}/mod/approved-submitters", c.getName()).header("Authorization", token(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":\"" + member + "\"}"))).andExpect(status().isOk());
        approve(owner, c, member).andExpect(status().isOk());
        assertEquals(1, rows(c, member, "approved"));
        assertEquals(1, count("SELECT count(*) FROM community_approved_submitters WHERE community_id = ? AND user_id = ?", c.getId(), member), "no duplicate approval state");
    }

    @Test
    void approvingTwiceIsANoOpTheSecondTime() throws Exception {
        request(member, c).andExpect(status().isOk());
        approve(owner, c, member).andExpect(status().isOk());
        approve(accessMod, c, member).andExpect(status().isOk());
        assertEquals(1, logged(c, "posting_approval_approved"));
        assertEquals(1, events(member, "posting_decision"));
        assertEquals(owner, jdbc.queryForObject("SELECT responded_by FROM posting_approval_requests WHERE user_id = ?", UUID.class, member), "the first decision stands");
    }

    // ================= denying =================

    @Test
    void denyingGrantsNothingAndTheRequesterMayAskAgain() throws Exception {
        request(member, c).andExpect(status().isOk());
        deny(owner, c, member).andExpect(status().isOk());
        assertEquals(1, rows(c, member, "denied"));
        assertFalse(approvedSubmitter(c, member));
        submit(member, c).andExpect(status().isForbidden());
        assertEquals(1, logged(c, "posting_approval_denied"));
        assertEquals(1, events(member, "posting_decision"));
        request(member, c).andExpect(status().isOk());
        assertEquals(1, rows(c, member, "pending"));
        assertEquals(1, rows(c, member, "denied"), "history is kept");
        assertEquals(2, logged(c, "posting_approval_requested"));
        assertEquals(1, logged(c, "posting_approval_denied"));
    }

    @Test
    void denyingTwiceIsANoOpTheSecondTime() throws Exception {
        request(member, c).andExpect(status().isOk());
        deny(owner, c, member).andExpect(status().isOk());
        deny(owner, c, member).andExpect(status().isOk());
        assertEquals(1, logged(c, "posting_approval_denied"));
        assertEquals(1, events(member, "posting_decision"));
    }

    // ================= settled requests stay settled =================

    @Test
    void aDeniedRequestCannotBeApprovedByAccident() throws Exception {
        request(member, c).andExpect(status().isOk());
        deny(owner, c, member).andExpect(status().isOk());
        approve(owner, c, member).andExpect(status().isConflict());
        assertFalse(approvedSubmitter(c, member));
        assertEquals(1, rows(c, member, "denied"));
    }

    @Test
    void denyingAfterApprovalKeepsTheApproval() throws Exception {
        request(member, c).andExpect(status().isOk());
        approve(owner, c, member).andExpect(status().isOk());
        deny(owner, c, member).andExpect(status().isConflict());
        assertTrue(approvedSubmitter(c, member));
        assertEquals(1, rows(c, member, "approved"));
        submit(member, c).andExpect(status().isOk());
    }

    @Test
    void nothingIsPendingToCancelOnceDecided() throws Exception {
        request(member, c).andExpect(status().isOk());
        approve(owner, c, member).andExpect(status().isOk());
        cancel(member, c).andExpect(status().isConflict());
        request(other, c).andExpect(status().isOk());
        deny(owner, c, other).andExpect(status().isOk());
        cancel(other, c).andExpect(status().isConflict());
        cancel(banMod, c).andExpect(status().isConflict());
        assertEquals(0, logged(c, "posting_approval_cancelled"));
    }

    @Test
    void approvingWithNoRequestIs404AndUnknownUsersAre404() throws Exception {
        approve(owner, c, member).andExpect(status().isNotFound());
        approve(owner, c, UUID.randomUUID()).andExpect(status().isNotFound());
        deny(owner, c, UUID.randomUUID()).andExpect(status().isNotFound());
        assertEquals(0, logged(c, "posting_approval_approved"));
    }

    @Test
    void anAccountDeactivatedMeanwhileCannotBeApprovedAndStaysPending() throws Exception {
        request(member, c).andExpect(status().isOk());
        jdbc.update("UPDATE users SET status = 'deleted' WHERE id = ?", member);
        em.clear();
        approve(owner, c, member).andExpect(status().isNotFound());
        assertEquals(1, rows(c, member, "pending"));
        assertFalse(approvedSubmitter(c, member));
    }

    // ================= cancelling =================

    @Test
    void theRequesterCanCancelAndAskAgain() throws Exception {
        request(member, c).andExpect(status().isOk());
        cancel(member, c).andExpect(status().isOk());
        assertEquals(1, rows(c, member, "cancelled"));
        assertEquals(1, logged(c, "posting_approval_cancelled"));
        list(owner, c).andExpect(jsonPath("$.length()").value(0));
        cancel(member, c).andExpect(status().isConflict());
        request(member, c).andExpect(status().isOk());
        assertEquals(1, rows(c, member, "pending"));
    }

    @Test
    void youCannotCancelSomeoneElsesRequest() throws Exception {
        request(member, c).andExpect(status().isOk());
        cancel(other, c).andExpect(status().isConflict());
        assertEquals(1, rows(c, member, "pending"));
    }

    // ================= isolation, deleted communities, leaving =================

    @Test
    void requestsAndDecisionsStayInTheirOwnCommunity() throws Exception {
        Community b = restricted();
        request(member, c).andExpect(status().isOk());
        approve(owner, b, member).andExpect(status().isNotFound());
        assertFalse(approvedSubmitter(b, member));
        list(owner, b).andExpect(jsonPath("$.length()").value(0));
        Community theirs = fx.community(other);
        jdbc.update("UPDATE communities SET type = 'restricted' WHERE id = ?", theirs.getId());
        em.flush();
        em.clear();
        list(other, c).andExpect(status().isForbidden());
        approve(other, c, member).andExpect(status().isForbidden());
        list(other, theirs).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        approve(accessMod, b, member).andExpect(status().isNotFound());
    }

    @Test
    void aDeletedCommunityAnswers404ForEveryRoute() throws Exception {
        request(member, c).andExpect(status().isOk());
        jdbc.update("UPDATE communities SET deleted_at = now(), deleted_by = ? WHERE id = ?", owner, c.getId());
        em.clear();
        request(other, c).andExpect(status().isNotFound());
        cancel(member, c).andExpect(status().isNotFound());
        list(owner, c).andExpect(status().isNotFound());
        approve(owner, c, member).andExpect(status().isNotFound());
        deny(owner, c, member).andExpect(status().isNotFound());
    }

    @Test
    void leavingTheCommunityDoesNotWithdrawARequestBecauseMembershipIsSeparate() throws Exception {
        service.join(member, c.getId());
        em.flush();
        request(member, c).andExpect(status().isOk());
        service.leave(member, c.getId());
        em.flush();
        assertEquals(1, rows(c, member, "pending"));
        list(owner, c).andExpect(jsonPath("$.length()").value(1));
        approve(owner, c, member).andExpect(status().isOk());
        assertFalse(isMember(c, member));
        submit(member, c).andExpect(status().isOk());
    }

    @Test
    void joiningARestrictedCommunityDoesNotGrantPosting() throws Exception {
        service.join(member, c.getId());
        em.flush();
        submit(member, c).andExpect(status().isForbidden());
    }

    // ================= what the community page is told =================

    private Object about(UUID viewer, String field) throws Exception {
        em.clear();
        return JsonPath.read(mvc.perform(get("/r/{n}/about", c.getName()).header("Authorization", token(viewer))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$." + field);
    }

    @Test
    void theAboutPageTracksTheViewersRequest() throws Exception {
        assertEquals(false, about(member, "postingRequestPending"));
        assertEquals(false, about(member, "canPost"));
        request(member, c).andExpect(status().isOk());
        assertEquals(true, about(member, "postingRequestPending"));
        assertEquals(false, about(other, "postingRequestPending"), "another person's request is not yours");
        approve(owner, c, member).andExpect(status().isOk());
        assertEquals(false, about(member, "postingRequestPending"));
        assertEquals(true, about(member, "canPost"));
    }

    // ================= mod log =================

    @Test
    void everyStateChangeIsLoggedWithTheAffectedUser() throws Exception {
        request(member, c).andExpect(status().isOk());
        approve(accessMod, c, member).andExpect(status().isOk());
        request(other, c).andExpect(status().isOk());
        deny(owner, c, other).andExpect(status().isOk());
        request(other, c).andExpect(status().isOk());
        cancel(other, c).andExpect(status().isOk());
        for (String action : List.of("posting_approval_requested", "posting_approval_approved", "posting_approval_denied", "posting_approval_cancelled")) {
            em.clear();
            String body = mvc.perform(get("/r/{n}/mod/actions?action={a}", c.getName(), action).header("Authorization", token(owner)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            List<String> targets = JsonPath.read(body, "$[*].targetUsername");
            assertFalse(targets.isEmpty(), action);
            assertTrue(targets.stream().allMatch(t -> t.equals(username(member)) || t.equals(username(other))), body);
        }
        String approved = mvc.perform(get("/r/{n}/mod/actions?action=posting_approval_approved", c.getName()).header("Authorization", token(owner)))
                .andReturn().getResponse().getContentAsString();
        assertEquals(username(accessMod), JsonPath.read(approved, "$[0].actorUsername"));
        assertEquals(username(member), JsonPath.read(approved, "$[0].targetUsername"));
    }
}
