package com.redditclone.moderation;

import com.redditclone.comment.CommentService;
import com.redditclone.common.ModerationAuditWriter;
import com.redditclone.common.UuidV7Generator;
import com.redditclone.common.exception.BadRequestException;
import com.redditclone.community.CommunityService;
import com.redditclone.post.PostService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

// Callable by any authenticated user (not moderator-gated) — filing a report is a user action, distinct
// from ModerationService's moderator-only actions.
@Service
public class ReportService {

    private final ReportRepository reports;
    private final PostService postService;
    private final CommentService commentService;
    private final UuidV7Generator ids;
    private final ModerationAuditWriter auditWriter;
    private final CommunityService communityService;

    public ReportService(ReportRepository reports, PostService postService, CommentService commentService,
                          UuidV7Generator ids, ModerationAuditWriter auditWriter,
                          CommunityService communityService) {
        this.reports = reports;
        this.postService = postService;
        this.commentService = commentService;
        this.ids = ids;
        this.auditWriter = auditWriter;
        this.communityService = communityService;
    }

    // communityId is resolved from the target here, never trusted from the client — a report's
    // community_id has to be the target's real community regardless of what a caller claims.
    @Transactional
    public Report fileReport(UUID reporterId, String targetType, UUID targetId, String reason) {
        UUID communityId = switch (targetType) {
            case "post" -> postService.findById(targetId).getCommunityId();
            case "comment" -> postService.findById(commentService.findById(targetId).getPostId()).getCommunityId();
            default -> throw new BadRequestException("targetType must be post or comment");
        };
        communityService.requireActive(communityId); // reports feed the community's mod queue: a deleted community takes none
        Report r = new Report();
        r.setId(ids.nextId());
        r.setTargetType(targetType);
        r.setTargetId(targetId);
        r.setCommunityId(communityId);
        r.setReporterId(reporterId);
        r.setReason(reason);
        Report saved = reports.save(r);
        auditWriter.upsertModQueue(communityId, targetType, targetId);
        return saved;
    }
}
