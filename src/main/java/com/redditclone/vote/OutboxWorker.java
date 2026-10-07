package com.redditclone.vote;

import com.redditclone.auth.AuthService;
import com.redditclone.comment.CommentService;
import com.redditclone.common.KarmaEvent;
import com.redditclone.common.UuidV7Generator;
import com.redditclone.common.VoteDelta;
import com.redditclone.common.correlation.CorrelationIdFilter;
import com.redditclone.post.PostService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Drains outbox_events in the same process as the API for now (Phase 2 scope) rather than a separate
// `worker` role process (the source plan's System architecture splits api/worker by env var — that's a
// Phase 5/deployment concern, not attempted here). @Scheduled + ShedLock still make this safe to later
// run alongside additional instances of the same process without double-processing a batch.
@Component
public class OutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);
    private static final int BATCH_SIZE = 500;

    private final JdbcTemplate jdbc;
    private final PostService postService;
    private final CommentService commentService;
    private final AuthService authService;
    private final UuidV7Generator ids;
    private final ObjectMapper json;

    public OutboxWorker(JdbcTemplate jdbc, PostService postService, CommentService commentService,
                         AuthService authService, UuidV7Generator ids, ObjectMapper json) {
        this.jdbc = jdbc;
        this.postService = postService;
        this.commentService = commentService;
        this.authService = authService;
        this.ids = ids;
        this.json = json;
    }

    @Scheduled(fixedDelay = 2000)
    @SchedulerLock(name = "outboxWorker", lockAtLeastFor = "1s", lockAtMostFor = "30s") // only one instance runs this at a time
    @Transactional
    public void processBatch() {
        // event_type NOT IN ('notification', 'email'): those slices belong to notify.NotificationOutboxWorker
        // and mail.EmailOutboxWorker respectively, each of which owns its own schema knowledge the way this
        // worker owns posts/comments/karma. The exclusion (rather than an exact allowlist of vote event
        // types) keeps this claim query forward-compatible with any future non-vote event type, which still
        // falls through to the existing "unrecognized" warning below exactly as before. Without excluding
        // 'email' here too, this worker's wider, unfiltered claim query (FOR UPDATE SKIP LOCKED, no
        // event_type filter in the WHERE beyond this exclusion) would race mail.EmailOutboxWorker for the
        // same rows and mark them processed as "unrecognized" before the mail worker ever saw them.
        List<Map<String, Object>> events = jdbc.queryForList("""
                SELECT id, event_type, payload, correlation_id FROM outbox_events
                WHERE processed_at IS NULL AND event_type NOT IN ('notification', 'email')
                ORDER BY id
                LIMIT %d
                FOR UPDATE SKIP LOCKED
                """.formatted(BATCH_SIZE));
        if (events.isEmpty()) {
            return;
        }

        // Grouping by target id before writing means many votes on the same post/comment in one tick
        // become one UPDATE, not one per vote — see Voting, karma & outbox in the source plan.
        Map<UUID, int[]> postAgg = new HashMap<>(); // {scoreDelta, upsDelta, downsDelta}
        Map<UUID, int[]> commentAgg = new HashMap<>();

        // Each event is isolated in its own try/catch: without this, one unparseable payload throws out
        // of the loop and rolls back the whole @Transactional batch, including every valid event in it —
        // and since none of them get marked processed_at, the next tick re-selects the identical batch
        // (same ORDER BY id LIMIT) and hits the same bad row again, forever. A bad event is logged and
        // left for the operator to investigate instead of blocking the rest of the queue.
        for (Map<String, Object> event : events) {
            String type = (String) event.get("event_type");
            // Re-applies the originating request's correlation id (persisted on the row by
            // OutboxWriter.writeEvent) before this row's own log lines — guards the put with a null check
            // since MDC.put rejects a null value, and clears per row (not just per tick) in the finally
            // since a 500-row batch can span many different original requests.
            String correlationId = (String) event.get("correlation_id");
            if (correlationId != null) {
                MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
            }
            try {
                if (type.startsWith("post_vote") || type.startsWith("comment_vote")) {
                    VoteEventPayload payload = parsePayload(event.get("payload").toString());
                    int[] delta = computeDelta(payload);
                    if (type.startsWith("post_vote")) {
                        accumulate(postAgg, payload.targetId(), delta);
                    } else {
                        accumulate(commentAgg, payload.targetId(), delta);
                    }
                    log.info("outbox event {} ({}) applied", event.get("id"), type);
                } else {
                    log.warn("outbox event {} has unrecognized event_type '{}', marking processed without applying it", event.get("id"), type);
                }
            } catch (Exception e) {
                log.warn("outbox event {} could not be applied, marking processed without applying it: {}", event.get("id"), e.getMessage());
            } finally {
                MDC.remove(CorrelationIdFilter.MDC_KEY);
            }
        }

        List<KarmaEvent> postKarma = postService.applyVoteDeltas(toVoteDeltas(postAgg));
        List<KarmaEvent> commentKarma = commentService.applyVoteDeltas(toVoteDeltas(commentAgg));

        applyKarma(postKarma, "post_vote", true);
        applyKarma(commentKarma, "comment_vote", false);

        jdbc.batchUpdate("UPDATE outbox_events SET processed_at = now() WHERE id = ?",
                events.stream().map(e -> new Object[]{e.get("id")}).toList());
    }

    private VoteEventPayload parsePayload(String payloadJson) {
        try {
            return json.readValue(payloadJson, VoteEventPayload.class);
        } catch (Exception e) {
            throw new IllegalStateException("corrupt outbox payload: " + payloadJson, e);
        }
    }

    // A "_cast" event with no prior vote (oldDirection null) is a fresh vote: delta = newDirection. One
    // with a prior vote is a direction SWITCH (e.g. up -> down): delta = newDirection - oldDirection, a
    // swing of 2, not 1 — the plan's own OutboxWorker sketch only ever applies +/-1 per cast event and
    // silently under/over-counts a switched vote. A "_removed" event (newDirection null) reverses
    // whatever oldDirection contributed: delta = -oldDirection, not the plan sketch's hard-coded 0 (which
    // would mean an unvote never actually undoes its score/karma effect).
    private int[] computeDelta(VoteEventPayload payload) {
        int oldDir = payload.oldDirection() == null ? 0 : payload.oldDirection();
        int newDir = payload.newDirection() == null ? 0 : payload.newDirection();
        int scoreDelta = newDir - oldDir;
        int upsDelta = (newDir == 1 ? 1 : 0) - (oldDir == 1 ? 1 : 0);
        int downsDelta = (newDir == -1 ? 1 : 0) - (oldDir == -1 ? 1 : 0);
        return new int[]{scoreDelta, upsDelta, downsDelta};
    }

    private void accumulate(Map<UUID, int[]> agg, UUID targetId, int[] delta) {
        agg.merge(targetId, delta, (a, b) -> new int[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]});
    }

    private Map<UUID, VoteDelta> toVoteDeltas(Map<UUID, int[]> agg) {
        Map<UUID, VoteDelta> out = new HashMap<>();
        agg.forEach((id, d) -> out.put(id, new VoteDelta(d[0], d[1], d[2])));
        return out;
    }

    // One karma_log row per post/comment that actually changed (append-only audit ledger, via a single
    // batched INSERT rather than one JPA save() per row — KarmaLog's id is a manually-assigned UUID with
    // no @GeneratedValue, so a JPA save() on it resolves to merge(), issuing a spurious SELECT-by-PK
    // before every INSERT), plus one grouped users.karma_post/karma_comment UPDATE per distinct author —
    // not one UPDATE per row, in case a single batch touches several posts by the same author.
    private void applyKarma(List<KarmaEvent> events, String reason, boolean isPost) {
        if (events.isEmpty()) {
            return;
        }
        List<Object[]> rows = events.stream()
                .map(e -> new Object[]{ids.nextId(), e.userId(), e.delta(), reason, e.sourceId()})
                .toList();
        jdbc.batchUpdate("INSERT INTO karma_log (id, user_id, delta, reason, source_id) VALUES (?, ?, ?, ?, ?)", rows);

        Map<UUID, Integer> byUser = new HashMap<>();
        for (KarmaEvent e : events) {
            byUser.merge(e.userId(), e.delta(), Integer::sum);
        }
        byUser.forEach((userId, delta) -> {
            if (isPost) {
                authService.applyPostKarmaDelta(userId, delta);
            } else {
                authService.applyCommentKarmaDelta(userId, delta);
            }
        });
    }
}
