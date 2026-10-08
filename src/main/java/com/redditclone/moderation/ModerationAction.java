package com.redditclone.moderation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

// Append-only audit ledger — mapped here for the read side (moderators viewing the log), but writes to
// this table happen from wherever a moderation-relevant action occurs (this module's own ModerationService,
// and community.CommunityService for automod-triggered actions) via raw SQL, not exclusively through this
// repository — see CommunityService's comment on why.
@Entity
@Table(name = "moderation_actions")
public class ModerationAction {

    @Id
    private UUID id;

    @Column(name = "community_id", nullable = false)
    private UUID communityId;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Column(nullable = false)
    private String action;

    @Column(name = "target_type", nullable = false)
    private String targetType;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    // Attached by ModerationService.listModerationActions in one batched lookup, never persisted.
    @jakarta.persistence.Transient
    private String actorUsername;

    // Resolved for rows whose target is a user (bans, invitations, approved submitters), so the log can say who it was about.
    @jakarta.persistence.Transient
    private String targetUsername;

    public UUID getId() {
        return id;
    }

    public UUID getCommunityId() {
        return communityId;
    }

    public UUID getActorId() {
        return actorId;
    }

    public String getAction() {
        return action;
    }

    public String getTargetType() {
        return targetType;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public String getReason() {
        return reason;
    }

    public String getTargetUsername() {
        return targetUsername;
    }

    public void setTargetUsername(String targetUsername) {
        this.targetUsername = targetUsername;
    }

    public String getActorUsername() {
        return actorUsername;
    }

    public void setActorUsername(String actorUsername) {
        this.actorUsername = actorUsername;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
