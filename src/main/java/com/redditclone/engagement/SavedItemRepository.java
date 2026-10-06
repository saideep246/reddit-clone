package com.redditclone.engagement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SavedItemRepository extends JpaRepository<SavedItem, SavedItemId> {

    boolean existsByUserIdAndTargetTypeAndTargetId(UUID userId, String targetType, UUID targetId);

    void deleteByUserIdAndTargetTypeAndTargetId(UUID userId, String targetType, UUID targetId);

    Optional<SavedItem> findByUserIdAndTargetTypeAndTargetId(UUID userId, String targetType, UUID targetId);
}
