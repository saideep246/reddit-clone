package com.redditclone.comment;

import com.redditclone.auth.AuthService;
import com.redditclone.block.BlockService;
import com.redditclone.comment.dto.CommentEditView;
import com.redditclone.comment.dto.CommentSearchResult;
import com.redditclone.comment.dto.CommentView;
import com.redditclone.comment.dto.UserCommentView;
import com.redditclone.common.KarmaEvent;
import com.redditclone.common.OutboxWriter;
import com.redditclone.common.RankFormulas;
import com.redditclone.common.UuidV7Generator;
import com.redditclone.common.VoteDelta;
import com.redditclone.common.exception.BadRequestException;
import com.redditclone.common.exception.ForbiddenException;
import com.redditclone.common.exception.NotFoundException;
import com.redditclone.common.paging.Cursor;
import com.redditclone.common.paging.CursorCodec;
import com.redditclone.common.paging.Listing;
import com.redditclone.common.paging.RankCursor;
import com.redditclone.common.paging.RankCursorCodec;
import com.redditclone.common.paging.Thing;
import com.redditclone.common.text.Sanitizer;
import com.redditclone.common.text.SearchTerms;
import com.redditclone.community.CommunityModerator;
import com.redditclone.community.CommunityService;
import com.redditclone.post.Post;
import com.redditclone.post.PostService;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class CommentService {

    private static final int MAX_DEPTH = 10;
    private static final int TOP_LEVEL_PAGE_SIZE = 50;
    // Feature 8: caps each page-of-roots' *entire* reply subtree (all depths) at this many per root, and
    // is also the page size for GET /api/morechildren's direct-children follow-up calls.
    private static final int REPLY_PAGE_SIZE = 50;
    private static final String COMMENT_KIND = "t1";
    // u/{username} mention detection. Registration itself allows any characters in a username (no
    // @Pattern on RegisterRequest), but restricting what's *mentionable* to the conventional
    // [A-Za-z0-9_] set (matching how comment ltree labels already treat "safe" identifier characters
    // elsewhere in this codebase) is a reasonable, explicit first-pass limitation, not a bug.
    // The (?<![\w/]) lookbehind requires whitespace/punctuation/start-of-string immediately before "u/" —
    // without it, "u/name" matches as a substring of ordinary text/URLs (e.g. "menu/foobar", or the "u/"
    // inside "example.com/u/alice"), firing a false "mention" notification whenever that substring happens
    // to match a real username.
    private static final Pattern MENTION_PATTERN = Pattern.compile("(?<![\\w/])u/([A-Za-z0-9_]{3,32})");

    private final BlockService blocks;
    private final CommentRepository comments;
    private final PostService postService;
    private final UuidV7Generator ids;
    private final Sanitizer sanitizer;
    private final NamedParameterJdbcTemplate jdbc;
    private final CommunityService communityService;
    private final AuthService authService;
    private final OutboxWriter outbox;

    public CommentService(CommentRepository comments, PostService postService, UuidV7Generator ids,
                           Sanitizer sanitizer, NamedParameterJdbcTemplate jdbc,
                           CommunityService communityService, AuthService authService, OutboxWriter outbox,
                           BlockService blocks) {
        this.comments = comments;
        this.postService = postService;
        this.ids = ids;
        this.sanitizer = sanitizer;
        this.jdbc = jdbc;
        this.communityService = communityService;
        this.authService = authService;
        this.outbox = outbox;
        this.blocks = blocks;
    }

    @Transactional
    public Comment reply(UUID authorId, UUID postId, UUID parentId, String body) {
        // 404s on a nonexistent/deleted post instead of creating an orphan; also gives us communityId
        // without a second lookup, for the ban/automod checks below.
        Post post = postService.findById(postId);
        // Private gates commenting too (if you can't view it, you can't reply to it) — but restricted does
        // not, it only gates posting, so this is intentionally requireViewAccess, not requirePostAccess.
        communityService.requireViewAccess(authorId, post.getCommunityId());
        communityService.requireNotBanned(authorId, post.getCommunityId());
        blocks.requireNotBlockedBy(post.getAuthorId(), authorId);
        if (post.isLocked()) {
            throw new ForbiddenException("this post is locked");
        }
        if (post.isDeleted()) {
            throw new NotFoundException("post not found");
        }
        String sanitizedBody = sanitizer.sanitize(body);
        Comment c = new Comment();
        c.setId(ids.nextId());
        c.setPostId(postId);
        c.setParentId(parentId);
        c.setAuthorId(authorId);
        c.setBody(sanitizedBody);

        Comment parent = null;
        if (parentId == null) {
            c.setDepth((short) 0);
            c.setPath(toLabel(c.getId()));
        } else {
            parent = comments.findById(parentId)
                    .orElseThrow(() -> new NotFoundException("parent comment not found"));
            if (!parent.getPostId().equals(postId)) {
                throw new BadRequestException("parent comment does not belong to this post");
            }
            if (parent.isDeleted()) {
                throw new NotFoundException("parent comment not found");
            }
            blocks.requireNotBlockedBy(parent.getAuthorId(), authorId);
            if (parent.getDepth() >= MAX_DEPTH) {
                throw new BadRequestException("max comment depth reached");
            }
            c.setDepth((short) (parent.getDepth() + 1));
            c.setPath(parent.getPath() + "." + toLabel(c.getId()));
            comments.incrementChildCount(parentId);
        }
        // Same ordering as PostService.create(): id is already assigned, evaluated before save() so a
        // "remove" verdict lands in the very first row written, and the audit/report rows automod writes
        // can reference this comment's real id from the moment it exists.
        int authorKarma = authService.getKarmaComment(authorId);
        if (communityService.evaluateAutomod(post.getCommunityId(), "comment", c.getId(), null, sanitizedBody, authorKarma)) {
            c.setRemoved(true);
        }
        Comment saved = comments.save(c);
        postService.incrementCommentCount(postId);
        if (!saved.isRemoved()) {
            notifyFanOut(saved, post, parent, authorId, sanitizedBody);
        }
        // Same attach as editBody/the read paths, so the response carries the author's username.
        attachAuthorUsernames(List.of(saved));
        return saved;
    }

    // Reply/mention notifications, written as outbox events (common.OutboxWriter) and fanned out into
    // notifications rows by notify.NotificationOutboxWorker in the same batch-processing shape already
    // used for vote score/karma updates — comment and notify have no dependency relationship to route a
    // direct write through, so a shared common writer is the correct fix, same as ModerationAuditWriter.
    // Skipped entirely for an automod-removed comment (checked by the caller) — no point notifying about a
    // reply nobody will ever see.
    private void notifyFanOut(Comment c, Post post, Comment parent, UUID authorId, String sanitizedBody) {
        if (parent == null) {
            if (!post.getAuthorId().equals(authorId)) {
                outbox.writeEvent("notification", Map.of(
                        "userId", post.getAuthorId(),
                        "type", "post_reply",
                        "source", Map.of("actorId", authorId, "postId", post.getId(), "communityId", post.getCommunityId())));
            }
        } else if (!parent.getAuthorId().equals(authorId)) {
            outbox.writeEvent("notification", Map.of(
                    "userId", parent.getAuthorId(),
                    "type", "reply",
                    "source", Map.of("actorId", authorId, "commentId", parent.getId(), "postId", post.getId(), "communityId", post.getCommunityId())));
        }
        notifyMentions(c, post, authorId, sanitizedBody);
    }

    // Concrete design choice for "mention" detection (the source plan lists it as a notification type
    // but never specifies how): scan for u/{username} tokens, resolve every distinct one in a single
    // batched query (AuthService.findUserIdsByUsernames) rather than one SELECT per mention, then write
    // one event per resolved user excluding the author. An unmatched/typo'd username is silently
    // skipped — the same "a soft failure here shouldn't block the main action" principle already applied
    // to automod's malformed-rule handling in CommunityService.
    private void notifyMentions(Comment c, Post post, UUID authorId, String sanitizedBody) {
        Matcher matcher = MENTION_PATTERN.matcher(sanitizedBody);
        Set<String> usernames = new HashSet<>();
        while (matcher.find()) {
            usernames.add(matcher.group(1));
        }
        if (usernames.isEmpty()) {
            return;
        }
        List<Map<String, Object>> payloads = authService.findUserIdsByUsernames(usernames).values().stream()
                .filter(mentionedId -> !mentionedId.equals(authorId))
                .<Map<String, Object>>map(mentionedId -> Map.of(
                        "userId", mentionedId,
                        "type", "mention",
                        "source", Map.of("actorId", authorId, "commentId", c.getId(), "postId", post.getId(), "communityId", post.getCommunityId())))
                .toList();
        outbox.writeEvents("notification", payloads);
    }

    // best is Reddit's own default (Wilson confidence, not raw score). Builds a genuinely nested tree
    // (CommentView.replies), not a flat top-level-only list — see F3's plan. Root comments are now really
    // paginated (feature 8) via the same keyset-cursor primitives PostController's feeds already use.
    // Each page of roots' entire reply subtree (every depth, not just direct children) is fetched eagerly,
    // bounded at REPLY_PAGE_SIZE per root — see findBoundedReplyIds — and sorted recursively at every
    // depth with the same comparator logic as the root query's own ORDER BY, matching how Reddit itself
    // sorts a whole thread, not just its first level. A node whose childCount exceeds how many of its
    // children actually made it into this bounded set (CommentView.childCount vs .replies.size(), checked
    // client-side) gets a "N more replies" affordance resolved via findMoreChildren below.
    public Listing<CommentView> findCommentTree(UUID postId, UUID viewerId, String sort, String after) {
        List<Comment> rawRoots = findTopLevel(postId, viewerId, sort, after);
        // Sticky comments lead the first page regardless of sort, and are dropped from the sorted page so they
        // never appear twice. The paging cursor is still computed from the raw (unfiltered) page below.
        List<Comment> stickies = comments.findByPostIdAndStickyTrueAndRemovedFalseAndDeletedFalseOrderByCreatedAtAsc(postId);
        Set<UUID> stickyIds = stickies.stream().map(Comment::getId).collect(Collectors.toSet());
        List<Comment> roots = new ArrayList<>();
        if (after == null || after.isBlank()) {
            roots.addAll(stickies);
        }
        rawRoots.stream().filter(c -> !stickyIds.contains(c.getId())).forEach(roots::add);
        if (roots.isEmpty()) {
            return Listing.of(List.of(), null);
        }
        List<String> rootLabels = roots.stream().map(Comment::getPath).toList();
        List<UUID> replyIds = findBoundedReplyIds(postId, rootLabels, viewerId, sort);
        List<Comment> replies = replyIds.isEmpty() ? List.of() : comments.findAllById(replyIds);

        List<Comment> all = new ArrayList<>(roots);
        all.addAll(replies);
        attachAuthorUsernames(all);

        Map<UUID, List<Comment>> childrenByParent = new HashMap<>();
        for (Comment c : replies) {
            childrenByParent.computeIfAbsent(c.getParentId(), k -> new ArrayList<>()).add(c);
        }
        Comparator<Comment> comparator = comparatorFor(sort);
        List<CommentView> views = roots.stream().map(r -> toViewRecursive(r, childrenByParent, comparator, sort)).toList();
        List<Thing<CommentView>> children = views.stream().map(v -> new Thing<>(COMMENT_KIND, v)).toList();
        String next = rawRoots.size() < TOP_LEVEL_PAGE_SIZE ? null : encodeCommentCursor(sort, rawRoots.getLast());
        return Listing.of(children, next);
    }

    // At most this many sticky comments per post.
    private static final int MAX_STICKY_COMMENTS = 2;

    // Caller (ModerationService) has already checked the moderator permission. Only live top-level comments
    // can be stickied, and the comment must belong to the named community's post.
    @Transactional
    public void setSticky(UUID commentId, UUID communityId, boolean sticky) {
        Comment c = findById(commentId);
        requireInCommunity(c, communityId);
        if (sticky) {
            if (c.getParentId() != null) {
                throw new BadRequestException("only top-level comments can be stickied");
            }
            if (c.isRemoved() || c.isDeleted()) {
                throw new BadRequestException("cannot sticky a removed or deleted comment");
            }
            if (!c.isSticky() && comments.countByPostIdAndStickyTrue(c.getPostId()) >= MAX_STICKY_COMMENTS) {
                throw new BadRequestException("this post already has the maximum number of sticky comments");
            }
        }
        c.setSticky(sticky);
        comments.save(c);
    }

    // A moderator distinguishes (or un-distinguishes) their OWN comment; nobody can distinguish someone else's.
    @Transactional
    public void setDistinguished(UUID actorId, UUID commentId, UUID communityId, boolean distinguished) {
        Comment c = findById(commentId);
        requireInCommunity(c, communityId);
        if (!c.getAuthorId().equals(actorId)) {
            throw new ForbiddenException("you can only distinguish your own comments");
        }
        if (c.isRemoved() || c.isDeleted()) {
            throw new BadRequestException("cannot distinguish a removed or deleted comment");
        }
        c.setDistinguished(distinguished ? "moderator" : null);
        comments.save(c);
    }

    private void requireInCommunity(Comment c, UUID communityId) {
        if (!postService.findById(c.getPostId()).getCommunityId().equals(communityId)) {
            throw new NotFoundException("comment not found");
        }
    }

    // GET /api/morechildren — the next page of one specific comment's *direct* children only (not deeper),
    // unlike findCommentTree's richer multi-level eager fetch above. Accepted simplification (see this
    // feature's plan): a freshly-loaded child whose own childCount > 0 shows its own "more replies"
    // affordance immediately, resolved by another call scoped to that child — keeps this query a plain
    // single-parent keyset page with no window function/ltree needed, unlike the multi-root case above.
    public Listing<CommentView> findMoreChildren(UUID postId, UUID parentId, UUID viewerId, String sort, String after) {
        Post post = postService.findById(postId);
        communityService.requireViewAccess(viewerId, post.getCommunityId());
        Comment parent = findById(parentId);
        if (!parent.getPostId().equals(postId)) {
            throw new NotFoundException("parent comment not found");
        }
        Pageable limit = Pageable.ofSize(REPLY_PAGE_SIZE);
        List<Comment> page = switch (sort) {
            case "best" -> {
                RankCursor cursor = RankCursorCodec.decode(after, sort);
                yield comments.findChildrenByBest(parentId, cursor.rank(), cursor.id(), viewerId, limit);
            }
            case "top" -> {
                RankCursor cursor = RankCursorCodec.decode(after, sort);
                yield comments.findChildrenByTop(parentId, (int) cursor.rank(), cursor.id(), viewerId, limit);
            }
            case "new" -> {
                Cursor cursor = decodeTimeCursor(after, sort);
                yield comments.findChildrenByNew(parentId, cursor.createdAt(), cursor.id(), viewerId, limit);
            }
            case "old" -> {
                Cursor cursor = decodeTimeCursor(after, sort);
                yield comments.findChildrenByOld(parentId, cursor.createdAt(), cursor.id(), viewerId, limit);
            }
            case "controversial" -> {
                RankCursor cursor = RankCursorCodec.decode(after, sort);
                yield comments.findChildrenByControversial(parentId, cursor.rank(), cursor.id(), viewerId, limit);
            }
            default -> throw new BadRequestException("invalid comment sort");
        };
        attachAuthorUsernames(page);
        // repliesAfter: "" (not null) whenever this freshly-loaded child has any real children of its own
        // (childCount > 0) — replies is always empty here (see this method's own comment), so childCount
        // alone decides it; "" correctly starts that child's own future morechildren call from scratch,
        // same reasoning as toViewRecursive's identical empty-kids case.
        List<CommentView> views = page.stream()
                .map(c -> CommentView.from(c, List.of(), c.getChildCount() > 0 ? "" : null))
                .toList();
        List<Thing<CommentView>> children = views.stream().map(v -> new Thing<>(COMMENT_KIND, v)).toList();
        String next = page.size() < REPLY_PAGE_SIZE ? null : encodeCommentCursor(sort, page.getLast());
        return Listing.of(children, next);
    }

    private CommentView toViewRecursive(Comment c, Map<UUID, List<Comment>> childrenByParent, Comparator<Comment> comparator, String sort) {
        List<Comment> kids = childrenByParent.getOrDefault(c.getId(), List.of()).stream().sorted(comparator).toList();
        List<CommentView> childViews = kids.stream()
                .map(k -> toViewRecursive(k, childrenByParent, comparator, sort))
                .toList();
        // Non-null exactly when this node's own direct children were truncated by the per-root reply
        // budget (childCount vs how many actually made it into `kids`) — see CommentView's own comment.
        // Empty string (not a real encoded cursor) specifically for "truncated but zero of this node's
        // children happened to make the cut" — passing that straight through as morechildren's blank
        // `after` correctly starts that call from page 1 of this parent's children, which is exactly
        // right here since nothing of this parent's own is visible yet to overlap with.
        String repliesAfter = kids.size() >= c.getChildCount() ? null
                : kids.isEmpty() ? "" : encodeCommentCursor(sort, kids.getLast());
        return CommentView.from(c, childViews, repliesAfter);
    }

    private List<Comment> findTopLevel(UUID postId, UUID viewerId, String sort, String after) {
        Pageable limit = Pageable.ofSize(TOP_LEVEL_PAGE_SIZE);
        return switch (sort) {
            case "best" -> {
                RankCursor cursor = RankCursorCodec.decode(after, sort);
                yield comments.findTopLevelByBest(postId, cursor.rank(), cursor.id(), viewerId, limit);
            }
            case "top" -> {
                RankCursor cursor = RankCursorCodec.decode(after, sort);
                yield comments.findTopLevelByTop(postId, (int) cursor.rank(), cursor.id(), viewerId, limit);
            }
            case "new" -> {
                Cursor cursor = decodeTimeCursor(after, sort);
                yield comments.findTopLevelByNew(postId, cursor.createdAt(), cursor.id(), viewerId, limit);
            }
            case "old" -> {
                Cursor cursor = decodeTimeCursor(after, sort);
                yield comments.findTopLevelByOld(postId, cursor.createdAt(), cursor.id(), viewerId, limit);
            }
            case "controversial" -> {
                RankCursor cursor = RankCursorCodec.decode(after, sort);
                yield comments.findTopLevelByControversial(postId, cursor.rank(), cursor.id(), viewerId, limit);
            }
            default -> throw new BadRequestException("invalid comment sort");
        };
    }

    // "old" is this codebase's only ascending-sorted paginated listing — a blank/missing `after` needs
    // Cursor.FIRST_PAGE_ASC (older/lower than any real row), not the shared FIRST_PAGE sentinel every
    // descending listing uses (which would match nothing under old's ">" keyset comparison). A real,
    // previously-encoded token round-trips the same way regardless of direction, so only the blank case
    // needs this branch.
    private Cursor decodeTimeCursor(String after, String sort) {
        if (after == null || after.isBlank()) {
            return "old".equals(sort) ? Cursor.FIRST_PAGE_ASC : Cursor.FIRST_PAGE;
        }
        return CursorCodec.decode(after);
    }

    private List<UUID> findBoundedReplyIds(UUID postId, List<String> rootLabels, UUID viewerId, String sort) {
        return switch (sort) {
            case "best" -> comments.findBoundedReplyIdsByBest(postId, rootLabels, viewerId, REPLY_PAGE_SIZE);
            case "top" -> comments.findBoundedReplyIdsByTop(postId, rootLabels, viewerId, REPLY_PAGE_SIZE);
            case "new" -> comments.findBoundedReplyIdsByNew(postId, rootLabels, viewerId, REPLY_PAGE_SIZE);
            case "old" -> comments.findBoundedReplyIdsByOld(postId, rootLabels, viewerId, REPLY_PAGE_SIZE);
            case "controversial" -> comments.findBoundedReplyIdsByControversial(postId, rootLabels, viewerId, REPLY_PAGE_SIZE);
            default -> throw new BadRequestException("invalid comment sort");
        };
    }

    // Shared by findCommentTree's top-level cursor and findMoreChildren's direct-children cursor — both
    // page over a Comment list ordered by the same per-sort column, so the same encoding applies to either.
    private String encodeCommentCursor(String sort, Comment last) {
        return switch (sort) {
            case "best" -> RankCursorCodec.encode(sort, last.getBestRank(), last.getId(), null);
            case "top" -> RankCursorCodec.encode(sort, last.getScore(), last.getId(), null);
            case "new", "old" -> CursorCodec.encode(last.getCreatedAt(), last.getId());
            case "controversial" -> RankCursorCodec.encode(sort, last.getControversialRank(), last.getId(), null);
            default -> throw new BadRequestException("invalid comment sort");
        };
    }

    // Mirrors each findTopLevelBy*'s own ORDER BY exactly, as a Java Comparator — used to sort each
    // reply group (a comment's direct children) at every depth of the tree above, since there's no SQL
    // query shaped to sort an arbitrary-depth tree recursively. Root comments don't need this: the
    // findTopLevelBy* query above already returns them in the right order.
    private Comparator<Comment> comparatorFor(String sort) {
        return switch (sort) {
            case "best" -> Comparator.comparingDouble(Comment::getBestRank).reversed()
                    .thenComparing(Comparator.comparing(Comment::getId).reversed());
            case "top" -> Comparator.comparingInt(Comment::getScore).reversed()
                    .thenComparing(Comparator.comparing(Comment::getId).reversed());
            case "new" -> Comparator.comparing(Comment::getCreatedAt).reversed()
                    .thenComparing(Comparator.comparing(Comment::getId).reversed());
            case "old" -> Comparator.comparing(Comment::getCreatedAt).thenComparing(Comment::getId);
            case "controversial" -> Comparator.comparingDouble(Comment::getControversialRank).reversed()
                    .thenComparing(Comparator.comparing(Comment::getId).reversed());
            default -> throw new BadRequestException("invalid comment sort");
        };
    }

    // Same batched-IN-query shape as PostService.attachAuthorUsername, via the same AuthService method —
    // called once across both roots and replies combined, not per level, so a thread with many commenters
    // still costs exactly one extra query regardless of its depth or shape.
    private void attachAuthorUsernames(List<Comment> allComments) {
        if (allComments.isEmpty()) {
            return;
        }
        Set<UUID> authorIds = new HashSet<>();
        for (Comment c : allComments) {
            authorIds.add(c.getAuthorId());
        }
        Map<UUID, String> usernames = authService.findUsernamesByIds(authorIds);
        for (Comment c : allComments) {
            c.setAuthorUsername(c.isDeleted() ? null : usernames.get(c.getAuthorId()));
        }
    }

    public Comment findById(UUID commentId) {
        return comments.findById(commentId).orElseThrow(() -> new NotFoundException("comment not found"));
    }

    // Batched, no tree/display attach — same "internal lookup, not a display path" reasoning as
    // post.PostService.findAllByIds (added in F7 for the identical purpose: ModerationService's mod queue,
    // F8, batch-resolving a page's comment-type targets to a preview in one query instead of one per row).
    public List<Comment> findAllByIds(Set<UUID> ids) {
        return comments.findAllById(ids);
    }

    // A user's "comments" profile tab (F7). Attaches postTitle/communityName in two batched queries (one
    // per page, never one per comment): postService.findAllByIds for the page's distinct post ids, then
    // communityService.findNamesByIds for those posts' distinct community ids — the same two-hop batching
    // pattern post.PostService.attachAll already uses for Post.communityName.
    public List<UserCommentView> findByAuthor(String username, Instant cursorCreatedAt, UUID cursorId,
                                               UUID viewerId, int limit) {
        UUID authorId = authService.findUserIdByUsername(username)
                .orElseThrow(() -> new NotFoundException("no such user"));
        List<Comment> page = comments.findByAuthorId(authorId, cursorCreatedAt, cursorId, viewerId, Pageable.ofSize(limit));
        if (page.isEmpty()) {
            return List.of();
        }

        Set<UUID> postIds = page.stream().map(Comment::getPostId).collect(Collectors.toSet());
        Map<UUID, Post> postsById = postService.findAllByIds(postIds).stream()
                .collect(Collectors.toMap(Post::getId, p -> p));

        Set<UUID> communityIds = postsById.values().stream().map(Post::getCommunityId)
                .collect(Collectors.toSet());
        Map<UUID, String> communityNames = communityService.findNamesByIds(communityIds);

        return page.stream().map(c -> {
            Post post = postsById.get(c.getPostId());
            String postTitle = post != null ? post.getTitle() : null;
            String communityName = post != null ? communityNames.get(post.getCommunityId()) : null;
            return new UserCommentView(c.getId(), c.getPostId(), postTitle, communityName, c.getParentId(),
                    c.getBody(), c.getScore(), c.getCreatedAt());
        }).toList();
    }

    // Ranked full-text search over comment bodies — a single capped page, same "ts_rank isn't a stable keyset
    // sort key" reasoning as PostService.search. communityId == null searches sitewide.
    public List<CommentSearchResult> search(UUID communityId, String query, UUID viewerId) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<UUID> rankedIds = comments.searchIds(communityId, query.trim(), SearchTerms.prefixTsQuery(query), viewerId);
        if (rankedIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, Comment> byId = comments.findAllById(rankedIds).stream().collect(Collectors.toMap(Comment::getId, c -> c));
        List<Comment> ordered = rankedIds.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
        attachAuthorUsernames(ordered);
        Map<UUID, Post> postsById = postService.findAllByIds(ordered.stream().map(Comment::getPostId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Post::getId, p -> p));
        Map<UUID, String> communityNames = communityService.findNamesByIds(
                postsById.values().stream().map(Post::getCommunityId).collect(Collectors.toSet()));
        return ordered.stream().map(c -> {
            Post post = postsById.get(c.getPostId());
            return new CommentSearchResult(c.getId(), c.getPostId(), post == null ? null : post.getTitle(),
                    post == null ? null : communityNames.get(post.getCommunityId()), c.getAuthorUsername(),
                    c.getBody(), c.getScore(), c.getCreatedAt());
        }).toList();
    }

    // For ModerationService's human-initiated removal path — Comment already has a public `removed`
    // setter (unlike score/best_rank/etc, it was never migrated to the raw-SQL-bulk-only pattern).
    @Transactional
    public void markRemoved(UUID commentId) {
        Comment c = findById(commentId);
        c.setRemoved(true);
        comments.save(c);
    }

    @Transactional
    public CommentView editBody(UUID actorId, UUID commentId, String body) {
        Comment c = requireAuthoredComment(actorId, commentId);
        if (c.isDeleted()) {
            throw new NotFoundException("comment not found");
        }
        jdbc.update("INSERT INTO comment_edits (id, comment_id, editor_id, old_body) VALUES (:id, :commentId, :editorId, :body)",
                new MapSqlParameterSource().addValue("id", ids.nextId()).addValue("commentId", commentId)
                        .addValue("editorId", actorId).addValue("body", c.getBody()));
        c.setBody(sanitizer.sanitize(body));
        c.setEditedAt(Instant.now());
        Post post = postService.findById(c.getPostId());
        if (communityService.evaluateAutomod(post.getCommunityId(), "comment", c.getId(), null, c.getBody(),
                authService.getKarmaComment(actorId))) {
            c.setRemoved(true);
        }
        comments.save(c);
        attachAuthorUsernames(List.of(c));
        return CommentView.from(c);
    }

    // Revision history for the comment's author or a moderator of its community (PERM_REMOVE_CONTENT).
    public List<CommentEditView> history(UUID actorId, UUID commentId) {
        Comment c = findById(commentId);
        if (!c.getAuthorId().equals(actorId)) {
            Post post = postService.findById(c.getPostId());
            communityService.requirePermission(actorId, post.getCommunityId(), CommunityModerator.PERM_REMOVE_CONTENT);
        }
        return jdbc.query("SELECT id, editor_id, old_body, edited_at FROM comment_edits WHERE comment_id = :id ORDER BY edited_at DESC",
                new MapSqlParameterSource("id", commentId),
                (rs, i) -> new CommentEditView(rs.getObject("id", UUID.class), rs.getObject("editor_id", UUID.class),
                        rs.getString("old_body"), rs.getTimestamp("edited_at").toInstant()));
    }

    // Wipes the body rather than only hiding it at read time, same as PostService.delete. The row, parentId,
    // path, and childCount stay put — the reply subtree underneath must not lose its place in the ltree.
    @Transactional
    public void delete(UUID actorId, UUID commentId) {
        Comment c = requireAuthoredComment(actorId, commentId);
        if (c.isDeleted()) {
            return;
        }
        c.setDeleted(true);
        c.setBody("[deleted]");
        comments.save(c);
    }

    private Comment requireAuthoredComment(UUID actorId, UUID commentId) {
        Comment c = findById(commentId);
        if (!c.getAuthorId().equals(actorId)) {
            throw new ForbiddenException("not the author of this comment");
        }
        if (c.isRemoved()) {
            throw new ForbiddenException("this comment has been removed by moderators");
        }
        Post post = postService.findById(c.getPostId());
        if (post.isDeleted()) {
            throw new NotFoundException("comment not found");
        }
        communityService.requireNotBanned(actorId, post.getCommunityId());
        communityService.requireViewAccess(actorId, post.getCommunityId());
        return c;
    }

    // Applies a batch of grouped vote deltas (one entry per comment touched, not per vote — see
    // OutboxWorker) via raw SQL bulk updates rather than loading Comment entities: avoids the same
    // stale-persistence-context class of bug incrementChildCount's clearAutomatically works around, and
    // matches the plan's "one grouped UPDATE per target, not one per vote" design goal. Returns one
    // KarmaEvent per comment whose net score actually changed, for the caller to attribute to karma_log
    // and users.karma_comment — this module never touches those tables itself (see ModuleBoundaryTest).
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
                UPDATE comments SET score = score + :score, ups = ups + :ups, downs = downs + :downs
                WHERE id = :id
                """, deltaParams);

        Set<UUID> ids = deltas.keySet();
        List<Map<String, Object>> fresh = jdbc.queryForList(
                "SELECT id, author_id, ups, downs FROM comments WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", ids));

        List<SqlParameterSource> rankParams = new ArrayList<>();
        List<KarmaEvent> karmaEvents = new ArrayList<>();
        for (Map<String, Object> row : fresh) {
            UUID id = (UUID) row.get("id");
            UUID authorId = (UUID) row.get("author_id");
            int ups = (Integer) row.get("ups");
            int downs = (Integer) row.get("downs");
            double bestRank = RankFormulas.bestRank(ups, downs);
            double controversialRank = RankFormulas.controversialRank(ups, downs);
            rankParams.add(new MapSqlParameterSource().addValue("id", id)
                    .addValue("bestRank", bestRank).addValue("controversialRank", controversialRank));

            int scoreDelta = deltas.get(id).scoreDelta();
            if (scoreDelta != 0) {
                karmaEvents.add(new KarmaEvent(authorId, scoreDelta, id));
            }
        }
        jdbc.batchUpdate("""
                UPDATE comments SET best_rank = :bestRank, controversial_rank = :controversialRank
                WHERE id = :id
                """, rankParams.toArray(new SqlParameterSource[0]));
        return karmaEvents;
    }

    // ltree labels only allow [A-Za-z0-9_] — a UUID's hyphens aren't valid, so this strips them to a
    // plain 32-char hex label.
    private String toLabel(UUID id) {
        return id.toString().replace("-", "");
    }
}
