package com.redditclone.community;

import com.redditclone.auth.JwtService;
import com.redditclone.auth.UserRepository;
import com.redditclone.comment.Comment;
import com.redditclone.comment.CommentRepository;
import com.redditclone.common.exception.NotFoundException;
import com.redditclone.post.Post;
import com.redditclone.post.PostRepository;
import com.redditclone.post.PostService;
import com.redditclone.post.ScheduledPostService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Step 3: once communities.deleted_at is set, the community and everything in it disappears from normal reads.
// Four communities share one author: an active and a deleted PUBLIC one, an active and a deleted PRIVATE one. Each holds
// one post (and one comment). Every site-wide read must return exactly the two ACTIVE posts for a viewer who may see them,
// and must never return the deleted private community's post, to its own (still-member) creator, an outsider or anonymous.
// Plain @SpringBootTest on the shared context (no extra Spring contexts: the local PgBouncer is nearly full); rolled back.
@SpringBootTest
@Transactional
class CommunityDeletedVisibilityTest {

    private static final Instant FAR_FUTURE = Instant.now().plus(1, ChronoUnit.DAYS);
    private static final UUID MAX_ID = new UUID(-1L, -1L);
    private static final Pageable PAGE = Pageable.ofSize(200);
    private static final int BIG = 1_000_000_000; // ranks/scores above anything real, so our rows sort first and fit a page

    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager em;
    @Autowired private CommunityService communityService;
    @Autowired private PostRepository posts;
    @Autowired private PostService postService;
    @Autowired private CommentRepository comments;
    @Autowired private ScheduledPostService scheduled;
    @Autowired private WebApplicationContext webContext;
    @Autowired private JwtService jwt;
    @Autowired private UserRepository users;

    private MockMvc mvc;
    private CommunityDeleteFixtures fx;
    private UUID owner;
    private UUID outsider;
    private String token;

    private Community activePublic;
    private Community deletedPublic;
    private Community activePrivate;
    private Community deletedPrivate;
    private UUID activePublicPost;
    private UUID deletedPublicPost;
    private UUID activePrivatePost;
    private UUID deletedPrivatePost;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        fx = new CommunityDeleteFixtures(jdbc, communityService, em);
        owner = fx.user("vis_owner");
        outsider = fx.user("vis_out");
        token = token(owner);

