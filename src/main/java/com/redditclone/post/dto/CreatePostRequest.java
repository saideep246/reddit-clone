package com.redditclone.post.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record CreatePostRequest(
        @NotBlank @Pattern(regexp = "text|link|image|video|gallery|poll|crosspost") String kind,
        @NotBlank @Size(max = 300) String title,
        @Size(max = 40000) String body,
        String url,
        UUID mediaId,
        @Size(max = 20) List<@NotNull UUID> mediaIds,
        UUID flairId,
        Boolean nsfw,
        Boolean spoiler,
        @Size(max = 6) List<String> pollOptions,
        Integer pollDays,
        UUID crosspostOf
) {

    // Records deserialize through their canonical constructor, not individual setters — unlike a
    // JavaBean, where Jackson simply skips calling the setter for an absent field (leaving the field's
    // Java-assigned default), an absent record component is passed to the constructor as null. A
    // primitive boolean nsfw/spoiler would crash unboxing null the moment any caller omits the field
    // (every script/client written before these fields existed). Boolean wrapper + this compact
    // constructor normalizes that null to false right here, once, so every other accessor/caller still
    // sees a plain non-null boolean.
    public CreatePostRequest {
        if (nsfw == null) {
            nsfw = false;
        }
        if (spoiler == null) {
            spoiler = false;
        }
    }

    // Jakarta Bean Validation discovers any getter-shaped method (isXxx()/getXxx()) via reflection
    // regardless of whether the enclosing class is a record — these close a real, previously unenforced
    // gap where any kind accepted any combination of empty fields.
    @AssertTrue(message = "url is required for kind=link")
    @JsonIgnore
    public boolean isUrlValidForKind() {
        return !"link".equals(kind) || (url != null && !url.isBlank());
    }

    @AssertTrue(message = "mediaId is required for kind=image or kind=video, and must be absent otherwise")
    @JsonIgnore
    public boolean isMediaValidForKind() {
        boolean singleMediaKind = "image".equals(kind) || "video".equals(kind);
        return singleMediaKind ? mediaId != null : mediaId == null;
    }

    @AssertTrue(message = "body is required for kind=text")
    @JsonIgnore
    public boolean isBodyValidForKind() {
        return !"text".equals(kind) || (body != null && !body.isBlank());
    }

    @AssertTrue(message = "a poll needs 2-6 non-blank options of at most 100 characters and lasts 1-7 days; poll fields are only for kind=poll")
    @JsonIgnore
    public boolean isPollValidForKind() {
        if (!"poll".equals(kind)) {
            return (pollOptions == null || pollOptions.isEmpty()) && pollDays == null;
        }
        if (pollOptions == null || pollOptions.size() < 2 || pollOptions.size() > 6) {
            return false;
        }
        if (pollOptions.stream().anyMatch(o -> o == null || o.isBlank() || o.length() > 100)) {
            return false;
        }
        return pollDays == null || (pollDays >= 1 && pollDays <= 7);
    }

    @AssertTrue(message = "crosspostOf is required for kind=crosspost, and must be absent otherwise")
    @JsonIgnore
    public boolean isCrosspostValidForKind() {
        return "crosspost".equals(kind) ? crosspostOf != null : crosspostOf == null;
    }

    // Gallery posts carry their images in mediaIds (ordered), never the singular mediaId — 2 is the floor
    // so "gallery" stays meaningfully distinct from a 1-image "image" post; 20 (enforced by the @Size
    // above, mirrored here so a too-large list fails this same clear message) matches real Reddit's own
    // gallery cap.
    @AssertTrue(message = "mediaIds must contain 2-20 images for kind=gallery, and must be absent otherwise")
    @JsonIgnore
    public boolean isMediaIdsValidForKind() {
        if ("gallery".equals(kind)) {
            return mediaIds != null && mediaIds.size() >= 2 && mediaIds.size() <= 20;
        }
        return mediaIds == null || mediaIds.isEmpty();
    }
}
