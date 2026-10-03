package com.redditclone.moderation;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

import java.util.List;
import java.util.UUID;

public interface ModerationActionRepository extends JpaRepository<ModerationAction, UUID> {

    List<ModerationAction> findByCommunityIdOrderByCreatedAtDesc(UUID communityId, Pageable pageable);

    // Every filter is optional (null = don't filter), keyset-paged on createdAt via `before`.
    @Query("""
            SELECT a FROM ModerationAction a
            WHERE a.communityId = :communityId
              AND (:action = '' OR a.action = :action)
              AND (:actorId IS NULL OR a.actorId = :actorId)
              AND (:targetType = '' OR a.targetType = :targetType)
              AND (:targetId IS NULL OR a.targetId = :targetId)
              AND a.createdAt < :before
            ORDER BY a.createdAt DESC
            """)
    List<ModerationAction> findFiltered(@Param("communityId") UUID communityId, @Param("action") String action,
                                         @Param("actorId") UUID actorId, @Param("targetType") String targetType,
                                         @Param("targetId") UUID targetId, @Param("before") Instant before,
                                         Pageable pageable);
}
