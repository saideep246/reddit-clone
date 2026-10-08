package com.redditclone.community;

import com.redditclone.auth.JwtService;
import com.redditclone.auth.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Flair management: create / edit / delete / list through the real security chain, authorization by the existing
// PERM_MANAGE_FLAIRS bit, validation, duplicates, community isolation, audit entries, and what deleting a flair does to posts.
// Shared Spring context, rolled-back transactions (the only real side effect is the post-idempotency key, removed in @AfterEach).
@SpringBootTest
@Transactional
class FlairManagementTest {

    private static final String RED = "#ff0000";
    private static final String BLUE = "#0000ff";

    @Autowired private WebApplicationContext webContext;
    @Autowired private CommunityService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager em;
    @Autowired private JwtService jwt;
    @Autowired private UserRepository users;
    @Autowired private StringRedisTemplate redis;
    @Autowired private ObjectMapper json;

    private MockMvc mvc;
    private CommunityDeleteFixtures fx;
    private UUID owner;
    private UUID flairMod;
    private UUID banOnlyMod;
    private UUID member;
    private UUID otherOwner;
    private Community community;
    private Community other;
    private final List<String> redisKeys = new ArrayList<>();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        fx = new CommunityDeleteFixtures(jdbc, service, em);
        owner = fx.user("fl_owner");
        flairMod = fx.user("fl_flairmod");
        banOnlyMod = fx.user("fl_banmod");
        member = fx.user("fl_member");
        otherOwner = fx.user("fl_otherowner");
        community = fx.community(owner);
        other = fx.community(otherOwner);
        service.addModerator(owner, community.getId(), flairMod, CommunityModerator.PERM_MANAGE_FLAIRS);
        service.addModerator(owner, community.getId(), banOnlyMod, CommunityModerator.PERM_BAN_USERS);
        em.flush();
    }

    @AfterEach
    void cleanRedis() {
        redisKeys.forEach(redis::delete);
    }

    // ---------- helpers ----------

    // Inside the test transaction nothing JPA wrote reaches the database until a flush, and the assertions below read with plain JDBC,
    // so every request is followed by a flush (the FK's ON DELETE SET NULL, for one, only fires once the DELETE is actually sent).
    private ResultActions perform(RequestBuilder request) throws Exception {
        ResultActions result = mvc.perform(request);
        em.flush();
        return result;
    }

    private String token(UUID id) {
        return "Bearer " + jwt.generateAccessToken(users.findById(id).orElseThrow());
    }

    private MockHttpServletRequestBuilder req(HttpMethod m, String path, UUID as, String body) {
        MockHttpServletRequestBuilder b = MockMvcRequestBuilders.request(m, path).contentType(MediaType.APPLICATION_JSON);
        if (as != null) b = b.header("Authorization", token(as));
        return body == null ? b : b.content(body);
    }

    private static String flairBody(String text, String color, String type) {
        return "{\"text\":\"" + text + "\",\"color\":\"" + color + "\",\"type\":\"" + type + "\"}";
    }

    private static String editBody(String text, String color) {
        return "{\"text\":\"" + text + "\",\"color\":\"" + color + "\"}";
    }

    private String modPath(Community c) { return "/r/" + c.getName() + "/mod/flairs"; }

    private JsonNode create(UUID as, Community c, String text, String type) throws Exception {
        MvcResult r = perform(req(HttpMethod.POST, modPath(c), as, flairBody(text, RED, type))).andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    private int flairCount(Community c) {
        return jdbc.queryForObject("SELECT count(*) FROM flairs WHERE community_id = ?", Integer.class, c.getId());
    }

    private int audit(Community c, String action) {
        return jdbc.queryForObject("SELECT count(*) FROM moderation_actions WHERE community_id = ? AND action = ?", Integer.class, c.getId(), action);
    }

    private UUID insertPost(UUID author, UUID flairId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO posts (id, community_id, author_id, kind, title, body, flair_id) VALUES (?, ?, ?, 'text', 't', 'b', ?)",
                id, community.getId(), author, flairId);
        return id;
    }

    // ---------- create ----------

    @Test
    void moderatorWithTheFlairPermissionCreatesPostAndUserFlairsAndTheyAreAudited() throws Exception {
        JsonNode post = create(flairMod, community, "Question", "post");
        JsonNode user = create(owner, community, "Veteran", "user");
        assertEquals("Question", post.get("text").asString());
        assertEquals(RED, post.get("color").asString());
        assertEquals("post", post.get("type").asString());
        assertEquals("user", user.get("type").asString());
        assertEquals(2, flairCount(community));
        assertEquals(2, audit(community, "create_flair"), "every creation is written to the mod log");
        assertEquals("flair", jdbc.queryForObject("SELECT target_type FROM moderation_actions WHERE action = 'create_flair' AND target_id = ?::uuid LIMIT 1",
                String.class, post.get("id").asString()));
    }

    @Test
    void createRequiresTheFlairPermission() throws Exception {
        String body = flairBody("Nope", RED, "post");
        perform(req(HttpMethod.POST, modPath(community), banOnlyMod, body)).andExpect(status().isForbidden());
        perform(req(HttpMethod.POST, modPath(community), member, body)).andExpect(status().isForbidden());
        perform(req(HttpMethod.POST, modPath(community), null, body)).andExpect(status().isUnauthorized());
        assertEquals(0, flairCount(community));
        assertEquals(0, audit(community, "create_flair"));
    }

    @Test
    void invalidTypeAndInvalidFieldsAreRejectedWith400() throws Exception {
        for (String bad : List.of(
                flairBody("Ok", RED, "bogus"),                      // type
                flairBody("   ", RED, "post"),                      // blank text
                flairBody("x".repeat(65), RED, "post"),             // over 64 characters
                flairBody("Ok", "red", "post"),                     // not #RRGGBB
                flairBody("Ok", "#ff00", "post"),                   // too short
                "{\"color\":\"" + RED + "\",\"type\":\"post\"}", // missing text
                "{\"text\":\"Ok\",\"type\":\"post\"}",          // missing colour
                "{\"text\":\"Ok\",\"color\":\"" + RED + "\"}")) {  // missing type
            perform(req(HttpMethod.POST, modPath(community), owner, bad)).andExpect(status().isBadRequest());
        }
        assertEquals(0, flairCount(community));
        perform(req(HttpMethod.GET, "/r/" + community.getName() + "/flairs?type=bogus", null, null)).andExpect(status().isBadRequest());
    }

    @Test
    void textIsTrimmedAndAMaximumLengthNameIsAccepted() throws Exception {
        JsonNode trimmed = create(owner, community, "  Spaced  ", "post");
        assertEquals("Spaced", trimmed.get("text").asString());
        JsonNode longest = create(owner, community, "y".repeat(64), "post");
        assertEquals(64, longest.get("text").asString().length());
    }

    // ---------- duplicates (application-level only) ----------

    @Test
    void duplicatesAreRejectedWith409IgnoringCaseButOnlyWithinTheSameTypeAndCommunity() throws Exception {
        create(owner, community, "Discussion", "post");
        perform(req(HttpMethod.POST, modPath(community), owner, flairBody("Discussion", BLUE, "post"))).andExpect(status().isConflict());
        perform(req(HttpMethod.POST, modPath(community), owner, flairBody("dIsCuSsIoN", BLUE, "post"))).andExpect(status().isConflict());
        perform(req(HttpMethod.POST, modPath(community), owner, flairBody(" Discussion ", BLUE, "post"))).andExpect(status().isConflict());
        // allowed: same text but a different type, and the same text in a different community
        perform(req(HttpMethod.POST, modPath(community), owner, flairBody("Discussion", BLUE, "user"))).andExpect(status().isOk());
        perform(req(HttpMethod.POST, modPath(other), otherOwner, flairBody("Discussion", BLUE, "post"))).andExpect(status().isOk());
        assertEquals(2, flairCount(community));
    }

    @Test
    void editingToACollidingNameIs409ButSavingAnUnchangedNameIsFine() throws Exception {
        create(owner, community, "News", "post");
        JsonNode help = create(owner, community, "Help", "post");
        String helpPath = modPath(community) + "/" + help.get("id").asString();
        perform(req(HttpMethod.PATCH, helpPath, owner, editBody("news", BLUE))).andExpect(status().isConflict());
        perform(req(HttpMethod.PATCH, helpPath, owner, editBody("Help", BLUE))).andExpect(status().isOk()); // colour-only change, own name
    }

    @Test
    void duplicatePreventionIsOnlyApplicationLevelBecauseTheTableHasNoUniqueConstraint() throws Exception {
        // This documents a deliberate limitation: the check above is NOT a database-level or concurrency guarantee. There is no unique
        // constraint on (community_id, type, lower(text)) and none was added for this feature, so the database itself accepts a
        // duplicate, which is what two simultaneous requests could produce.
        create(owner, community, "Racey", "post");
        jdbc.update("INSERT INTO flairs (id, community_id, text, color, type) VALUES (?, ?, 'Racey', ?, 'post')", UUID.randomUUID(), community.getId(), BLUE);
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM flairs WHERE community_id = ? AND text = 'Racey'", Integer.class, community.getId()));
    }

    // ---------- edit ----------

    @Test
    void moderatorWithTheFlairPermissionEditsTextAndColourButNotTheType() throws Exception {
        JsonNode f = create(flairMod, community, "Old", "post");
        String path = modPath(community) + "/" + f.get("id").asString();
        MvcResult r = perform(req(HttpMethod.PATCH, path, flairMod, editBody("New", BLUE))).andExpect(status().isOk()).andReturn();
        JsonNode updated = json.readTree(r.getResponse().getContentAsString());
        assertEquals("New", updated.get("text").asString());
        assertEquals(BLUE, updated.get("color").asString());
        assertEquals("post", updated.get("type").asString(), "type is fixed");
        assertEquals(1, audit(community, "update_flair"));
        perform(req(HttpMethod.PATCH, path, flairMod, "{\"text\":\"New\",\"color\":\"" + BLUE + "\",\"type\":\"user\"}")).andExpect(status().isOk());
        assertEquals("post", jdbc.queryForObject("SELECT type FROM flairs WHERE id = ?::uuid", String.class, f.get("id").asString()), "a type in the body is ignored");
    }

    @Test
    void editRequiresThePermissionAndValidInput() throws Exception {
        JsonNode f = create(owner, community, "Keep", "post");
        String path = modPath(community) + "/" + f.get("id").asString();
        perform(req(HttpMethod.PATCH, path, banOnlyMod, editBody("X", BLUE))).andExpect(status().isForbidden());
        perform(req(HttpMethod.PATCH, path, member, editBody("X", BLUE))).andExpect(status().isForbidden());
        perform(req(HttpMethod.PATCH, path, null, editBody("X", BLUE))).andExpect(status().isUnauthorized());
        perform(req(HttpMethod.PATCH, path, owner, editBody("", BLUE))).andExpect(status().isBadRequest());
        perform(req(HttpMethod.PATCH, path, owner, editBody("X", "blue"))).andExpect(status().isBadRequest());
        assertEquals("Keep", jdbc.queryForObject("SELECT text FROM flairs WHERE id = ?::uuid", String.class, f.get("id").asString()));
        assertEquals(0, audit(community, "update_flair"));
    }

    @Test
    void editingAFlairThatDoesNotExistIs404() throws Exception {
        perform(req(HttpMethod.PATCH, modPath(community) + "/" + UUID.randomUUID(), owner, editBody("X", BLUE))).andExpect(status().isNotFound());
    }

    // ---------- delete ----------

    @Test
    void moderatorWithTheFlairPermissionDeletesAFlairAndItIsAudited() throws Exception {
        JsonNode f = create(flairMod, community, "Gone", "post");
        perform(req(HttpMethod.DELETE, modPath(community) + "/" + f.get("id").asString(), flairMod, null)).andExpect(status().isOk());
        assertEquals(0, flairCount(community));
        assertEquals(1, audit(community, "delete_flair"));
        assertTrue(jdbc.queryForObject("SELECT reason FROM moderation_actions WHERE action = 'delete_flair' AND community_id = ?", String.class,
                community.getId()).contains("Gone"), "the log keeps what was deleted, since the row is gone");
    }

    @Test
    void deleteRequiresThePermissionAndANonexistentFlairIs404() throws Exception {
        JsonNode f = create(owner, community, "Stay", "post");
        String path = modPath(community) + "/" + f.get("id").asString();
        perform(req(HttpMethod.DELETE, path, banOnlyMod, null)).andExpect(status().isForbidden());
        perform(req(HttpMethod.DELETE, path, member, null)).andExpect(status().isForbidden());
        perform(req(HttpMethod.DELETE, path, null, null)).andExpect(status().isUnauthorized());
        assertEquals(1, flairCount(community));
        assertEquals(0, audit(community, "delete_flair"));
        perform(req(HttpMethod.DELETE, modPath(community) + "/" + UUID.randomUUID(), owner, null)).andExpect(status().isNotFound());
    }

    @Test
    void deletingAFlairKeepsPostsAndMembershipsAndJustClearsTheirFlair() throws Exception {
        JsonNode postFlair = create(owner, community, "Tagged", "post");
        JsonNode userFlair = create(owner, community, "Badge", "user");
        UUID postFlairId = UUID.fromString(postFlair.get("id").asString());
        UUID userFlairId = UUID.fromString(userFlair.get("id").asString());
        UUID post = insertPost(member, postFlairId);
        service.join(member, community.getId());
        service.setOwnFlair(member, community.getId(), userFlairId);
        em.flush();
        assertEquals(postFlairId, jdbc.queryForObject("SELECT flair_id FROM posts WHERE id = ?", UUID.class, post));

        perform(req(HttpMethod.DELETE, modPath(community) + "/" + postFlairId, owner, null)).andExpect(status().isOk());
        perform(req(HttpMethod.DELETE, modPath(community) + "/" + userFlairId, owner, null)).andExpect(status().isOk());

        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM posts WHERE id = ?", Integer.class, post), "the post is kept");
        assertNull(jdbc.queryForObject("SELECT flair_id FROM posts WHERE id = ?", UUID.class, post), "its flair_id is cleared by ON DELETE SET NULL");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM memberships WHERE community_id = ? AND user_id = ?", Integer.class, community.getId(), member),
                "the membership is kept");
        assertNull(jdbc.queryForObject("SELECT flair_id FROM memberships WHERE community_id = ? AND user_id = ?", UUID.class, community.getId(), member));
        assertEquals("t", jdbc.queryForObject("SELECT title FROM posts WHERE id = ?", String.class, post), "post content is untouched");
    }

    // ---------- community isolation and deleted community ----------

    @Test
    void aFlairCannotBeChangedThroughAnotherCommunity() throws Exception {
        JsonNode f = create(owner, community, "Mine", "post");
        String id = f.get("id").asString();
        // the other community's owner, addressing the flair through THEIR community: 404, not an edit
        perform(req(HttpMethod.PATCH, modPath(other) + "/" + id, otherOwner, editBody("Hijack", BLUE))).andExpect(status().isNotFound());
        perform(req(HttpMethod.DELETE, modPath(other) + "/" + id, otherOwner, null)).andExpect(status().isNotFound());
        // and through the real community they hold no flair permission: 403
        perform(req(HttpMethod.PATCH, modPath(community) + "/" + id, otherOwner, editBody("Hijack", BLUE))).andExpect(status().isForbidden());
        perform(req(HttpMethod.DELETE, modPath(community) + "/" + id, otherOwner, null)).andExpect(status().isForbidden());
        assertEquals("Mine", jdbc.queryForObject("SELECT text FROM flairs WHERE id = ?::uuid", String.class, id));
    }

    @Test
    void aDeletedCommunityRejectsEveryFlairMutationWith404() throws Exception {
        JsonNode f = create(owner, community, "Before", "post");
        String id = f.get("id").asString();
        jdbc.update("UPDATE communities SET deleted_at = now(), deleted_by = ? WHERE id = ?", owner, community.getId());
        em.flush();
        em.clear();
        perform(req(HttpMethod.POST, modPath(community), owner, flairBody("After", RED, "post"))).andExpect(status().isNotFound());
        perform(req(HttpMethod.PATCH, modPath(community) + "/" + id, owner, editBody("After", BLUE))).andExpect(status().isNotFound());
        perform(req(HttpMethod.DELETE, modPath(community) + "/" + id, owner, null)).andExpect(status().isNotFound());
        assertEquals("Before", jdbc.queryForObject("SELECT text FROM flairs WHERE id = ?::uuid", String.class, id));
    }

    // ---------- listing ----------

    @Test
    void listsPostAndUserFlairsSeparatelyInCreationOrderForAnyone() throws Exception {
        // explicit created_at values so the order does not depend on how fast the rows are inserted
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(), d = UUID.randomUUID();
        jdbc.update("INSERT INTO flairs (id, community_id, text, color, type, created_at) VALUES (?, ?, 'Third', ?, 'post', now())", a, community.getId(), RED);
        jdbc.update("INSERT INTO flairs (id, community_id, text, color, type, created_at) VALUES (?, ?, 'First', ?, 'post', now() - interval '2 hours')", b, community.getId(), RED);
        jdbc.update("INSERT INTO flairs (id, community_id, text, color, type, created_at) VALUES (?, ?, 'Second', ?, 'post', now() - interval '1 hour')", c, community.getId(), RED);
        jdbc.update("INSERT INTO flairs (id, community_id, text, color, type, created_at) VALUES (?, ?, 'Vet', ?, 'user', now())", d, community.getId(), RED);

        List<String> posts = texts(perform(req(HttpMethod.GET, "/r/" + community.getName() + "/flairs?type=post", null, null))
                .andExpect(status().isOk()).andReturn());
        assertEquals(List.of("First", "Second", "Third"), posts);
        assertEquals(List.of("Vet"), texts(perform(req(HttpMethod.GET, "/r/" + community.getName() + "/flairs?type=user", null, null))
                .andExpect(status().isOk()).andReturn()));
        assertEquals(4, texts(perform(req(HttpMethod.GET, "/r/" + community.getName() + "/flairs", null, null)).andReturn()).size());
    }

    private List<String> texts(MvcResult r) throws Exception {
        List<String> out = new ArrayList<>();
        json.readTree(r.getResponse().getContentAsString()).forEach(n -> out.add(n.get("text").asString()));
        return out;
    }

    // ---------- posts: with and without a flair ----------

    private JsonNode submit(UUID as, String body) throws Exception {
        String key = UUID.randomUUID().toString();
        redisKeys.add("idempotency:" + as + ":" + key);
        MockHttpServletRequestBuilder b = req(HttpMethod.POST, "/r/" + community.getName() + "/submit", as, body).header("Idempotency-Key", key);
        MvcResult r = perform(b).andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    @Test
    void aPostCanBeCreatedWithoutAFlairAndWithAPostFlair() throws Exception {
        JsonNode plain = submit(member, "{\"kind\":\"text\",\"title\":\"no flair\",\"body\":\"b\"}");
        assertTrue(plain.get("flairId").isNull());
        assertTrue(plain.get("flair") == null || plain.get("flair").isNull());

        JsonNode f = create(owner, community, "Question", "post");
        JsonNode tagged = submit(member, "{\"kind\":\"text\",\"title\":\"with flair\",\"body\":\"b\",\"flairId\":\"" + f.get("id").asString() + "\"}");
        assertEquals(f.get("id").asString(), tagged.get("flairId").asString());
        assertEquals("Question", tagged.get("flair").get("text").asString());
        assertEquals(f.get("id").asString(), jdbc.queryForObject("SELECT flair_id::text FROM posts WHERE id = ?::uuid", String.class, tagged.get("id").asString()));
    }

    @Test
    void aPostCannotUseAUserFlairOrAnotherCommunitysFlair() throws Exception {
        JsonNode userFlair = create(owner, community, "Badge", "user");
        JsonNode foreign = create(otherOwner, other, "Foreign", "post");
        for (String id : List.of(userFlair.get("id").asString(), foreign.get("id").asString())) {
            String key = UUID.randomUUID().toString();
            redisKeys.add("idempotency:" + member + ":" + key);
            perform(req(HttpMethod.POST, "/r/" + community.getName() + "/submit", member,
                            "{\"kind\":\"text\",\"title\":\"bad\",\"body\":\"b\",\"flairId\":\"" + id + "\"}").header("Idempotency-Key", key))
                    .andExpect(status().is4xxClientError());
        }
    }
}
