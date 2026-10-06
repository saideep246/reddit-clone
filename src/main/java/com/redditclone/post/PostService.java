package com.redditclone.post;

import com.redditclone.auth.AuthService;
import com.redditclone.common.KarmaEvent;
import com.redditclone.common.ModerationAuditWriter;
import com.redditclone.common.RankFormulas;
import com.redditclone.common.UuidV7Generator;
import com.redditclone.common.VoteDelta;
import com.redditclone.common.exception.BadRequestException;
import com.redditclone.common.exception.ForbiddenException;
import com.redditclone.common.exception.NotFoundException;
import com.redditclone.common.text.Sanitizer;
import com.redditclone.common.text.SearchTerms;
import com.redditclone.community.CommunityModerator;
import com.redditclone.community.CommunityService;
import com.redditclone.community.Flair;
import com.redditclone.media.Media;
import com.redditclone.media.MediaService;
import com.redditclone.media.MediaView;
import com.redditclone.post.dto.CreatePostRequest;
import com.redditclone.post.dto.CrosspostParent;
import com.redditclone.post.dto.PollView;
import com.redditclone.post.dto.PostEditView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class PostService {

    private final PostRepository posts;
    private final PostMediaRepository postMedia;
    private final UuidV7Generator ids;
    private final StringRedisTemplate redis;
    private final Sanitizer sanitizer;
    private final NamedParameterJdbcTemplate jdbc;
    private final CommunityService communityService;
    private final AuthService authService;
    private final MediaService mediaService;
    private final ModerationAuditWriter auditWriter;
    private final int maxPinnedPosts;
    private final int titleEditWindowMinutes;

    public PostService(PostRepository posts, PostMediaRepository postMedia, UuidV7Generator ids,
                        StringRedisTemplate redis, Sanitizer sanitizer, NamedParameterJdbcTemplate jdbc,
                        CommunityService communityService, AuthService authService, MediaService mediaService,
                        ModerationAuditWriter auditWriter,
                        @Value("${app.moderation.max-pinned-posts}") int maxPinnedPosts,
                        @Value("${app.posts.title-edit-window-minutes:10}") int titleEditWindowMinutes) {
        this.posts = posts;
        this.postMedia = postMedia;
        this.ids = ids;
        this.redis = redis;
        this.sanitizer = sanitizer;
        this.jdbc = jdbc;
        this.communityService = communityService;
        this.authService = authService;
        this.mediaService = mediaService;
        this.auditWriter = auditWriter;
        this.maxPinnedPosts = maxPinnedPosts;
        this.titleEditWindowMinutes = titleEditWindowMinutes;
    }

    @Transactional
    public Post create(UUID authorId, UUID communityId, CreatePostRequest req, String idempotencyKey) {
        String key = "idempotency:" + authorId + ":" + idempotencyKey;
        UUID newId = ids.nextId();
        // Claim the key BEFORE inserting (SETNX-first), not after: a GET-then-insert-then-SETNX order
        // leaves a window where two concurrent requests both see no existing key and both insert a row.
        Boolean claimed = redis.opsForValue().setIfAbsent(key, newId.toString(), Duration.ofHours(24));
        if (!Boolean.TRUE.equals(claimed)) {
            String existingPostId = redis.opsForValue().get(key);
            return attachAll(posts.findById(UUID.fromString(existingPostId))
                    .orElseThrow(() -> new NotFoundException("post not found")));
        }
        try {
            communityService.requireNotBanned(authorId, communityId);
            communityService.requirePostAccess(authorId, communityId);
            // Never trust a client-supplied mediaId without checking it resolves to something real, owned
            // by the caller, and actually usable — same principle already applied to vote targets. Runs
            // whenever mediaId is present, regardless of kind (a text/link post with a mediaId is exactly
            // as untrusted as an image/video one), and expectedKind=req.kind() rejects a mismatch between
            // the media's real type and what the post claims to be — including any text/link kind, since
            // Media.mediaType is never anything but "image"/"video". Placed here, inside the try block, so
            // a rejection releases the idempotency key above instead of permanently poisoning it.
            Media validatedMedia = null;
            if (req.mediaId() != null) {
                validatedMedia = mediaService.requireOwnedAndUsable(req.mediaId(), authorId, req.kind());
            }
            // Same principle as mediaId above, batched: a gallery's images are validated as a list (owned,
            // ready, and every one actually an "image") in the caller's own chosen order, so that order can
            // be written straight into post_media.position below with no extra re-sorting.
            List<Media> validatedGalleryMedia = null;
            if ("gallery".equals(req.kind())) {
                validatedGalleryMedia = mediaService.requireOwnedAndUsableBatch(req.mediaIds(), authorId, "image");
            }
            // Same never-trust-a-client-id principle as mediaId above, applied to flair — always optional
            // regardless of kind, so a null flairId is never rejected, only a present-but-invalid one.
            Flair validatedFlair = null;
            if (req.flairId() != null) {
                validatedFlair = communityService.requireFlairUsable(communityId, req.flairId(), "post");
            }
            UUID crosspostRoot = "crosspost".equals(req.kind()) ? resolveCrosspostRoot(authorId, req.crosspostOf()) : null;
            String title = sanitizer.sanitize(req.title());
            String body = sanitizer.sanitize(req.body());
            Post p = new Post();
            p.setId(newId);
            p.setCommunityId(communityId);
            p.setAuthorId(authorId);
            p.setKind(req.kind());
            p.setTitle(title);
            p.setBody(body);
            p.setUrl(req.url());
            p.setCrosspostOf(crosspostRoot);
            p.setMediaId(req.mediaId());
            p.setFlairId(req.flairId());
            p.setNsfw(req.nsfw());
            p.setSpoiler(req.spoiler());
            // Unlike controversial_rank/rising_rank, hot_rank's formula isn't 0 at zero votes (it also
            // carries a time term) — without this, every new post sits at the column default of 0 until its
            // first vote, sorting below any post that's ever been voted on, regardless of how new it is.
            p.setHotRank(RankFormulas.hotRank(0, p.getCreatedAt()));
            // Evaluated after the id is assigned but before save(), so a "remove" verdict is reflected in the
            // very first row written (never a visible-then-removed flash) and the audit/report rows automod
            // writes can reference a real, already-decided target id.
            int authorKarma = authService.getKarmaPost(authorId);
            if (communityService.evaluateAutomod(communityId, "post", newId, title, body, authorKarma)) {
                p.setRemoved(true);
            }
            // Polls insert their option rows with plain JDBC below, so the post row must already exist (flushed).
            if ("poll".equals(req.kind())) {
                posts.saveAndFlush(p);
            } else {
                posts.save(p);
            }
            if ("poll".equals(req.kind())) {
                insertPoll(p.getId(), req.pollOptions(), req.pollDays() == null ? 3 : req.pollDays());
            }
            if (validatedGalleryMedia != null) {
                List<PostMedia> rows = new ArrayList<>();
                for (short i = 0; i < validatedGalleryMedia.size(); i++) {
                    rows.add(new PostMedia(p.getId(), validatedGalleryMedia.get(i).getId(), i));
                }
                postMedia.saveAll(rows);
                // Reuse the rows requireOwnedAndUsableBatch already fetched instead of a second query a
                // moment later via attachGalleryMedia() for rows that can't have changed since.
                p.setMediaItems(validatedGalleryMedia.stream().map(mediaService::toMediaView).toList());
            }
            if (validatedFlair != null) {
                // Reuse the row requireFlairUsable already fetched instead of a second findAllById round
                // trip a moment later via attachFlair() for a row that can't have changed since.
                p.setFlair(validatedFlair);
            }
            if (validatedMedia != null) {
                // Reuse the row requireOwnedAndUsable already fetched instead of a second findAllById
                // round trip a moment later via attachMedia() for a row that can't have changed since.
                p.setMedia(mediaService.toMediaView(validatedMedia));
                return withExtras(attachCommunityName(attachAuthorUsername(p)));
            }
            if (validatedGalleryMedia != null) {
                return withExtras(attachCommunityName(attachAuthorUsername(p)));
            }
            return withExtras(attachCommunityName(attachAuthorUsername(attachMedia(p))));
        } catch (RuntimeException e) {
            // The Redis claim above is outside this method's @Transactional boundary, so rolling back the
            // DB insert (e.g. on a ForbiddenException from requireNotBanned) doesn't undo it — release the
            // key so it doesn't point at a post that was never created, which would otherwise block any
            // retry with the same Idempotency-Key for 24h behind a misleading 404.
            redis.delete(key);
            throw e;
        }
    }

    public List<Post> findNewPage(UUID communityId, Instant cursorCreatedAt, UUID cursorId, UUID viewerId, int limit) {
        return attachAll(posts.findNewPage(communityId, cursorCreatedAt, cursorId, viewerId, Pageable.ofSize(limit)));
    }

    // Sitewide "r/all" counterpart — same query minus the communityId predicate (see PostRepository).
    // PostController branches on communityName.equalsIgnoreCase("all") to call these instead of the
    // per-community methods above; CommunityService.create() rejects a real community ever being named
    // "all" so the two can never collide.
    public List<Post> findNewAllPage(Instant cursorCreatedAt, UUID cursorId, UUID viewerId, int limit) {
        return attachAll(posts.findNewAllPage(cursorCreatedAt, cursorId, viewerId, Pageable.ofSize(limit)));
    }

    // F7's "submitted" tab on a user's profile page. Resolves the username the same way
    // comment.CommentService resolves u/-mentions (authService.findUserIdByUsername) rather than post
    // reaching into auth.UserRepository directly, which ModuleBoundaryTest forbids.
    public List<Post> findSubmittedByUsername(String username, Instant cursorCreatedAt, UUID cursorId,
                                               UUID viewerId, int limit) {
        UUID authorId = authService.findUserIdByUsername(username)
                .orElseThrow(() -> new NotFoundException("no such user"));
        return attachAll(posts.findByAuthorId(authorId, cursorCreatedAt, cursorId, viewerId, Pageable.ofSize(limit)));
    }

    // A user's own "saved" tab — same shape as findSubmittedByUsername, but the caller is always the
    // viewer themselves (no separate viewerId) and the sort key is SavedItem.savedAt, not Post.createdAt.
    public List<Post> findSavedByUser(UUID userId, Instant cursorSavedAt, UUID cursorId, int limit) {
        return attachAll(posts.findSavedPage(userId, cursorSavedAt, cursorId, Pageable.ofSize(limit)));
    }

    // Deliberately does NOT attach media — used internally by other services (ban checks, comment-reply's
    // post lookup, moderation target checks) that don't display the post and shouldn't pay for an extra
    // query they don't need. findByIdWithMedia below is for the display path.
    public Post findById(UUID postId) {
        return posts.findById(postId).orElseThrow(() -> new NotFoundException("post not found"));
    }

    public Post findByIdWithMedia(UUID postId) {
        return attachAll(findById(postId));
    }

    // Batched, no media/flair/display-field attach — same "internal lookup, not a display path" reasoning
    // as findById above. Used by comment.CommentService.findByAuthor (F7) to resolve a page of the user's
    // comments' post titles/communityIds in one query instead of one per comment.
    public List<Post> findAllByIds(Set<UUID> ids) {
        return posts.findAllById(ids);
    }

    // For ModerationService's human-initiated removal path — Post already has a public `removed` setter
    // (unlike score/hot_rank/etc, it was never migrated to the raw-SQL-bulk-only pattern).
    @Transactional
    public void markRemoved(UUID postId) {
        Post p = findById(postId);
        p.setRemoved(true);
        posts.save(p);
    }

    // body is editable on text posts at any time; title and (link-post) url only inside a short grace window
    // after posting, so a post can't be silently rewritten under the votes and comments it has gathered.
    // Every change snapshots the previous values into post_edits first.
    @Transactional
    public Post edit(UUID actorId, UUID communityId, UUID postId, String body, String title, String url) {
        if (body == null && title == null && url == null) {
            throw new BadRequestException("nothing to edit");
        }
        Post p = requireAuthoredPost(actorId, communityId, postId);
        if (p.isDeleted()) {
            throw new NotFoundException("post not found");
        }
        if (body != null && body.isBlank()) {
            throw new BadRequestException("body must not be blank");
        }
        if (title != null && title.isBlank()) {
            throw new BadRequestException("title must not be blank");
        }
        if (body != null && !"text".equals(p.getKind())) {
            throw new BadRequestException("only text posts have an editable body");
        }
        if (url != null && !"link".equals(p.getKind())) {
            throw new BadRequestException("only link posts have an editable url");
        }
        if (url != null && !url.matches("^https?://\\S+$")) {
            throw new BadRequestException("url must start with http:// or https://");
        }
        if ((title != null || url != null)
                && Instant.now().isAfter(p.getCreatedAt().plus(Duration.ofMinutes(titleEditWindowMinutes)))) {
            throw new ForbiddenException("title and link can only be edited within " + titleEditWindowMinutes
                    + " minutes of posting");
        }
        jdbc.update("""
                INSERT INTO post_edits (id, post_id, editor_id, old_title, old_body, old_url)
                VALUES (:id, :postId, :editorId, :title, :body, :url)
                """, new MapSqlParameterSource().addValue("id", ids.nextId()).addValue("postId", postId)
                .addValue("editorId", actorId).addValue("title", p.getTitle()).addValue("body", p.getBody())
                .addValue("url", p.getUrl()));
        if (title != null) {
            p.setTitle(sanitizer.sanitize(title));
        }
        if (body != null) {
            p.setBody(sanitizer.sanitize(body));
        }
        if (url != null) {
            p.setUrl(url);
        }
        p.setEditedAt(Instant.now());
        if (communityService.evaluateAutomod(p.getCommunityId(), "post", postId, p.getTitle(), p.getBody(),
                authService.getKarmaPost(actorId))) {
            p.setRemoved(true);
        }
        posts.save(p);
        return attachAll(p);
    }

    // Revision history: the author, or a moderator who could act on the post (PERM_REMOVE_CONTENT), newest
    // first. Everyone else gets 403 rather than 404 — the post itself is public, only its past is not.
    public List<PostEditView> history(UUID actorId, UUID communityId, UUID postId) {
        Post p = findById(postId);
        if (!p.getCommunityId().equals(communityId)) {
            throw new NotFoundException("post not found");
        }
        if (!p.getAuthorId().equals(actorId)) {
            communityService.requirePermission(actorId, communityId, CommunityModerator.PERM_REMOVE_CONTENT);
        }
        return jdbc.query("""
                SELECT id, editor_id, old_title, old_body, old_url, edited_at FROM post_edits
                WHERE post_id = :postId ORDER BY edited_at DESC
                """, new MapSqlParameterSource("postId", postId),
                (rs, i) -> new PostEditView(rs.getObject("id", UUID.class), rs.getObject("editor_id", UUID.class),
                        rs.getString("old_title"), rs.getString("old_body"), rs.getString("old_url"),
                        rs.getTimestamp("edited_at").toInstant()));
    }

    // Tombstones rather than hard-deleting: the row, its comment thread, and its ranking history stay so
    // replies keep their place in the tree. Content is wiped here (not just hidden at read time). Two
    // leaks remain by design: the anonymous /hot page cache (FeedCacheService, 45s TTL) can serve the
    // original title, body and media until it expires, and deleted media objects stay at their public URLs.
    @Transactional
    public void delete(UUID actorId, UUID communityId, UUID postId) {
        Post p = findById(postId);
        if (!p.getCommunityId().equals(communityId)) {
            throw new NotFoundException("post not found");
        }
        boolean isAuthor = p.getAuthorId().equals(actorId);
        if (isAuthor) {
            // Moderator removal is final for the author — same rule as requireAuthoredPost.
            if (p.isRemoved()) {
                throw new ForbiddenException("this post has been removed by moderators");
            }
        } else {
            // Moderators/owner (the owner's bitmask covers every bit) may delete anyone's post.
            communityService.requirePermission(actorId, communityId, CommunityModerator.PERM_REMOVE_CONTENT);
        }
        if (p.isDeleted()) {
            return;
        }
        p.setDeleted(true);
        p.setTitle("[deleted]");
        p.setBody(null);
        p.setUrl(null);
        p.setMediaId(null);
        p.setFlairId(null);
        p.setPinned(false);
        posts.save(p);
        postMedia.deleteByPostId(postId);
        if (!isAuthor) {
            auditWriter.logAction(communityId, actorId, "remove_post", "post", postId, null);
        }
        jdbc.update("DELETE FROM mod_queue WHERE community_id = :c AND target_type = 'post' AND target_id = :t",
                new MapSqlParameterSource().addValue("c", communityId).addValue("t", postId));
    }

    // Shared author gate for edit and delete (house style: MediaService.requireOwner). Moderator removal is
    // final for the author — a removed post can't be edited or deleted by whoever wrote it.
    private Post requireAuthoredPost(UUID actorId, UUID communityId, UUID postId) {
        Post p = findById(postId);
        if (!p.getCommunityId().equals(communityId)) {
            throw new NotFoundException("post not found");
        }
        if (!p.getAuthorId().equals(actorId)) {
            throw new ForbiddenException("not the author of this post");
        }
        if (p.isRemoved()) {
            throw new ForbiddenException("this post has been removed by moderators");
        }
        communityService.requireNotBanned(actorId, communityId);
        communityService.requirePostAccess(actorId, communityId);
        return p;
    }

    // No permission check here — same convention as setFlair, ModerationController checks
    // PERM_MANAGE_POSTS before calling. Pinned posts deliberately don't fold into /new or /hot's sort
    // order (see the feature's plan) — findPinned below is the only place they're surfaced together.
    @Transactional
    public void setPinned(UUID postId, UUID communityId, boolean pinned) {
        Post p = findById(postId);
        if (!p.getCommunityId().equals(communityId)) {
            throw new NotFoundException("post not found");
        }
        if (p.isDeleted()) {
            throw new NotFoundException("post not found");
        }
        if (pinned && posts.countByCommunityIdAndPinnedTrue(communityId) >= maxPinnedPosts) {
            throw new BadRequestException("this community already has the maximum number of pinned posts");
        }
        p.setPinned(pinned);
        posts.save(p);
    }

    @Transactional
    public void setLocked(UUID postId, UUID communityId, boolean locked) {
        Post p = findById(postId);
        if (!p.getCommunityId().equals(communityId)) {
            throw new NotFoundException("post not found");
        }
        p.setLocked(locked);
        posts.save(p);
    }

    public List<Post> findPinned(UUID communityId) {
        return attachAll(posts.findByCommunityIdAndPinnedTrueAndRemovedFalseAndDeletedFalseOrderByCreatedAtDesc(communityId));
    }

    // No pagination — a relevance ranking (ts_rank) isn't a stable keyset sort key the way created_at/
    // score are, so this returns a single page, same as the source plan's own search sketch.
    public List<Post> search(UUID communityId, String query) {
        List<UUID> rankedIds = posts.searchIds(communityId, query, SearchTerms.prefixTsQuery(query));
        if (rankedIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, Post> byId = new HashMap<>();
        posts.findAllById(rankedIds).forEach(p -> byId.put(p.getId(), p));
        return attachAll(rankedIds.stream().map(byId::get).filter(Objects::nonNull).toList());
    }

    // Sitewide "r/all" counterpart of search above — same rank-then-refetch shape, backed by a query that
    // excludes private communities the viewer can't see instead of a single community_id filter.
    public List<Post> searchAll(String query, UUID viewerId) {
        List<UUID> rankedIds = posts.searchAllIds(query, SearchTerms.prefixTsQuery(query), viewerId);
        if (rankedIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, Post> byId = new HashMap<>();
        posts.findAllById(rankedIds).forEach(p -> byId.put(p.getId(), p));
        return attachAll(rankedIds.stream().map(byId::get).filter(Objects::nonNull).toList());
    }

    public void incrementCommentCount(UUID postId) {
        posts.incrementCommentCount(postId);
    }

    public List<Post> findHotPage(UUID communityId, double cursorRank, UUID cursorId, UUID viewerId, int limit) {
        return attachAll(posts.findHotPage(communityId, cursorRank, cursorId, viewerId, Pageable.ofSize(limit)));
    }

    public List<Post> findHotAllPage(double cursorRank, UUID cursorId, UUID viewerId, int limit) {
        return attachAll(posts.findHotAllPage(cursorRank, cursorId, viewerId, Pageable.ofSize(limit)));
    }

    public List<Post> findTopPage(UUID communityId, Instant since, double cursorRank, UUID cursorId, UUID viewerId, int limit) {
        return attachAll(posts.findTopPage(communityId, since, (int) cursorRank, cursorId, viewerId, Pageable.ofSize(limit)));
    }

    public List<Post> findTopAllPage(Instant since, double cursorRank, UUID cursorId, UUID viewerId, int limit) {
        return attachAll(posts.findTopAllPage(since, (int) cursorRank, cursorId, viewerId, Pageable.ofSize(limit)));
    }

    public List<Post> findRisingPage(UUID communityId, double cursorRank, UUID cursorId, UUID viewerId, int limit) {
        return attachAll(posts.findRisingPage(communityId, cursorRank, cursorId, viewerId, Pageable.ofSize(limit)));
    }

    public List<Post> findRisingAllPage(double cursorRank, UUID cursorId, UUID viewerId, int limit) {
        return attachAll(posts.findRisingAllPage(cursorRank, cursorId, viewerId, Pageable.ofSize(limit)));
    }

    public List<Post> findControversialPage(UUID communityId, double cursorRank, UUID cursorId, UUID viewerId, int limit) {
        return attachAll(posts.findControversialPage(communityId, cursorRank, cursorId, viewerId, Pageable.ofSize(limit)));
    }

    public List<Post> findControversialAllPage(double cursorRank, UUID cursorId, UUID viewerId, int limit) {
        return attachAll(posts.findControversialAllPage(cursorRank, cursorId, viewerId, Pageable.ofSize(limit)));
    }

    // Single batched IN query, never N+1 — called at the end of every page-returning method above (plus
    // create()'s single-post return) rather than from PostController, so the /hot cache path is
    // automatically correct: media is attached before the listing is serialized and cached.
    private List<Post> attachMedia(List<Post> page) {
        Set<UUID> mediaIds = new HashSet<>();
        for (Post p : page) {
            if (p.getMediaId() != null) {
                mediaIds.add(p.getMediaId());
            }
        }
        if (mediaIds.isEmpty()) {
            return page;
        }
        Map<UUID, MediaView> views = mediaService.getMediaViews(mediaIds);
        for (Post p : page) {
            if (p.getMediaId() != null) {
                p.setMedia(views.get(p.getMediaId()));
            }
        }
        return page;
    }

    private Post attachMedia(Post p) {
        if (p.getMediaId() != null) {
            p.setMedia(mediaService.getMediaViews(Set.of(p.getMediaId())).get(p.getMediaId()));
        }
        return p;
    }

    // Same batched-never-N+1 shape as attachMedia above, over the post_media join table instead of a
    // single FK column — only "gallery"-kind posts in the page ever have rows here. One query for every
    // (post_id, media_id, position) row in the page, one batched mediaService.getMediaViews call across
    // every media id gathered from them, then grouped back into each gallery post's mediaItems in position
    // order (findByPostIdInOrderByPostIdAscPositionAsc already returns rows in that order).
    private List<Post> attachGalleryMedia(List<Post> page) {
        List<UUID> galleryPostIds = new ArrayList<>();
        for (Post p : page) {
            if ("gallery".equals(p.getKind())) {
                galleryPostIds.add(p.getId());
            }
        }
        if (galleryPostIds.isEmpty()) {
            return page;
        }
        List<PostMedia> rows = postMedia.findByPostIdInOrderByPostIdAscPositionAsc(galleryPostIds);
        Set<UUID> mediaIds = new HashSet<>();
        for (PostMedia row : rows) {
            mediaIds.add(row.getMediaId());
        }
        Map<UUID, MediaView> views = mediaService.getMediaViews(mediaIds);
        Map<UUID, List<MediaView>> byPost = new HashMap<>();
        for (PostMedia row : rows) {
            byPost.computeIfAbsent(row.getPostId(), k -> new ArrayList<>()).add(views.get(row.getMediaId()));
        }
        for (Post p : page) {
            if ("gallery".equals(p.getKind())) {
                p.setMediaItems(byPost.getOrDefault(p.getId(), List.of()));
            }
        }
        return page;
    }

    private Post attachGalleryMedia(Post p) {
        if (!"gallery".equals(p.getKind())) {
            return p;
        }
        List<PostMedia> rows = postMedia.findByPostIdInOrderByPostIdAscPositionAsc(List.of(p.getId()));
        Set<UUID> mediaIds = new HashSet<>();
        for (PostMedia row : rows) {
            mediaIds.add(row.getMediaId());
        }
        Map<UUID, MediaView> views = mediaService.getMediaViews(mediaIds);
        List<MediaView> items = new ArrayList<>();
        for (PostMedia row : rows) {
            items.add(views.get(row.getMediaId()));
        }
        p.setMediaItems(items);
        return p;
    }

    // Same batched-IN-query, never-N+1 shape as attachMedia above, via CommunityService.getFlairs.
    private List<Post> attachFlair(List<Post> page) {
        Set<UUID> flairIds = new HashSet<>();
        for (Post p : page) {
            if (p.getFlairId() != null) {
                flairIds.add(p.getFlairId());
            }
        }
        if (flairIds.isEmpty()) {
            return page;
        }
        Map<UUID, Flair> views = communityService.getFlairs(flairIds);
        for (Post p : page) {
            if (p.getFlairId() != null) {
                p.setFlair(views.get(p.getFlairId()));
            }
        }
        return page;
    }

    private Post attachFlair(Post p) {
        if (p.getFlairId() != null) {
            p.setFlair(communityService.getFlairs(Set.of(p.getFlairId())).get(p.getFlairId()));
        }
        return p;
    }

    // Same batched-IN-query shape as attachMedia/attachFlair, via AuthService.findUsernamesByIds (already
    // built for chat's room-summary rendering) — every post has an author, so no null-check gate needed
    // the way media/flair's optional ids have.
    private List<Post> attachAuthorUsername(List<Post> page) {
        if (page.isEmpty()) {
            return page;
        }
        Set<UUID> authorIds = new HashSet<>();
        for (Post p : page) {
            authorIds.add(p.getAuthorId());
        }
        Map<UUID, String> usernames = authService.findUsernamesByIds(authorIds);
        for (Post p : page) {
            p.setAuthorUsername(p.isDeleted() ? null : usernames.get(p.getAuthorId()));
        }
        return page;
    }

    private Post attachAuthorUsername(Post p) {
        if (p.isDeleted()) {
            return p;
        }
        p.setAuthorUsername(authService.findUsernamesByIds(Set.of(p.getAuthorId())).get(p.getAuthorId()));
        return p;
    }

    // Same shape again, via the new CommunityService.findNamesByIds — needed because a sitewide "r/all"
    // page (see findNewAllPage et al.) mixes posts from many communities, so the route alone no longer
    // tells the client which community each post belongs to the way a single-community feed's URL does.
    private List<Post> attachCommunityName(List<Post> page) {
        if (page.isEmpty()) {
            return page;
        }
        Set<UUID> communityIds = new HashSet<>();
        for (Post p : page) {
            communityIds.add(p.getCommunityId());
        }
        Map<UUID, String> names = communityService.findNamesByIds(communityIds);
        for (Post p : page) {
            p.setCommunityName(names.get(p.getCommunityId()));
        }
        return page;
    }

    private Post attachCommunityName(Post p) {
        p.setCommunityName(communityService.findNamesByIds(Set.of(p.getCommunityId())).get(p.getCommunityId()));
        return p;
    }

    private List<Post> attachAll(List<Post> page) {
        return attachCardExtras(attachCrosspostParent(attachPoll(attachCommunityName(attachAuthorUsername(attachFlair(attachGalleryMedia(attachMedia(page))))))));
    }

    private Post attachAll(Post p) {
        attachCardExtras(attachCrosspostParent(attachPoll(List.of(attachCommunityName(attachAuthorUsername(attachFlair(attachGalleryMedia(attachMedia(p)))))))));
        return p;
    }

    // Per-card extras for the Reddit-style action bar and header: the community's icon and the repost count.
    // Both batched across the page (one grouped COUNT query, two lookups for icons).
    private List<Post> attachCardExtras(List<Post> page) {
        if (page.isEmpty()) {
            return page;
        }
        Set<UUID> communityIds = page.stream().map(Post::getCommunityId).collect(Collectors.toSet());
        Map<UUID, String> icons = communityService.findIconUrlsByIds(communityIds);
        List<UUID> postIds = page.stream().map(Post::getId).toList();
        Map<UUID, Integer> reposts = new HashMap<>();
        jdbc.query("""
                SELECT crosspost_of, count(*) AS n FROM posts
                WHERE crosspost_of IN (:ids) AND NOT removed AND NOT deleted GROUP BY crosspost_of
                """, new MapSqlParameterSource("ids", postIds),
                rs -> {
                    reposts.put(rs.getObject("crosspost_of", UUID.class), rs.getInt("n"));
                });
        for (Post p : page) {
            p.setCommunityIconUrl(icons.get(p.getCommunityId()));
            p.setCrosspostCount(reposts.getOrDefault(p.getId(), 0));
        }
        return page;
    }

    // Short share links: /p/<postId> resolves to the canonical /r/<community>/comments/<postId> URL. Removed
    // posts and posts the viewer can't see (private community) resolve to 404, so a share link never confirms
    // that such a post exists.
    public String resolveCommunityName(UUID postId, UUID viewerId) {
        Post p = posts.findById(postId).orElseThrow(() -> new NotFoundException("post not found"));
        if (p.isRemoved()) {
            throw new NotFoundException("post not found");
        }
        try {
            communityService.requireViewAccess(viewerId, p.getCommunityId());
        } catch (ForbiddenException e) {
            throw new NotFoundException("post not found");
        }
        return communityService.findNamesByIds(Set.of(p.getCommunityId())).get(p.getCommunityId());
    }

    // Poll and crosspost-parent data for a single freshly built post (the list path does this in attachAll).
    private Post withExtras(Post p) {
        attachCardExtras(attachCrosspostParent(attachPoll(List.of(p))));
        return p;
    }

    // ==================== Polls ====================

    private static final int POLL_OPTION_MAX = 6;

    private void insertPoll(UUID postId, List<String> options, int days) {
        jdbc.update("INSERT INTO polls (post_id, ends_at) VALUES (:postId, :endsAt)",
                new MapSqlParameterSource().addValue("postId", postId)
                        .addValue("endsAt", Timestamp.from(Instant.now().plus(Duration.ofDays(days)))));
        short position = 0;
        for (String text : options.subList(0, Math.min(options.size(), POLL_OPTION_MAX))) {
            jdbc.update("INSERT INTO poll_options (id, post_id, text, position) VALUES (:id, :postId, :text, :position)",
                    new MapSqlParameterSource().addValue("id", ids.nextId()).addValue("postId", postId)
                            .addValue("text", sanitizer.sanitize(text.trim())).addValue("position", position++));
        }
    }

    // Batched, never one query per poll: one options query + one polls query per page of posts.
    private List<Post> attachPoll(List<Post> page) {
        List<UUID> pollIds = page.stream().filter(p -> "poll".equals(p.getKind())).map(Post::getId).toList();
        if (pollIds.isEmpty()) {
            return page;
        }
        Map<UUID, Instant> endsAt = new HashMap<>();
        jdbc.query("SELECT post_id, ends_at FROM polls WHERE post_id IN (:ids)", new MapSqlParameterSource("ids", pollIds),
                rs -> {
                    endsAt.put(rs.getObject("post_id", UUID.class), rs.getTimestamp("ends_at").toInstant());
                });
        Map<UUID, List<PollView.PollOptionView>> options = new HashMap<>();
        jdbc.query("SELECT id, post_id, text, votes FROM poll_options WHERE post_id IN (:ids) ORDER BY post_id, position",
                new MapSqlParameterSource("ids", pollIds), rs -> {
                    options.computeIfAbsent(rs.getObject("post_id", UUID.class), k -> new ArrayList<>())
                            .add(new PollView.PollOptionView(rs.getObject("id", UUID.class), rs.getString("text"), rs.getInt("votes")));
                });
        Instant now = Instant.now();
        for (Post p : page) {
            List<PollView.PollOptionView> opts = options.get(p.getId());
            Instant ends = endsAt.get(p.getId());
            if (opts != null && ends != null) {
                p.setPoll(new PollView(opts, opts.stream().mapToInt(PollView.PollOptionView::votes).sum(), ends, now.isAfter(ends), null));
            }
        }
        return page;
    }

    // The viewer's own state for one poll: same payload as the embedded poll plus which option they picked.
    public PollView getPoll(UUID viewerId, UUID communityId, UUID postId) {
        Post p = requirePollPost(communityId, postId);
        communityService.requireViewAccess(viewerId, communityId);
        attachPoll(List.of(p));
        PollView base = p.getPoll();
        if (base == null) {
            throw new NotFoundException("poll not found");
        }
        UUID mine = viewerId == null ? null : jdbc.query("SELECT option_id FROM poll_votes WHERE post_id = :p AND user_id = :u",
                new MapSqlParameterSource().addValue("p", postId).addValue("u", viewerId),
                rs -> rs.next() ? rs.getObject("option_id", UUID.class) : null);
        return new PollView(base.options(), base.totalVotes(), base.endsAt(), base.ended(), mine);
    }

    @Transactional
    public PollView votePoll(UUID userId, UUID communityId, UUID postId, UUID optionId) {
        Post p = requirePollPost(communityId, postId);
        communityService.requireViewAccess(userId, communityId);
        communityService.requireNotBanned(userId, communityId);
        attachPoll(List.of(p));
        if (p.getPoll() == null) {
            throw new NotFoundException("poll not found");
        }
        if (p.getPoll().ended()) {
            throw new BadRequestException("this poll has ended");
        }
        boolean validOption = p.getPoll().options().stream().anyMatch(o -> o.id().equals(optionId));
        if (!validOption) {
            throw new BadRequestException("that option does not belong to this poll");
        }
        int inserted = jdbc.update("""
                INSERT INTO poll_votes (post_id, user_id, option_id) VALUES (:p, :u, :o)
                ON CONFLICT (post_id, user_id) DO NOTHING
                """, new MapSqlParameterSource().addValue("p", postId).addValue("u", userId).addValue("o", optionId));
        if (inserted == 0) {
            throw new BadRequestException("you have already voted in this poll");
        }
        jdbc.update("UPDATE poll_options SET votes = votes + 1 WHERE id = :o", new MapSqlParameterSource("o", optionId));
        return getPoll(userId, communityId, postId);
    }

    private Post requirePollPost(UUID communityId, UUID postId) {
        Post p = findById(postId);
        if (!p.getCommunityId().equals(communityId) || p.isRemoved() || p.isDeleted() || !"poll".equals(p.getKind())) {
            throw new NotFoundException("poll not found");
        }
        return p;
    }

    // ==================== Crossposts ====================

    // Resolves the ORIGINAL post (a crosspost of a crosspost points at the same root), and checks the author
    // could actually see it: live, not in a private community (a crosspost would leak it to the target's
    // audience), and the author has view access to its community.
    private UUID resolveCrosspostRoot(UUID authorId, UUID crosspostOf) {
        Post original = findById(crosspostOf);
        if (original.getCrosspostOf() != null) {
            original = findById(original.getCrosspostOf());
        }
        if (original.isRemoved() || original.isDeleted()) {
            throw new NotFoundException("post not found");
        }
        communityService.requireViewAccess(authorId, original.getCommunityId());
        if ("private".equals(communityService.findTypeById(original.getCommunityId()))) {
            throw new BadRequestException("posts from private communities can't be crossposted");
        }
        return original.getId();
    }

    private List<Post> attachCrosspostParent(List<Post> page) {
        Set<UUID> parentIds = new HashSet<>();
        for (Post p : page) {
            if (p.getCrosspostOf() != null) {
                parentIds.add(p.getCrosspostOf());
            }
        }
        if (parentIds.isEmpty()) {
            return page;
        }
        Map<UUID, CrosspostParent> parents = new HashMap<>();
        jdbc.query("""
                SELECT p.id, p.title, p.kind, p.body, p.url, p.removed, p.deleted, u.username, c.name, c.type
                FROM posts p JOIN users u ON u.id = p.author_id JOIN communities c ON c.id = p.community_id
                WHERE p.id IN (:ids)
                """, new MapSqlParameterSource("ids", parentIds), rs -> {
            UUID id = rs.getObject("id", UUID.class);
            boolean available = !rs.getBoolean("removed") && !rs.getBoolean("deleted") && !"private".equals(rs.getString("type"));
            String body = rs.getString("body");
            parents.put(id, available
                    ? new CrosspostParent(id, true, rs.getString("title"), rs.getString("kind"),
                            body == null || body.length() <= 300 ? body : body.substring(0, 300) + "…",
                            rs.getString("url"), rs.getString("username"), rs.getString("name"))
                    : CrosspostParent.unavailable(id));
        });
        for (Post p : page) {
            if (p.getCrosspostOf() != null) {
                p.setCrosspostParent(parents.getOrDefault(p.getCrosspostOf(), CrosspostParent.unavailable(p.getCrosspostOf())));
            }
        }
        return page;
    }

    // ModerationController pushes the PERM_MANAGE_FLAIRS check down before calling this — no permission
    // check here, same convention as markRemoved above.
    @Transactional
    public void setFlair(UUID postId, UUID communityId, UUID flairId) {
        Post p = findById(postId);
        if (!p.getCommunityId().equals(communityId)) {
            throw new NotFoundException("post not found");
        }
        if (p.isDeleted()) {
            throw new NotFoundException("post not found");
        }
        if (flairId != null) {
            communityService.requireFlairUsable(communityId, flairId, "post");
        }
        p.setFlairId(flairId);
        posts.save(p);
    }

    // Applies a batch of grouped vote deltas (one entry per post touched, not per vote — see
    // OutboxWorker) via raw SQL bulk updates rather than loading Post entities, same reasoning as
    // CommentService.applyVoteDeltas. Also recomputes hot_rank/controversial_rank/rising_rank here,
    // right after the score/ups/downs they depend on change, so a feed read is always a plain indexed
    // ORDER BY and never a runtime calculation (see the source plan's Search & feed caching section).
    // Returns one KarmaEvent per post whose net score actually changed.
    @Transactional
    public List<KarmaEvent> applyVoteDeltas(Map<UUID, VoteDelta> deltas) {
        if (deltas.isEmpty()) {
            return List.of();
        }
        SqlParameterSource[] deltaParams = deltas.entrySet().stream()
                .map(e -> new MapSqlParameterSource()
                        .addValue("id", e.getKey())
                        .addValue("score", e.getValue().scoreDelta())
                        .addValue("ups", e.getValue().upsDelta())
                        .addValue("downs", e.getValue().downsDelta()))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate("""
                UPDATE posts SET score = score + :score, ups = ups + :ups, downs = downs + :downs
                WHERE id = :id
                """, deltaParams);

        Set<UUID> ids = deltas.keySet();
        List<Map<String, Object>> fresh = jdbc.queryForList("""
                SELECT id, author_id, score, ups, downs, created_at, rising_updated_at
                FROM posts WHERE id IN (:ids)
                """, new MapSqlParameterSource("ids", ids));

        Instant now = Instant.now();
        List<SqlParameterSource> rankParams = new ArrayList<>();
        List<KarmaEvent> karmaEvents = new ArrayList<>();
        for (Map<String, Object> row : fresh) {
            UUID id = (UUID) row.get("id");
            UUID authorId = (UUID) row.get("author_id");
            int score = (Integer) row.get("score");
            int ups = (Integer) row.get("ups");
            int downs = (Integer) row.get("downs");
            Instant createdAt = ((Timestamp) row.get("created_at")).toInstant();
            Instant risingUpdatedAt = ((Timestamp) row.get("rising_updated_at")).toInstant();

            double hotRank = RankFormulas.hotRank(score, createdAt);
            double controversialRank = RankFormulas.controversialRank(ups, downs);

            // "Rising" is this project's own definition (the source plan leaves it undefined): recent
            // vote velocity, measured as this batch's net vote-magnitude (|upsDelta| + |downsDelta|) over
            // the time since this post's rank was last touched (or since it was created, for its
            // first-ever vote). Using the net magnitude rather than a raw per-event count means a vote
            // immediately canceled by an unvote (net zero ups/downs change) contributes nothing — vote
            // churn can't inflate rising_rank with no real engagement behind it. A burst of votes spikes
            // rising_rank; RankDecayJob halves it periodically so silence lets it fade, since this value
            // is only ever recomputed here, on a vote, not continuously.
            VoteDelta delta = deltas.get(id);
            int voteMagnitude = Math.abs(delta.upsDelta()) + Math.abs(delta.downsDelta());
            double elapsedMinutes = Math.max(Duration.between(risingUpdatedAt, now).toSeconds() / 60.0, 1.0 / 60);
            double risingRank = voteMagnitude / elapsedMinutes;

            rankParams.add(new MapSqlParameterSource()
                    .addValue("id", id)
                    .addValue("hotRank", hotRank)
                    .addValue("controversialRank", controversialRank)
                    .addValue("risingRank", risingRank));

            int scoreDelta = delta.scoreDelta();
            if (scoreDelta != 0) {
                karmaEvents.add(new KarmaEvent(authorId, scoreDelta, id));
            }
        }
        jdbc.batchUpdate("""
                UPDATE posts SET hot_rank = :hotRank, controversial_rank = :controversialRank,
                                  rising_rank = :risingRank, rising_updated_at = now()
                WHERE id = :id
                """, rankParams.toArray(new SqlParameterSource[0]));
        return karmaEvents;
    }
}
