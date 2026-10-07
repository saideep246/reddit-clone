package com.redditclone.community;

import com.redditclone.auth.JwtService;
import com.redditclone.auth.UserRepository;
import com.redditclone.common.exception.NotFoundException;
import com.redditclone.post.PostService;
import com.redditclone.post.ScheduledPostService;
import com.redditclone.post.dto.CreatePostRequest;
import com.redditclone.vote.VoteService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Step 4B: after a community is deleted nothing can be written into it. Two kinds of write path exist:
//  - name-scoped (/r/{name}/...): resolved through CommunityService.findByName, which 404s for a deleted community. The sweep below
//    discovers EVERY such write route from the handler mappings and hits each one, so a new route cannot slip through unnoticed.
//  - id-scoped (vote, report, comment reply/edit/delete, post creation): check the target's community explicitly.
// Shared Spring context, rolled-back transactions.
@SpringBootTest
@Transactional
class CommunityDeletedWriteProtectionTest {

    @Autowired private WebApplicationContext webContext;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager em;
    @Autowired private CommunityService communityService;
    @Autowired private PostService postService;
    @Autowired private VoteService voteService;
    @Autowired private ScheduledPostService scheduled;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;
    @Autowired private JwtService jwt;
    @Autowired private UserRepository users;

    private MockMvc mvc;
    private CommunityDeleteFixtures fx;
    private UUID owner;
    private UUID member;
    private String ownerToken;
    private String memberToken;
    private Community active;
    private Community deleted;
    private UUID activePost;
    private UUID deletedPost;
    private UUID activeComment;
    private UUID deletedComment;
    private UUID memberCommentInDeleted;

