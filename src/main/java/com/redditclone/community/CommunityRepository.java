package com.redditclone.community;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CommunityRepository extends JpaRepository<Community, UUID> {

    // No IgnoreCase: name is citext, already case-insensitive at the DB level — see UserRepository for why
    // IgnoreCase itself (Hibernate's upper() HQL function) is incompatible with a citext-mapped column.
    Optional<Community> findByName(String name);

    boolean existsByName(String name);

    @Modifying
    @Query("UPDATE Community c SET c.subscriberCount = c.subscriberCount + 1 WHERE c.id = :id")
    void incrementSubscriberCount(UUID id);

    @Modifying
    @Query("UPDATE Community c SET c.subscriberCount = c.subscriberCount - 1 WHERE c.id = :id")
    void decrementSubscriberCount(UUID id);

    // Compare-and-set for community deletion: the row is marked deleted only if it is not already, in one atomic UPDATE,
    // so of any number of concurrent deletes exactly one gets 1 back and the rest get 0. Native SQL so deleted_at takes the
    // database's own clock; the persistence context is cleared afterwards so no stale, un-deleted copy of the entity is
    // left in the session.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE communities SET deleted_at = now(), deleted_by = :actorId WHERE id = :id AND deleted_at IS NULL",
            nativeQuery = true)
    int markDeleted(@Param("id") UUID id, @Param("actorId") UUID actorId);

    // Browse/discovery — same keyset-pagination shape as every post-feed listing in this codebase. Every
    // community is included regardless of type: existence/description/subscriber count are metadata, the
    // same category GET /flairs, GET /rules and GET /pinned already treat as public even for private
    // communities. A deleted community (deleted_at set) is never listed: it is gone as far as users can tell.
    @Query("""
            SELECT c FROM Community c
            WHERE c.deletedAt IS NULL
              AND (c.createdAt < :cursorCreatedAt OR (c.createdAt = :cursorCreatedAt AND c.id < :cursorId))
            ORDER BY c.createdAt DESC, c.id DESC
            """)
    List<Community> findNewPage(@Param("cursorCreatedAt") Instant cursorCreatedAt,
                                 @Param("cursorId") UUID cursorId, Pageable limit);

    @Query("""
            SELECT c FROM Community c
            WHERE c.deletedAt IS NULL
              AND (c.subscriberCount < :cursorSubscriberCount
                   OR (c.subscriberCount = :cursorSubscriberCount AND c.id < :cursorId))
            ORDER BY c.subscriberCount DESC, c.id DESC
            """)
    List<Community> findPopularPage(@Param("cursorSubscriberCount") int cursorSubscriberCount,
                                     @Param("cursorId") UUID cursorId, Pageable limit);

    // Native query: the GIN trigram index (communities_name_trgm_idx) accelerates ILIKE's substring match;
    // similarity() orders best-match-first, subscriber_count breaks ties. No pagination — same "a
    // relevance ranking isn't a stable keyset sort key" reasoning PostService.search already documents.
    @Query(value = """
            SELECT * FROM communities
            WHERE deleted_at IS NULL AND name ILIKE '%' || :query || '%'
            ORDER BY similarity(name, :query) DESC, subscriber_count DESC
            LIMIT 25
            """, nativeQuery = true)
    List<Community> searchByName(@Param("query") String query);
}
