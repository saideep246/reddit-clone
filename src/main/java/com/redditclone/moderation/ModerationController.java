package com.redditclone.moderation;

import com.redditclone.community.AutomodRule;
import com.redditclone.community.Ban;
import com.redditclone.community.CommunityJoinRequest;
import com.redditclone.community.CommunityModerator;
import com.redditclone.community.CommunityService;
import com.redditclone.community.Flair;
import com.redditclone.community.dto.ApprovedSubmitterRequest;
import com.redditclone.community.dto.SetCommunityTypeRequest;
import com.redditclone.community.dto.SetFlairRequest;
import com.redditclone.community.dto.SetRulesRequest;
import com.redditclone.community.dto.UpdateCommunitySettingsRequest;
import com.redditclone.moderation.dto.AddModeratorRequest;
import com.redditclone.moderation.dto.AutomodRuleRequest;
import com.redditclone.moderation.dto.BanRequest;
import com.redditclone.moderation.dto.FlairRequest;
import com.redditclone.moderation.dto.ModMailRequest;
import com.redditclone.moderation.dto.ModNoteRequest;
import com.redditclone.moderation.dto.ModNoteView;
import com.redditclone.moderation.dto.MuteRequest;
import com.redditclone.moderation.dto.RemoveRequest;
import com.redditclone.moderation.dto.ReportRequest;
import com.redditclone.post.PostService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class ModerationController {

    private final ModerationService moderation;
    private final ReportService reportService;
    private final CommunityService communityService;
    private final PostService postService;
    private final ObjectMapper json;

    public ModerationController(ModerationService moderation, ReportService reportService,
                                 CommunityService communityService, PostService postService, ObjectMapper json) {
        this.moderation = moderation;
        this.reportService = reportService;
        this.communityService = communityService;
        this.postService = postService;
        this.json = json;
    }

    @PostMapping("/api/report")
    public Report report(@AuthenticationPrincipal UUID userId, @Valid @RequestBody ReportRequest req) {
        return reportService.fileReport(userId, req.targetType(), req.targetId(), req.reason());
    }

    @GetMapping("/r/{name}/mod/queue")
    public List<ModQueueEntry> queue(@AuthenticationPrincipal UUID userId, @PathVariable String name) {
        return moderation.listModQueue(userId, communityId(name));
    }

    // Optional filters: action (e.g. remove_post, ban), actorId (the acting moderator), targetType, targetId;
    // `before` (ISO instant, the createdAt of the last row seen) pages backwards through older entries.
    @GetMapping("/r/{name}/mod/actions")
    public List<ModerationAction> actions(@AuthenticationPrincipal UUID userId, @PathVariable String name,
                                           @RequestParam(required = false) String action,
                                           @RequestParam(required = false) UUID actorId,
                                           @RequestParam(required = false) String targetType,
                                           @RequestParam(required = false) UUID targetId,
                                           @RequestParam(required = false) Instant before) {
        return moderation.listModerationActions(userId, communityId(name), action, actorId, targetType, targetId, before);
    }

    @GetMapping("/r/{name}/mod/notes")
    public List<ModNoteView> notes(@AuthenticationPrincipal UUID userId, @PathVariable String name,
                                    @RequestParam("userId") UUID subjectId) {
        return moderation.listNotes(userId, communityId(name), subjectId);
    }

    @PostMapping("/r/{name}/mod/notes")
    public ModNoteView addNote(@AuthenticationPrincipal UUID userId, @PathVariable String name,
                                @Valid @RequestBody ModNoteRequest req) {
        return moderation.addNote(userId, communityId(name), req.userId(), req.note());
    }

    @DeleteMapping("/r/{name}/mod/notes/{noteId}")
    public void deleteNote(@AuthenticationPrincipal UUID userId, @PathVariable String name, @PathVariable UUID noteId) {
        moderation.deleteNote(userId, communityId(name), noteId);
    }

    @PostMapping("/r/{name}/mod/reports/{reportId}/resolve")
    public void resolveReport(@AuthenticationPrincipal UUID userId, @PathVariable String name, @PathVariable UUID reportId) {
        moderation.resolveReport(userId, communityId(name), reportId, "resolved");
    }

    @PostMapping("/r/{name}/mod/reports/{reportId}/dismiss")
    public void dismissReport(@AuthenticationPrincipal UUID userId, @PathVariable String name, @PathVariable UUID reportId) {
        moderation.resolveReport(userId, communityId(name), reportId, "dismissed");
    }

    // The individual reports behind one mod/queue entry (F8) — the queue itself only ever returns an
    // aggregate count, never report ids; this is what a dashboard calls when a moderator expands one entry.
    @GetMapping("/r/{name}/mod/reports")
    public List<Report> reportsForTarget(@AuthenticationPrincipal UUID userId, @PathVariable String name,
                                          @RequestParam String targetType, @RequestParam UUID targetId) {
        return moderation.listReportsForTarget(userId, communityId(name), targetType, targetId);
    }

    @PostMapping("/r/{name}/mod/remove/{targetType}/{targetId}")
    public void remove(@AuthenticationPrincipal UUID userId, @PathVariable String name,
                        @PathVariable String targetType, @PathVariable UUID targetId,
                        @RequestBody(required = false) RemoveRequest req) {
        moderation.removeContent(userId, communityId(name), targetType, targetId, req == null ? null : req.reason());
    }

    // F8's Bans tab (first-ever consumer — until now a moderator could issue/lift a ban but never see the
    // current list at all).
    @GetMapping("/r/{name}/mod/bans")
    public List<Ban> bans(@AuthenticationPrincipal UUID userId, @PathVariable String name) {
        return communityService.listBans(userId, communityId(name));
    }

    @PostMapping("/r/{name}/mod/ban")
    public void ban(@AuthenticationPrincipal UUID userId, @PathVariable String name, @Valid @RequestBody BanRequest req) {
        communityService.issueBan(userId, communityId(name), req.userId(), req.reason(), req.expiresAt());
    }

    @DeleteMapping("/r/{name}/mod/ban/{targetUserId}")
    public void unban(@AuthenticationPrincipal UUID userId, @PathVariable String name, @PathVariable UUID targetUserId) {
        communityService.liftBan(userId, communityId(name), targetUserId, null);
    }

    @PostMapping("/r/{name}/mod/mute")
    public void mute(@AuthenticationPrincipal UUID userId, @PathVariable String name, @Valid @RequestBody MuteRequest req) {
        moderation.muteUser(userId, communityId(name), req.userId(), req.reason(), req.expiresAt());
    }

    @DeleteMapping("/r/{name}/mod/mute/{targetUserId}")
    public void unmute(@AuthenticationPrincipal UUID userId, @PathVariable String name, @PathVariable UUID targetUserId) {
        moderation.unmuteUser(userId, communityId(name), targetUserId, null);
    }

    @PostMapping("/r/{name}/mod/automod-rules")
    public AutomodRule addAutomodRule(@AuthenticationPrincipal UUID userId, @PathVariable String name,
                                       @Valid @RequestBody AutomodRuleRequest req) {
        String config = json.writeValueAsString(req.config());
        return communityService.addAutomodRule(userId, communityId(name), req.ruleType(), config, req.action());
    }

    @GetMapping("/r/{name}/mod/automod-rules")
    public List<AutomodRule> listAutomodRules(@AuthenticationPrincipal UUID userId, @PathVariable String name) {
        communityService.requireAnyModPermission(userId, communityId(name));
        return communityService.listAutomodRules(communityId(name));
    }

    @DeleteMapping("/r/{name}/mod/automod-rules/{ruleId}")
    public void removeAutomodRule(@AuthenticationPrincipal UUID userId, @PathVariable String name, @PathVariable UUID ruleId) {
        communityService.removeAutomodRule(userId, communityId(name), ruleId);
    }

    @PostMapping("/r/{name}/mod/moderators")
    public void addModerator(@AuthenticationPrincipal UUID userId, @PathVariable String name,
                              @Valid @RequestBody AddModeratorRequest req) {
        communityService.addModerator(userId, communityId(name), req.userId(), req.permissions());
    }

    @DeleteMapping("/r/{name}/mod/moderators/{targetUserId}")
    public void removeModerator(@AuthenticationPrincipal UUID userId, @PathVariable String name, @PathVariable UUID targetUserId) {
        communityService.removeModerator(userId, communityId(name), targetUserId);
    }

    @PostMapping("/r/{name}/mod/mail")
    public ModMailMessage sendModMail(@AuthenticationPrincipal UUID userId, @PathVariable String name,
                                       @Valid @RequestBody ModMailRequest req) {
        return moderation.sendModMail(userId, communityId(name), req.body());
    }

    @GetMapping("/r/{name}/mod/mail")
    public List<ModMailMessage> listModMail(@AuthenticationPrincipal UUID userId, @PathVariable String name) {
        return moderation.listModMail(userId, communityId(name));
    }

    @PostMapping("/r/{name}/mod/flairs")
    public Flair addFlair(@AuthenticationPrincipal UUID userId, @PathVariable String name,
                           @Valid @RequestBody FlairRequest req) {
        return communityService.addFlair(userId, communityId(name), req.text(), req.color(), req.type());
    }

    @DeleteMapping("/r/{name}/mod/flairs/{flairId}")
    public void removeFlair(@AuthenticationPrincipal UUID userId, @PathVariable String name, @PathVariable UUID flairId) {
        communityService.removeFlair(userId, communityId(name), flairId);
    }

    // Whole-list replace — communityService.setRules checks PERM_MANAGE_RULES internally, same convention
    // addFlair/removeFlair already use (unlike setPostFlair below, which checks in the controller).
    @PutMapping("/r/{name}/mod/rules")
    public void setRules(@AuthenticationPrincipal UUID userId, @PathVariable String name,
                          @Valid @RequestBody SetRulesRequest req) {
        communityService.setRules(userId, communityId(name), req.rules());
    }

    // communityService.updateSettings checks PERM_MANAGE_SETTINGS internally, same convention as setRules. Echoes the
    // saved description (the Mod Tools Settings tab and seed_and_verify_community_settings.sh read it back).
    @PatchMapping("/r/{name}/mod/settings")
    public Map<String, String> updateSettings(@AuthenticationPrincipal UUID userId, @PathVariable String name,
                                               @Valid @RequestBody UpdateCommunitySettingsRequest req) {
        String description = communityService.updateSettings(userId, communityId(name), req.description(),
                req.iconMediaId(), req.bannerMediaId(), req.clearIcon(), req.clearBanner());
        return Collections.singletonMap("description", description);
    }

    @PatchMapping("/r/{name}/mod/users/{targetUserId}/flair")
    public void setUserFlair(@AuthenticationPrincipal UUID userId, @PathVariable String name,
                              @PathVariable UUID targetUserId, @RequestBody SetFlairRequest req) {
        communityService.setUserFlair(userId, communityId(name), targetUserId, req.flairId());
    }

    @PatchMapping("/r/{name}/mod/posts/{postId}/flair")
    public void setPostFlair(@AuthenticationPrincipal UUID userId, @PathVariable String name,
                              @PathVariable UUID postId, @RequestBody SetFlairRequest req) {
        UUID communityId = communityId(name);
        communityService.requirePermission(userId, communityId, CommunityModerator.PERM_MANAGE_FLAIRS);
        postService.setFlair(postId, communityId, req.flairId());
    }

    @PostMapping("/r/{name}/mod/comments/{commentId}/sticky")
    public void stickyComment(@AuthenticationPrincipal UUID userId, @PathVariable String name, @PathVariable UUID commentId) {
        moderation.stickyComment(userId, communityId(name), commentId, true);
    }

    @DeleteMapping("/r/{name}/mod/comments/{commentId}/sticky")
    public void unstickyComment(@AuthenticationPrincipal UUID userId, @PathVariable String name, @PathVariable UUID commentId) {
        moderation.stickyComment(userId, communityId(name), commentId, false);
    }

    @PostMapping("/r/{name}/mod/comments/{commentId}/distinguish")
    public void distinguishComment(@AuthenticationPrincipal UUID userId, @PathVariable String name, @PathVariable UUID commentId) {
        moderation.distinguishComment(userId, communityId(name), commentId, true);
    }

    @DeleteMapping("/r/{name}/mod/comments/{commentId}/distinguish")
    public void undistinguishComment(@AuthenticationPrincipal UUID userId, @PathVariable String name, @PathVariable UUID commentId) {
        moderation.distinguishComment(userId, communityId(name), commentId, false);
    }

    @PostMapping("/r/{name}/mod/posts/{postId}/pin")
    public void pinPost(@AuthenticationPrincipal UUID userId, @PathVariable String name, @PathVariable UUID postId) {
        UUID communityId = communityId(name);
        communityService.requirePermission(userId, communityId, CommunityModerator.PERM_MANAGE_POSTS);
        postService.setPinned(postId, communityId, true);
    }

    @DeleteMapping("/r/{name}/mod/posts/{postId}/pin")
    public void unpinPost(@AuthenticationPrincipal UUID userId, @PathVariable String name, @PathVariable UUID postId) {
        UUID communityId = communityId(name);
        communityService.requirePermission(userId, communityId, CommunityModerator.PERM_MANAGE_POSTS);
        postService.setPinned(postId, communityId, false);
    }

    @PostMapping("/r/{name}/mod/posts/{postId}/lock")
    public void lockPost(@AuthenticationPrincipal UUID userId, @PathVariable String name, @PathVariable UUID postId) {
        UUID communityId = communityId(name);
        communityService.requirePermission(userId, communityId, CommunityModerator.PERM_MANAGE_POSTS);
        postService.setLocked(postId, communityId, true);
    }

    @DeleteMapping("/r/{name}/mod/posts/{postId}/lock")
    public void unlockPost(@AuthenticationPrincipal UUID userId, @PathVariable String name, @PathVariable UUID postId) {
        UUID communityId = communityId(name);
        communityService.requirePermission(userId, communityId, CommunityModerator.PERM_MANAGE_POSTS);
        postService.setLocked(postId, communityId, false);
    }

    // Owner-only — communityService.setType checks OWNER_PERMISSIONS internally (every bit set, which in
    // practice means only the literal creator), not PERM_MANAGE_ACCESS below.
    @PatchMapping("/r/{name}/mod/type")
    public void setType(@AuthenticationPrincipal UUID userId, @PathVariable String name, @Valid @RequestBody SetCommunityTypeRequest req) {
        communityService.setType(userId, communityId(name), req.type());
    }

    @GetMapping("/r/{name}/mod/join-requests")
    public List<CommunityJoinRequest> listJoinRequests(@AuthenticationPrincipal UUID userId, @PathVariable String name) {
        return communityService.listJoinRequests(userId, communityId(name));
    }

    @PostMapping("/r/{name}/mod/join-requests/{targetUserId}/approve")
    public void approveJoinRequest(@AuthenticationPrincipal UUID userId, @PathVariable String name, @PathVariable UUID targetUserId) {
        communityService.approveJoinRequest(userId, communityId(name), targetUserId);
    }

    @PostMapping("/r/{name}/mod/join-requests/{targetUserId}/deny")
    public void denyJoinRequest(@AuthenticationPrincipal UUID userId, @PathVariable String name, @PathVariable UUID targetUserId) {
        communityService.denyJoinRequest(userId, communityId(name), targetUserId);
    }

    @PostMapping("/r/{name}/mod/approved-submitters")
    public void addApprovedSubmitter(@AuthenticationPrincipal UUID userId, @PathVariable String name,
                                      @Valid @RequestBody ApprovedSubmitterRequest req) {
        communityService.addApprovedSubmitter(userId, communityId(name), req.userId());
    }

    @DeleteMapping("/r/{name}/mod/approved-submitters/{targetUserId}")
    public void removeApprovedSubmitter(@AuthenticationPrincipal UUID userId, @PathVariable String name, @PathVariable UUID targetUserId) {
        communityService.removeApprovedSubmitter(userId, communityId(name), targetUserId);
    }

    private UUID communityId(String name) {
        return communityService.findByName(name).getId();
    }
}
