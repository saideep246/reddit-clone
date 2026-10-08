package com.redditclone.community;

import com.redditclone.community.dto.PostingApprovalRequestView;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

// Posting approval for restricted communities. The requester's own routes sit beside the community's other member routes
// (/r/{name}/...); the moderators' routes sit under /r/{name}/mod/ like join requests and approved submitters. All authenticated
// by the default rule; the service enforces who may do what.
@RestController
public class PostingApprovalController {

    private final PostingApprovalService approvals;
    private final CommunityService communityService;

    public PostingApprovalController(PostingApprovalService approvals, CommunityService communityService) {
        this.approvals = approvals;
        this.communityService = communityService;
    }

    @PostMapping("/r/{name}/posting-requests")
    public void request(@AuthenticationPrincipal UUID userId, @PathVariable String name) {
        approvals.request(userId, communityService.findByName(name).getId());
    }

    @DeleteMapping("/r/{name}/posting-requests")
    public void cancel(@AuthenticationPrincipal UUID userId, @PathVariable String name) {
        approvals.cancel(userId, communityService.findByName(name).getId());
    }

    @GetMapping("/r/{name}/mod/posting-requests")
    public List<PostingApprovalRequestView> list(@AuthenticationPrincipal UUID userId, @PathVariable String name) {
        return approvals.list(userId, communityService.findByName(name).getId());
    }

    @PostMapping("/r/{name}/mod/posting-requests/{targetUserId}/approve")
    public void approve(@AuthenticationPrincipal UUID userId, @PathVariable String name, @PathVariable UUID targetUserId) {
        approvals.approve(userId, communityService.findByName(name).getId(), targetUserId);
    }

    @PostMapping("/r/{name}/mod/posting-requests/{targetUserId}/deny")
    public void deny(@AuthenticationPrincipal UUID userId, @PathVariable String name, @PathVariable UUID targetUserId) {
        approvals.deny(userId, communityService.findByName(name).getId(), targetUserId);
    }
}
