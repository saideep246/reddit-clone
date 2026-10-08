package com.redditclone.community;

import com.redditclone.auth.AuthService;
import com.redditclone.common.ModerationAuditWriter;
import com.redditclone.common.OutboxWriter;
import com.redditclone.common.UuidV7Generator;
import com.redditclone.common.exception.BadRequestException;
import com.redditclone.common.exception.ConflictException;
import com.redditclone.common.exception.ForbiddenException;
import com.redditclone.common.exception.NotFoundException;
import com.redditclone.common.ratelimit.RateLimiter;
import com.redditclone.community.dto.PostingApprovalRequestView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

// "Request posting approval" for RESTRICTED communities: the requester asks, a moderator with Manage access approves or denies,
// and approval creates an approved-submitter row. Entirely separate from private-community join requests (which are about
// membership): asking never changes membership, and approving never creates one. Posting rights themselves are still decided
// only by CommunityService.requirePostAccess; this class just feeds the approved-submitter list that check reads.
//
// State rules (one pending request per person per community, enforced by a partial unique index):
//   request  : creates a pending request; repeating it while pending is a harmless no-op that returns the same state.
//   cancel   : only the requester, only while pending; anything else is a 409.
//   approve  : pending -> approved (+ approved submitter). Repeating it after approval is a no-op; after denial or
//              cancellation it is a 409, so a decided request can never be approved by accident.
//   deny     : pending -> denied. Repeating it after denial is a no-op; after approval it is a 409 and the approval is untouched.
//   After a denial or cancellation the person may ask again.
// Every state change is written to the mod log and notified once; no-ops write neither.
@Service
public class PostingApprovalService {

    private final PostingApprovalRequestRepository requests;
    private final CommunityApprovedSubmitterRepository approvedSubmitters;
    private final CommunityModeratorRepository moderators;
    private final CommunityRepository communities;
    private final CommunityService communityService;
    private final AuthService authService;
    private final ModerationAuditWriter audit;
    private final OutboxWriter outbox;
    private final UuidV7Generator ids;
    private final RateLimiter rateLimiter;
    private final int rateCapacity;
    private final int ratePeriodMinutes;

    public PostingApprovalService(PostingApprovalRequestRepository requests, CommunityApprovedSubmitterRepository approvedSubmitters,
                                  CommunityModeratorRepository moderators, CommunityRepository communities,
                                  CommunityService communityService, AuthService authService, ModerationAuditWriter audit,
                                  OutboxWriter outbox, UuidV7Generator ids, RateLimiter rateLimiter,
                                  @Value("${app.rate-limit.posting-request.capacity}") int rateCapacity,
                                  @Value("${app.rate-limit.posting-request.period-minutes}") int ratePeriodMinutes) {
        this.requests = requests;
        this.approvedSubmitters = approvedSubmitters;
        this.moderators = moderators;
        this.communities = communities;
        this.communityService = communityService;
        this.authService = authService;
        this.audit = audit;
        this.outbox = outbox;
        this.ids = ids;
        this.rateLimiter = rateLimiter;
        this.rateCapacity = rateCapacity;
        this.ratePeriodMinutes = ratePeriodMinutes;
    }

    @Transactional
    public void request(UUID userId, UUID communityId) {
        communityService.requireActive(communityId);
        Community community = communities.findById(communityId).orElseThrow(() -> new NotFoundException("no such community"));
        if (!"restricted".equals(community.getType())) {
            throw new BadRequestException("posting approval only applies to restricted communities");
        }
        if (!authService.isActive(userId)) {
            throw new ForbiddenException("account is not active");
        }
        communityService.requireNotBanned(userId, communityId);
        if (moderators.existsByCommunityIdAndUserId(communityId, userId) || approvedSubmitters.existsByCommunityIdAndUserId(communityId, userId)) {
            throw new ConflictException("you can already post in this community");
        }
        if (requests.findPending(communityId, userId).isPresent()) {
            return; // already asked: nothing to add, log or notify
        }
        rateLimiter.checkLimit("posting-request", userId.toString(), rateCapacity, Duration.ofMinutes(ratePeriodMinutes));
        if (requests.insertIfNoPending(ids.nextId(), communityId, userId) == 0) {
            return; // lost a simultaneous race to another request from this same person: they already have one pending
        }
        audit.logAction(communityId, userId, "posting_approval_requested", "user", userId, null);

        // Everyone who can act on it: the owner and moderators holding Manage access (owner rows hold every bit).
        List<Object> events = new ArrayList<>();
        for (CommunityModerator m : moderators.findByCommunityIdOrderByAddedAtAsc(communityId)) {
            if ((m.getPermissions() & CommunityModerator.PERM_MANAGE_ACCESS) != 0 && !m.getUserId().equals(userId)) {
                events.add(Map.of("userId", m.getUserId(), "type", "posting_request",
                        "source", Map.of("actorId", userId, "communityId", communityId)));
            }
        }
        outbox.writeEvents("notification", events);
    }

