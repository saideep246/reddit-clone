package com.redditclone.block;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface UserBlockRepository extends JpaRepository<UserBlock, UserBlockId> {

    boolean existsByBlockerIdAndBlockedId(UUID blockerId, UUID blockedId);

    long deleteByBlockerIdAndBlockedId(UUID blockerId, UUID blockedId);

    @Modifying
    @Query(value = """
            INSERT INTO user_blocks (blocker_id, blocked_id, created_at) VALUES (:blockerId, :blockedId, :createdAt)
            ON CONFLICT (blocker_id, blocked_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("blockerId") UUID blockerId, @Param("blockedId") UUID blockedId,
                        @Param("createdAt") Instant createdAt);

    List<UserBlock> findByBlockerIdOrderByCreatedAtDesc(UUID blockerId);
}
