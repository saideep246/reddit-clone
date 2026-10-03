package com.redditclone.comment;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface CommentRepository extends JpaRepository<Comment, UUID> {

    // A post's live sticky comments (oldest first), for CommentService.findCommentTree's first page.
    List<Comment> findByPostIdAndStickyTrueAndRemovedFalseAndDeletedFalseOrderByCreatedAtAsc(UUID postId);

    long countByPostIdAndStickyTrue(UUID postId);

    // Ranked ids only (search_vector is deliberately unmapped on Comment, same as Post's), refetched via
    // findAllById by CommentService.search. A null :communityId searches sitewide, excluding private
    // communities the viewer can't see (same rule as PostRepository.searchAllIds) and anything by an author
    // the viewer has blocked. Removed/deleted comments and comments on removed/deleted posts never match.
    @Query(value = """
            SELECT c.id FROM comments c
            JOIN posts p ON p.id = c.post_id
            JOIN communities cm ON cm.id = p.community_id
            WHERE NOT c.removed AND NOT c.deleted AND NOT p.removed AND NOT p.deleted
              AND c.search_vector @@ websearch_to_tsquery('english', :query)
              AND (CAST(:communityId AS uuid) IS NULL OR p.community_id = CAST(:communityId AS uuid))
              AND (cm.type <> 'private'
                   OR EXISTS (SELECT 1 FROM memberships m WHERE m.user_id = :viewerId AND m.community_id = p.community_id)
                   OR EXISTS (SELECT 1 FROM community_moderators cmod WHERE cmod.user_id = :viewerId AND cmod.community_id = p.community_id))
              AND NOT EXISTS (SELECT 1 FROM user_blocks b WHERE b.blocker_id = :viewerId AND b.blocked_id = c.author_id)
            ORDER BY ts_rank(c.search_vector, websearch_to_tsquery('english', :query)) DESC
            LIMIT 25
            """, nativeQuery = true)
    List<UUID> searchIds(@Param("communityId") UUID communityId, @Param("query") String query,
                          @Param("viewerId") UUID viewerId);

    // Ordered by best_rank (Wilson score lower bound on the up/down split, see RankFormulas.bestRank) —
    // Reddit's own default comment sort, not raw score: a 95-up/5-down reply outranks a 10-up/0-down one
    // despite the smaller net score, because the larger sample gives more confidence in the ratio.
    // The NOT EXISTS/HiddenItem clause is the same viewer-scoped, ArchUnit-invisible JPQL pattern used in
    // PostRepository — see its comment. viewerId is null for an unauthenticated request.
    // No "AND c.removed = false" here (unlike before F3): a removed root comment must still appear — as
    // "[removed]", see CommentView — so any replies underneath it stay attached in the nested tree F3
    // builds (CommentService.findCommentTree). Excluding the row entirely would silently orphan its whole
    // reply subtree once replies became readable at all, which they weren't until F3.
    // Keyset cursor (feature 8) — same shape as PostRepository's hot/top/controversial queries.
    @Query("""
            SELECT c FROM Comment c
            WHERE c.postId = :postId AND c.parentId IS NULL
              AND (c.bestRank < :cursorRank OR (c.bestRank = :cursorRank AND c.id < :cursorId))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM HiddenItem h WHERE h.userId = :viewerId AND h.targetType = 'comment' AND h.targetId = c.id))
            ORDER BY c.bestRank DESC, c.id DESC
            """)
    List<Comment> findTopLevelByBest(@Param("postId") UUID postId, @Param("cursorRank") double cursorRank,
                                      @Param("cursorId") UUID cursorId, @Param("viewerId") UUID viewerId, Pageable limit);

    @Query("""
            SELECT c FROM Comment c
            WHERE c.postId = :postId AND c.parentId IS NULL
              AND (c.score < :cursorScore OR (c.score = :cursorScore AND c.id < :cursorId))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM HiddenItem h WHERE h.userId = :viewerId AND h.targetType = 'comment' AND h.targetId = c.id))
            ORDER BY c.score DESC, c.id DESC
            """)
    List<Comment> findTopLevelByTop(@Param("postId") UUID postId, @Param("cursorScore") int cursorScore,
                                     @Param("cursorId") UUID cursorId, @Param("viewerId") UUID viewerId, Pageable limit);

    @Query("""
            SELECT c FROM Comment c
            WHERE c.postId = :postId AND c.parentId IS NULL
              AND (c.createdAt < :cursorCreatedAt OR (c.createdAt = :cursorCreatedAt AND c.id < :cursorId))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM HiddenItem h WHERE h.userId = :viewerId AND h.targetType = 'comment' AND h.targetId = c.id))
            ORDER BY c.createdAt DESC, c.id DESC
            """)
    List<Comment> findTopLevelByNew(@Param("postId") UUID postId, @Param("cursorCreatedAt") Instant cursorCreatedAt,
                                     @Param("cursorId") UUID cursorId, @Param("viewerId") UUID viewerId, Pageable limit);

    // Ascending throughout (not mixed with a descending tiebreaker), as this comment already anticipated
    // for feature 8 — the keyset predicate direction is ">" not "<" to match. See Cursor.FIRST_PAGE_ASC
    // for the matching first-page sentinel this sort needs (FIRST_PAGE's year-9999 sentinel would match
    // nothing under a ">" comparison).
    @Query("""
            SELECT c FROM Comment c
            WHERE c.postId = :postId AND c.parentId IS NULL
              AND (c.createdAt > :cursorCreatedAt OR (c.createdAt = :cursorCreatedAt AND c.id > :cursorId))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM HiddenItem h WHERE h.userId = :viewerId AND h.targetType = 'comment' AND h.targetId = c.id))
            ORDER BY c.createdAt ASC, c.id ASC
            """)
    List<Comment> findTopLevelByOld(@Param("postId") UUID postId, @Param("cursorCreatedAt") Instant cursorCreatedAt,
                                     @Param("cursorId") UUID cursorId, @Param("viewerId") UUID viewerId, Pageable limit);

    @Query("""
            SELECT c FROM Comment c
            WHERE c.postId = :postId AND c.parentId IS NULL
              AND (c.controversialRank < :cursorRank OR (c.controversialRank = :cursorRank AND c.id < :cursorId))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM HiddenItem h WHERE h.userId = :viewerId AND h.targetType = 'comment' AND h.targetId = c.id))
            ORDER BY c.controversialRank DESC, c.id DESC
            """)
    List<Comment> findTopLevelByControversial(@Param("postId") UUID postId, @Param("cursorRank") double cursorRank,
                                               @Param("cursorId") UUID cursorId, @Param("viewerId") UUID viewerId, Pageable limit);

    // Bounds a page of roots' *entire* reply subtrees (every depth, not just direct children) at
    // :limitPerRoot total per root — the fix for the previous findRepliesByPostId's unbounded fetch.
    // subpath(path, 0, 1) is a root's own ltree label (a root's path IS just its own label, no dots), so
    // partitioning by it gives each root on this page an independent budget — one popular root can't
    // starve the others. rootLabels is the current page's roots' own .getPath() values, already in hand.
    // Returns ranked ids only, not full entities — same reasoning as PostRepository.searchIds/searchAllIds
    // (a native query selecting c.* risks Hibernate entity-mapping friction over the extra "rn" column);
    // CommentService.findCommentTree re-fetches via the ordinary, safely-mapped findAllById. Truncation
    // ranks a root's whole subtree together, not per-parent, so it isn't guaranteed to cut at clean
    // sibling-group boundaries — accepted, see this feature's plan for why that's still correct for the
    // childCount-vs-replies.size() truncation signal regardless.
    @Query(value = """
            SELECT id FROM (
              SELECT c.id, ROW_NUMBER() OVER (PARTITION BY subpath(c.path, 0, 1)::text ORDER BY c.best_rank DESC, c.id DESC) AS rn
              FROM comments c
              WHERE c.post_id = :postId AND c.parent_id IS NOT NULL
                AND subpath(c.path, 0, 1)::text IN (:rootLabels)
                AND (:viewerId IS NULL OR NOT EXISTS (
                    SELECT 1 FROM hidden_items h WHERE h.user_id = :viewerId AND h.target_type = 'comment' AND h.target_id = c.id))
            ) sub
            WHERE sub.rn <= :limitPerRoot
            """, nativeQuery = true)
    List<UUID> findBoundedReplyIdsByBest(@Param("postId") UUID postId, @Param("rootLabels") List<String> rootLabels,
                                          @Param("viewerId") UUID viewerId, @Param("limitPerRoot") int limitPerRoot);

    @Query(value = """
            SELECT id FROM (
              SELECT c.id, ROW_NUMBER() OVER (PARTITION BY subpath(c.path, 0, 1)::text ORDER BY c.score DESC, c.id DESC) AS rn
              FROM comments c
              WHERE c.post_id = :postId AND c.parent_id IS NOT NULL
                AND subpath(c.path, 0, 1)::text IN (:rootLabels)
                AND (:viewerId IS NULL OR NOT EXISTS (
                    SELECT 1 FROM hidden_items h WHERE h.user_id = :viewerId AND h.target_type = 'comment' AND h.target_id = c.id))
            ) sub
            WHERE sub.rn <= :limitPerRoot
            """, nativeQuery = true)
    List<UUID> findBoundedReplyIdsByTop(@Param("postId") UUID postId, @Param("rootLabels") List<String> rootLabels,
                                         @Param("viewerId") UUID viewerId, @Param("limitPerRoot") int limitPerRoot);

    @Query(value = """
            SELECT id FROM (
              SELECT c.id, ROW_NUMBER() OVER (PARTITION BY subpath(c.path, 0, 1)::text ORDER BY c.created_at DESC, c.id DESC) AS rn
              FROM comments c
              WHERE c.post_id = :postId AND c.parent_id IS NOT NULL
                AND subpath(c.path, 0, 1)::text IN (:rootLabels)
                AND (:viewerId IS NULL OR NOT EXISTS (
                    SELECT 1 FROM hidden_items h WHERE h.user_id = :viewerId AND h.target_type = 'comment' AND h.target_id = c.id))
            ) sub
            WHERE sub.rn <= :limitPerRoot
            """, nativeQuery = true)
    List<UUID> findBoundedReplyIdsByNew(@Param("postId") UUID postId, @Param("rootLabels") List<String> rootLabels,
                                         @Param("viewerId") UUID viewerId, @Param("limitPerRoot") int limitPerRoot);

    @Query(value = """
            SELECT id FROM (
              SELECT c.id, ROW_NUMBER() OVER (PARTITION BY subpath(c.path, 0, 1)::text ORDER BY c.created_at ASC, c.id ASC) AS rn
              FROM comments c
              WHERE c.post_id = :postId AND c.parent_id IS NOT NULL
                AND subpath(c.path, 0, 1)::text IN (:rootLabels)
                AND (:viewerId IS NULL OR NOT EXISTS (
                    SELECT 1 FROM hidden_items h WHERE h.user_id = :viewerId AND h.target_type = 'comment' AND h.target_id = c.id))
            ) sub
            WHERE sub.rn <= :limitPerRoot
            """, nativeQuery = true)
    List<UUID> findBoundedReplyIdsByOld(@Param("postId") UUID postId, @Param("rootLabels") List<String> rootLabels,
                                         @Param("viewerId") UUID viewerId, @Param("limitPerRoot") int limitPerRoot);

    @Query(value = """
            SELECT id FROM (
              SELECT c.id, ROW_NUMBER() OVER (PARTITION BY subpath(c.path, 0, 1)::text ORDER BY c.controversial_rank DESC, c.id DESC) AS rn
              FROM comments c
              WHERE c.post_id = :postId AND c.parent_id IS NOT NULL
                AND subpath(c.path, 0, 1)::text IN (:rootLabels)
                AND (:viewerId IS NULL OR NOT EXISTS (
                    SELECT 1 FROM hidden_items h WHERE h.user_id = :viewerId AND h.target_type = 'comment' AND h.target_id = c.id))
            ) sub
            WHERE sub.rn <= :limitPerRoot
            """, nativeQuery = true)
    List<UUID> findBoundedReplyIdsByControversial(@Param("postId") UUID postId, @Param("rootLabels") List<String> rootLabels,
                                                    @Param("viewerId") UUID viewerId, @Param("limitPerRoot") int limitPerRoot);

    // Direct children of one specific comment, paginated — backs GET /api/morechildren. Exactly the same
    // shape as the root queries above (keyset cursor per sort), just c.parentId = :parentId instead of
    // c.parentId IS NULL. No ltree/window function needed here: only one parent is in scope per call,
    // unlike the multi-root bounding above.
    @Query("""
            SELECT c FROM Comment c
            WHERE c.parentId = :parentId
              AND (c.bestRank < :cursorRank OR (c.bestRank = :cursorRank AND c.id < :cursorId))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM HiddenItem h WHERE h.userId = :viewerId AND h.targetType = 'comment' AND h.targetId = c.id))
            ORDER BY c.bestRank DESC, c.id DESC
            """)
    List<Comment> findChildrenByBest(@Param("parentId") UUID parentId, @Param("cursorRank") double cursorRank,
                                      @Param("cursorId") UUID cursorId, @Param("viewerId") UUID viewerId, Pageable limit);

    @Query("""
            SELECT c FROM Comment c
            WHERE c.parentId = :parentId
              AND (c.score < :cursorScore OR (c.score = :cursorScore AND c.id < :cursorId))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM HiddenItem h WHERE h.userId = :viewerId AND h.targetType = 'comment' AND h.targetId = c.id))
            ORDER BY c.score DESC, c.id DESC
            """)
    List<Comment> findChildrenByTop(@Param("parentId") UUID parentId, @Param("cursorScore") int cursorScore,
                                     @Param("cursorId") UUID cursorId, @Param("viewerId") UUID viewerId, Pageable limit);

    @Query("""
            SELECT c FROM Comment c
            WHERE c.parentId = :parentId
              AND (c.createdAt < :cursorCreatedAt OR (c.createdAt = :cursorCreatedAt AND c.id < :cursorId))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM HiddenItem h WHERE h.userId = :viewerId AND h.targetType = 'comment' AND h.targetId = c.id))
            ORDER BY c.createdAt DESC, c.id DESC
            """)
    List<Comment> findChildrenByNew(@Param("parentId") UUID parentId, @Param("cursorCreatedAt") Instant cursorCreatedAt,
                                     @Param("cursorId") UUID cursorId, @Param("viewerId") UUID viewerId, Pageable limit);

    // Ascending direction — see findTopLevelByOld's comment and Cursor.FIRST_PAGE_ASC.
    @Query("""
            SELECT c FROM Comment c
            WHERE c.parentId = :parentId
              AND (c.createdAt > :cursorCreatedAt OR (c.createdAt = :cursorCreatedAt AND c.id > :cursorId))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM HiddenItem h WHERE h.userId = :viewerId AND h.targetType = 'comment' AND h.targetId = c.id))
            ORDER BY c.createdAt ASC, c.id ASC
            """)
    List<Comment> findChildrenByOld(@Param("parentId") UUID parentId, @Param("cursorCreatedAt") Instant cursorCreatedAt,
                                     @Param("cursorId") UUID cursorId, @Param("viewerId") UUID viewerId, Pageable limit);

    @Query("""
            SELECT c FROM Comment c
            WHERE c.parentId = :parentId
              AND (c.controversialRank < :cursorRank OR (c.controversialRank = :cursorRank AND c.id < :cursorId))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM HiddenItem h WHERE h.userId = :viewerId AND h.targetType = 'comment' AND h.targetId = c.id))
            ORDER BY c.controversialRank DESC, c.id DESC
            """)
    List<Comment> findChildrenByControversial(@Param("parentId") UUID parentId, @Param("cursorRank") double cursorRank,
                                                @Param("cursorId") UUID cursorId, @Param("viewerId") UUID viewerId, Pageable limit);

    // clearAutomatically: without it, a `parent` entity already loaded in this transaction (see
    // CommentService.reply) keeps its stale pre-increment childCount in the persistence context, and a
    // later read or an unrelated dirty-checked flush of that entity would silently clobber this update.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Comment c SET c.childCount = c.childCount + 1 WHERE c.id = :id")
    void incrementChildCount(@Param("id") UUID id);

    @Query("SELECT c.id AS id, c.authorId AS authorId FROM Comment c WHERE c.id IN :ids")
    List<CommentAuthorProjection> findAuthorIdsByIds(@Param("ids") Set<UUID> ids);

    // A user's "comments" profile tab (F7) — every comment they've made, top-level or reply (unlike the
    // per-post root queries above, there's no parentId IS NULL restriction: Reddit's own Comments tab
    // shows replies too). Same HiddenItem viewer filter and unremoved-only convention as every other
    // listing query in this codebase.
    // Private-community exclusion: same bug class and fix as post.PostRepository's findByAuthorId/
    // "All" queries — this is a public, cross-community listing with no filter on the comment's own
    // community, so a comment made in a private community the viewer isn't a member/moderator of must be
    // excluded. Comment only carries postId, not communityId, so this goes one hop further than the post
    // side: Post/Community/Membership/CommunityModerator are all referenced as bare JPQL entity names
    // (the comment module already legitimately depends on post at the Java level via PostService, so this
    // is even more clearly safe than the equivalent post-side subquery — see that file's comment for the
    // no-Java-import, no-ModuleBoundaryTest-risk reasoning). A comment whose postId matches no real post
    // (comments.post_id has no FK constraint by design, see Phase 1's own notes) can't satisfy any of
    // these EXISTS checks either way, so it stays visible — same fail-open behavior as today for that
    // edge case, not a new risk.
    @Query("""
            SELECT c FROM Comment c
            WHERE c.authorId = :authorId AND c.removed = false AND NOT c.deleted
              AND (c.createdAt < :cursorCreatedAt OR (c.createdAt = :cursorCreatedAt AND c.id < :cursorId))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM HiddenItem h WHERE h.userId = :viewerId AND h.targetType = 'comment' AND h.targetId = c.id))
              AND (NOT EXISTS (SELECT 1 FROM Post p, Community cm WHERE p.id = c.postId AND cm.id = p.communityId AND cm.type = 'private')
                   OR EXISTS (SELECT 1 FROM Post p, Membership m WHERE p.id = c.postId AND m.communityId = p.communityId AND m.userId = :viewerId)
                   OR EXISTS (SELECT 1 FROM Post p, CommunityModerator cmod WHERE p.id = c.postId AND cmod.communityId = p.communityId AND cmod.userId = :viewerId))
            ORDER BY c.createdAt DESC, c.id DESC
            """)
    List<Comment> findByAuthorId(@Param("authorId") UUID authorId,
                                  @Param("cursorCreatedAt") Instant cursorCreatedAt,
                                  @Param("cursorId") UUID cursorId,
                                  @Param("viewerId") UUID viewerId,
                                  Pageable limit);

    interface CommentAuthorProjection {
        UUID getId();

        UUID getAuthorId();
    }
}
