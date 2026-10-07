package com.redditclone.community.dto;

import jakarta.validation.constraints.NotBlank;

// The body of DELETE /r/{name}: the caller must type the community's exact name, so a stray or scripted request cannot
// delete a community by accident. Matching against the real name is CommunityService.deleteCommunity's job.
public record DeleteCommunityRequest(@NotBlank String confirmName) {
}