        activePublic = fx.community(owner);
        deletedPublic = fx.community(owner);
        activePrivate = privateCommunity();
        deletedPrivate = privateCommunity();
        activePublicPost = newPost(activePublic, "alpha");
        deletedPublicPost = newPost(deletedPublic, "alpha");
        activePrivatePost = newPost(activePrivate, "alpha");
        deletedPrivatePost = newPost(deletedPrivate, "alpha");
        em.flush();
    }

    // ---------- helpers ----------

    private String token(UUID userId) {
        return "Bearer " + jwt.generateAccessToken(users.findById(userId).orElseThrow());
    }

    private Community privateCommunity() {
        Community c = communityService.create(owner, "cdel_" + UUID.randomUUID().toString().substring(0, 8), "private test", "private");
        em.flush();
        return c;
    }

    private UUID newPost(Community c, String body) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO posts (id, community_id, author_id, kind, title, body, hot_rank, rising_rank, controversial_rank, score)"
                + " VALUES (?, ?, ?, 'text', ?, ?, ?, ?, ?, ?)",
                id, c.getId(), owner, "title " + id, body, (double) BIG, (double) BIG, (double) BIG, BIG);
        return id;
    }

    private void delete(Community... cs) {
        for (Community c : cs) {
            jdbc.update("UPDATE communities SET deleted_at = now(), deleted_by = ? WHERE id = ?", owner, c.getId());
        }
        em.flush();
        em.clear(); // entities loaded before the UPDATE would hide it
    }

    private static Set<UUID> ids(List<Post> list) {
        return list.stream().map(Post::getId).collect(Collectors.toSet());
    }

    private Set<UUID> mine(Set<UUID> all) {
        Set<UUID> ours = Set.of(activePublicPost, deletedPublicPost, activePrivatePost, deletedPrivatePost);
        return all.stream().filter(ours::contains).collect(Collectors.toSet());
    }

    // ---------- communities ----------

    @Test
    void deletedCommunityIsAbsentFromNewPopularAndSearch() {
        jdbc.update("UPDATE communities SET subscriber_count = ? WHERE id IN (?, ?)", BIG, activePublic.getId(), deletedPublic.getId());
        delete(deletedPublic);

        var fresh = communityService.browseNew(FAR_FUTURE, MAX_ID, 200).stream().map(Community::getId).toList();
        assertTrue(fresh.contains(activePublic.getId()));
        assertFalse(fresh.contains(deletedPublic.getId()));

        var popular = communityService.browsePopular(Integer.MAX_VALUE, MAX_ID, 200).stream().map(Community::getId).toList();
        assertTrue(popular.contains(activePublic.getId()));
        assertFalse(popular.contains(deletedPublic.getId()));

        assertTrue(communityService.searchByName(activePublic.getName()).stream().anyMatch(c -> c.getId().equals(activePublic.getId())));
        assertTrue(communityService.searchByName(deletedPublic.getName()).isEmpty());
    }

    @Test
    void deletedCommunityIsNotFoundByNameOrViewAccessAndTheNameStaysTaken() {
        delete(deletedPublic, deletedPrivate);

        assertEquals(activePublic.getId(), communityService.findByName(activePublic.getName()).getId());
        assertThrows(NotFoundException.class, () -> communityService.findByName(deletedPublic.getName()));
        assertThrows(NotFoundException.class, () -> communityService.requireViewAccess(owner, deletedPublic.getId()));
        // a deleted PRIVATE community is 404 even for its own (still-member) creator - never "not private -> allowed"
        assertThrows(NotFoundException.class, () -> communityService.requireViewAccess(owner, deletedPrivate.getId()));
        assertThrows(NotFoundException.class, () -> communityService.requireViewAccess(null, deletedPrivate.getId()));
        communityService.requireViewAccess(owner, activePrivate.getId());
        // reserved: deleting must not free the name for re-registration (not part of this step to change)
        assertThrows(RuntimeException.class, () -> communityService.create(outsider, deletedPublic.getName(), "again", "public"));
    }

    @Test
    void namesAndIconsOfDeletedCommunitiesAreOmitted() {
        delete(deletedPublic);
        var names = communityService.findNamesByIds(Set.of(activePublic.getId(), deletedPublic.getId()));
        assertEquals(activePublic.getName(), names.get(activePublic.getId()));
        assertFalse(names.containsKey(deletedPublic.getId()));
        assertTrue(communityService.findIconUrlsByIds(Set.of(deletedPublic.getId())).isEmpty());
    }

    @Test
    void endpointsOfADeletedCommunityAre404() throws Exception {
        delete(deletedPublic, deletedPrivate);

        mvc.perform(get("/r/{n}/about", activePublic.getName())).andExpect(status().isOk());
        for (String name : List.of(deletedPublic.getName(), deletedPrivate.getName())) {
            mvc.perform(get("/r/{n}/new", name)).andExpect(status().isNotFound());
            mvc.perform(get("/r/{n}/hot", name).header("Authorization", token)).andExpect(status().isNotFound());
            mvc.perform(get("/r/{n}/about", name)).andExpect(status().isNotFound());
            mvc.perform(get("/r/{n}/rules", name)).andExpect(status().isNotFound());
            mvc.perform(get("/r/{n}/search", name).param("q", "alpha")).andExpect(status().isNotFound());
            mvc.perform(post("/r/{n}/join", name).header("Authorization", token)).andExpect(status().isNotFound());
        }
    }

    // ---------- site-wide posts ----------

    private void assertOnlyActiveVisible(UUID viewer, boolean viewerSeesActivePrivate) {
        Set<UUID> expected = viewerSeesActivePrivate ? Set.of(activePublicPost, activePrivatePost) : Set.of(activePublicPost);

        assertEquals(expected, mine(ids(posts.findNewAllPage(FAR_FUTURE, MAX_ID, viewer, PAGE))), "new");
        assertEquals(expected, mine(ids(posts.findHotAllPage(Double.MAX_VALUE, MAX_ID, viewer, PAGE))), "hot");
        assertEquals(expected, mine(ids(posts.findTopAllPage(Instant.now().minus(1, ChronoUnit.DAYS), Integer.MAX_VALUE, MAX_ID, viewer, PAGE))), "top");
        assertEquals(expected, mine(ids(posts.findRisingAllPage(Double.MAX_VALUE, MAX_ID, viewer, PAGE))), "rising");
        assertEquals(expected, mine(ids(posts.findControversialAllPage(Double.MAX_VALUE, MAX_ID, viewer, PAGE))), "controversial");
        assertEquals(expected, mine(ids(posts.findByAuthorId(owner, FAR_FUTURE, MAX_ID, viewer, PAGE))), "author");
        assertEquals(expected, mine(Set.copyOf(posts.searchAllIds("alpha", "alpha:*", viewer))), "search");
    }

    @Test
    void deletedCommunityPostsAreAbsentFromEverySitewideRead() {
        delete(deletedPublic, deletedPrivate);
        assertOnlyActiveVisible(owner, true);   // the member/moderator of the (active) private community
        assertOnlyActiveVisible(outsider, false);
        assertOnlyActiveVisible(null, false);
    }

    @Test
    void savedTabDropsPostsOfDeletedCommunities() {
        for (UUID p : List.of(activePublicPost, deletedPublicPost, activePrivatePost, deletedPrivatePost)) {
            jdbc.update("INSERT INTO saved_items (user_id, target_type, target_id) VALUES (?, 'post', ?)", owner, p);
        }
        Set<UUID> before = mine(ids(posts.findSavedPage(owner, FAR_FUTURE, MAX_ID, PAGE)));
        assertEquals(4, before.size(), "control: all four are saved and visible before the delete");

        delete(deletedPublic, deletedPrivate);
        assertEquals(Set.of(activePublicPost, activePrivatePost), mine(ids(posts.findSavedPage(owner, FAR_FUTURE, MAX_ID, PAGE))));
    }

    // ---------- the critical case: DELETED + PRIVATE must never turn PUBLIC ----------

    @Test
    void deletedPrivateCommunityPostsNeverAppearInRAllForAnyone() {
        // control: before the delete the private post is visible to its member and invisible to everyone else
        assertTrue(ids(posts.findNewAllPage(FAR_FUTURE, MAX_ID, owner, PAGE)).contains(deletedPrivatePost));
        assertFalse(ids(posts.findNewAllPage(FAR_FUTURE, MAX_ID, null, PAGE)).contains(deletedPrivatePost));
        assertFalse(ids(posts.findNewAllPage(FAR_FUTURE, MAX_ID, outsider, PAGE)).contains(deletedPrivatePost));

        delete(deletedPrivate);

        // the community row, its type and its membership rows are all untouched by a soft delete...
        assertEquals("private", jdbc.queryForObject("SELECT type FROM communities WHERE id = ?", String.class, deletedPrivate.getId()));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM memberships WHERE community_id = ? AND user_id = ?",
                Integer.class, deletedPrivate.getId(), owner));
        // ...yet nobody - not even that still-listed member, and not anonymous - sees its posts in any site-wide read
        for (UUID viewer : new UUID[]{owner, outsider, null}) {
            assertFalse(ids(posts.findNewAllPage(FAR_FUTURE, MAX_ID, viewer, PAGE)).contains(deletedPrivatePost), "new " + viewer);
            assertFalse(ids(posts.findHotAllPage(Double.MAX_VALUE, MAX_ID, viewer, PAGE)).contains(deletedPrivatePost), "hot " + viewer);
            assertFalse(ids(posts.findControversialAllPage(Double.MAX_VALUE, MAX_ID, viewer, PAGE)).contains(deletedPrivatePost));
            assertFalse(posts.searchAllIds("alpha", "alpha:*", viewer).contains(deletedPrivatePost), "search " + viewer);
            assertFalse(ids(posts.findByAuthorId(owner, FAR_FUTURE, MAX_ID, viewer, PAGE)).contains(deletedPrivatePost));
        }
    }

    @Test
    void anActivePrivateCommunityStillObeysMembershipVisibility() {
        delete(deletedPublic, deletedPrivate); // unrelated deletions must not disturb the private-membership rule
        assertTrue(ids(posts.findNewAllPage(FAR_FUTURE, MAX_ID, owner, PAGE)).contains(activePrivatePost));
        assertFalse(ids(posts.findNewAllPage(FAR_FUTURE, MAX_ID, outsider, PAGE)).contains(activePrivatePost));
        assertFalse(ids(posts.findNewAllPage(FAR_FUTURE, MAX_ID, null, PAGE)).contains(activePrivatePost));
    }

    // ---------- comments ----------

    private UUID comment(UUID postId, String body) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO comments (id, post_id, path, depth, author_id, body) VALUES (?, ?, text2ltree(replace(?, '-', '_')), 0, ?, ?)",
                id, postId, id.toString(), owner, body);
        return id;
    }

    @Test
    void commentsOfDeletedCommunitiesAreAbsentFromSearchAndTheAuthorsOwnComments() {
        UUID activeC = comment(activePublicPost, "bravo comment");
        UUID deletedC = comment(deletedPublicPost, "bravo comment");
        UUID activePrivC = comment(activePrivatePost, "bravo comment");
        UUID deletedPrivC = comment(deletedPrivatePost, "bravo comment");
        UUID orphan = comment(UUID.randomUUID(), "bravo comment"); // comments.post_id has no FK: stays visible, as before
        em.flush();
        Set<UUID> all = Set.of(activeC, deletedC, activePrivC, deletedPrivC, orphan);

        delete(deletedPublic, deletedPrivate);

        for (UUID viewer : new UUID[]{owner, outsider, null}) {
            Set<UUID> found = Set.copyOf(comments.searchIds(null, "bravo", "bravo:*", viewer)).stream().filter(all::contains).collect(Collectors.toSet());
            assertTrue(found.contains(activeC), "search active " + viewer);
            assertFalse(found.contains(deletedC), "search deleted " + viewer);
            assertFalse(found.contains(deletedPrivC), "search deleted private " + viewer);

            Set<UUID> own = comments.findByAuthorId(owner, FAR_FUTURE, MAX_ID, viewer, PAGE).stream()
                    .map(Comment::getId).filter(all::contains).collect(Collectors.toSet());
            assertTrue(own.contains(activeC), "own active " + viewer);
            assertTrue(own.contains(orphan), "own orphan " + viewer);
            assertFalse(own.contains(deletedC), "own deleted " + viewer);
            assertFalse(own.contains(deletedPrivC), "own deleted private " + viewer);
        }
        // the member of the active private community still sees its comment, the outsider does not
        assertTrue(comments.findByAuthorId(owner, FAR_FUTURE, MAX_ID, owner, PAGE).stream().anyMatch(c -> c.getId().equals(activePrivC)));
        assertFalse(comments.findByAuthorId(owner, FAR_FUTURE, MAX_ID, outsider, PAGE).stream().anyMatch(c -> c.getId().equals(activePrivC)));
    }

    // ---------- other reads ----------

    @Test
    void aCrosspostOfAPostInADeletedCommunityShowsTheParentAsUnavailable() {
        UUID childOfActive = crosspost(activePublicPost);
        UUID childOfDeleted = crosspost(deletedPublicPost);
        em.flush();
        delete(deletedPublic);

        List<Post> page = postService.findNewAllPage(FAR_FUTURE, MAX_ID, null, 200);
        Post ok = page.stream().filter(p -> p.getId().equals(childOfActive)).findFirst().orElseThrow();
        Post gone = page.stream().filter(p -> p.getId().equals(childOfDeleted)).findFirst().orElseThrow();
        assertTrue(ok.getCrosspostParent().available(), "control: parent in an active community");
        assertFalse(gone.getCrosspostParent().available());
        assertEquals(null, gone.getCrosspostParent().title(), "no parent content leaks");
        assertEquals(null, gone.getCrosspostParent().communityName());
    }

    @Test
    void crosspostCountIgnoresCrosspostsInDeletedCommunities() {
        crosspost(activePublicPost);                                   // lives in the active community
        UUID inDeleted = crosspostIn(deletedPublic, activePublicPost); // lives in the community about to be deleted
        em.flush();
        assertEquals(2, countFor(activePublicPost), "control: both crossposts counted before the delete");

        delete(deletedPublic);

        assertEquals(1, countFor(activePublicPost), "the deleted community's crosspost no longer counts");
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM posts WHERE id = ?", Integer.class, inDeleted) == 1, "row itself untouched");
    }

    private int countFor(UUID parent) {
        return postService.findNewAllPage(FAR_FUTURE, MAX_ID, null, 200).stream()
                .filter(p -> p.getId().equals(parent)).findFirst().orElseThrow().getCrosspostCount();
    }

    private UUID crosspostIn(Community c, UUID parent) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO posts (id, community_id, author_id, kind, title, body, crosspost_of, hot_rank, score)"
                + " VALUES (?, ?, ?, 'text', 'xp', 'xp', ?, 0, 0)", id, c.getId(), owner, parent);
        return id;
    }

    private UUID crosspost(UUID parent) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO posts (id, community_id, author_id, kind, title, body, crosspost_of, hot_rank, score)"
                + " VALUES (?, ?, ?, 'text', 'xp', 'xp', ?, ?, ?)", id, activePublic.getId(), owner, parent, (double) BIG, BIG);
        return id;
    }

    @Test
    void scheduledPostListHidesDeletedCommunities() {
        UUID inActive = scheduledPost(activePublic);
        UUID inDeleted = scheduledPost(deletedPublic);
        em.flush();
        assertEquals(Set.of(inActive, inDeleted), scheduled.list(owner).stream().map(s -> s.id()).collect(Collectors.toSet()));

        delete(deletedPublic);
        assertEquals(Set.of(inActive), scheduled.list(owner).stream().map(s -> s.id()).collect(Collectors.toSet()));
        // read-only: the row itself is untouched (cancellation is a later step)
        assertEquals("pending", jdbc.queryForObject("SELECT status FROM scheduled_posts WHERE id = ?", String.class, inDeleted));
    }

    private UUID scheduledPost(Community c) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO scheduled_posts (id, author_id, community_id, payload, publish_at) VALUES (?, ?, ?, '{\"title\":\"t\",\"kind\":\"text\"}'::jsonb, now() + interval '1 day')",
                id, owner, c.getId());
        return id;
    }

    @Test
    void notificationsDoNotResolveADeletedCommunityName() {
        delete(deletedPublic);
        assertFalse(communityService.findNamesByIds(Set.of(deletedPublic.getId())).containsKey(deletedPublic.getId()));
        assertEquals(activePublic.getName(), communityService.findNamesByIds(Set.of(activePublic.getId())).get(activePublic.getId()));
    }
}
