package com.redditclone.community;

import com.redditclone.auth.JwtService;
import com.redditclone.auth.UserRepository;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// GET /r/{name}/mod/moderators: the Moderators tab's data. Shared Spring context, rolled-back transactions.
@SpringBootTest
@Transactional
class ModeratorsListEndpointTest {

    @Autowired private WebApplicationContext webContext;
    @Autowired private CommunityService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager em;
    @Autowired private JwtService jwt;
    @Autowired private UserRepository users;

    private MockMvc mvc;
    private CommunityDeleteFixtures fx;
    private UUID owner;
    private UUID modWithAll;
    private UUID modBanOnly;
    private UUID member;
    private Community community;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        fx = new CommunityDeleteFixtures(jdbc, service, em);
        owner = fx.user("ml_owner");
        modWithAll = fx.user("ml_modall");
        modBanOnly = fx.user("ml_modban");
        member = fx.user("ml_member");
        community = fx.community(owner);
        service.addModerator(owner, community.getId(), modWithAll, CommunityModerator.PERM_MANAGE_MODERATORS | CommunityModerator.PERM_BAN_USERS);
        service.addModerator(owner, community.getId(), modBanOnly, CommunityModerator.PERM_BAN_USERS);
        em.flush();
    }

    private String token(UUID id) {
        return "Bearer " + jwt.generateAccessToken(users.findById(id).orElseThrow());
    }

    @Test
    void ownerSeesEveryModeratorWithPermissionsAndTheOwnerFirst() throws Exception {
        mvc.perform(get("/r/{n}/mod/moderators", community.getName()).header("Authorization", token(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].owner").value(true))
                .andExpect(jsonPath("$[0].userId").value(owner.toString()))
                .andExpect(jsonPath("$[0].username").value(users.findById(owner).orElseThrow().getUsername()));

        List<ModeratorSnapshot> rows = service.listModerators(owner, community.getId()).stream()
                .map(m -> new ModeratorSnapshot(m.userId(), m.permissions(), m.owner())).toList();
        assertEquals(owner, rows.get(0).userId());
        assertEquals(CommunityModerator.OWNER_PERMISSIONS, rows.get(0).permissions());
        assertTrue(rows.stream().anyMatch(r -> r.userId().equals(modBanOnly) && r.permissions() == CommunityModerator.PERM_BAN_USERS && !r.owner()));
        assertEquals(1, rows.stream().filter(ModeratorSnapshot::owner).count(), "exactly one owner row");
    }

    @Test
    void anyModeratorCanSeeTheListEvenWithoutTheManagePermission() throws Exception {
        mvc.perform(get("/r/{n}/mod/moderators", community.getName()).header("Authorization", token(modBanOnly)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void nonModeratorsAnonymousAndOtherCommunitiesModeratorsAreRefused() throws Exception {
        mvc.perform(get("/r/{n}/mod/moderators", community.getName()).header("Authorization", token(member))).andExpect(status().isForbidden());
        mvc.perform(get("/r/{n}/mod/moderators", community.getName())).andExpect(status().isUnauthorized());
        Community other = fx.community(fx.user("ml_otherowner"));
        UUID otherOwner = other.getCreatorId();
        em.flush();
        mvc.perform(get("/r/{n}/mod/moderators", community.getName()).header("Authorization", token(otherOwner))).andExpect(status().isForbidden());
    }

    @Test
    void theListDoesNotLeakAnotherCommunitysModerators() {
        Community other = fx.community(fx.user("ml_other2"));
        em.flush();
        List<UUID> ids = service.listModerators(owner, community.getId()).stream().map(m -> m.userId()).toList();
        assertFalse(ids.contains(other.getCreatorId()));
    }

    @Test
    void aDeletedCommunityIs404() throws Exception {
        jdbc.update("UPDATE communities SET deleted_at = now(), deleted_by = ? WHERE id = ?", owner, community.getId());
        em.flush();
        em.clear();
        mvc.perform(get("/r/{n}/mod/moderators", community.getName()).header("Authorization", token(owner))).andExpect(status().isNotFound());
    }

    @Test
    void listReflectsAddAndRemove() {
        UUID extra = fx.user("ml_extra");
        service.addModerator(owner, community.getId(), extra, CommunityModerator.PERM_REMOVE_CONTENT);
        em.flush();
        assertTrue(service.listModerators(owner, community.getId()).stream().anyMatch(m -> m.userId().equals(extra)));
        service.removeModerator(owner, community.getId(), extra);
        em.flush();
        assertFalse(service.listModerators(owner, community.getId()).stream().anyMatch(m -> m.userId().equals(extra)));
        assertThrows(RuntimeException.class, () -> service.listModerators(extra, community.getId()), "a removed moderator can no longer read it");
    }

    private record ModeratorSnapshot(UUID userId, int permissions, boolean owner) {
    }
}