    @Transactional
    public void cancel(UUID userId, UUID communityId) {
        communityService.requireActive(communityId);
        PostingApprovalRequest r = requests.findPendingForUpdate(communityId, userId)
                .orElseThrow(() -> new ConflictException("you have no pending posting request here"));
        r.resolve(PostingApprovalRequest.CANCELLED, userId);
        audit.logAction(communityId, userId, "posting_approval_cancelled", "user", userId, null);
    }

    @Transactional(readOnly = true)
    public List<PostingApprovalRequestView> list(UUID actorId, UUID communityId) {
        communityService.requirePermission(actorId, communityId, CommunityModerator.PERM_MANAGE_ACCESS);
        communityService.requireActive(communityId);
        List<PostingApprovalRequest> rows = requests.findByCommunityIdAndStatusOrderByCreatedAtAsc(communityId, PostingApprovalRequest.PENDING);
        Map<UUID, String> names = authService.findUsernamesByIds(rows.stream().map(PostingApprovalRequest::getUserId).collect(Collectors.toSet()));
        return rows.stream()
                .map(r -> new PostingApprovalRequestView(r.getUserId(), names.get(r.getUserId()), r.getStatus(), r.getCreatedAt()))
                .toList();
    }

    @Transactional
    public void approve(UUID actorId, UUID communityId, UUID targetUserId) {
        communityService.requirePermission(actorId, communityId, CommunityModerator.PERM_MANAGE_ACCESS);
        communityService.requireActive(communityId);
        PostingApprovalRequest r = requests.findPendingForUpdate(communityId, targetUserId).orElse(null);
        if (r == null) {
            settledOrMissing(communityId, targetUserId, PostingApprovalRequest.APPROVED);
            return;
        }
        if (!authService.isActive(targetUserId)) {
            throw new NotFoundException("no such user");
        }
        // Already an approved submitter by another route (a direct approval): just close the request, never a second row.
        if (!approvedSubmitters.existsByCommunityIdAndUserId(communityId, targetUserId)) {
            approvedSubmitters.save(new CommunityApprovedSubmitter(communityId, targetUserId, actorId));
        }
        r.resolve(PostingApprovalRequest.APPROVED, actorId);
        audit.logAction(communityId, actorId, "posting_approval_approved", "user", targetUserId, null);
        notifyDecision(targetUserId, communityId, "approved");
    }

    @Transactional
    public void deny(UUID actorId, UUID communityId, UUID targetUserId) {
        communityService.requirePermission(actorId, communityId, CommunityModerator.PERM_MANAGE_ACCESS);
        communityService.requireActive(communityId);
        PostingApprovalRequest r = requests.findPendingForUpdate(communityId, targetUserId).orElse(null);
        if (r == null) {
            settledOrMissing(communityId, targetUserId, PostingApprovalRequest.DENIED);
            return;
        }
        r.resolve(PostingApprovalRequest.DENIED, actorId);
        audit.logAction(communityId, actorId, "posting_approval_denied", "user", targetUserId, null);
        notifyDecision(targetUserId, communityId, "denied");
    }

    // Nothing is pending. Repeating the decision that was already made is a no-op; asking for the opposite (or for a request that
    // was cancelled) is refused so a settled request can never be flipped by accident; no request at all is a 404.
    private void settledOrMissing(UUID communityId, UUID targetUserId, String wanted) {
        PostingApprovalRequest latest = requests.findFirstByCommunityIdAndUserIdOrderByCreatedAtDesc(communityId, targetUserId)
                .orElseThrow(() -> new NotFoundException("no posting request from that user"));
        if (!latest.getStatus().equals(wanted)) {
            throw new ConflictException("that request was already " + latest.getStatus());
        }
    }

    private void notifyDecision(UUID requesterId, UUID communityId, String decision) {
        outbox.writeEvent("notification", Map.of("userId", requesterId, "type", "posting_decision",
                "source", Map.of("communityId", communityId, "decision", decision)));
    }
}
