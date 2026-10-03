package com.redditclone.block;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

// Authenticated-only (no SecurityConfig entry needed), same as the follow endpoints.
@RestController
public class BlockController {

    private final BlockService blocks;

    public BlockController(BlockService blocks) {
        this.blocks = blocks;
    }

    @PostMapping("/user/{username}/block")
    public Map<String, Boolean> block(@AuthenticationPrincipal UUID userId, @PathVariable String username) {
        return Map.of("changed", blocks.block(userId, username));
    }

    @DeleteMapping("/user/{username}/block")
    public Map<String, Boolean> unblock(@AuthenticationPrincipal UUID userId, @PathVariable String username) {
        return Map.of("changed", blocks.unblock(userId, username));
    }

    @GetMapping("/user/{username}/block")
    public Map<String, Boolean> status(@AuthenticationPrincipal UUID userId, @PathVariable String username) {
        return Map.of("isBlocked", blocks.isBlocking(userId, username));
    }

    @GetMapping("/api/blocked")
    public List<BlockedUser> blocked(@AuthenticationPrincipal UUID userId) {
        return blocks.list(userId);
    }
}
