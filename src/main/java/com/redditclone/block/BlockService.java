package com.redditclone.block;

import com.redditclone.auth.AuthService;
import com.redditclone.common.exception.BadRequestException;
import com.redditclone.common.exception.ForbiddenException;
import com.redditclone.common.exception.NotFoundException;
import com.redditclone.follow.FollowService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

// Blocking is one-directional state ("A blocks B") with two-directional effects on interaction: B can no
// longer reply to A's posts/comments or start a new chat with A, and A stops seeing B's posts in feeds.
// Existing follows in either direction are removed so a block really severs the relationship.
@Service
public class BlockService {

    private final UserBlockRepository blocks;
    private final AuthService auth;
    private final FollowService follows;

    public BlockService(UserBlockRepository blocks, AuthService auth, FollowService follows) {
        this.blocks = blocks;
        this.auth = auth;
        this.follows = follows;
    }

    // Returns whether this call changed anything (false for the idempotent already-blocked case).
    @Transactional
    public boolean block(UUID blockerId, String targetUsername) {
        UUID targetId = auth.findUserIdByUsername(targetUsername).orElseThrow(() -> new NotFoundException("no such user"));
        if (blockerId.equals(targetId)) {
            throw new BadRequestException("cannot block yourself");
        }
        boolean changed = blocks.insertIfAbsent(blockerId, targetId, Instant.now()) > 0;
        if (changed) {
            String blockerName = auth.findUsernamesByIds(Set.of(blockerId)).get(blockerId);
            follows.unfollow(blockerId, targetUsername);
            if (blockerName != null) {
                follows.unfollow(targetId, blockerName);
            }
        }
        return changed;
    }

    @Transactional
    public boolean unblock(UUID blockerId, String targetUsername) {
        UUID targetId = auth.findUserIdByUsername(targetUsername).orElseThrow(() -> new NotFoundException("no such user"));
        return blocks.deleteByBlockerIdAndBlockedId(blockerId, targetId) > 0;
    }

    public boolean isBlocking(UUID blockerId, String targetUsername) {
        UUID targetId = auth.findUserIdByUsername(targetUsername).orElseThrow(() -> new NotFoundException("no such user"));
        return blocks.existsByBlockerIdAndBlockedId(blockerId, targetId);
    }

    // Ids only (no username lookup), for callers that just need to leave blocked users out, e.g. the chat user picker.
    public Set<UUID> blockedIds(UUID blockerId) {
        return blocks.findByBlockerIdOrderByCreatedAtDesc(blockerId).stream().map(UserBlock::getBlockedId).collect(Collectors.toSet());
    }

    public List<BlockedUser> list(UUID blockerId) {
        List<UserBlock> rows = blocks.findByBlockerIdOrderByCreatedAtDesc(blockerId);
        Map<UUID, String> names = auth.findUsernamesByIds(rows.stream().map(UserBlock::getBlockedId).collect(Collectors.toSet()));
        return rows.stream().map(r -> new BlockedUser(r.getBlockedId(), names.get(r.getBlockedId()), r.getCreatedAt())).toList();
    }

    // Called by comment replies and new chat rooms: `owner` is whoever's content/inbox is being interacted
    // with, `actor` is the one trying to interact. Null owner (e.g. a deleted parent) never blocks.
    public void requireNotBlockedBy(UUID ownerId, UUID actorId) {
        if (ownerId != null && !ownerId.equals(actorId) && blocks.existsByBlockerIdAndBlockedId(ownerId, actorId)) {
            throw new ForbiddenException("you can't interact with this user");
        }
    }
}
