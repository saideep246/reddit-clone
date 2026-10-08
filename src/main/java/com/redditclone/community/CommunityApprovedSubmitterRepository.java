package com.redditclone.community;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CommunityApprovedSubmitterRepository extends JpaRepository<CommunityApprovedSubmitter, CommunityApprovedSubmitterId> {

    // Newest approvals first, for the Approved posters tab (a community's list is small by nature).
    List<CommunityApprovedSubmitter> findByCommunityIdOrderByApprovedAtDesc(UUID communityId);

    boolean existsByCommunityIdAndUserId(UUID communityId, UUID userId);

    long deleteByCommunityIdAndUserId(UUID communityId, UUID userId);

    // Batched counterpart of existsByCommunityIdAndUserId, read by CommunityService.attachViewerContextBatch (never N+1).
    List<CommunityApprovedSubmitter> findByUserIdAndCommunityIdIn(UUID userId, java.util.Collection<UUID> communityIds);
}
