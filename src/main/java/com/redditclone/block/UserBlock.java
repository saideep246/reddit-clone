package com.redditclone.block;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

// Referenced by name from PostRepository's JPQL feed queries (NOT EXISTS subquery), the same no-compile-
// dependency trick the HiddenItem filter uses.
@Entity
@Table(name = "user_blocks")
@IdClass(UserBlockId.class)
public class UserBlock {

    @Id
    @Column(name = "blocker_id")
    private UUID blockerId;

    @Id
    @Column(name = "blocked_id")
    private UUID blockedId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public UserBlock() {
    }

    public UUID getBlockerId() {
        return blockerId;
    }

    public UUID getBlockedId() {
        return blockedId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
