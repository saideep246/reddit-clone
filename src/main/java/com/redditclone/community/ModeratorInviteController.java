package com.redditclone.community;

import com.redditclone.community.dto.ModeratorInviteView;
import com.redditclone.community.dto.MyModeratorInviteView;
import com.redditclone.community.dto.SendModeratorInviteRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

// Moderator-side routes live under /r/{name}/mod/ like every other moderation action; the recipient's own routes are
// /api/moderator-invites (their data, like /api/notifications). All are authenticated by the default anyRequest rule.
@RestController
public class ModeratorInviteController {

    private final ModeratorInviteService invites;
    private final CommunityService communityService;

    public ModeratorInviteController(ModeratorInviteService invites, CommunityService communityService) {
        this.invites = invites;
        this.communityService = communityService;
    }

    @PostMapping("/r/{name}/mod/moderator-invites")
    public ModeratorInviteView send(@AuthenticationPrincipal UUID userId, @PathVariable String name,
                                    @Valid @RequestBody SendModeratorInviteRequest req) {
        UUID communityId = communityService.findByName(name).getId();
        ModeratorInvite invite = invites.send(userId, communityId, req.username().trim(), req.permissions());
        return invites.listForCommunity(userId, communityId).stream()
                .filter(v -> v.id().equals(invite.getId())).findFirst().orElseThrow();
    }

    @GetMapping("/r/{name}/mod/moderator-invites")
    public List<ModeratorInviteView> list(@AuthenticationPrincipal UUID userId, @PathVariable String name) {
        return invites.listForCommunity(userId, communityService.findByName(name).getId());
    }

    @DeleteMapping("/r/{name}/mod/moderator-invites/{inviteId}")
    public void cancel(@AuthenticationPrincipal UUID userId, @PathVariable String name, @PathVariable UUID inviteId) {
        invites.cancel(userId, communityService.findByName(name).getId(), inviteId);
    }

    @GetMapping("/api/moderator-invites")
    public List<MyModeratorInviteView> mine(@AuthenticationPrincipal UUID userId) {
        return invites.listMine(userId);
    }

    @PostMapping("/api/moderator-invites/{inviteId}/accept")
    public void accept(@AuthenticationPrincipal UUID userId, @PathVariable UUID inviteId) {
        invites.accept(userId, inviteId);
    }

    @PostMapping("/api/moderator-invites/{inviteId}/decline")
    public void decline(@AuthenticationPrincipal UUID userId, @PathVariable UUID inviteId) {
        invites.decline(userId, inviteId);
    }
}
