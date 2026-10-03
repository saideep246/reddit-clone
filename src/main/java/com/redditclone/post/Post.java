package com.redditclone.post;

import com.redditclone.community.Flair;
import com.redditclone.media.MediaView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;

import com.redditclone.post.dto.CrosspostParent;
import com.redditclone.post.dto.PollView;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "posts")
public class Post {

    @Id
    private UUID id;

    @Column(name = "community_id", nullable = false)
    private UUID communityId;

    // Populated by PostService.attachCommunityName(), same pattern/placement as media/flair below — a
    // sitewide "r/all" listing mixes posts from many communities, so the route alone can't tell the
    // client which one a given post belongs to the way a single-community feed's URL does.
    @Transient
    private String communityName;

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    // Populated by PostService.attachAuthorUsername(), same pattern/placement as media/flair below.
    @Transient
    private String authorUsername;

    @Column(nullable = false)
    private String kind; // text | link | image | video | gallery

    @Column(nullable = false)
    private String title;

    private String body;

    private String url;

    @Column(name = "media_id")
    private UUID mediaId;

    // Populated by PostService.attachMedia() before a Post is ever serialized — never persisted, and
    // null for text/link posts. Populating it in PostService (not PostController) means the /hot cache
    // path is automatically correct: media is attached before the listing is serialized and cached.
    @Transient
    private MediaView media;

    // Populated by PostService.attachGalleryMedia() for kind="gallery" posts only — null for every other
    // kind, same null-means-not-applicable convention as media/flair above. media/mediaId stay null for a
    // gallery post; this is the only place its images live.
    @Transient
    private List<MediaView> mediaItems;

    @Column(name = "flair_id")
    private UUID flairId;

    // Populated by PostService.attachFlair(), same pattern/placement as media above.
    @Transient
    private Flair flair;

    @Column(nullable = false)
    private boolean nsfw;

    @Column(nullable = false)
    private boolean spoiler;

    @Column(nullable = false)
    private int score = 0;

    @Column(name = "comment_count", nullable = false)
    private int commentCount = 0;

    @Column(name = "hot_rank", nullable = false)
    private double hotRank = 0;

    @Column(nullable = false)
    private int ups = 0;

    @Column(nullable = false)
    private int downs = 0;

    @Column(name = "controversial_rank", nullable = false)
    private double controversialRank = 0;

    @Column(name = "rising_rank", nullable = false)
    private double risingRank = 0;

    @Column(name = "rising_updated_at", nullable = false)
    private Instant risingUpdatedAt = Instant.now();

    @Column(nullable = false)
    private boolean pinned;

    @Column(nullable = false)
    private boolean locked;

    @Column(nullable = false)
    private boolean removed;

    // Author-initiated, distinct from `removed` (a moderator action): a deleted post keeps its row and
    // comment thread as a "[deleted]" tombstone, and must never be unremovable by a moderator.
    @Column(nullable = false)
    private boolean deleted;

    // Full-row saves from a stale snapshot must fail, not silently rewrite columns a concurrent
    // moderator removal or author delete already changed.
    @Version
    private int version;

    // The ORIGINAL post this one crossposts (never another crosspost), null for ordinary posts.
    @Column(name = "crosspost_of")
    private UUID crosspostOf;

    // Populated by PostService.attachPoll / attachCrosspostParent, same pattern/placement as media/flair.
    @Transient
    private PollView poll;

    @Transient
    private CrosspostParent crosspostParent;

    @Column(name = "edited_at")
    private Instant editedAt;

    // search_vector is intentionally not mapped: it's populated by the V3 posts_search_vector_trigger
    // and unused until Phase 3 search. ddl-auto=validate only checks mapped columns, so leaving it out is safe.

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getCommunityId() {
        return communityId;
    }

    public void setCommunityId(UUID communityId) {
        this.communityId = communityId;
    }

    public String getCommunityName() {
        return communityName;
    }

