package com.redditclone.post;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

// Backs the short share links (/p/<postId> in the frontend): maps a bare post id to its community so the client
// can redirect to the canonical thread URL. Public (listed in SecurityConfig), like the thread itself.
@RestController
public class PermalinkController {

    private final PostService postService;

    public PermalinkController(PostService postService) {
        this.postService = postService;
    }

    @GetMapping("/api/p/{postId}")
    public Map<String, Object> resolve(@AuthenticationPrincipal UUID viewerId, @PathVariable UUID postId) {
        return Map.of("postId", postId, "communityName", postService.resolveCommunityName(postId, viewerId));
    }
}
