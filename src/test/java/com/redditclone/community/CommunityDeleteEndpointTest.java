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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// DELETE /r/{name} through the real security chain and exception handler (real local PostgreSQL, rolled-back transactions).
// Covers the HTTP contract; who-may-delete and race behaviour are covered in the service and concurrency tests.
// Deliberately plain @SpringBootTest, with MockMvc built by hand below, so this class shares the ONE Spring context every
// other database test uses: each cached context holds its own pool of database connections, and the local PgBouncer only
// has 20 to give (the dev container already holds 10).
@SpringBootTest
@Transactional
class CommunityDeleteEndpointTest {

    @Autowired
    private WebApplicationContext webContext;
    private MockMvc mvc;
    @Autowired
    private CommunityService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager em;
    @Autowired
    private JwtService jwt;
    @Autowired
    private UserRepository users;

    private CommunityDeleteFixtures fx;
    private UUID creator;
    private Community community;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        fx = new CommunityDeleteFixtures(jdbc, service, em);
        creator = fx.user("cdowner");
        community = fx.community(creator);
    }

    private String tokenFor(UUID userId) {
        return "Bearer " + jwt.generateAccessToken(users.findById(userId).orElseThrow());
    }

    private MockHttpServletRequestBuilder deleteAs(UUID userId, String name, String body) {
        MockHttpServletRequestBuilder request = delete("/r/{name}", name).contentType(MediaType.APPLICATION_JSON);
        if (userId != null) {
            request = request.header("Authorization", tokenFor(userId));
        }
        return body == null ? request : request.content(body);
    }

    private static String confirm(String name) {
        return "{\"confirmName\":\"" + name + "\"}";
    }

    @Test
    void theCreatorGets204AndTheCommunityIsMarkedDeleted() throws Exception {
        var result = mvc.perform(deleteAs(creator, community.getName(), confirm(community.getName())))
                .andExpect(status().isNoContent())
                .andReturn();

        assertEquals("", result.getResponse().getContentAsString(), "204 has no body");
        assertTrue(fx.isDeleted(community.getId()));
        assertEquals(creator, fx.deletedBy(community.getId()));
        assertEquals(1, fx.auditRows(community.getId()));
    }

    @Test
    void theCommunityInThePathIsResolvedCaseInsensitively() throws Exception {
        // the URL segment resolves the community the way every /r/{name} endpoint does (names are case-insensitive)
        mvc.perform(deleteAs(creator, community.getName().toUpperCase(), confirm(community.getName())))
                .andExpect(status().isNoContent());
        assertTrue(fx.isDeleted(community.getId()));
    }

    @Test
    void aWrongConfirmationIs400() throws Exception {
        mvc.perform(deleteAs(creator, community.getName(), confirm("something-else")))
                .andExpect(status().isBadRequest());
        assertFalse(fx.isDeleted(community.getId()));
    }

    @Test
    void aMissingBlankOrMalformedBodyIs400() throws Exception {
        mvc.perform(deleteAs(creator, community.getName(), null)).andExpect(status().isBadRequest());
        mvc.perform(deleteAs(creator, community.getName(), "{}")).andExpect(status().isBadRequest());
        mvc.perform(deleteAs(creator, community.getName(), "{\"confirmName\":\"  \"}")).andExpect(status().isBadRequest());
        mvc.perform(deleteAs(creator, community.getName(), "not json")).andExpect(status().isBadRequest());
        assertFalse(fx.isDeleted(community.getId()));
    }

    @Test
    void anonymousCallersGet401() throws Exception {
        mvc.perform(deleteAs(null, community.getName(), confirm(community.getName())))
                .andExpect(status().isUnauthorized());
        assertFalse(fx.isDeleted(community.getId()));
    }

    @Test
    void aNonCreatorGets403() throws Exception {
        UUID outsider = fx.user("cdoutsider");
        mvc.perform(deleteAs(outsider, community.getName(), confirm(community.getName())))
                .andExpect(status().isForbidden());
        assertFalse(fx.isDeleted(community.getId()));
    }

    @Test
    void aModeratorWithEveryPermissionBitButNotTheCreatorGets403() throws Exception {
        UUID coOwner = fx.user("cdcoowner");
        fx.moderator(community.getId(), coOwner, CommunityDeleteFixtures.ALL_BITS, creator);
        mvc.perform(deleteAs(coOwner, community.getName(), confirm(community.getName())))
                .andExpect(status().isForbidden());
        assertFalse(fx.isDeleted(community.getId()));
    }

    @Test
    void anUnknownCommunityIs404() throws Exception {
        mvc.perform(deleteAs(creator, "no_such_" + UUID.randomUUID().toString().substring(0, 8), confirm("x")))
                .andExpect(status().isNotFound());
    }

    @Test
    void aSecondDeleteOfTheSameCommunityIs404() throws Exception {
        mvc.perform(deleteAs(creator, community.getName(), confirm(community.getName()))).andExpect(status().isNoContent());
        mvc.perform(deleteAs(creator, community.getName(), confirm(community.getName()))).andExpect(status().isNotFound());
        assertEquals(1, fx.auditRows(community.getId()));
    }

    @Test
    void theSubscribeEndpointsKeepTheirOwnDeleteRoute() throws Exception {
        // DELETE /r/{name}/subscribe (leave) must still be a different route from DELETE /r/{name} (delete the community)
        UUID member = fx.user("cdmember");
        mvc.perform(delete("/r/{name}/subscribe", community.getName()).header("Authorization", tokenFor(member)))
                .andExpect(status().isOk());
        assertFalse(fx.isDeleted(community.getId()));
    }
}
