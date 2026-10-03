package com.redditclone.moderation;

import com.redditclone.auth.AuthService;
import com.redditclone.comment.Comment;
import com.redditclone.comment.CommentService;
import com.redditclone.common.ModerationAuditWriter;
import com.redditclone.common.UuidV7Generator;
import com.redditclone.common.exception.BadRequestException;
import com.redditclone.common.exception.ForbiddenException;
import com.redditclone.common.exception.NotFoundException;
import com.redditclone.community.CommunityModerator;
import com.redditclone.community.CommunityService;
import com.redditclone.post.Post;
import com.redditclone.post.PostService;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ModerationService {

    // Caps the mod-queue/action-log/modmail list endpoints, which previously returned every row ever
    // inserted with no limit at all.
    private static final int LIST_PAGE_SIZE = 100;

    private final CommunityService communityService;
    private final PostService postService;
    private final CommentService commentService;
    private final AuthService authService;
    private final ReportRepository reports;
    private final ModQueueRepository modQueue;
    private final ModerationActionRepository actions;
    private final ModMailMessageRepository modMailMessages;
    private final ModMailMuteRepository modMailMutes;
    private final UuidV7Generator ids;
    private final JdbcTemplate jdbc;
    private final ModerationAuditWriter auditWriter;

    public ModerationService(CommunityService communityService, PostService postService, CommentService commentService,
                              AuthService authService, ReportRepository reports, ModQueueRepository modQueue,
                              ModerationActionRepository actions, ModMailMessageRepository modMailMessages,
                              ModMailMuteRepository modMailMutes, UuidV7Generator ids, JdbcTemplate jdbc,
                              ModerationAuditWriter auditWriter) {
        this.communityService = communityService;
        this.postService = postService;
        this.commentService = commentService;
        this.authService = authService;
        this.reports = reports;
        this.modQueue = modQueue;
        this.actions = actions;
        this.modMailMessages = modMailMessages;
        this.modMailMutes = modMailMutes;
        this.ids = ids;
        this.jdbc = jdbc;
        this.auditWriter = auditWriter;
    }

    @Transactional
    public void removeContent(UUID actorId, UUID communityId, String targetType, UUID targetId, String reason) {
        communityService.requirePermission(actorId, communityId, CommunityModerator.PERM_REMOVE_CONTENT);
        requireTargetInCommunity(targetType, targetId, communityId);
        switch (targetType) {
            case "post" -> postService.markRemoved(targetId);
            case "comment" -> commentService.markRemoved(targetId);
            default -> throw new BadRequestException("targetType must be post or comment");
        }
        auditWriter.logAction(communityId, actorId, "remove_" + targetType, targetType, targetId, reason);
        deleteFromModQueue(communityId, targetType, targetId);
    }

    // Post deletion by its author, or by a moderator/owner holding PERM_REMOVE_CONTENT (the owner's bitmask
    // covers every bit). Same soft removal as removeContent — the row stays, hidden from every listing.
    @Transactional
    public void deletePost(UUID actorId, UUID communityId, UUID postId) {
        Post post = postService.findById(postId);
        if (!post.getCommunityId().equals(communityId)) {
            throw new NotFoundException("post not found in this community");
        }
        boolean isAuthor = actorId.equals(post.getAuthorId());
        if (!isAuthor) {
            communityService.requirePermission(actorId, communityId, CommunityModerator.PERM_REMOVE_CONTENT);
        }
        if (post.isRemoved()) {
            return;
        }
        postService.markRemoved(postId);
        if (!isAuthor) {
            auditWriter.logAction(communityId, actorId, "remove_post", "post", postId, null);
        }
        deleteFromModQueue(communityId, "post", postId);
    }

    // A moderator's permission is only checked against the community named in the URL — without this,
    // a targetId from an unrelated community would still pass that check and get silently acted on.
    private void requireTargetInCommunity(String targetType, UUID targetId, UUID communityId) {
        UUID targetCommunityId = switch (targetType) {
            case "post" -> postService.findById(targetId).getCommunityId();
            case "comment" -> {
                Post post = postService.findById(commentService.findById(targetId).getPostId());
                yield post.getCommunityId();
            }
            default -> throw new BadRequestException("targetType must be post or comment");
        };
        if (!targetCommunityId.equals(communityId)) {
            throw new NotFoundException(targetType + " not found in this community");
        }
    }

    @Transactional
    public void resolveReport(UUID actorId, UUID communityId, UUID reportId, String outcome) {
        communityService.requirePermission(actorId, communityId, CommunityModerator.PERM_REMOVE_CONTENT);
        if (!"resolved".equals(outcome) && !"dismissed".equals(outcome)) {
            throw new BadRequestException("outcome must be resolved or dismissed");
        }
        Report report = reports.findByReportId(reportId).orElseThrow(() -> new NotFoundException("report not found"));
        if (!report.getCommunityId().equals(communityId)) {
            throw new NotFoundException("report not found");
        }
        report.setStatus(outcome);
        report.setResolverId(actorId);
        reports.save(report);
        auditWriter.logAction(communityId, actorId, outcome + "_report", report.getTargetType(), report.getTargetId(), null);
        deleteFromModQueue(communityId, report.getTargetType(), report.getTargetId());
    }

    private static final int PREVIEW_LENGTH = 140;

    public List<ModQueueEntry> listModQueue(UUID actorId, UUID communityId) {
        communityService.requireAnyModPermission(actorId, communityId);
        List<ModQueueEntry> page = modQueue.findByCommunityIdOrderByFirstReportedAtAsc(communityId, Pageable.ofSize(LIST_PAGE_SIZE));
        attachPreviews(page);
        return page;
    }

    // A bare targetId tells a moderator nothing about what was actually reported — batches the page's
    // post-type and comment-type targets into one lookup each (never one query per row), the same
    // never-N+1 convention every listing in this codebase follows.
    private void attachPreviews(List<ModQueueEntry> page) {
        if (page.isEmpty()) {
            return;
        }
        Set<UUID> postIds = new HashSet<>();
        Set<UUID> commentIds = new HashSet<>();
        for (ModQueueEntry entry : page) {
            if ("post".equals(entry.getTargetType())) {
                postIds.add(entry.getTargetId());
            } else if ("comment".equals(entry.getTargetType())) {
                commentIds.add(entry.getTargetId());
            }
        }
        Map<UUID, Post> postsById = postService.findAllByIds(postIds).stream()
                .collect(Collectors.toMap(Post::getId, p -> p));
        Map<UUID, Comment> commentsById = commentService.findAllByIds(commentIds).stream()
                .collect(Collectors.toMap(Comment::getId, c -> c));

        Set<UUID> authorIds = new HashSet<>();
        postsById.values().forEach(p -> authorIds.add(p.getAuthorId()));
        commentsById.values().forEach(c -> authorIds.add(c.getAuthorId()));
        Map<UUID, String> usernames = authService.findUsernamesByIds(authorIds);

        for (ModQueueEntry entry : page) {
            if ("post".equals(entry.getTargetType())) {
                Post p = postsById.get(entry.getTargetId());
                if (p != null) {
                    entry.setPreview(p.getTitle());
                    entry.setAuthorUsername(usernames.get(p.getAuthorId()));
                }
            } else if ("comment".equals(entry.getTargetType())) {
                Comment c = commentsById.get(entry.getTargetId());
                if (c != null) {
                    String body = c.getBody();
                    entry.setPreview(body != null && body.length() > PREVIEW_LENGTH
                            ? body.substring(0, PREVIEW_LENGTH) + "…" : body);
                    entry.setAuthorUsername(usernames.get(c.getAuthorId()));
                }
            }
        }
    }

    // Resolves which individual reports sit behind one mod-queue entry (F8) — the queue itself only ever
    // carries an aggregate count, never report ids, so there was previously no way for a moderator to
    // discover a report id to resolve/dismiss unless they happened to file it themselves (see commit
    // message). Same permission as resolving/dismissing, since viewing these only matters to act on them.
    public List<Report> listReportsForTarget(UUID actorId, UUID communityId, String targetType, UUID targetId) {
        communityService.requirePermission(actorId, communityId, CommunityModerator.PERM_REMOVE_CONTENT);
        List<Report> reportList = reports.findByCommunityIdAndTargetTypeAndTargetIdOrderByCreatedAtDesc(
                communityId, targetType, targetId);
        Set<UUID> reporterIds = reportList.stream().map(Report::getReporterId).collect(Collectors.toSet());
        Map<UUID, String> usernames = authService.findUsernamesByIds(reporterIds);
        reportList.forEach(r -> r.setReporterUsername(usernames.get(r.getReporterId())));
        return reportList;
    }

    public List<ModerationAction> listModerationActions(UUID actorId, UUID communityId) {
        communityService.requireAnyModPermission(actorId, communityId);
        return actions.findByCommunityIdOrderByCreatedAtDesc(communityId, Pageable.ofSize(LIST_PAGE_SIZE));
    }

    @Transactional
    public void muteUser(UUID actorId, UUID communityId, UUID targetUserId, String reason, Instant expiresAt) {
        communityService.requirePermission(actorId, communityId, CommunityModerator.PERM_MUTE_USERS);
        ModMailMute mute = new ModMailMute(communityId, targetUserId, actorId, reason, expiresAt);
        // Re-muting the same user re-issues this row via JPA merge — preserve the original created_at
        // instead of letting merge overwrite it with the new instance's Instant.now() default.
        modMailMutes.findById(new ModMailMuteId(communityId, targetUserId))
                .ifPresent(existing -> mute.setCreatedAt(existing.getCreatedAt()));
        modMailMutes.save(mute);
        auditWriter.logAction(communityId, actorId, "mute", "user", targetUserId, reason);
    }

    @Transactional
    public void unmuteUser(UUID actorId, UUID communityId, UUID targetUserId, String reason) {
        communityService.requirePermission(actorId, communityId, CommunityModerator.PERM_MUTE_USERS);
        modMailMutes.deleteById(new ModMailMuteId(communityId, targetUserId));
        auditWriter.logAction(communityId, actorId, "unmute", "user", targetUserId, reason);
    }

    @Transactional
    public ModMailMessage sendModMail(UUID senderId, UUID communityId, String body) {
        modMailMutes.findById(new ModMailMuteId(communityId, senderId)).ifPresent(mute -> {
            if (mute.getExpiresAt() == null || mute.getExpiresAt().isAfter(Instant.now())) {
                throw new ForbiddenException("muted from sending modmail in this community");
            }
        });
        ModMailMessage m = new ModMailMessage();
        m.setId(ids.nextId());
        m.setCommunityId(communityId);
        m.setSenderId(senderId);
        m.setBody(body);
        return modMailMessages.save(m);
    }

    public List<ModMailMessage> listModMail(UUID actorId, UUID communityId) {
        communityService.requireAnyModPermission(actorId, communityId);
        return modMailMessages.findByCommunityIdOrderByCreatedAtDesc(communityId, Pageable.ofSize(LIST_PAGE_SIZE));
    }

    private void deleteFromModQueue(UUID communityId, String targetType, UUID targetId) {
        jdbc.update("DELETE FROM mod_queue WHERE community_id = ? AND target_type = ? AND target_id = ?",
                communityId, targetType, targetId);
    }
}
