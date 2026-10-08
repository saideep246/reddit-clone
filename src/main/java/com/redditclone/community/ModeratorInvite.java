package com.redditclone.community;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "moderator_invites")
public class ModeratorInvite {

    public static final String PENDING = "pending";
    public static final String ACCEPTED = "accepted";
    public static final String DECLINED = "declined";
    public static final String CANCELLED = "cancelled";
    public static final String EXPIRED = "expired";

    @Id
    private UUID id;

    @Column(name = "community_id", nullable = false)
    private UUID communityId;

    @Column(name = "invitee_id", nullable = false)
    private UUID inviteeId;

    @Column(name = "inviter_id", nullable = false)
    private UUID inviterId;

    @Column(nullable = false)
    private int permissions;

    @Column(nullable = false)
    private String status = PENDING;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    public ModeratorInvite() {
    }

    public ModeratorInvite(UUID id, UUID communityId, UUID inviteeId, UUID inviterId, int permissions, Instant expiresAt) {
        this.id = id;
        this.communityId = communityId;
        this.inviteeId = inviteeId;
        this.inviterId = inviterId;
        this.permissions = permissions;
        this.expiresAt = expiresAt;
    }

    public boolean isPending() {
        return PENDING.equals(status);
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public void resolve(String newStatus) {
        this.status = newStatus;
        this.respondedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getCommunityId() { return communityId; }
    public UUID getInviteeId() { return inviteeId; }
    public UUID getInviterId() { return inviterId; }
    public int getPermissions() { return permissions; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRespondedAt() { return respondedAt; }
}
