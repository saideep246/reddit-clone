package com.redditclone.community;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

// A request for posting access in a RESTRICTED community. Not a membership request (see V39's note on community_join_requests).
@Entity
@Table(name = "posting_approval_requests")
public class PostingApprovalRequest {

    public static final String PENDING = "pending";
    public static final String APPROVED = "approved";
    public static final String DENIED = "denied";
    public static final String CANCELLED = "cancelled";

    @Id
    private UUID id;

    @Column(name = "community_id", nullable = false)
    private UUID communityId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String status = PENDING;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "responded_at")
    private Instant respondedAt;

    @Column(name = "responded_by")
    private UUID respondedBy;

    public PostingApprovalRequest() {
    }

    public void resolve(String newStatus, UUID by) {
        this.status = newStatus;
        this.respondedAt = Instant.now();
        this.respondedBy = by;
    }

    public UUID getId() { return id; }
    public UUID getCommunityId() { return communityId; }
    public UUID getUserId() { return userId; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getRespondedAt() { return respondedAt; }
    public UUID getRespondedBy() { return respondedBy; }
}
