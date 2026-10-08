package com.redditclone.community;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ModeratorInviteRepository extends JpaRepository<ModeratorInvite, UUID> {

    // Row lock for accept/decline/cancel: concurrent responses to the same invitation serialize here, so exactly one
    // wins and the loser sees the resolved status.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from ModeratorInvite i where i.id = :id")
    Optional<ModeratorInvite> findByIdForUpdate(@Param("id") UUID id);

    Optional<ModeratorInvite> findByCommunityIdAndInviteeIdAndStatus(UUID communityId, UUID inviteeId, String status);

    // Live pending invitations only (lazy expiry): the Moderators tab list.
    @Query("select i from ModeratorInvite i where i.communityId = :communityId and i.status = 'pending' and i.expiresAt > :now order by i.createdAt desc")
    List<ModeratorInvite> findLivePendingForCommunity(@Param("communityId") UUID communityId, @Param("now") Instant now);

    // The recipient's own pending invitations.
    @Query("select i from ModeratorInvite i where i.inviteeId = :inviteeId and i.status = 'pending' and i.expiresAt > :now order by i.createdAt desc")
    List<ModeratorInvite> findLivePendingForInvitee(@Param("inviteeId") UUID inviteeId, @Param("now") Instant now);

    // Frees the partial-unique slot before a re-invite: a lapsed pending row would otherwise block it forever.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ModeratorInvite i set i.status = 'expired', i.respondedAt = :now where i.communityId = :communityId and i.inviteeId = :inviteeId and i.status = 'pending' and i.expiresAt <= :now")
    int expireLapsed(@Param("communityId") UUID communityId, @Param("inviteeId") UUID inviteeId, @Param("now") Instant now);
}