    private static final CreatePostRequest TEXT_POST =
            new CreatePostRequest("text", "a title", "a body", null, null, null, null, null, null, null, null, null);

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        fx = new CommunityDeleteFixtures(jdbc, communityService, em);
        owner = fx.user("wp_owner");
        member = fx.user("wp_member");
        ownerToken = token(owner);
        memberToken = token(member);
        active = fx.community(owner);
        deleted = fx.community(owner);
        activePost = post(active, owner);
        deletedPost = post(deleted, owner);
        activeComment = comment(activePost, owner);
        deletedComment = comment(deletedPost, owner);
        memberCommentInDeleted = comment(deletedPost, member);
        em.flush();
        jdbc.update("UPDATE communities SET deleted_at = now(), deleted_by = ? WHERE id = ?", owner, deleted.getId());
        em.flush();
        em.clear();
    }

    private String token(UUID id) {
        return "Bearer " + jwt.generateAccessToken(users.findById(id).orElseThrow());
    }

    private UUID post(Community c, UUID author) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO posts (id, community_id, author_id, kind, title, body) VALUES (?, ?, ?, 'text', 't', 'b')", id, c.getId(), author);
        return id;
    }

    private UUID comment(UUID postId, UUID author) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO comments (id, post_id, path, depth, author_id, body) VALUES (?, ?, text2ltree(replace(?, '-', '_')), 0, ?, 'c')",
                id, postId, id.toString(), author);
        return id;
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private String json(String template) {
        return template.replace("$UUID", UUID.randomUUID().toString());
    }

    private MockHttpServletRequestBuilder req(HttpMethod method, String path, String token, String body) {
        MockHttpServletRequestBuilder b = MockMvcRequestBuilders.request(method, path).contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", token).header("Idempotency-Key", UUID.randomUUID().toString());
        return body == null ? b : b.content(body);
    }

    // ---------- id-scoped writes ----------

    @Test
    void postCreationIsRejectedForEveryoneIncludingTheCreatorModerator() {
        assertThrows(NotFoundException.class, () -> communityService.requirePostAccess(member, deleted.getId()));
        assertThrows(NotFoundException.class, () -> communityService.requirePostAccess(owner, deleted.getId()), "moderator shortcut must not bypass");
        assertThrows(NotFoundException.class, () -> postService.create(owner, deleted.getId(), TEXT_POST, UUID.randomUUID().toString()));
        communityService.requirePostAccess(member, active.getId()); // control: an active community still allows it
        assertEquals(1, count("SELECT count(*) FROM posts WHERE community_id = ?", deleted.getId()), "no post was added");
    }

    @Test
    void schedulingIntoADeletedCommunityFails() {
        Instant at = Instant.now().plus(1, ChronoUnit.HOURS);
        assertThrows(NotFoundException.class, () -> scheduled.schedule(owner, deleted.getId(), TEXT_POST, at));
        assertEquals(0, count("SELECT count(*) FROM scheduled_posts WHERE community_id = ?", deleted.getId()));
        assertTrue(scheduled.schedule(owner, active.getId(), TEXT_POST, at).id() != null, "control: active community");
    }

    @Test
    void votesOnPostsAndCommentsOfADeletedCommunityAreRejected() throws Exception {
        String post = "{\"targetType\":\"post\",\"targetId\":\"" + deletedPost + "\",\"dir\":1}";
        String comment = "{\"targetType\":\"comment\",\"targetId\":\"" + deletedComment + "\",\"dir\":1}";
        mvc.perform(req(HttpMethod.POST, "/api/vote", memberToken, post)).andExpect(status().isNotFound());
        mvc.perform(req(HttpMethod.POST, "/api/vote", memberToken, comment)).andExpect(status().isNotFound());
        mvc.perform(req(HttpMethod.DELETE, "/api/vote?targetType=post&targetId=" + deletedPost, memberToken, null)).andExpect(status().isNotFound());
        mvc.perform(req(HttpMethod.DELETE, "/api/vote?targetType=comment&targetId=" + deletedComment, memberToken, null)).andExpect(status().isNotFound());
        assertEquals(0, count("SELECT count(*) FROM post_votes WHERE post_id = ?", deletedPost));

        // control: the same calls on an ACTIVE community succeed
        mvc.perform(req(HttpMethod.POST, "/api/vote", memberToken,
                "{\"targetType\":\"post\",\"targetId\":\"" + activePost + "\",\"dir\":1}")).andExpect(status().isOk());
        mvc.perform(req(HttpMethod.POST, "/api/vote", memberToken,
                "{\"targetType\":\"comment\",\"targetId\":\"" + activeComment + "\",\"dir\":1}")).andExpect(status().isOk());
    }

    @Test
    void commentReplyEditAndDeleteInADeletedCommunityAreRejected() throws Exception {
        mvc.perform(req(HttpMethod.POST, "/api/comment", memberToken, "{\"postId\":\"" + deletedPost + "\",\"body\":\"hi\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(req(HttpMethod.PATCH, "/api/comment/" + memberCommentInDeleted, memberToken, "{\"body\":\"edited\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(req(HttpMethod.DELETE, "/api/comment/" + memberCommentInDeleted, memberToken, null)).andExpect(status().isNotFound());

        assertEquals("c", jdbc.queryForObject("SELECT body FROM comments WHERE id = ?", String.class, memberCommentInDeleted), "body untouched");
        assertEquals(false, jdbc.queryForObject("SELECT deleted FROM comments WHERE id = ?", Boolean.class, memberCommentInDeleted));
        assertEquals(2, count("SELECT count(*) FROM comments WHERE post_id = ?", deletedPost), "no comment was added");

        // control: an active community still accepts a reply
        mvc.perform(req(HttpMethod.POST, "/api/comment", memberToken, "{\"postId\":\"" + activePost + "\",\"body\":\"hi\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void reportsAgainstADeletedCommunityAreRejected() throws Exception {
        mvc.perform(req(HttpMethod.POST, "/api/report", memberToken,
                "{\"targetType\":\"post\",\"targetId\":\"" + deletedPost + "\",\"reason\":\"spam\"}")).andExpect(status().isNotFound());
        mvc.perform(req(HttpMethod.POST, "/api/report", memberToken,
                "{\"targetType\":\"comment\",\"targetId\":\"" + deletedComment + "\",\"reason\":\"spam\"}")).andExpect(status().isNotFound());
        assertEquals(0, count("SELECT count(*) FROM reports WHERE community_id = ?", deleted.getId()));
        assertEquals(0, count("SELECT count(*) FROM mod_queue WHERE community_id = ?", deleted.getId()));

        mvc.perform(req(HttpMethod.POST, "/api/report", memberToken,
                "{\"targetType\":\"post\",\"targetId\":\"" + activePost + "\",\"reason\":\"spam\"}")).andExpect(status().isOk()); // control
    }

    // ---------- name-scoped writes: every route, discovered automatically ----------

    private static final Pattern VAR = Pattern.compile("\\{([^}/]+)}");

    // Minimal valid bodies, so request validation passes and the community lookup is what answers. A route with a required body
    // that is missing here fails the sweep with 400 (the message says which), forcing a conscious addition.
    private String bodyFor(String pattern) {
        Map<String, String> bodies = Map.ofEntries(
                Map.entry("DELETE /r/{name}", "{\"confirmName\":\"x\"}"),
                Map.entry("PATCH /r/{name}/me/flair", "{}"),
                Map.entry("POST /r/{communityName}/submit", "{\"kind\":\"text\",\"title\":\"t\",\"body\":\"b\"}"),
                Map.entry("PATCH /r/{communityName}/posts/{postId}", "{\"body\":\"x\"}"),
                Map.entry("POST /r/{communityName}/posts/{postId}/poll/vote", "{\"optionId\":\"$UUID\"}"),
                Map.entry("POST /r/{communityName}/schedule", "{\"post\":{\"kind\":\"text\",\"title\":\"t\",\"body\":\"b\"},\"publishAt\":\""
                        + Instant.now().plus(1, ChronoUnit.HOURS) + "\"}"),
                Map.entry("POST /r/{name}/mod/notes", "{\"userId\":\"$UUID\",\"note\":\"n\"}"),
                Map.entry("POST /r/{name}/mod/ban", "{\"userId\":\"$UUID\"}"),
                Map.entry("POST /r/{name}/mod/mute", "{\"userId\":\"$UUID\"}"),
                Map.entry("POST /r/{name}/mod/automod-rules", "{\"ruleType\":\"keyword\",\"config\":{\"keywords\":[\"x\"]},\"action\":\"remove\"}"),
                Map.entry("POST /r/{name}/mod/moderators", "{\"userId\":\"$UUID\",\"permissions\":1}"),
                Map.entry("POST /r/{name}/mod/mail", "{\"body\":\"b\"}"),
                Map.entry("POST /r/{name}/mod/flairs", "{\"text\":\"t\",\"color\":\"#aabbcc\",\"type\":\"post\"}"),
                Map.entry("PUT /r/{name}/mod/rules", "{\"rules\":[]}"),
                Map.entry("PATCH /r/{name}/mod/settings", "{\"description\":\"d\"}"),
                Map.entry("PATCH /r/{name}/mod/users/{targetUserId}/flair", "{}"),
                Map.entry("PATCH /r/{name}/mod/posts/{postId}/flair", "{}"),
                Map.entry("PATCH /r/{name}/mod/type", "{\"type\":\"public\"}"),
                Map.entry("POST /r/{name}/mod/approved-submitters", "{\"userId\":\"$UUID\"}"));
        for (Map.Entry<String, String> e : bodies.entrySet()) {
            if (e.getKey().equals(pattern)) {
                return json(e.getValue());
            }
        }
        return "{}";
    }

    private List<String> communityScopedWriteRoutes() {
        Set<String> out = new TreeSet<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            for (RequestMethod m : info.getMethodsCondition().getMethods()) {
                if (m == RequestMethod.GET || m == RequestMethod.HEAD || m == RequestMethod.OPTIONS) {
                    continue;
                }
                for (String p : info.getPathPatternsCondition().getPatternValues()) {
                    if (p.startsWith("/r/{")) {
                        out.add(m.name() + " " + p);
                    }
                }
            }
        }
        return new ArrayList<>(out);
    }

    @Test
    void everyNameScopedCommunityWriteRouteAnswers404ForADeletedCommunity() throws Exception {
        List<String> routes = communityScopedWriteRoutes();
        assertTrue(routes.size() >= 40, "route discovery looks broken, found " + routes.size());
        List<String> failures = new ArrayList<>();
        for (String route : routes) {
            String methodName = route.substring(0, route.indexOf(' '));
            String pattern = route.substring(route.indexOf(' ') + 1);
            Matcher mt = VAR.matcher(pattern);
            StringBuilder path = new StringBuilder();
            while (mt.find()) {
                String var = mt.group(1);
                String value = var.equals("name") || var.equals("communityName") ? deleted.getName()
                        : var.equals("targetType") ? "post" : UUID.randomUUID().toString();
                mt.appendReplacement(path, value);
            }
            mt.appendTail(path);
            int status = mvc.perform(req(HttpMethod.valueOf(methodName), path.toString(), ownerToken, bodyFor(route)))
                    .andReturn().getResponse().getStatus();
            if (status != 404) {
                failures.add(route + " -> " + status);
            }
        }
        assertTrue(failures.isEmpty(), "routes that did not 404 for a deleted community: " + failures);
    }

    @Test
    void theSameRoutesAreNotAll404ForAnActiveCommunityWhichProvesTheSweepDiscriminates() throws Exception {
        // control: a few representative routes on the ACTIVE community do not return 404
        mvc.perform(req(HttpMethod.POST, "/r/" + active.getName() + "/subscribe", memberToken, null)).andExpect(status().isOk());
        mvc.perform(req(HttpMethod.PATCH, "/r/" + active.getName() + "/mod/settings", ownerToken, "{\"description\":\"d\"}")).andExpect(status().isOk());
        mvc.perform(req(HttpMethod.POST, "/r/" + active.getName() + "/mod/mail", memberToken, "{\"body\":\"b\"}")).andExpect(status().is2xxSuccessful());
    }

    @Test
    void historicalDataIsUntouchedByTheRejectedWrites() {
        assertEquals(1, count("SELECT count(*) FROM communities WHERE id = ? AND deleted_at IS NOT NULL", deleted.getId()));
        assertEquals(1, count("SELECT count(*) FROM posts WHERE id = ?", deletedPost));
        assertEquals(2, count("SELECT count(*) FROM comments WHERE post_id = ?", deletedPost));
        assertTrue(count("SELECT count(*) FROM memberships WHERE community_id = ?", deleted.getId()) >= 1, "memberships kept");
        assertTrue(count("SELECT count(*) FROM community_moderators WHERE community_id = ?", deleted.getId()) >= 1, "moderators kept");
    }
}
