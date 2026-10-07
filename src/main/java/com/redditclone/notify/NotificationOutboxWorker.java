package com.redditclone.notify;

import com.redditclone.auth.AuthService;
import com.redditclone.common.OutboxWriter;
import com.redditclone.common.UuidV7Generator;
import com.redditclone.common.correlation.CorrelationIdFilter;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Owns the "notification" slice of outbox_events — split out from vote.OutboxWorker (which excludes
// event_type='notification' from its own claim query) so the notify module, which already owns the
// notifications table's schema, is what actually writes to it. Matches the "each concern gets its own
// dedicated worker" pattern this codebase already uses for media (ImageProcessingWorker/
// VideoProcessingWorker), rather than bolting notification handling onto a worker named for a different
// concern. Raw JDBC against outbox_events, not a cross-module repository, for the same shared-
// infrastructure-table reasoning ModerationAuditWriter/OutboxWriter already establish elsewhere.
@Component
public class NotificationOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(NotificationOutboxWorker.class);
    private static final int BATCH_SIZE = 500;

    private final JdbcTemplate jdbc;
    private final UuidV7Generator ids;
    private final ObjectMapper json;
    private final OutboxWriter outboxWriter;
    private final AuthService authService;
    private final String frontendUrl;

    public NotificationOutboxWorker(JdbcTemplate jdbc, UuidV7Generator ids, ObjectMapper json,
                                     OutboxWriter outboxWriter, AuthService authService,
                                     @Value("${app.frontend-url}") String frontendUrl) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.json = json;
        this.outboxWriter = outboxWriter;
        this.authService = authService;
        this.frontendUrl = frontendUrl;
    }

    @Scheduled(fixedDelay = 2000)
    @SchedulerLock(name = "notificationOutboxWorker", lockAtLeastFor = "1s", lockAtMostFor = "30s")
    @Transactional
    public void processBatch() {
        List<Map<String, Object>> events = jdbc.queryForList("""
                SELECT id, payload, correlation_id FROM outbox_events
                WHERE processed_at IS NULL AND event_type = 'notification'
                ORDER BY id
                LIMIT %d
                FOR UPDATE SKIP LOCKED
                """.formatted(BATCH_SIZE));
        if (events.isEmpty()) {
            return;
        }

        List<Object[]> rows = new ArrayList<>();
        for (Map<String, Object> event : events) {
            // Re-applies the originating request's/STOMP-send's correlation id (persisted on the row by
            // OutboxWriter.writeEvent, including from ChatStompHandler.send) before this row's own log
            // lines — see OutboxWorker's identical comment for why the null guard and per-row clear matter.
            String correlationId = (String) event.get("correlation_id");
            if (correlationId != null) {
                MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
            }
            try {
                JsonNode payload = json.readTree(event.get("payload").toString());
                UUID userId = UUID.fromString(payload.path("userId").asString());
                String type = payload.path("type").asString();
                // Checked here, not by each producer (CommentService/ChatStompHandler) — every
                // notification-type event already funnels through this one worker before becoming a row,
                // so this is the single chokepoint to enforce a mute, not N call sites that would each
                // need to know about preference storage. The outbox event is still marked processed below
                // either way, same as the existing "unrecognized event_type" skip-and-continue elsewhere.
                if (authService.wantsNotification(userId, type)) {
                    rows.add(new Object[]{ids.nextId(), userId, type, json.writeValueAsString(payload.path("source"))});
                    log.info("outbox event {} ({}) queued for insertion", event.get("id"), type);
                } else {
                    log.info("outbox event {} ({}) skipped (muted)", event.get("id"), type);
                }
            } catch (Exception e) {
                log.warn("outbox event {} could not be applied, marking processed without applying it: {}", event.get("id"), e.getMessage());
            } finally {
                MDC.remove(CorrelationIdFilter.MDC_KEY);
            }
        }

        // Isolated in its own transaction (see OutboxWriter.insertNotifications) so one bad row (e.g. a
        // stale user_id violating notifications_user_id_fkey) can't poison this tick's outbox_events
        // processed_at marking below for every other, otherwise-valid event in the batch.
        try {
            outboxWriter.insertNotifications(rows);
            // Only reached once the in-app rows actually exist — an email for a notification that never
            // made it into the inbox would be confusing, so this piggybacks on the same try, not a
            // separate best-effort pass.
            for (Object[] row : rows) {
                UUID userId = (UUID) row[1];
                String type = (String) row[2];
                if (authService.wantsEmailNotification(userId, type)) {
                    authService.findEmailRecipient(userId).ifPresent(recipient -> outboxWriter.writeEvent("email", Map.of(
                            "kind", "notification",
                            "to", recipient.email(),
                            "toName", recipient.username(),
                            "type", type,
                            "link", frontendUrl + "/notifications"
                    )));
                }
            }
        } catch (Exception e) {
            log.warn("failed to insert {} notification row(s) from this batch: {}", rows.size(), e.getMessage());
        }

        jdbc.batchUpdate("UPDATE outbox_events SET processed_at = now() WHERE id = ?",
                events.stream().map(e -> new Object[]{e.get("id")}).toList());
    }
}
