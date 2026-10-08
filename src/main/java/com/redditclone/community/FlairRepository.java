package com.redditclone.community;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FlairRepository extends JpaRepository<Flair, UUID> {

    // Deterministic order for every flair list: oldest first by the existing created_at, id as the tie-break.
    List<Flair> findByCommunityIdOrderByCreatedAtAscIdAsc(UUID communityId);

    List<Flair> findByCommunityIdAndTypeOrderByCreatedAtAscIdAsc(UUID communityId, String type);

    // Application-level duplicate check (same community, same type, same text ignoring case). There is deliberately NO unique
    // constraint behind it, so two simultaneous requests could both pass this check; see CommunityService.addFlair.
    boolean existsByCommunityIdAndTypeAndTextIgnoreCase(UUID communityId, String type, String text);

    boolean existsByCommunityIdAndTypeAndTextIgnoreCaseAndIdNot(UUID communityId, String type, String text, UUID id);
}
