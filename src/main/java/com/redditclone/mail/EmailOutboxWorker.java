package com.redditclone.mail;

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

import java.util.List;
import java.util.Map;

// Owns the "email" slice of outbox_events, same split-by-concern reasoning as notify.NotificationOutboxWorker
// (which owns 'notification'). A dumb consumer by design: every field a template needs (recipient, name,
// link, type) is already in the payload the producer (auth.AuthService, notify.NotificationOutboxWorker)
// wrote — this worker never calls back into auth or notify, so it adds no edge to ModuleBoundaryTest's
// module-cycle check.
@Component
public class EmailOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(EmailOutboxWorker.class);
    private static final int BATCH_SIZE = 50; // each row makes a synchronous HTTP call to Brevo, unlike notify's DB-only batch
    private static final int MAX_ATTEMPTS = 5;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final BrevoClient brevo;
    private final boolean mailEnabled;

    public EmailOutboxWorker(JdbcTemplate jdbc, ObjectMapper json, BrevoClient brevo,
                              @Value("${app.mail.enabled}") boolean mailEnabled) {
        this.jdbc = jdbc;
        this.json = json;
        this.brevo = brevo;
        this.mailEnabled = mailEnabled;
    }

    @Scheduled(fixedDelay = 5000)
    @SchedulerLock(name = "emailOutboxWorker", lockAtLeastFor = "1s", lockAtMostFor = "2m")
    @Transactional
    public void processBatch() {
        List<Map<String, Object>> events = jdbc.queryForList("""
                SELECT id, payload, attempts, correlation_id FROM outbox_events
                WHERE processed_at IS NULL AND event_type = 'email'
                ORDER BY id
                LIMIT %d
                FOR UPDATE SKIP LOCKED
                """.formatted(BATCH_SIZE));
        if (events.isEmpty()) {
            return;
        }

        for (Map<String, Object> event : events) {
            String correlationId = (String) event.get("correlation_id");
            if (correlationId != null) {
                MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
            }
            Object id = event.get("id");
            try {
                int attempts = ((Number) event.get("attempts")).intValue();
                processOne(id, attempts, json.readTree(event.get("payload").toString()));
            } catch (Exception e) {
                log.warn("outbox email event {} could not be parsed, marking processed without sending: {}", id, e.getMessage());
                jdbc.update("UPDATE outbox_events SET processed_at = now() WHERE id = ?", id);
            } finally {
                MDC.remove(CorrelationIdFilter.MDC_KEY);
            }
        }
    }

    private void processOne(Object id, int attempts, JsonNode payload) {
        String kind = payload.path("kind").asString();
        String to = payload.path("to").asString();
        String toName = payload.path("toName").asString();

        EmailMessage message = switch (kind) {
            case "verification" -> new EmailMessage(to, toName, EmailTemplates.verificationSubject(),
                    EmailTemplates.verificationHtml(toName, payload.path("link").asString()));
            case "password_reset" -> new EmailMessage(to, toName, EmailTemplates.passwordResetSubject(),
                    EmailTemplates.passwordResetHtml(toName, payload.path("link").asString()));
            case "notification" -> new EmailMessage(to, toName, EmailTemplates.notificationSubject(payload.path("type").asString()),
                    EmailTemplates.notificationHtml(toName, payload.path("link").asString(), payload.path("type").asString()));
            default -> null;
        };

        if (message == null) {
            log.warn("outbox email event {} has unrecognized kind '{}', marking processed without sending", id, kind);
            jdbc.update("UPDATE outbox_events SET processed_at = now() WHERE id = ?", id);
            return;
        }

        if (!mailEnabled) {
            log.info("app.mail.enabled=false, skipping send for outbox email event {} ({})", id, kind);
            markSent(id, kind);
            return;
        }

        try {
            brevo.send(message);
            markSent(id, kind);
        } catch (Exception e) {
            int nextAttempts = attempts + 1;
            if (nextAttempts >= MAX_ATTEMPTS) {
                log.error("outbox email event {} ({}) failed {} times, giving up: {}", id, kind, nextAttempts, e.getMessage());
                jdbc.update("UPDATE outbox_events SET processed_at = now(), attempts = ? WHERE id = ?", nextAttempts, id);
            } else {
                log.warn("outbox email event {} ({}) send failed (attempt {}/{}), will retry: {}", id, kind, nextAttempts, MAX_ATTEMPTS, e.getMessage());
                jdbc.update("UPDATE outbox_events SET attempts = ? WHERE id = ?", nextAttempts, id);
            }
        }
    }

    // verification/password_reset payloads carry a live, single-use secret link — once the email is sent
    // there's no further use for it, so the payload is redacted rather than left sitting in outbox_events
    // indefinitely (unlike notification payloads, which never held a secret).
    private void markSent(Object id, String kind) {
        if ("verification".equals(kind) || "password_reset".equals(kind)) {
            jdbc.update("UPDATE outbox_events SET processed_at = now(), payload = ?::jsonb WHERE id = ?",
                    "{\"kind\":\"" + kind + "\"}", id);
        } else {
            jdbc.update("UPDATE outbox_events SET processed_at = now() WHERE id = ?", id);
        }
    }
}
