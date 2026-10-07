package com.redditclone.common;

// Published inside the deleteCommunity transaction. post.FeedCacheService evicts the community's cached /hot page and the r/all
// page when it receives this AFTER commit, so a rolled-back delete never evicts anything. Lives in common because
// community must not depend on post (post already depends on community).
public record CommunityDeletedEvent(String communityName) {
}
