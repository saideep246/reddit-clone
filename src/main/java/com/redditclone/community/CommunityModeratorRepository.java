package com.redditclone.community;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CommunityModeratorRepository extends JpaRepository<CommunityModerator, CommunityModeratorId> {

    Optional<CommunityModerator> findByCommunityIdAndUserId(UUID communityId, UUID userId);

    // A community's whole moderator list (small by nature), oldest first.
    List<CommunityModerator> findByCommunityIdOrderByAddedAtAsc(UUID communityId);

    boolean existsByCommunityIdAndUserId(UUID communityId, UUID userId);

    long deleteByCommunityIdAndUserId(UUID communityId, UUID userId);

    // Batched counterpart of existsByCommunityIdAndUserId — read by
    // CommunityService.attachViewerContextBatch, same never-N+1 reasoning as MembershipRepository's.
    List<CommunityModerator> findByUserIdAndCommunityIdIn(UUID userId, Collection<UUID> communityIds);
}