    public void setCommunityName(String communityName) {
        this.communityName = communityName;
    }

    public UUID getAuthorId() {
        return authorId;
    }

    public void setAuthorId(UUID authorId) {
        this.authorId = authorId;
    }

    public String getAuthorUsername() {
        return authorUsername;
    }

    public void setAuthorUsername(String authorUsername) {
        this.authorUsername = authorUsername;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public UUID getMediaId() {
        return mediaId;
    }

    public void setMediaId(UUID mediaId) {
        this.mediaId = mediaId;
    }

    public MediaView getMedia() {
        return media;
    }

    public void setMedia(MediaView media) {
        this.media = media;
    }

    public List<MediaView> getMediaItems() {
        return mediaItems;
    }

    public void setMediaItems(List<MediaView> mediaItems) {
        this.mediaItems = mediaItems;
    }

    public UUID getFlairId() {
        return flairId;
    }

    public void setFlairId(UUID flairId) {
        this.flairId = flairId;
    }

    public Flair getFlair() {
        return flair;
    }

    public void setFlair(Flair flair) {
        this.flair = flair;
    }

    public boolean isNsfw() {
        return nsfw;
    }

    public void setNsfw(boolean nsfw) {
        this.nsfw = nsfw;
    }

    public boolean isSpoiler() {
        return spoiler;
    }

    public void setSpoiler(boolean spoiler) {
        this.spoiler = spoiler;
    }

    public int getScore() {
        return score;
    }

    // No setter: score is exclusively mutated via PostService.applyVoteDeltas's raw-SQL bulk update,
    // same reasoning as hot_rank/ups/downs below.

    public int getCommentCount() {
        return commentCount;
    }

    // No setter: commentCount is exclusively mutated via PostRepository.incrementCommentCount's atomic
    // bulk UPDATE, same reasoning as Comment.childCount.

    public double getHotRank() {
        return hotRank;
    }

    // Package-private, not public: hot_rank's formula gives a never-voted post a nonzero, time-driven
    // value (unlike controversial_rank/rising_rank, which are correctly 0 at zero votes), so PostService
    // .create() must set it once at insert time or new posts sort below every voted post on /hot forever.
    // Every later change goes through PostService.applyVoteDeltas's raw-SQL bulk update instead — same
    // one-legitimate-use reasoning as Community.setSubscriberCount.
    void setHotRank(double hotRank) {
        this.hotRank = hotRank;
    }

    // No setter: ups/downs/controversial_rank/rising_rank/rising_updated_at are exclusively mutated via
    // PostService.applyVoteDeltas's raw-SQL bulk update, same reasoning as commentCount — Hibernate still
    // hydrates these fields on read via its own field access, no setter needed for that.

    public int getUps() {
        return ups;
    }

    public int getDowns() {
        return downs;
    }

    public double getControversialRank() {
        return controversialRank;
    }

    public double getRisingRank() {
        return risingRank;
    }

    public Instant getRisingUpdatedAt() {
        return risingUpdatedAt;
    }

    public boolean isPinned() {
        return pinned;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }

    public boolean isLocked() {
        return locked;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
    }

    public boolean isRemoved() {
        return removed;
    }

    public void setRemoved(boolean removed) {
        this.removed = removed;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public UUID getCrosspostOf() {
        return crosspostOf;
    }

    public void setCrosspostOf(UUID crosspostOf) {
        this.crosspostOf = crosspostOf;
    }

    public PollView getPoll() {
        return poll;
    }

    public void setPoll(PollView poll) {
        this.poll = poll;
    }

    public CrosspostParent getCrosspostParent() {
        return crosspostParent;
    }

    public void setCrosspostParent(CrosspostParent crosspostParent) {
        this.crosspostParent = crosspostParent;
    }

    public Instant getEditedAt() {
        return editedAt;
    }

    public void setEditedAt(Instant editedAt) {
        this.editedAt = editedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
