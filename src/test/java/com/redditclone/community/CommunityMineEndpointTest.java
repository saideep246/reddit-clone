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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// GET /r/mine: the caller's joined, live communities (Manage communities page). Shared Spring context, rolled-back transactions.
@SpringBootTest
@Transactional
class CommunityMineEndpointTest {

    @Autowired private WebApplicationContext webContext;
    @Autowired private CommunityService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager em;
    @Autowired private JwtService jwt;
    @Autowired private UserRepository users;

    private MockMvc mvc;
    private CommunityDeleteFixtures fx;
    private UUID creator;
    private UUID me;
    private UUID other;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        fx = new CommunityDeleteFixtures(jdbc, service, em);
        creator = fx.user("mine_creator");
        me = fx.user("mine_me");
        other = fx.user("mine_other");
    }

    private String token(UUID id) {
        return "Bearer " + jwt.generateAccessToken(users.findById(id).orElseThrow());
    }

    private Community joined(UUID user, Community c) {
        service.join(user, c.getId());
        em.flush();
        return c;
    }

    @Test
    void listsOnlyTheCallersJoinedLiveCommunitiesWithMembershipFlagged() throws Exception {
        Community a = joined(me, fx.community(creator));
        Community b = joined(me, fx.community(creator));
        Community notJoined = fx.community(creator);
        Community othersOnly = joined(other, fx.community(creator));
        Community deleted = joined(me, fx.community(creator));
        jdbc.update("UPDATE communities SET deleted_at = now(), deleted_by = ? WHERE id = ?", creator, deleted.getId());
        em.flush();
        em.clear();

        String body = mvc.perform(get("/r/mine").header("Authorization", token(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].isMember").value(true))
                .andReturn().getResponse().getContentAsString();

        List<UUID> ids = service.listJoined(me).stream().map(Community::getId).toList();
        assertTrue(ids.containsAll(List.of(a.getId(), b.getId())), "both joined communities are listed");
        assertEquals(2, ids.size(), "nothing else is listed");
        for (Community excluded : List.of(notJoined, othersOnly, deleted)) {
            assertTrue(!ids.contains(excluded.getId()) && !body.contains(excluded.getName()), excluded.getName() + " must not be listed");
        }
        assertTrue(!body.contains("deleted"), "no deletion fields leak into the JSON");
    }

    @Test
    void isAlphabeticalAndEmptyForAUserWhoJoinedNothing() throws Exception {
        mvc.perform(get("/r/mine").header("Authorization", token(other))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        Community first = joined(me, fx.community(creator));
        Community second = joined(me, fx.community(creator));
        List<String> names = service.listJoined(me).stream().map(Community::getName).toList();
        assertEquals(names.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList(), names);
        assertTrue(names.contains(first.getName()) && names.contains(second.getName()));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/r/mine")).andExpect(status().isUnauthorized());
    }
}
