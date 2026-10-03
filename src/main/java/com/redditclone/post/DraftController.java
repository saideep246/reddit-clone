package com.redditclone.post;

import com.redditclone.community.CommunityService;
import com.redditclone.post.dto.DraftRequest;
import com.redditclone.post.dto.DraftView;
import com.redditclone.post.dto.ScheduleRequest;
import com.redditclone.post.dto.ScheduledPostView;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

// Drafts and scheduled posts: both are private to their author and authenticated-only (no SecurityConfig entry).
@RestController
public class DraftController {

    private final DraftService drafts;
    private final ScheduledPostService scheduled;
    private final CommunityService communityService;

    public DraftController(DraftService drafts, ScheduledPostService scheduled, CommunityService communityService) {
        this.drafts = drafts;
        this.scheduled = scheduled;
        this.communityService = communityService;
    }

    @GetMapping("/api/drafts")
    public List<DraftView> listDrafts(@AuthenticationPrincipal UUID userId) {
        return drafts.list(userId);
    }

    @PostMapping("/api/drafts")
    public DraftView createDraft(@AuthenticationPrincipal UUID userId, @Valid @RequestBody DraftRequest req) {
        return drafts.create(userId, req.communityName(), req.payload());
    }

    @PutMapping("/api/drafts/{id}")
    public DraftView updateDraft(@AuthenticationPrincipal UUID userId, @PathVariable UUID id, @Valid @RequestBody DraftRequest req) {
        return drafts.update(userId, id, req.communityName(), req.payload());
    }

    @DeleteMapping("/api/drafts/{id}")
    public void deleteDraft(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        drafts.delete(userId, id);
    }

    @PostMapping("/r/{communityName}/schedule")
    public ScheduledPostView schedule(@AuthenticationPrincipal UUID userId, @PathVariable String communityName,
                                       @Valid @RequestBody ScheduleRequest req) {
        UUID communityId = communityService.findByName(communityName).getId();
        return scheduled.schedule(userId, communityId, req.post(), req.publishAt());
    }

    @GetMapping("/api/scheduled")
    public List<ScheduledPostView> listScheduled(@AuthenticationPrincipal UUID userId) {
        return scheduled.list(userId);
    }

    @DeleteMapping("/api/scheduled/{id}")
    public void cancelScheduled(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        scheduled.cancel(userId, id);
    }
}
