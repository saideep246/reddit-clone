package com.redditclone.community;

import com.redditclone.auth.AuthService;
import com.redditclone.common.ModerationAuditWriter;
import com.redditclone.common.OutboxWriter;
import com.redditclone.common.UuidV7Generator;
import com.redditclone.common.exception.BadRequestException;
import com.redditclone.common.exception.ConflictException;
import com.redditclone.common.exception.ForbiddenException;
import com.redditclone.common.exception.NotFoundException;
import com.redditclone.community.dto.ModeratorInviteView;
import com.redditclone.community.dto.MyModeratorInviteView;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Moderator invitations: send -> pending -> accept | decline | cancel | (lazy) expire. A pending invitation is a persisted
// delegation: acceptance grants exactly invite.permissions, read from the row, never from the request. The sender's rights
// are checked when the invitation is SENT (same MANAGE_MODERATORS + "not beyond your own bits" rules as addModerator);
// they are deliberately NOT re-checked at acceptance — the invitation is an already-authorized delegation, and any
// MANAGE_MODERATORS moderator (or the owner) can cancel it if the community no longer wants it. Everything at acceptance
// that concerns the community or the invitee (deleted community, inactive/banned invitee, already a moderator) IS checked.
@Service
public class ModeratorInviteService {

    public static final Duration INVITE_TTL = Duration.ofDays(7);
    // Every defined permission bit; anything outside is rejected so OWNER_PERMISSIONS (all bits) can't be minted via an invite.
    static final int ALL_PERMISSION_BITS = (1 << 10) - 1;

    private final ModeratorInviteRepository invites;
    private final CommunityModeratorRepository moderators;
    private final CommunityService communityService;
    private final CommunityRepository communities;
    private final AuthService authService;
    private final ModerationAuditWriter audit;
    private final UuidV7Generator ids;
    private final OutboxWriter outbox;

    public ModeratorInviteService(ModeratorInviteRepository invites, CommunityModeratorRepository moderators,
                                  CommunityService communityService, CommunityRepository communities,
                                  AuthService authService, ModerationAuditWriter audit, UuidV7Generator ids,
                                  OutboxWriter outbox) {
        this.invites = invites;
        this.moderators = moderators;
        this.communityService = communityService;
        this.communities = communities;
        this.authService = authService;
        this.audit = audit;
        this.ids = ids;
        this.outbox = outbox;
    }

    @Transactional
    public ModeratorInvite send(UUID actorId, UUID communityId, String inviteeUsername, int permissions) {
        communityService.requirePermission(actorId, communityId, CommunityModerator.PERM_MANAGE_MODERATORS);
        communityService.requireActive(communityId);
        Community community = communities.findById(communityId).orElseThrow(() -> new NotFoundException("no such community"));

        if (permissions < 0 || (permissions & ~ALL_PERMISSION_BITS) != 0) {
            throw new BadRequestException("invalid permissions");
        }
        int grantorPermissions = moderators.findByCommunityIdAndUserId(communityId, actorId)
                .map(CommunityModerator::getPermissions).orElse(0);
        if ((permissions & ~grantorPermissions) != 0) {
            throw new ForbiddenException("cannot grant permissions beyond your own");
        }

        UUID inviteeId = authService.findUserIdsByUsernames(Set.of(inviteeUsername)).values().stream().findFirst()
                .filter(authService::isActive)
                .orElseThrow(() -> new NotFoundException("no such user"));
        if (inviteeId.equals(community.getCreatorId())) {
            throw new BadRequestException("the community owner cannot be invited");
        }
        if (moderators.existsByCommunityIdAndUserId(communityId, inviteeId)) {
            throw new ConflictException("that user is already a moderator");
        }

        Instant now = Instant.now();
        invites.expireLapsed(communityId, inviteeId, now);
        if (invites.findByCommunityIdAndInviteeIdAndStatus(communityId, inviteeId, ModeratorInvite.PENDING).isPresent()) {
            throw new ConflictException("that user already has a pending invitation");
        }
        ModeratorInvite invite = new ModeratorInvite(ids.nextId(), communityId, inviteeId, actorId, permissions, now.plus(INVITE_TTL));
        try {
            invites.saveAndFlush(invite); // the partial unique index is the real guard against a concurrent duplicate send
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("that user already has a pending invitation");
        }
        audit.logAction(communityId, actorId, "invite_moderator", "user", inviteeId, null);
        // One 'notification' outbox event, written in the same transaction as the invitation: it exists if and only if the
        // invitation does. notify.NotificationOutboxWorker turns it into an inbox row (unless the invitee muted mod_invite)
        // and the existing opt-in email. Muting only suppresses this row; the invitation itself stays reachable through
        // GET /api/moderator-invites. inviteId lets a client tell which invitation a row refers to; the invitee's
        // actions always go through the server-held invitation, never through data in this payload.
        outbox.writeEvent("notification", Map.of(
                "userId", inviteeId,
                "type", "mod_invite",
                "source", Map.of("actorId", actorId, "communityId", communityId, "inviteId", invite.getId())));
        return invite;
    }

