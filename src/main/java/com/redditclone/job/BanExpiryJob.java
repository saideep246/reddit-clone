package com.redditclone.job;

import com.redditclone.common.ModerationAuditWriter;
import com.redditclone.common.SystemAccounts;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

// A temporary ban already stops applying the moment expires_at passes (CommunityService.requireNotBanned
// checks it), so this isn't what lifts the restriction — it tidies the row away and leaves an audit trail
// ("unban_expired", attributed to the AutoModerator account) so the mod log explains why someone was let
// back in, and so the Bans tab doesn't accumulate dead rows.
@Component
public class BanExpiryJob {

    private final JdbcTemplate jdbc;
    private final ModerationAuditWriter auditWriter;

    public BanExpiryJob(JdbcTemplate jdbc, ModerationAuditWriter auditWriter) {
        this.jdbc = jdbc;
        this.auditWriter = auditWriter;
    }

    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(name = "banExpiryJob", lockAtLeastFor = "10s", lockAtMostFor = "2m")
    @Transactional
    public void liftExpiredBans() {
        record Expired(UUID communityId, UUID userId) {
        }
        List<Expired> expired = jdbc.query("""
                DELETE FROM bans WHERE expires_at IS NOT NULL AND expires_at <= now()
                RETURNING community_id, user_id
                """, (rs, i) -> new Expired(rs.getObject("community_id", UUID.class), rs.getObject("user_id", UUID.class)));
        for (Expired e : expired) {
            auditWriter.logAction(e.communityId(), SystemAccounts.AUTOMOD_USER_ID, "unban_expired", "user", e.userId(), "ban expired");
        }
    }
}
