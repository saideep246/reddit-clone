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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// A pending posting request must not outlive the community being restricted: changing the type closes every pending request as
// cancelled (history kept), so nothing comes back when the community is restricted again. Other state is left alone.
@SpringBootTest
@Transactional
class PostingApprovalTypeChangeTest {

    @Autowired private WebApplicationContext webContext;
    @Autowired private CommunityService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager em;
    @Autowired private JwtService jwt;
    @Autowired private UserRepository users;

    private MockMvc mvc;
    private CommunityDeleteFixtures fx;
    private UUID owner;
    private UUID accessMod;
    private UUID member;
    private UUID other;
    private Community c;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        fx = new CommunityDeleteFixtures(jdbc, service, em);
        owner = fx.user("tc_owner");
        accessMod = fx.user("tc_accessmod");
        member = fx.user("tc_member");
        other = fx.user("tc_other");
        c = community("restricted");
    }

    private Community community(String type) {
        Community x = fx.community(owner);
        service.addModerator(owner, x.getId(), accessMod, CommunityModerator.PERM_MANAGE_ACCESS);
        jdbc.update("UPDATE communities SET type = ? WHERE id = ?", type, x.getId());
        em.flush();
        em.clear();
        return x;
    }

    private String token(UUID id) {
        return "Bearer " + jwt.generateAccessToken(users.findById(id).orElseThrow());
    }

    private ResultActions flushed(ResultActions r) {
        em.flush();
        em.clear();
        return r;
    }

    private ResultActions setType(UUID who, Community x, String type) throws Exception {
        return flushed(mvc.perform(patch("/r/{n}/mod/type", x.getName()).header("Authorization", token(who))
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"" + type + "\"}")));
    }

    private ResultActions request(UUID who, Community x) throws Exception {
        return flushed(mvc.perform(post("/r/{n}/posting-requests", x.getName()).header("Authorization", token(who))));
    }

    private ResultActions approve(UUID who, Community x, UUID target) throws Exception {
        return flushed(mvc.perform(post("/r/{n}/mod/posting-requests/{u}/approve", x.getName(), target).header("Authorization", token(who))));
    }

    private ResultActions list(UUID who, Community x) throws Exception {
        return mvc.perform(get("/r/{n}/mod/posting-requests", x.getName()).header("Authorization", token(who)));
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private int rows(Community x, UUID user, String status) {
        return count("SELECT count(*) FROM posting_approval_requests WHERE community_id = ? AND user_id = ? AND status = ?", x.getId(), user, status);
    }

    @Test
    void restrictedToPublicClosesPendingRequestsAsCancelledAndLogsEach() throws Exception {
        request(member, c).andExpect(status().isOk());
        request(other, c).andExpect(status().isOk());
        setType(owner, c, "public").andExpect(status().isOk());
        assertEquals(0, rows(c, member, "pending") + rows(c, other, "pending"));
        assertEquals(1, rows(c, member, "cancelled"));
        assertEquals(1, rows(c, other, "cancelled"));
        assertEquals(2, count("SELECT count(*) FROM posting_approval_requests WHERE community_id = ? AND responded_at IS NOT NULL AND responded_by = ?", c.getId(), owner));
        assertEquals(2, count("SELECT count(*) FROM moderation_actions WHERE community_id = ? AND action = 'posting_approval_cancelled' AND actor_id = ? AND reason = 'community type changed'", c.getId(), owner));
        list(owner, c).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        approve(owner, c, member).andExpect(status().isConflict()); // no longer actionable
    }

    @Test
    void restrictedToPrivateClosesThemToo() throws Exception {
        request(member, c).andExpect(status().isOk());
        setType(owner, c, "private").andExpect(status().isOk());
        assertEquals(1, rows(c, member, "cancelled"));
        assertEquals(0, rows(c, member, "pending"));
        list(owner, c).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void goingBackToRestrictedDoesNotResurrectTheOldRequest() throws Exception {
        request(member, c).andExpect(status().isOk());
        setType(owner, c, "public").andExpect(status().isOk());
        setType(owner, c, "restricted").andExpect(status().isOk());
        list(owner, c).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        approve(owner, c, member).andExpect(status().isConflict());
        assertEquals(1, rows(c, member, "cancelled"), "the old request stays as history");
        assertEquals(0, rows(c, member, "pending"));
        assertEquals(0, count("SELECT count(*) FROM community_approved_submitters WHERE community_id = ? AND user_id = ?", c.getId(), member));
        em.clear();
        assertEquals(false, JsonPath.read(mvc.perform(get("/r/{n}/about", c.getName()).header("Authorization", token(member)))
                .andReturn().getResponse().getContentAsString(), "$.postingRequestPending"));
    }

    @Test
    void aFreshRequestWorksAfterReturningToRestricted() throws Exception {
        request(member, c).andExpect(status().isOk());
        setType(owner, c, "public").andExpect(status().isOk());
        setType(owner, c, "restricted").andExpect(status().isOk());
        request(member, c).andExpect(status().isOk());
        assertEquals(1, rows(c, member, "pending"));
        assertEquals(1, rows(c, member, "cancelled"), "history keeps the old one beside the new one");
        list(owner, c).andExpect(jsonPath("$.length()").value(1));
        approve(owner, c, member).andExpect(status().isOk());
        assertEquals(1, count("SELECT count(*) FROM community_approved_submitters WHERE community_id = ? AND user_id = ?", c.getId(), member));
    }

    @Test
    void whileNotRestrictedNewRequestsAreRefused() throws Exception {
        setType(owner, c, "public").andExpect(status().isOk());
        request(member, c).andExpect(status().isBadRequest());
    }

    @Test
    void approvedSubmittersAndTheirApprovedRequestsAreUntouched() throws Exception {
        request(member, c).andExpect(status().isOk());
        approve(owner, c, member).andExpect(status().isOk());
        request(other, c).andExpect(status().isOk());
        setType(owner, c, "public").andExpect(status().isOk());
        assertEquals(1, count("SELECT count(*) FROM community_approved_submitters WHERE community_id = ? AND user_id = ?", c.getId(), member));
        assertEquals(1, rows(c, member, "approved"));
        assertEquals(1, rows(c, other, "cancelled"));
        setType(owner, c, "restricted").andExpect(status().isOk());
        assertEquals(1, count("SELECT count(*) FROM community_approved_submitters WHERE community_id = ? AND user_id = ?", c.getId(), member));
        flushed(mvc.perform(post("/r/{n}/submit", c.getName()).header("Authorization", token(member)).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"text\",\"title\":\"still approved\",\"body\":\"b\"}"))).andExpect(status().isOk());
    }

    @Test
    void joinRequestsAndMembershipsAreUntouchedByTheChange() throws Exception {
        Community priv = community("private");
        flushed(mvc.perform(post("/r/{n}/join-requests", priv.getName()).header("Authorization", token(member)))).andExpect(status().isOk());
        service.join(other, community("public").getId());
        service.join(member, c.getId());
        em.flush();
        request(member, c).andExpect(status().isOk());
        int membersBefore = count("SELECT count(*) FROM memberships WHERE community_id = ?", c.getId());

        setType(owner, c, "private").andExpect(status().isOk());
        setType(owner, priv, "restricted").andExpect(status().isOk());

        assertEquals(1, count("SELECT count(*) FROM community_join_requests WHERE community_id = ? AND user_id = ? AND status = 'pending'", priv.getId(), member),
                "a private community's join request is not a posting request and is not cancelled");
        assertEquals(membersBefore, count("SELECT count(*) FROM memberships WHERE community_id = ?", c.getId()));
        assertEquals(1, count("SELECT count(*) FROM memberships WHERE community_id = ? AND user_id = ?", c.getId(), member));
        assertEquals(1, rows(c, member, "cancelled"));
    }

    @Test
    void sendingTheSameTypeChangesNothing() throws Exception {
        request(member, c).andExpect(status().isOk());
        setType(owner, c, "restricted").andExpect(status().isOk());
        assertEquals(1, rows(c, member, "pending"));
        assertEquals(0, count("SELECT count(*) FROM moderation_actions WHERE community_id = ? AND action = 'posting_approval_cancelled'", c.getId()));
    }

    @Test
    void onlyTheOwnerCanChangeTheTypeSoAManageAccessModeratorCancelsNothing() throws Exception {
        request(member, c).andExpect(status().isOk());
        setType(accessMod, c, "public").andExpect(status().isForbidden());
        assertEquals(1, rows(c, member, "pending"));
    }

    @Test
    void otherCommunitiesRequestsAreUnaffected() throws Exception {
        Community b = community("restricted");
        request(member, c).andExpect(status().isOk());
        request(member, b).andExpect(status().isOk());
        setType(owner, c, "public").andExpect(status().isOk());
        assertEquals(1, rows(b, member, "pending"));
        list(owner, b).andExpect(jsonPath("$.length()").value(1));
        assertTrue(rows(c, member, "cancelled") == 1);
        List<String> pendingNames = JsonPath.read(list(owner, b).andReturn().getResponse().getContentAsString(), "$[*].status");
        assertEquals(List.of("pending"), pendingNames);
    }
}
