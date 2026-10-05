package com.redditclone.post;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PostRepository extends JpaRepository<Post, UUID> {

    // The NOT EXISTS clause against HiddenItem is a standard JPQL subquery against another mapped
    // entity — Hibernate resolves "HiddenItem" against its global metamodel regardless of which Java
    // package declares it, and since the reference lives inside this @Query string (not a Java import),
    // it creates no compile-time post -> engagement dependency and no ModuleBoundaryTest cycle risk.
    // viewerId is null for an unauthenticated request (these endpoints stay public), in which case the
    // filter is skipped entirely rather than matching nothing.
    @Query("""
            SELECT p FROM Post p
            WHERE p.communityId = :communityId AND p.removed = false AND NOT p.deleted
              AND (p.createdAt < :cursorCreatedAt OR (p.createdAt = :cursorCreatedAt AND p.id < :cursorId))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM HiddenItem h WHERE h.userId = :viewerId AND h.targetType = 'post' AND h.targetId = p.id))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM UserBlock b WHERE b.blockerId = :viewerId AND b.blockedId = p.authorId))
            ORDER BY p.createdAt DESC, p.id DESC
            """)
    List<Post> findNewPage(@Param("communityId") UUID communityId,
                            @Param("cursorCreatedAt") Instant cursorCreatedAt,
                            @Param("cursorId") UUID cursorId,
                            @Param("viewerId") UUID viewerId,
                            Pageable limit);

    // Sitewide "r/all" counterpart of findNewPage above — identical keyset ordering and hidden-items
    // filter, just without the single-community predicate. See PostController/PostService for how the
    // "all" pseudo-community name routes here instead of the per-community method.
    // Private-community exclusion mirrors CommunityService.requireViewAccess's own "non-private, or a
    // member, or any moderator" rule, done in bulk since there's no single community to call that
    // per-community check against — same shape as searchAllIds below, translated to JPQL. Referencing
    // Community/Membership/CommunityModerator by simple class name here is a standard JPQL subquery
    // against another mapped entity (Hibernate resolves it against the global metamodel), not a Java
    // import, so it creates no compile-time post -> community dependency and no ModuleBoundaryTest cycle
    // risk — same precedent as the HiddenItem subquery just above. A NULL :viewerId (anonymous) never
    // satisfies either EXISTS, so anonymous viewers correctly see only non-private posts with no extra
    // IS NULL special-casing needed.
    @Query("""
            SELECT p FROM Post p
            WHERE p.removed = false AND NOT p.deleted
              AND (p.createdAt < :cursorCreatedAt OR (p.createdAt = :cursorCreatedAt AND p.id < :cursorId))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM HiddenItem h WHERE h.userId = :viewerId AND h.targetType = 'post' AND h.targetId = p.id))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM UserBlock b WHERE b.blockerId = :viewerId AND b.blockedId = p.authorId))
              AND (NOT EXISTS (SELECT 1 FROM Community cm WHERE cm.id = p.communityId AND cm.type = 'private')
                   OR EXISTS (SELECT 1 FROM Membership m WHERE m.userId = :viewerId AND m.communityId = p.communityId)
                   OR EXISTS (SELECT 1 FROM CommunityModerator cmod WHERE cmod.userId = :viewerId AND cmod.communityId = p.communityId))
            ORDER BY p.createdAt DESC, p.id DESC
            """)
    List<Post> findNewAllPage(@Param("cursorCreatedAt") Instant cursorCreatedAt,
                               @Param("cursorId") UUID cursorId,
                               @Param("viewerId") UUID viewerId,
                               Pageable limit);

    @Modifying
    @Query("UPDATE Post p SET p.commentCount = p.commentCount + 1 WHERE p.id = :id")
    void incrementCommentCount(@Param("id") UUID id);

    // A user's own "submitted" tab (F7) — identical shape to findNewAllPage (same removed/HiddenItem
    // filters, same (createdAt, id) keyset order), scoped by author instead of sitewide. Also carries the
    // same private-community exclusion as the "All" queries below (this is a public endpoint spanning every
    // community the author has ever posted in, the same leak risk as a sitewide listing) — see
    // findNewAllPage's comment for why the Community/Membership/CommunityModerator references below are
    // safe.
    @Query("""
            SELECT p FROM Post p
            WHERE p.authorId = :authorId AND p.removed = false AND NOT p.deleted
              AND (p.createdAt < :cursorCreatedAt OR (p.createdAt = :cursorCreatedAt AND p.id < :cursorId))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM HiddenItem h WHERE h.userId = :viewerId AND h.targetType = 'post' AND h.targetId = p.id))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM UserBlock b WHERE b.blockerId = :viewerId AND b.blockedId = p.authorId))
              AND (NOT EXISTS (SELECT 1 FROM Community cm WHERE cm.id = p.communityId AND cm.type = 'private')
                   OR EXISTS (SELECT 1 FROM Membership m WHERE m.userId = :viewerId AND m.communityId = p.communityId)
                   OR EXISTS (SELECT 1 FROM CommunityModerator cmod WHERE cmod.userId = :viewerId AND cmod.communityId = p.communityId))
            ORDER BY p.createdAt DESC, p.id DESC
            """)
    List<Post> findByAuthorId(@Param("authorId") UUID authorId,
                               @Param("cursorCreatedAt") Instant cursorCreatedAt,
                               @Param("cursorId") UUID cursorId,
                               @Param("viewerId") UUID viewerId,
                               Pageable limit);

    @Query("""
            SELECT p FROM Post p
            WHERE p.communityId = :communityId AND p.removed = false AND NOT p.deleted
              AND (p.hotRank < :cursorRank OR (p.hotRank = :cursorRank AND p.id < :cursorId))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM HiddenItem h WHERE h.userId = :viewerId AND h.targetType = 'post' AND h.targetId = p.id))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM UserBlock b WHERE b.blockerId = :viewerId AND b.blockedId = p.authorId))
            ORDER BY p.hotRank DESC, p.id DESC
            """)
    List<Post> findHotPage(@Param("communityId") UUID communityId,
                            @Param("cursorRank") double cursorRank,
                            @Param("cursorId") UUID cursorId,
                            @Param("viewerId") UUID viewerId,
                            Pageable limit);

    // Private-community exclusion: see findNewAllPage's comment for the full reasoning.
    @Query("""
            SELECT p FROM Post p
            WHERE p.removed = false AND NOT p.deleted
              AND (p.hotRank < :cursorRank OR (p.hotRank = :cursorRank AND p.id < :cursorId))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM HiddenItem h WHERE h.userId = :viewerId AND h.targetType = 'post' AND h.targetId = p.id))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM UserBlock b WHERE b.blockerId = :viewerId AND b.blockedId = p.authorId))
              AND (NOT EXISTS (SELECT 1 FROM Community cm WHERE cm.id = p.communityId AND cm.type = 'private')
                   OR EXISTS (SELECT 1 FROM Membership m WHERE m.userId = :viewerId AND m.communityId = p.communityId)
                   OR EXISTS (SELECT 1 FROM CommunityModerator cmod WHERE cmod.userId = :viewerId AND cmod.communityId = p.communityId))
            ORDER BY p.hotRank DESC, p.id DESC
            """)
    List<Post> findHotAllPage(@Param("cursorRank") double cursorRank,
                               @Param("cursorId") UUID cursorId,
                               @Param("viewerId") UUID viewerId,
                               Pageable limit);

    @Query("""
            SELECT p FROM Post p
            WHERE p.communityId = :communityId AND p.removed = false AND NOT p.deleted AND p.createdAt >= :since
              AND (p.score < :cursorScore OR (p.score = :cursorScore AND p.id < :cursorId))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM HiddenItem h WHERE h.userId = :viewerId AND h.targetType = 'post' AND h.targetId = p.id))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM UserBlock b WHERE b.blockerId = :viewerId AND b.blockedId = p.authorId))
            ORDER BY p.score DESC, p.id DESC
            """)
    List<Post> findTopPage(@Param("communityId") UUID communityId,
                            @Param("since") Instant since,
                            @Param("cursorScore") int cursorScore,
                            @Param("cursorId") UUID cursorId,
                            @Param("viewerId") UUID viewerId,
                            Pageable limit);

    // Private-community exclusion: see findNewAllPage's comment for the full reasoning.
    @Query("""
            SELECT p FROM Post p
            WHERE p.removed = false AND NOT p.deleted AND p.createdAt >= :since
              AND (p.score < :cursorScore OR (p.score = :cursorScore AND p.id < :cursorId))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM HiddenItem h WHERE h.userId = :viewerId AND h.targetType = 'post' AND h.targetId = p.id))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM UserBlock b WHERE b.blockerId = :viewerId AND b.blockedId = p.authorId))
              AND (NOT EXISTS (SELECT 1 FROM Community cm WHERE cm.id = p.communityId AND cm.type = 'private')
                   OR EXISTS (SELECT 1 FROM Membership m WHERE m.userId = :viewerId AND m.communityId = p.communityId)
                   OR EXISTS (SELECT 1 FROM CommunityModerator cmod WHERE cmod.userId = :viewerId AND cmod.communityId = p.communityId))
            ORDER BY p.score DESC, p.id DESC
            """)
    List<Post> findTopAllPage(@Param("since") Instant since,
                               @Param("cursorScore") int cursorScore,
                               @Param("cursorId") UUID cursorId,
                               @Param("viewerId") UUID viewerId,
                               Pageable limit);

    @Query("""
            SELECT p FROM Post p
            WHERE p.communityId = :communityId AND p.removed = false AND NOT p.deleted
              AND (p.risingRank < :cursorRank OR (p.risingRank = :cursorRank AND p.id < :cursorId))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM HiddenItem h WHERE h.userId = :viewerId AND h.targetType = 'post' AND h.targetId = p.id))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM UserBlock b WHERE b.blockerId = :viewerId AND b.blockedId = p.authorId))
            ORDER BY p.risingRank DESC, p.id DESC
            """)
    List<Post> findRisingPage(@Param("communityId") UUID communityId,
                               @Param("cursorRank") double cursorRank,
                               @Param("cursorId") UUID cursorId,
                               @Param("viewerId") UUID viewerId,
                               Pageable limit);

    // Private-community exclusion: see findNewAllPage's comment for the full reasoning.
    @Query("""
            SELECT p FROM Post p
            WHERE p.removed = false AND NOT p.deleted
              AND (p.risingRank < :cursorRank OR (p.risingRank = :cursorRank AND p.id < :cursorId))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM HiddenItem h WHERE h.userId = :viewerId AND h.targetType = 'post' AND h.targetId = p.id))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM UserBlock b WHERE b.blockerId = :viewerId AND b.blockedId = p.authorId))
              AND (NOT EXISTS (SELECT 1 FROM Community cm WHERE cm.id = p.communityId AND cm.type = 'private')
                   OR EXISTS (SELECT 1 FROM Membership m WHERE m.userId = :viewerId AND m.communityId = p.communityId)
                   OR EXISTS (SELECT 1 FROM CommunityModerator cmod WHERE cmod.userId = :viewerId AND cmod.communityId = p.communityId))
            ORDER BY p.risingRank DESC, p.id DESC
            """)
    List<Post> findRisingAllPage(@Param("cursorRank") double cursorRank,
                                  @Param("cursorId") UUID cursorId,
                                  @Param("viewerId") UUID viewerId,
                                  Pageable limit);

    @Query("""
            SELECT p FROM Post p
            WHERE p.communityId = :communityId AND p.removed = false AND NOT p.deleted
              AND (p.controversialRank < :cursorRank OR (p.controversialRank = :cursorRank AND p.id < :cursorId))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM HiddenItem h WHERE h.userId = :viewerId AND h.targetType = 'post' AND h.targetId = p.id))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM UserBlock b WHERE b.blockerId = :viewerId AND b.blockedId = p.authorId))
            ORDER BY p.controversialRank DESC, p.id DESC
            """)
    List<Post> findControversialPage(@Param("communityId") UUID communityId,
                                      @Param("cursorRank") double cursorRank,
                                      @Param("cursorId") UUID cursorId,
                                      @Param("viewerId") UUID viewerId,
                                      Pageable limit);

    // Private-community exclusion: see findNewAllPage's comment for the full reasoning.
    @Query("""
            SELECT p FROM Post p
            WHERE p.removed = false AND NOT p.deleted
              AND (p.controversialRank < :cursorRank OR (p.controversialRank = :cursorRank AND p.id < :cursorId))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM HiddenItem h WHERE h.userId = :viewerId AND h.targetType = 'post' AND h.targetId = p.id))
              AND (:viewerId IS NULL OR NOT EXISTS (
                  SELECT 1 FROM UserBlock b WHERE b.blockerId = :viewerId AND b.blockedId = p.authorId))
              AND (NOT EXISTS (SELECT 1 FROM Community cm WHERE cm.id = p.communityId AND cm.type = 'private')
                   OR EXISTS (SELECT 1 FROM Membership m WHERE m.userId = :viewerId AND m.communityId = p.communityId)
                   OR EXISTS (SELECT 1 FROM CommunityModerator cmod WHERE cmod.userId = :viewerId AND cmod.communityId = p.communityId))
            ORDER BY p.controversialRank DESC, p.id DESC
            """)
    List<Post> findControversialAllPage(@Param("cursorRank") double cursorRank,
                                         @Param("cursorId") UUID cursorId,
                                         @Param("viewerId") UUID viewerId,
                                         Pageable limit);

    // Returns ranked ids only, not full entities: search_vector is deliberately unmapped on Post (see its
    // comment), so a native query selecting p.* would return an extra column Hibernate isn't expecting.
    // PostService.search fetches the actual entities via the ordinary, safely-mapped findAllById and
    // re-applies this ranking order, rather than fighting native-query-to-entity mapping for one endpoint.
    @Query(value = """
            SELECT id FROM posts
            WHERE community_id = :communityId AND NOT removed AND NOT deleted
              AND (search_vector @@ websearch_to_tsquery('english', :query)
                   OR search_vector @@ to_tsquery('english', :prefixQuery))
            ORDER BY ts_rank(search_vector, websearch_to_tsquery('english', :query))
                     + ts_rank(search_vector, to_tsquery('english', :prefixQuery)) DESC
            LIMIT 25
            """, nativeQuery = true)
    List<UUID> searchIds(@Param("communityId") UUID communityId, @Param("query") String query,
                          @Param("prefixQuery") String prefixQuery);

    // Sitewide "r/all" counterpart of searchIds above — same ranked-ids-only shape, minus the community_id
    // filter, plus an explicit private-community exclusion (mirrors CommunityService.requireViewAccess's
    // own "non-private, or a member, or any moderator" rule, done in bulk here since there's no single
    // community to call that per-community check against). Joining communities/memberships/
    // community_moderators by table name in this native query is not a cross-module repository reference
    // (no Java import of MembershipRepository/CommunityModeratorRepository), so this stays inside the post
    // module with no new ModuleBoundaryTest risk — same precedent as the HiddenItem JPQL subquery above.
    // A NULL :viewerId (anonymous) never satisfies either EXISTS, so anonymous viewers correctly see only
    // non-private matches with no extra IS NULL special-casing needed.
    @Query(value = """
            SELECT p.id FROM posts p
            JOIN communities c ON c.id = p.community_id
            WHERE NOT p.removed AND NOT p.deleted
              AND (p.search_vector @@ websearch_to_tsquery('english', :query)
                   OR p.search_vector @@ to_tsquery('english', :prefixQuery))
              AND (c.type <> 'private'
                   OR EXISTS (SELECT 1 FROM memberships m WHERE m.user_id = :viewerId AND m.community_id = p.community_id)
                   OR EXISTS (SELECT 1 FROM community_moderators cm WHERE cm.user_id = :viewerId AND cm.community_id = p.community_id))
            ORDER BY ts_rank(p.search_vector, websearch_to_tsquery('english', :query))
                     + ts_rank(p.search_vector, to_tsquery('english', :prefixQuery)) DESC
            LIMIT 25
            """, nativeQuery = true)
    List<UUID> searchAllIds(@Param("query") String query, @Param("prefixQuery") String prefixQuery,
                             @Param("viewerId") UUID viewerId);

    int countByCommunityIdAndPinnedTrue(UUID communityId);

    List<Post> findByCommunityIdAndPinnedTrueAndRemovedFalseAndDeletedFalseOrderByCreatedAtDesc(UUID communityId);
}
