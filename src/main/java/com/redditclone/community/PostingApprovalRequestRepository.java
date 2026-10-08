package com.redditclone.community;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PostingApprovalRequestRepository extends JpaRepository<PostingApprovalRequest, UUID> {

    // Row lock for approve/deny/cancel: concurrent responses to the same request serialize here. Under READ COMMITTED the
    // loser's WHERE is re-checked after the wait, so it finds no pending row and takes the "already resolved" path.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PostingApprovalRequest r where r.communityId = :communityId and r.userId = :userId and r.status = 'pending'")
    Optional<PostingApprovalRequest> findPendingForUpdate(@Param("communityId") UUID communityId, @Param("userId") UUID userId);

    @Query("select r from PostingApprovalRequest r where r.communityId = :communityId and r.userId = :userId and r.status = 'pending'")
    Optional<PostingApprovalRequest> findPending(@Param("communityId") UUID communityId, @Param("userId") UUID userId);

    // The person's most recent request here, to tell "denied", "approved" and "cancelled" apart once nothing is pending.
    Optional<PostingApprovalRequest> findFirstByCommunityIdAndUserIdOrderByCreatedAtDesc(UUID communityId, UUID userId);

    List<PostingApprovalRequest> findByCommunityIdAndStatusOrderByCreatedAtAsc(UUID communityId, String status);

    // Batched, for the viewer's own pending state across several communities (CommunityService.attachViewerContextBatch).
    List<PostingApprovalRequest> findByUserIdAndCommunityIdInAndStatus(UUID userId, Collection<UUID> communityIds, String status);

    // Inserts only if this person has no pending request here; the partial unique index decides a simultaneous race, so exactly
    // one caller gets 1 back and the other gets 0 (and then writes no log entry and no notification).
    @Modifying
    @Query(value = """
            INSERT INTO posting_approval_requests (id, community_id, user_id, status)
            VALUES (:id, :communityId, :userId, 'pending')
            ON CONFLICT (community_id, user_id) WHERE status = 'pending' DO NOTHING
            """, nativeQuery = true)
    int insertIfNoPending(@Param("id") UUID id, @Param("communityId") UUID communityId, @Param("userId") UUID userId);
}
