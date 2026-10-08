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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Who may post in a restricted community (approved posters) and how a private community admits people (join requests), end to
// end over HTTP. Shared Spring context, rolled-back transactions; every write is flushed so JDBC assertions see it.
@SpringBootTest
@Transactional
class CommunityAccessApprovalTest {

    @Autowired private WebApplicationContext webContext;
    @Autowired private CommunityService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager em;
    @Autowired private JwtService jwt;
    @Autowired private UserRepository users;

    private MockMvc mvc;
    private CommunityDeleteFixtures fx;
    private UUID owner;
    private UUID accessMod;   // MANAGE_ACCESS
    private UUID banMod;      // BAN_USERS only
    private UUID member;      // the person being approved
    private UUID other;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        fx = new CommunityDeleteFixtures(jdbc, service, em);
        owner = fx.user("ac_owner");
        accessMod = fx.user("ac_accessmod");
        banMod = fx.user("ac_banmod");
        member = fx.user("ac_member");
        other = fx.user("ac_other");
    }

    private Community community(String type) {
        Community c = fx.community(owner);
        service.addModerator(owner, c.getId(), accessMod, CommunityModerator.PERM_MANAGE_ACCESS);
        service.addModerator(owner, c.getId(), banMod, CommunityModerator.PERM_BAN_USERS);
        jdbc.update("UPDATE communities SET type = ? WHERE id = ?", type, c.getId());
        em.flush();
        em.clear();
        return c;
    }

    private String token(UUID id) {
        return "Bearer " + jwt.generateAccessToken(users.findById(id).orElseThrow());
    }

    private ResultActions submit(UUID who, Community c) throws Exception {
        ResultActions r = mvc.perform(post("/r/{n}/submit", c.getName()).header("Authorization", token(who))
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"text\",\"title\":\"hello\",\"body\":\"body\"}"));
        em.flush();
        return r;
    }

    private ResultActions approve(UUID actor, Community c, UUID target) throws Exception {
        ResultActions r = mvc.perform(post("/r/{n}/mod/approved-submitters", c.getName()).header("Authorization", token(actor))
                .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":\"" + target + "\"}"));
        em.flush();
        return r;
    }

    private ResultActions unapprove(UUID actor, Community c, UUID target) throws Exception {
        ResultActions r = mvc.perform(delete("/r/{n}/mod/approved-submitters/{u}", c.getName(), target).header("Authorization", token(actor)));
        em.flush();
        em.clear();
        return r;
    }

    private int approvedRows(Community c, UUID user) {
        return jdbc.queryForObject("SELECT count(*) FROM community_approved_submitters WHERE community_id = ? AND user_id = ?", Integer.class, c.getId(), user);
    }

    private boolean isMember(Community c, UUID user) {
        return jdbc.queryForObject("SELECT count(*) FROM memberships WHERE community_id = ? AND user_id = ?", Integer.class, c.getId(), user) > 0;
    }

    private String username(UUID id) {
        return users.findById(id).orElseThrow().getUsername();
    }

    // ================= restricted: approved posters =================

    @Test
    void anUnapprovedPersonCannotPostInARestrictedCommunityEvenAfterJoining() throws Exception {
        Community c = community("restricted");
        service.join(member, c.getId());
        em.flush();
        submit(member, c).andExpect(status().isForbidden());
        submit(other, c).andExpect(status().isForbidden());
    }

    @Test
    void approvingSomeoneLetsThemPostAndRemovingThemStopsIt() throws Exception {
        Community c = community("restricted");
        submit(member, c).andExpect(status().isForbidden());

        approve(owner, c, member).andExpect(status().isOk());
        assertEquals(1, approvedRows(c, member));
        submit(member, c).andExpect(status().isOk());
        submit(other, c).andExpect(status().isForbidden()); // approval is per person

        unapprove(owner, c, member).andExpect(status().isOk());
        assertEquals(0, approvedRows(c, member));
        submit(member, c).andExpect(status().isForbidden());
    }

    @Test
    void approvingTwiceAndRemovingTwiceAreHarmless() throws Exception {
        Community c = community("restricted");
        approve(owner, c, member).andExpect(status().isOk());
        approve(owner, c, member).andExpect(status().isOk());
        assertEquals(1, approvedRows(c, member));
        unapprove(owner, c, member).andExpect(status().isOk());
        unapprove(owner, c, member).andExpect(status().isOk());
    }

    @Test
    void theListShowsWhoWasApprovedByWhomAndIsOnlyForManageAccess() throws Exception {
        Community c = community("restricted");
        approve(owner, c, member).andExpect(status().isOk());
        approve(accessMod, c, other).andExpect(status().isOk()); // a moderator with Manage access can approve too
        String body = mvc.perform(get("/r/{n}/mod/approved-submitters", c.getName()).header("Authorization", token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2)).andReturn().getResponse().getContentAsString();
        List<String> names = JsonPath.read(body, "$[*].username");
        assertTrue(names.containsAll(List.of(username(member), username(other))), body);
        List<String> approvers = JsonPath.read(body, "$[?(@.username=='" + username(other) + "')].approvedByUsername");
        assertEquals(List.of(username(accessMod)), approvers);

        mvc.perform(get("/r/{n}/mod/approved-submitters", c.getName()).header("Authorization", token(accessMod))).andExpect(status().isOk());
        mvc.perform(get("/r/{n}/mod/approved-submitters", c.getName()).header("Authorization", token(banMod))).andExpect(status().isForbidden());
        mvc.perform(get("/r/{n}/mod/approved-submitters", c.getName()).header("Authorization", token(member))).andExpect(status().isForbidden());
        mvc.perform(get("/r/{n}/mod/approved-submitters", c.getName())).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyManageAccessCanApproveOrRemove() throws Exception {
        Community c = community("restricted");
        approve(banMod, c, member).andExpect(status().isForbidden());
        approve(member, c, other).andExpect(status().isForbidden());
        assertEquals(0, approvedRows(c, member));
        approve(owner, c, member).andExpect(status().isOk());
        unapprove(banMod, c, member).andExpect(status().isForbidden());
        assertEquals(1, approvedRows(c, member));
        mvc.perform(post("/r/{n}/mod/approved-submitters", c.getName()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + member + "\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void anUnknownOrInactiveAccountCannotBeApproved() throws Exception {
        Community c = community("restricted");
        approve(owner, c, UUID.randomUUID()).andExpect(status().isNotFound());
        UUID banned = fx.bannedUser();
        em.flush();
        approve(owner, c, banned).andExpect(status().isNotFound());
        assertEquals(0, approvedRows(c, banned));
    }

    @Test
    void approvalInOneCommunityDoesNotCarryToAnother() throws Exception {
        Community a = community("restricted");
        Community b = community("restricted");
        approve(owner, a, member).andExpect(status().isOk());
        submit(member, a).andExpect(status().isOk());
        submit(member, b).andExpect(status().isForbidden());
        mvc.perform(get("/r/{n}/mod/approved-submitters", b.getName()).header("Authorization", token(owner)))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void moderatorsPostWithoutApprovalAndPublicCommunitiesNeedNone() throws Exception {
        Community c = community("restricted");
        submit(owner, c).andExpect(status().isOk());
        submit(banMod, c).andExpect(status().isOk());
        Community open = community("public");
        submit(member, open).andExpect(status().isOk());
    }

    @Test
    void aDeletedRestrictedCommunityAnswers404() throws Exception {
        Community c = community("restricted");
        jdbc.update("UPDATE communities SET deleted_at = now(), deleted_by = ? WHERE id = ?", owner, c.getId());
        em.clear();
        mvc.perform(get("/r/{n}/mod/approved-submitters", c.getName()).header("Authorization", token(owner))).andExpect(status().isNotFound());
    }

    // ================= restricted: mod log =================

    private String modLog(UUID viewer, Community c, String action) throws Exception {
        return mvc.perform(get("/r/{n}/mod/actions?action={a}", c.getName(), action).header("Authorization", token(viewer)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void approvingAndRemovingASubmitterAreLoggedWithActorAndAffectedUser() throws Exception {
        Community c = community("restricted");
        approve(owner, c, member).andExpect(status().isOk());
        String added = modLog(owner, c, "approved_submitter_added");
        assertEquals(1, ((List<?>) JsonPath.read(added, "$")).size(), added);
        assertEquals(username(owner), JsonPath.read(added, "$[0].actorUsername"));
        assertEquals(username(member), JsonPath.read(added, "$[0].targetUsername"));
        assertEquals(member.toString(), JsonPath.read(added, "$[0].targetId"));
        assertEquals("user", JsonPath.read(added, "$[0].targetType"));
        assertEquals(c.getId().toString(), JsonPath.read(added, "$[0].communityId"));
        assertTrue(((String) JsonPath.read(added, "$[0].createdAt")).length() > 10, "has a timestamp");

        unapprove(owner, c, member).andExpect(status().isOk());
        String removed = modLog(owner, c, "approved_submitter_removed");
        assertEquals(1, ((List<?>) JsonPath.read(removed, "$")).size(), removed);
        assertEquals(username(owner), JsonPath.read(removed, "$[0].actorUsername"));
        assertEquals(username(member), JsonPath.read(removed, "$[0].targetUsername"));
    }

    @Test
    void theLogNamesTheModeratorWhoActed() throws Exception {
        Community c = community("restricted");
        approve(accessMod, c, other).andExpect(status().isOk());
        assertEquals(username(accessMod), JsonPath.read(modLog(owner, c, "approved_submitter_added"), "$[0].actorUsername"));
    }

    @Test
    void repeatedOrNoOpRequestsDoNotPadTheLog() throws Exception {
        Community c = community("restricted");
        approve(owner, c, member).andExpect(status().isOk());
        approve(owner, c, member).andExpect(status().isOk());
        unapprove(owner, c, member).andExpect(status().isOk());
        unapprove(owner, c, member).andExpect(status().isOk());
        assertEquals(1, ((List<?>) JsonPath.read(modLog(owner, c, "approved_submitter_added"), "$")).size());
        assertEquals(1, ((List<?>) JsonPath.read(modLog(owner, c, "approved_submitter_removed"), "$")).size());
    }

    @Test
    void refusedAttemptsWriteNoLogEntry() throws Exception {
        Community c = community("restricted");
        approve(banMod, c, member).andExpect(status().isForbidden());
        approve(member, c, other).andExpect(status().isForbidden());
        approve(owner, c, UUID.randomUUID()).andExpect(status().isNotFound());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM moderation_actions WHERE community_id = ? AND action LIKE 'approved_submitter%'", Integer.class, c.getId()));
        approve(owner, c, member).andExpect(status().isOk());
        unapprove(banMod, c, member).andExpect(status().isForbidden());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM moderation_actions WHERE community_id = ? AND action = 'approved_submitter_removed'", Integer.class, c.getId()));
    }

    @Test
    void logEntriesStayInTheirOwnCommunity() throws Exception {
        Community a = community("restricted");
        Community b = community("restricted");
        approve(owner, a, member).andExpect(status().isOk());
        approve(owner, b, other).andExpect(status().isOk());
        String inA = modLog(owner, a, "approved_submitter_added");
        String inB = modLog(owner, b, "approved_submitter_added");
        assertEquals(List.of(username(member)), JsonPath.read(inA, "$[*].targetUsername"));
        assertEquals(List.of(username(other)), JsonPath.read(inB, "$[*].targetUsername"));
        assertEquals(0, ((List<?>) JsonPath.read(modLog(owner, b, "approved_submitter_removed"), "$")).size());
    }

    @Test
    void theLogIsReadableOnlyByModeratorsOfThatCommunity() throws Exception {
        Community c = community("restricted");
        approve(owner, c, member).andExpect(status().isOk());
        mvc.perform(get("/r/{n}/mod/actions", c.getName()).header("Authorization", token(member))).andExpect(status().isForbidden());
        mvc.perform(get("/r/{n}/mod/actions", c.getName())).andExpect(status().isUnauthorized());
        Community theirs = fx.community(other); // a different owner with a community of their own
        em.flush();
        mvc.perform(get("/r/{n}/mod/actions", c.getName()).header("Authorization", token(other))).andExpect(status().isForbidden());
        mvc.perform(get("/r/{n}/mod/actions", theirs.getName()).header("Authorization", token(other))).andExpect(status().isOk());
    }

    // ================= what the community page is told about posting =================

    private Object canPost(UUID viewer, Community c) throws Exception {
        em.clear(); // each request gets a fresh view of the community, as it does in production (one session per request)
        ResultActions r = viewer == null ? mvc.perform(get("/r/{n}/about", c.getName()))
                : mvc.perform(get("/r/{n}/about", c.getName()).header("Authorization", token(viewer)));
        return JsonPath.read(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.canPost");
    }

    @Test
    void theAboutPageSaysWhoCanPostInARestrictedCommunity() throws Exception {
        Community c = community("restricted");
        assertEquals(false, canPost(member, c));
        assertEquals(true, canPost(owner, c));
        assertEquals(true, canPost(banMod, c), "any moderator can post");
        assertEquals(null, canPost(null, c), "anonymous viewers get no verdict");
        approve(owner, c, member).andExpect(status().isOk());
        assertEquals(true, canPost(member, c));
        unapprove(owner, c, member).andExpect(status().isOk());
        assertEquals(false, canPost(member, c));
    }

    @Test
    void thePostingFlagMatchesTheRuleForPublicAndPrivateToo() throws Exception {
        assertEquals(true, canPost(member, community("public")));
        Community priv = community("private");
        assertEquals(false, canPost(member, priv));
        assertEquals(true, canPost(owner, priv));
        mvc.perform(post("/r/{n}/join-requests", priv.getName()).header("Authorization", token(member))).andExpect(status().isOk());
        em.flush();
        mvc.perform(post("/r/{n}/mod/join-requests/{u}/approve", priv.getName(), member).header("Authorization", token(owner))).andExpect(status().isOk());
        em.flush();
        em.clear();
        assertEquals(true, canPost(member, priv));
    }

    @Test
    void theFlagNeverGrantsAnythingTheServerStillRefuses() throws Exception {
        Community c = community("restricted");
        assertEquals(false, canPost(member, c));
        submit(member, c).andExpect(status().isForbidden());
    }

    // ================= private: join requests =================

    @Test
    void aPrivateCommunityKeepsOutsidersUntilARequestIsApproved() throws Exception {
        Community c = community("private");
        assertThrows(RuntimeException.class, () -> service.requireViewAccess(member, c.getId()));
        submit(member, c).andExpect(status().isForbidden());
        assertThrows(RuntimeException.class, () -> service.join(member, c.getId())); // no direct join

        mvc.perform(post("/r/{n}/join-requests", c.getName()).header("Authorization", token(member))).andExpect(status().isOk());
        em.flush();
        assertFalse(isMember(c, member), "asking is not joining");
        submit(member, c).andExpect(status().isForbidden());

        mvc.perform(get("/r/{n}/mod/join-requests", c.getName()).header("Authorization", token(accessMod)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].userId").value(member.toString())).andExpect(jsonPath("$[0].username").value(username(member)));

        int before = jdbc.queryForObject("SELECT subscriber_count FROM communities WHERE id = ?", Integer.class, c.getId());
        mvc.perform(post("/r/{n}/mod/join-requests/{u}/approve", c.getName(), member).header("Authorization", token(accessMod))).andExpect(status().isOk());
        em.flush();
        em.clear();
        assertTrue(isMember(c, member));
        assertEquals(before + 1, jdbc.queryForObject("SELECT subscriber_count FROM communities WHERE id = ?", Integer.class, c.getId()));
        service.requireViewAccess(member, c.getId());
        submit(member, c).andExpect(status().isOk());
        mvc.perform(get("/r/{n}/mod/join-requests", c.getName()).header("Authorization", token(owner))).andExpect(jsonPath("$.length()").value(0));
        submit(other, c).andExpect(status().isForbidden());
    }

    @Test
    void aDeniedRequestStaysOutAndCanBeAskedAgain() throws Exception {
        Community c = community("private");
        mvc.perform(post("/r/{n}/join-requests", c.getName()).header("Authorization", token(member))).andExpect(status().isOk());
        em.flush();
        mvc.perform(post("/r/{n}/mod/join-requests/{u}/deny", c.getName(), member).header("Authorization", token(owner))).andExpect(status().isOk());
        em.flush();
        em.clear();
        assertFalse(isMember(c, member));
        submit(member, c).andExpect(status().isForbidden());
        mvc.perform(get("/r/{n}/mod/join-requests", c.getName()).header("Authorization", token(owner))).andExpect(jsonPath("$.length()").value(0));

        mvc.perform(post("/r/{n}/join-requests", c.getName()).header("Authorization", token(member))).andExpect(status().isOk());
        em.flush();
        em.clear();
        mvc.perform(get("/r/{n}/mod/join-requests", c.getName()).header("Authorization", token(owner))).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void onlyManageAccessHandlesJoinRequests() throws Exception {
        Community c = community("private");
        mvc.perform(post("/r/{n}/join-requests", c.getName()).header("Authorization", token(member))).andExpect(status().isOk());
        em.flush();
        mvc.perform(get("/r/{n}/mod/join-requests", c.getName()).header("Authorization", token(banMod))).andExpect(status().isForbidden());
        mvc.perform(post("/r/{n}/mod/join-requests/{u}/approve", c.getName(), member).header("Authorization", token(banMod))).andExpect(status().isForbidden());
        mvc.perform(post("/r/{n}/mod/join-requests/{u}/approve", c.getName(), member).header("Authorization", token(other))).andExpect(status().isForbidden());
        mvc.perform(post("/r/{n}/mod/join-requests/{u}/deny", c.getName(), member).header("Authorization", token(banMod))).andExpect(status().isForbidden());
        assertFalse(isMember(c, member));
    }

    @Test
    void askingToJoinANonPrivateCommunityIsRefused() throws Exception {
        Community c = community("restricted");
        mvc.perform(post("/r/{n}/join-requests", c.getName()).header("Authorization", token(member))).andExpect(status().isBadRequest());
    }

    @Test
    void anAlreadyApprovedMemberAskingAgainChangesNothing() throws Exception {
        Community c = community("private");
        mvc.perform(post("/r/{n}/join-requests", c.getName()).header("Authorization", token(member))).andExpect(status().isOk());
        em.flush();
        mvc.perform(post("/r/{n}/mod/join-requests/{u}/approve", c.getName(), member).header("Authorization", token(owner))).andExpect(status().isOk());
        em.flush();
        em.clear();
        mvc.perform(post("/r/{n}/join-requests", c.getName()).header("Authorization", token(member))).andExpect(status().isOk());
        em.flush();
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM memberships WHERE community_id = ? AND user_id = ?", Integer.class, c.getId(), member));
    }
}
