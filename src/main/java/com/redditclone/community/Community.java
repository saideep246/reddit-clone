package com.redditclone.community;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.ColumnTransformer;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "communities")
public class Community {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, columnDefinition = "citext")
    private String name;

    @Column(nullable = false)
    private String type = "public";

    private String description;

    @Column(name = "creator_id", nullable = false)
    private UUID creatorId;

    @Column(name = "subscriber_count", nullable = false)
    private int subscriberCount = 0;

    // Human-readable sidebar rules (e.g. "1. Be civil") — a plain JSON string, same ColumnTransformer
    // convention as AutomodRule.config/OutboxEvent.payload, parsed/built by CommunityService, not this
    // entity. Deliberately separate from automod_rules, which is machine-evaluated filter config.
    @ColumnTransformer(write = "?::jsonb")
    @Column(columnDefinition = "jsonb", nullable = false)
    private String rules = "[]";

    @JsonIgnore
    @Column(name = "icon_media_id")
    private UUID iconMediaId;

    @JsonIgnore
    @Column(name = "banner_media_id")
    private UUID bannerMediaId;

    // Resolved from the media ids above by CommunityService.attachViewerContext (GET /{name}/about only).
    @Transient
    private String iconUrl;

    @Transient
    private String bannerUrl;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    // Populated by CommunityService.attachViewerContext() for GET /{name}/about only — left null (not
    // false) everywhere else, e.g. GET /r browse and /r/search, the same "null means not computed, not a
    // real false" convention as Post.media/Post.flair. Boolean wrapper, not primitive, specifically so
    // that absence serializes as JSON null rather than a misleading false.
    @Transient
    private Boolean isMember;

    @Transient
    private Boolean isModerator;

    // "pending" | "approved" | "denied" | null — null for an anonymous viewer, a non-private community, or
    // simply no join request ever made. Only ever populated alongside isMember/isModerator above.
    @Transient
    private String joinRequestStatus;

    // The viewer's own CommunityModerator.permissions bitmask (F8) — null (not 0) for a non-moderator or
    // anonymous viewer, same "absence vs a real zero" distinction isMember/isModerator already use; 0 is a
    // legitimate "moderator with no bits granted" value, distinct from "never computed". Lets the frontend
    // show only the controls a capped-permission moderator can actually use, instead of every moderator
    // seeing every control and discovering the gaps via 403s.
    @Transient
    private Integer myPermissions;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public UUID getCreatorId() {
        return creatorId;
    }

    public void setCreatorId(UUID creatorId) {
        this.creatorId = creatorId;
    }

    public int getSubscriberCount() {
        return subscriberCount;
    }

    public void setSubscriberCount(int subscriberCount) {
        this.subscriberCount = subscriberCount;
    }

    // Hidden from any endpoint that serializes this entity directly (POST /r, GET /r/{name}/about) — this
    // is the raw JSON-encoded string Hibernate maps the column to, and returning it as-is would double-
    // encode (a string field containing already-JSON-encoded text instead of a real array). Structured
    // access goes through CommunityService.getRules()/the dedicated GET /{name}/rules endpoint instead.
    @JsonIgnore
    public String getRules() {
        return rules;
    }

    public void setRules(String rules) {
        this.rules = rules;
    }

    public UUID getIconMediaId() {
        return iconMediaId;
    }

    public void setIconMediaId(UUID iconMediaId) {
        this.iconMediaId = iconMediaId;
    }

    public UUID getBannerMediaId() {
        return bannerMediaId;
    }

    public void setBannerMediaId(UUID bannerMediaId) {
        this.bannerMediaId = bannerMediaId;
    }

    public String getIconUrl() {
        return iconUrl;
    }

    public void setIconUrl(String iconUrl) {
        this.iconUrl = iconUrl;
    }

    public String getBannerUrl() {
        return bannerUrl;
    }

    public void setBannerUrl(String bannerUrl) {
        this.bannerUrl = bannerUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Boolean getIsMember() {
        return isMember;
    }

    public void setIsMember(Boolean isMember) {
        this.isMember = isMember;
    }

    public Boolean getIsModerator() {
        return isModerator;
    }

    public void setIsModerator(Boolean isModerator) {
        this.isModerator = isModerator;
    }

    public String getJoinRequestStatus() {
        return joinRequestStatus;
    }

    public void setJoinRequestStatus(String joinRequestStatus) {
        this.joinRequestStatus = joinRequestStatus;
    }

    public Integer getMyPermissions() {
        return myPermissions;
    }

    public void setMyPermissions(Integer myPermissions) {
        this.myPermissions = myPermissions;
    }
}
