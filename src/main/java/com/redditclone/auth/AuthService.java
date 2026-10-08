package com.redditclone.auth;

import com.redditclone.auth.dto.UserSearchHit;
import com.redditclone.common.OutboxWriter;
import com.redditclone.common.UuidV7Generator;
import com.redditclone.common.exception.ConflictException;
import com.redditclone.common.exception.ForbiddenException;
import com.redditclone.common.exception.UnauthorizedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AuthService {

    private final UserRepository users;
    private final UserSettingsRepository userSettings;
    private final RefreshTokenRepository refreshTokens;
    private final AccountActionRepository accountActions;
    private final EmailVerificationTokenRepository emailVerificationTokens;
    private final PasswordResetTokenRepository passwordResetTokens;
    private final OutboxWriter outboxWriter;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final UuidV7Generator ids;
    private final String frontendUrl;

    public AuthService(UserRepository users, UserSettingsRepository userSettings,
                        RefreshTokenRepository refreshTokens, AccountActionRepository accountActions,
                        EmailVerificationTokenRepository emailVerificationTokens,
                        PasswordResetTokenRepository passwordResetTokens, OutboxWriter outboxWriter,
                        PasswordEncoder encoder, JwtService jwt, UuidV7Generator ids,
                        @Value("${app.frontend-url}") String frontendUrl) {
        this.users = users;
        this.userSettings = userSettings;
        this.refreshTokens = refreshTokens;
        this.accountActions = accountActions;
        this.emailVerificationTokens = emailVerificationTokens;
        this.passwordResetTokens = passwordResetTokens;
        this.outboxWriter = outboxWriter;
        this.encoder = encoder;
        this.jwt = jwt;
        this.ids = ids;
        this.frontendUrl = frontendUrl;
    }

    @Transactional
    public void register(String username, String email, String rawPassword) {
        if (users.existsByEmail(email)) {
            throw new ConflictException("email in use");
        }
        if (users.existsByUsername(username)) {
            throw new ConflictException("username in use");
        }
        User user = new User();
        user.setId(ids.nextId());
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(encoder.encode(rawPassword));
        users.save(user);

        UserSettings settings = new UserSettings();
        settings.setUserId(user.getId());
        userSettings.save(settings);

        // No auto-login: issuing tokens here would bypass login()'s verification gate below for the one
        // session that matters most (the user's very first). Signing up only creates the account and
        // sends the verification link — the user must come back through login() to get a session, same
        // as every subsequent login.
        issueVerificationEmail(user);
    }

    public TokenPair login(String username, String rawPassword) {
        User user = users.findByUsername(username)
                .orElseThrow(() -> new UnauthorizedException("invalid credentials"));
        if (!encoder.matches(rawPassword, user.getPasswordHash())) {
            throw new UnauthorizedException("invalid credentials");
        }
        // Checked after credential verification, not before — don't leak account status to a guesser
        // who doesn't even have the right password.
        if (!"active".equals(user.getStatus())) {
            throw new UnauthorizedException("account is not active");
        }
        // register() never issues tokens (see its own comment) — every session, including the user's
        // very first, is minted here and is locked out until the owner clicks the link register() emailed
        // them. See EmailOutboxWorker/verifyEmail.
        if (user.getEmailVerifiedAt() == null) {
            throw new UnauthorizedException("email not verified");
        }
        return issueTokens(user, ids.nextId());
    }

    @Transactional
    public TokenPair refresh(String rawRefreshToken) {
        String hash = sha256(rawRefreshToken);
        RefreshToken stored = refreshTokens.findByTokenHash(hash)
                .orElseThrow(() -> new UnauthorizedException("invalid refresh token"));
        if (stored.getRevokedAt() != null) {
            refreshTokens.revokeFamily(stored.getFamilyId()); // reuse of a dead token = theft signal
            throw new UnauthorizedException("token reuse detected");
        }
        if (stored.getExpiresAt().isBefore(Instant.now())) {
            throw new UnauthorizedException("refresh token expired");
        }
        stored.setRevokedAt(Instant.now());
        refreshTokens.save(stored);
        User user = users.findById(stored.getUserId())
                .orElseThrow(() -> new UnauthorizedException("invalid refresh token"));
        // Same status check as login(), right where the User row is already loaded for the family-id
        // lookup (no extra query) — closes the loop so a ban also immediately kills the ability to mint
        // a *new* access token, not just future logins. The already-issued access token this call would
        // have refreshed keeps running until its own short TTL expires regardless (see banAccount).
        if (!"active".equals(user.getStatus())) {
            throw new UnauthorizedException("account is not active");
        }
        return issueTokens(user, stored.getFamilyId());
    }

    // Read by post.PostService.create() / comment.CommentService.reply() to evaluate a community's
    // karma_threshold automod rules.
    public int getKarmaPost(UUID userId) {
        return users.findById(userId).map(User::getKarmaPost).orElse(0);
    }

    public int getKarmaComment(UUID userId) {
        return users.findById(userId).map(User::getKarmaComment).orElse(0);
    }

    // Read by comment.CommentService.reply() to resolve u/{username} mentions to a user id — a
    // module-boundary-respecting alternative to comment reaching into auth.UserRepository directly
    // (UserRepository may only be accessed from within auth/common per ModuleBoundaryTest).
    public Optional<UUID> findUserIdByUsername(String username) {
        return users.findByUsername(username).map(User::getId);
    }

    // Batched counterpart to findUserIdByUsername — read by comment.CommentService.notifyMentions to
    // resolve every u/{username} mention in one query instead of one SELECT per mention.
    public Map<String, UUID> findUserIdsByUsernames(Set<String> usernames) {
        if (usernames.isEmpty()) {
            return Map.of();
        }
        return users.findByUsernameIn(usernames).stream()
                .collect(Collectors.toMap(User::getUsername, User::getId));
    }

    // Reverse of findUserIdsByUsernames — read by chat.ChatService to render a room summary's other-
    // participant usernames without chat reaching into auth.UserRepository directly.
    public Map<UUID, String> findUsernamesByIds(Set<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return users.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getUsername));
    }

    // Batched, order-preserving resolve of ids to User — read by follow.FollowService to turn a
    // cursor-ordered page of Follow rows into the matching users (then PublicProfiles, with isFollowing
    // attached) without a per-row lookup. findAllById doesn't guarantee result order, so this re-orders to
    // match the caller's id order rather than returning whatever order the DB happens to hand back.
    public List<User> findUsersByIds(List<UUID> orderedIds) {
        Map<UUID, User> byId = users.findAllById(orderedIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));
        return orderedIds.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    // Read by community.CommunityService's permission checks so a deleted/banned account's still-valid
    // access token can't keep exercising moderator/site-admin authority for the remainder of its TTL —
    // deleteAccount() anonymizes the row but never touches community_moderators/is_site_admin directly.
    // Read by usersearch.UserSearchService so the picker search never reaches into auth's repository. Over-fetches by the number of
    // excluded ids (a caller's blocked users) and filters here, so the final page still holds `limit` rows when it can.
    public List<UserSearchHit> searchActiveUsers(UUID callerId, String query, Set<UUID> excludedIds, int limit) {
        String escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return users.searchForPicker(query, escaped, callerId, limit + excludedIds.size()).stream()
                .filter(h -> !excludedIds.contains(h.getId()))
                .limit(limit)
                .map(h -> new UserSearchHit(h.getId(), h.getUsername()))
                .toList();
    }

    public boolean isActive(UUID userId) {
        return users.findById(userId).map(u -> "active".equals(u.getStatus())).orElse(false);
    }

    public void requireSiteAdmin(UUID userId) {
        User user = users.findById(userId).orElseThrow(() -> new ForbiddenException("site admin only"));
        if (!"active".equals(user.getStatus()) || !user.isSiteAdmin()) {
            throw new ForbiddenException("site admin only");
        }
    }

    // Account-level (site-wide) ban — independent of any community.CommunityService.issueBan, which is
    // scoped to one community. Reuses revokeAllForUser (already built for account deletion, unused until
    // now): the refresh-token family dies immediately; the still-valid access token this doesn't touch
    // keeps running for its own short remaining TTL — see refresh()'s comment for why that's accepted.
    @Transactional
    public void banAccount(UUID actorId, UUID targetUserId, String reason) {
        requireSiteAdmin(actorId);
        User target = users.findById(targetUserId).orElseThrow(() -> new UnauthorizedException("no such user"));
        target.setStatus("banned");
        users.save(target);
        refreshTokens.revokeAllForUser(targetUserId);
        logAccountAction(actorId, targetUserId, "ban_account", reason);
    }

    @Transactional
    public void unbanAccount(UUID actorId, UUID targetUserId, String reason) {
        requireSiteAdmin(actorId);
        User target = users.findById(targetUserId).orElseThrow(() -> new UnauthorizedException("no such user"));
        target.setStatus("active");
        users.save(target);
        logAccountAction(actorId, targetUserId, "unban_account", reason);
    }

    // Idempotent: used by SystemAccountBootstrap on every app startup, not just the first time.
    @Transactional
    public void promoteToSiteAdmin(String username) {
        users.findByUsername(username).filter(u -> !u.isSiteAdmin()).ifPresent(u -> {
            u.setSiteAdmin(true);
            users.save(u);
        });
    }

    public UserSettings getSettings(UUID userId) {
        return userSettings.findById(userId).orElseThrow(() -> new UnauthorizedException("no such user"));
    }

    @Transactional
    public UserSettings updateSettings(UUID userId, Boolean nsfwBlur, Map<String, Object> privacyPrefs,
                                        Map<String, Boolean> notificationPrefs) {
        UserSettings settings = getSettings(userId);
        if (nsfwBlur != null) {
            settings.setNsfwBlur(nsfwBlur);
        }
        if (privacyPrefs != null) {
            // Merge, not replace — a PATCH carrying only one key (e.g. {"showEmail": true}) must not wipe
            // out every other previously-set key, which a wholesale settings.setPrivacyPrefs(privacyPrefs)
            // would otherwise do.
            Map<String, Object> merged = new HashMap<>(settings.getPrivacyPrefs());
            merged.putAll(privacyPrefs);
            settings.setPrivacyPrefs(merged);
        }
        if (notificationPrefs != null) {
            // Same merge-not-replace reasoning as privacyPrefs above.
            Map<String, Boolean> merged = new HashMap<>(settings.getNotificationPrefs());
            merged.putAll(notificationPrefs);
            settings.setNotificationPrefs(merged);
        }
        return userSettings.save(settings);
    }

    // Sparse, default-on: absent key or no settings row at all means "enabled," only an explicit false
    // disables a type. Read by notify.NotificationOutboxWorker before inserting a notifications row, so a
    // muted type is never persisted to the inbox in the first place — not a display-time filter.
    public boolean wantsNotification(UUID userId, String type) {
        return userSettings.findById(userId)
                .map(s -> !Boolean.FALSE.equals(s.getNotificationPrefs().get(type)))
                .orElse(true);
    }

    // Sparse, default-off (opposite polarity from wantsNotification, same sparse shape): absent key or no
    // settings row means "unrestricted," matching today's status quo — only an explicit true opts a user
    // into requiring an existing relationship before a stranger can start a new chat with them. Read by
    // chat.ChatService before creating a brand-new room; never affects a room that already exists.
    public boolean restrictsChatToKnown(UUID userId) {
        return userSettings.findById(userId)
                .map(s -> Boolean.TRUE.equals(s.getPrivacyPrefs().get("restrictChatToKnown")))
                .orElse(false);
    }

    // Sparse, default-OFF — opposite polarity from wantsNotification's default-on, because an email isn't
    // a passive inbox view the way the in-app list is: unsolicited mail for every reply/mention would
    // surprise users who never asked for it. A muted-in-app type (wantsNotification's own check, applied
    // by NotificationOutboxWorker before this one ever runs) is never emailed either.
    public boolean wantsEmailNotification(UUID userId, String type) {
        return userSettings.findById(userId)
                .map(s -> Boolean.TRUE.equals(s.getPrivacyPrefs().get("emailNotifications")))
                .orElse(false);
    }

    // Read by notify.NotificationOutboxWorker to address an "email" outbox event without reaching into
    // UserRepository directly — see EmailRecipient.
    public Optional<EmailRecipient> findEmailRecipient(UUID userId) {
        return users.findById(userId).map(u -> new EmailRecipient(u.getEmail(), u.getUsername()));
    }

    // Shared by register() (first email) and resendVerification() (a replacement link) — a fresh raw
    // token is generated every time rather than reusing an unexpired one, same "always issue a new one"
    // simplicity as issueTokens' refresh-token minting.
    private void issueVerificationEmail(User user) {
        String rawToken = UUID.randomUUID().toString();
        EmailVerificationToken token = new EmailVerificationToken();
        token.setId(ids.nextId());
        token.setUserId(user.getId());
        token.setTokenHash(sha256(rawToken));
        token.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
        emailVerificationTokens.save(token);
        outboxWriter.writeEvent("email", Map.of(
                "kind", "verification",
                "to", user.getEmail(),
                "toName", user.getUsername(),
                "link", frontendUrl + "/verify-email?token=" + rawToken
        ));
    }

    @Transactional
    public void verifyEmail(String rawToken) {
        EmailVerificationToken token = emailVerificationTokens.findByTokenHash(sha256(rawToken))
                .orElseThrow(() -> new UnauthorizedException("invalid or expired verification link"));
        if (token.getUsedAt() != null || token.getExpiresAt().isBefore(Instant.now())) {
            throw new UnauthorizedException("invalid or expired verification link");
        }
        User user = users.findById(token.getUserId())
                .orElseThrow(() -> new UnauthorizedException("invalid or expired verification link"));
        token.setUsedAt(Instant.now());
        emailVerificationTokens.save(token);
        user.setEmailVerifiedAt(Instant.now());
        users.save(user);
    }

    // Silently no-ops for an unknown email or an already-verified account — the controller returns the
    // exact same response either way, so this can't be used to probe which emails are registered.
    @Transactional
    public void resendVerification(String email) {
        users.findByEmail(email)
                .filter(u -> u.getEmailVerifiedAt() == null)
                .ifPresent(this::issueVerificationEmail);
    }

    // Same anti-enumeration no-op reasoning as resendVerification: the controller's response never reveals
    // whether the email was found.
    @Transactional
    public void requestPasswordReset(String email) {
        users.findByEmail(email).ifPresent(user -> {
            String rawToken = UUID.randomUUID().toString();
            PasswordResetToken token = new PasswordResetToken();
            token.setId(ids.nextId());
            token.setUserId(user.getId());
            token.setTokenHash(sha256(rawToken));
            token.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
            passwordResetTokens.save(token);
            outboxWriter.writeEvent("email", Map.of(
                    "kind", "password_reset",
                    "to", user.getEmail(),
                    "toName", user.getUsername(),
                    "link", frontendUrl + "/reset-password?token=" + rawToken
            ));
        });
    }

    // Revokes every refresh-token family afterward (revokeAllForUser, same method banAccount/
    // deleteAccount already use) — whoever could complete this reset now owns the account going forward,
    // so any session issued before the reset (possibly by whoever lost control of it) is cut off too.
    @Transactional
    public void confirmPasswordReset(String rawToken, String newPassword) {
        PasswordResetToken token = passwordResetTokens.findByTokenHash(sha256(rawToken))
                .orElseThrow(() -> new UnauthorizedException("invalid or expired reset link"));
        if (token.getUsedAt() != null || token.getExpiresAt().isBefore(Instant.now())) {
            throw new UnauthorizedException("invalid or expired reset link");
        }
        User user = users.findById(token.getUserId())
                .orElseThrow(() -> new UnauthorizedException("invalid or expired reset link"));
        token.setUsedAt(Instant.now());
        passwordResetTokens.save(token);
        user.setPasswordHash(encoder.encode(newPassword));
        users.save(user);
        refreshTokens.revokeAllForUser(user.getId());
    }

    // Near-identical shape to banAccount: verify the password first (401, no state change, on mismatch —
    // matches the checkpoint exactly), then anonymize rather than hard-delete or cascade, matching this
    // codebase's established soft-delete philosophy for content (posts/comments keep their now-anonymized
    // authorId untouched). email/username/password_hash are all NOT NULL, so they're overwritten with
    // unusable-but-valid values, never nulled. revokeAllForUser is the exact pre-built, previously-unused
    // method banAccount's own comment already flagged as being there for this.
    @Transactional
    public void deleteAccount(UUID userId, String rawPassword) {
        User user = users.findById(userId).orElseThrow(() -> new UnauthorizedException("no such user"));
        if (!encoder.matches(rawPassword, user.getPasswordHash())) {
            throw new UnauthorizedException("password does not match");
        }
        user.setUsername("deleted_" + user.getId());
        user.setEmail(user.getId() + "@deleted.invalid");
        user.setPasswordHash(encoder.encode(UUID.randomUUID().toString()));
        user.setStatus("deleted");
        users.save(user);
        refreshTokens.revokeAllForUser(userId);
    }

    private void logAccountAction(UUID actorId, UUID targetUserId, String action, String reason) {
        AccountAction a = new AccountAction();
        a.setId(ids.nextId());
        a.setActorId(actorId);
        a.setTargetUserId(targetUserId);
        a.setAction(action);
        a.setReason(reason);
        accountActions.save(a);
    }

    // Grouped karma deltas from a batch of vote events (see vote.OutboxWorker) — one bulk UPDATE per
    // affected user, not per post/comment, same "grouped, not per-vote" principle as PostService/
    // CommentService.applyVoteDeltas.
    @Transactional
    public void applyPostKarmaDelta(UUID userId, int delta) {
        if (delta != 0) {
            users.adjustKarmaPost(userId, delta);
        }
    }

    @Transactional
    public void applyCommentKarmaDelta(UUID userId, int delta) {
        if (delta != 0) {
            users.adjustKarmaComment(userId, delta);
        }
    }

    // Read by follow.FollowService.follow/unfollow — follow is a synchronous relationship toggle (unlike
    // vote-driven karma, which batches through an async outbox worker), so these adjust the counter inline
    // in the same request, same as CommunityService's incrementSubscriberCount/decrementSubscriberCount.
    @Transactional
    public void adjustFollowerCount(UUID userId, int delta) {
        users.adjustFollowerCount(userId, delta);
    }

    @Transactional
    public void adjustFollowingCount(UUID userId, int delta) {
        users.adjustFollowingCount(userId, delta);
    }

    // Revokes only the family tied to this one refresh token ("log out of this device"), not every
    // session the user has — revokeAllForUser (ban/delete) is the deliberately broader sibling. A
    // missing/unknown/already-revoked token is a silent no-op: logout must always succeed from the
    // client's perspective even if the cookie is stale or absent.
    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokens.findByTokenHash(sha256(rawRefreshToken))
                .ifPresent(stored -> refreshTokens.revokeFamily(stored.getFamilyId()));
    }

    private TokenPair issueTokens(User user, UUID familyId) {
        String access = jwt.generateAccessToken(user);
        String rawRefresh = UUID.randomUUID().toString(); // the opaque refresh secret itself — a plain random UUID is fine here, it's never used as a sortable primary key
        RefreshToken rt = new RefreshToken();
        rt.setId(ids.nextId());
        rt.setUserId(user.getId());
        rt.setTokenHash(sha256(rawRefresh));
        rt.setFamilyId(familyId);
        rt.setExpiresAt(Instant.now().plus(30, ChronoUnit.DAYS));
        refreshTokens.save(rt);
        return new TokenPair(access, rawRefresh);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
