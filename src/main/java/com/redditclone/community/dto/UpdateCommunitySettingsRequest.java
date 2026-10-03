package com.redditclone.community.dto;

import jakarta.validation.constraints.Size;

import java.util.UUID;

// PATCH semantics: a null field means "leave unchanged". clearIcon/clearBanner remove an existing image
// (a null iconMediaId alone can't distinguish "unchanged" from "remove"). Boolean wrappers, not primitives,
// so an absent flag deserializes to null (normalized to false below) instead of failing the whole request.
public record UpdateCommunitySettingsRequest(
        @Size(max = 500) String description,
        UUID iconMediaId,
        UUID bannerMediaId,
        Boolean clearIcon,
        Boolean clearBanner) {

    public UpdateCommunitySettingsRequest {
        if (clearIcon == null) {
            clearIcon = false;
        }
        if (clearBanner == null) {
            clearBanner = false;
        }
    }
}
