package com.redditclone.community;

import com.jayway.jsonpath.JsonPath;
import com.redditclone.auth.JwtService;
import com.redditclone.auth.UserRepository;
import com.redditclone.common.ModerationAuditWriter;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The mod log resolves the affected user's name for user-targeted rows (targetUsername). This pins that the older actions still
// read exactly as before (same fields, same reason text), gain only the name where the target is a user, and that rows about
// anything else are unchanged.
@SpringBootTest
@Transactional
class ModLogTargetUsernameTest {

    @Autowired private WebApplicationContext webContext;
    @Autowired private CommunityService service;
    @Autowired private ModeratorInviteService invites;
    @Autowired private ModerationAuditWriter audit;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager em;
    @Autowired private JwtService jwt;
    @Autowired private UserRepository users;

    private MockMvc mvc;
    private UUID owner;
    private UUID member;
    private UUID invitee;
    private Community community;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        CommunityDeleteFixtures fx = new CommunityDeleteFixtures(jdbc, service, em);
        owner = fx.user("tu_owner");
        member = fx.user("tu_member");
        invitee = fx.user("tu_invitee");
        community = fx.community(owner);
        em.flush();
    }

    private String name(UUID id) {
        return users.findById(id).orElseThrow().getUsername();
    }

    private String log(String query) throws Exception {
        em.flush();
        em.clear();
        return mvc.perform(get("/r/{n}/mod/actions" + query, community.getName()).header("Authorization", "Bearer " + jwt.generateAccessToken(users.findById(owner).orElseThrow())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void aBanRowKeepsItsFieldsAndReasonAndNamesTheBannedUser() throws Exception {
        service.issueBan(owner, community.getId(), member, "spam links", null);
        service.liftBan(owner, community.getId(), member, "appeal accepted");
        String ban = log("?action=ban");
        assertEquals(1, ((List<?>) JsonPath.read(ban, "$")).size(), ban);
        assertEquals("ban", JsonPath.read(ban, "$[0].action"));
        assertEquals("user", JsonPath.read(ban, "$[0].targetType"));
        assertEquals(member.toString(), JsonPath.read(ban, "$[0].targetId"));
        assertEquals("spam links", JsonPath.read(ban, "$[0].reason"));
        assertEquals(name(owner), JsonPath.read(ban, "$[0].actorUsername"));
        assertEquals(name(member), JsonPath.read(ban, "$[0].targetUsername"));
        assertEquals(name(member), JsonPath.read(log("?action=unban"), "$[0].targetUsername"));
    }

    @Test
    void invitationRowsKeepTheirMeaningAndNameTheInvitee() throws Exception {
        var invite = invites.send(owner, community.getId(), name(invitee), CommunityModerator.PERM_BAN_USERS);
        invites.accept(invitee, invite.getId());
        String sent = log("?action=invite_moderator");
        assertEquals(name(owner), JsonPath.read(sent, "$[0].actorUsername"));
        assertEquals(name(invitee), JsonPath.read(sent, "$[0].targetUsername"));
        String accepted = log("?action=accept_moderator_invite");
        assertEquals(name(invitee), JsonPath.read(accepted, "$[0].actorUsername"), "the invitee is the actor of their own acceptance");
        assertEquals(name(invitee), JsonPath.read(accepted, "$[0].targetUsername"));
    }

    @Test
    void rowsAboutAnythingOtherThanAUserAreUnchanged() throws Exception {
        service.addFlair(owner, community.getId(), "News", "#aabbcc", "post");
        String flair = log("?action=create_flair");
        assertEquals("flair", JsonPath.read(flair, "$[0].targetType"));
        assertNull(JsonPath.read(flair, "$[0].targetUsername"), flair);
        assertEquals(name(owner), JsonPath.read(flair, "$[0].actorUsername"));
    }

    @Test
    void aUserTargetThatCannotBeResolvedStillRendersWithoutAName() throws Exception {
        UUID ghost = UUID.randomUUID();
        audit.logAction(community.getId(), owner, "ban", "user", ghost, "gone");
        String body = log("?action=ban");
        assertEquals(ghost.toString(), JsonPath.read(body, "$[0].targetId"));
        assertNull(JsonPath.read(body, "$[0].targetUsername"));
        assertEquals("gone", JsonPath.read(body, "$[0].reason"));
    }

    @Test
    void theLogShapeOnlyGainedTheOneField() throws Exception {
        service.issueBan(owner, community.getId(), member, "x", null);
        Map<String, Object> row = JsonPath.read(log(""), "$[0]");
        assertEquals(Set.of("id", "communityId", "actorId", "actorUsername", "action", "targetType", "targetId", "targetUsername", "reason", "createdAt"), row.keySet());
    }

    @Test
    void filteringByActionAndTargetTypeStillWorks() throws Exception {
        service.issueBan(owner, community.getId(), member, "x", null);
        service.addFlair(owner, community.getId(), "Tag", "#aabbcc", "post");
        assertEquals(List.of("ban"), JsonPath.read(log("?action=ban"), "$[*].action"));
        assertEquals(List.of("create_flair"), JsonPath.read(log("?targetType=flair"), "$[*].action"));
        assertTrue(((List<?>) JsonPath.read(log(""), "$")).size() >= 2);
    }
}
