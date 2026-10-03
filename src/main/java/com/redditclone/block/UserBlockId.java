package com.redditclone.block;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class UserBlockId implements Serializable {

    private UUID blockerId;
    private UUID blockedId;

    public UserBlockId() {
    }

    public UserBlockId(UUID blockerId, UUID blockedId) {
        this.blockerId = blockerId;
        this.blockedId = blockedId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserBlockId that)) return false;
        return Objects.equals(blockerId, that.blockerId) && Objects.equals(blockedId, that.blockedId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(blockerId, blockedId);
    }
}