    // A moderator may only cancel an invitation they could have sent: one whose bits fit inside their own.
    @Transactional
    public void cancel(UUID actorId, UUID communityId, UUID inviteId) {
        communityService.requirePermission(actorId, communityId, CommunityModerator.PERM_MANAGE_MODERATORS);
        ModeratorInvite invite = invites.findByIdForUpdate(inviteId)
                .filter(i -> i.getCommunityId().equals(communityId))
                .orElseThrow(() -> new NotFoundException("no such invitation"));
        if (ModeratorInvite.CANCELLED.equals(invite.getStatus())) {
            return; // idempotent
        }
        if (!invite.isPending()) {
            throw new ConflictException("invitation is already " + invite.getStatus());
        }
        int actorPermissions = moderators.findByCommunityIdAndUserId(communityId, actorId)
                .map(CommunityModerator::getPermissions).orElse(0);
        if ((invite.getPermissions() & ~actorPermissions) != 0) {
            throw new ForbiddenException("cannot cancel an invitation that grants permissions beyond your own");
        }
        invite.resolve(ModeratorInvite.CANCELLED);
        audit.logAction(communityId, actorId, "cancel_moderator_invite", "user", invite.getInviteeId(), null);
    }

    // noRollbackFor: a lapsed invitation is flipped to 'expired' and that write must survive the 409 we throw for it.
    @Transactional(noRollbackFor = ConflictException.class)
    public void accept(UUID userId, UUID inviteId) {
        ModeratorInvite invite = lockOwn(userId, inviteId);
        requireStillPending(invite);

        communityService.requireActive(invite.getCommunityId());
        if (!authService.isActive(userId)) {
            throw new ForbiddenException("account is not active");
        }
        communityService.requireNotBanned(userId, invite.getCommunityId());

        // Already a moderator by another route (e.g. a direct add) — nothing to grant; just close the invitation and
        // never touch the existing row's permissions.
        if (!moderators.existsByCommunityIdAndUserId(invite.getCommunityId(), userId)) {
            communityService.addMemberDirectly(userId, invite.getCommunityId()); // the existing membership path; idempotent
            moderators.save(new CommunityModerator(invite.getCommunityId(), userId, invite.getPermissions(), invite.getInviterId()));
        }
        invite.resolve(ModeratorInvite.ACCEPTED);
        audit.logAction(invite.getCommunityId(), userId, "accept_moderator_invite", "user", userId, null);
    }

    @Transactional(noRollbackFor = ConflictException.class)
    public void decline(UUID userId, UUID inviteId) {
        ModeratorInvite invite = lockOwn(userId, inviteId);
        if (ModeratorInvite.DECLINED.equals(invite.getStatus())) {
            return; // idempotent
        }
        requireStillPending(invite);
        invite.resolve(ModeratorInvite.DECLINED);
        audit.logAction(invite.getCommunityId(), userId, "decline_moderator_invite", "user", userId, null);
    }

    // 404 (not 403) for someone else's invitation: don't confirm that an id exists to anyone but its recipient.
    private ModeratorInvite lockOwn(UUID userId, UUID inviteId) {
        return invites.findByIdForUpdate(inviteId)
                .filter(i -> i.getInviteeId().equals(userId))
                .orElseThrow(() -> new NotFoundException("no such invitation"));
    }

    private void requireStillPending(ModeratorInvite invite) {
        if (!invite.isPending()) {
            throw new ConflictException("invitation is already " + invite.getStatus());
        }
        if (invite.isExpired(Instant.now())) {
            invite.resolve(ModeratorInvite.EXPIRED);
            throw new ConflictException("invitation has expired");
        }
    }

    @Transactional(readOnly = true)
    public List<ModeratorInviteView> listForCommunity(UUID actorId, UUID communityId) {
        communityService.requireAnyModPermission(actorId, communityId);
        List<ModeratorInvite> rows = invites.findLivePendingForCommunity(communityId, Instant.now());
        Set<UUID> userIds = new HashSet<>();
        rows.forEach(i -> { userIds.add(i.getInviteeId()); userIds.add(i.getInviterId()); });
        Map<UUID, String> names = authService.findUsernamesByIds(userIds);
        return rows.stream()
                .map(i -> new ModeratorInviteView(i.getId(), i.getInviteeId(), names.get(i.getInviteeId()),
                        names.get(i.getInviterId()), i.getPermissions(), i.getStatus(), i.getCreatedAt(), i.getExpiresAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<MyModeratorInviteView> listMine(UUID userId) {
        List<ModeratorInvite> rows = invites.findLivePendingForInvitee(userId, Instant.now());
        Set<UUID> communityIds = new HashSet<>();
        Set<UUID> inviterIds = new HashSet<>();
        rows.forEach(i -> { communityIds.add(i.getCommunityId()); inviterIds.add(i.getInviterId()); });
        Map<UUID, String> communityNames = communityService.findNamesByIds(communityIds);
        Map<UUID, String> inviterNames = authService.findUsernamesByIds(inviterIds);
        return rows.stream()
                .filter(i -> communityNames.containsKey(i.getCommunityId())) // a deleted community's invitations aren't offered
                .map(i -> new MyModeratorInviteView(i.getId(), communityNames.get(i.getCommunityId()),
                        inviterNames.get(i.getInviterId()), i.getPermissions(), i.getCreatedAt(), i.getExpiresAt()))
                .toList();
    }
}
