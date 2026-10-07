package com.redditclone.post;

import com.redditclone.common.UuidV7Generator;
import com.redditclone.common.exception.BadRequestException;
import com.redditclone.common.exception.NotFoundException;
import com.redditclone.community.CommunityService;
import com.redditclone.post.dto.CreatePostRequest;
import com.redditclone.post.dto.ScheduledPostView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

// Queue a post for later. The author's access (banned? restricted/private?) is checked when scheduling so a
// doomed schedule fails fast, and again by PostService.create when the job actually publishes (the world can
// change in between) — a publish-time failure is recorded on the row as status=failed with the reason.
@Service
public class ScheduledPostService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledPostService.class);
    private static final int MAX_PENDING_PER_USER = 20;
    private static final Duration MIN_LEAD = Duration.ofMinutes(1);
    private static final Duration MAX_LEAD = Duration.ofDays(30);
    private static final int BATCH = 20;

    private final NamedParameterJdbcTemplate jdbc;
    private final UuidV7Generator ids;
    private final ObjectMapper json;
    private final PostService postService;
    private final CommunityService communityService;
    private final TransactionTemplate requiresNew;

    public ScheduledPostService(NamedParameterJdbcTemplate jdbc, UuidV7Generator ids, ObjectMapper json,
                                 PostService postService, CommunityService communityService,
                                 PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.json = json;
        this.postService = postService;
        this.communityService = communityService;
        this.requiresNew = new TransactionTemplate(txManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public ScheduledPostView schedule(UUID authorId, UUID communityId, CreatePostRequest req, Instant publishAt) {
        Instant now = Instant.now();
        if (publishAt.isBefore(now.plus(MIN_LEAD)) || publishAt.isAfter(now.plus(MAX_LEAD))) {
            throw new BadRequestException("publishAt must be between 1 minute and 30 days from now");
        }
        communityService.requireNotBanned(authorId, communityId);
        communityService.requirePostAccess(authorId, communityId);
        Integer pending = jdbc.queryForObject("SELECT count(*) FROM scheduled_posts WHERE author_id = :u AND status = 'pending'",
                new MapSqlParameterSource("u", authorId), Integer.class);
        if (pending != null && pending >= MAX_PENDING_PER_USER) {
            throw new BadRequestException("you can have at most " + MAX_PENDING_PER_USER + " scheduled posts");
        }
        UUID id = ids.nextId();
        jdbc.update("""
                INSERT INTO scheduled_posts (id, author_id, community_id, payload, publish_at)
                VALUES (:id, :a, :c, CAST(:p AS jsonb), :at)
                """, new MapSqlParameterSource().addValue("id", id).addValue("a", authorId).addValue("c", communityId)
                .addValue("p", json.writeValueAsString(req)).addValue("at", Timestamp.from(publishAt)));
        return list(authorId).stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    public List<ScheduledPostView> list(UUID authorId) {
        return jdbc.query("""
                SELECT s.id, c.name, s.payload->>'title' AS title, s.payload->>'kind' AS kind, s.publish_at,
                       s.status, s.error, s.post_id
                FROM scheduled_posts s JOIN communities c ON c.id = s.community_id
                WHERE s.author_id = :u AND c.deleted_at IS NULL ORDER BY s.publish_at DESC LIMIT 50
                """, new MapSqlParameterSource("u", authorId),
                (rs, i) -> new ScheduledPostView(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("title"),
                        rs.getString("kind"), rs.getTimestamp("publish_at").toInstant(), rs.getString("status"),
                        rs.getString("error"), rs.getObject("post_id", UUID.class)));
    }

    @Transactional
    public void cancel(UUID authorId, UUID id) {
        int changed = jdbc.update("UPDATE scheduled_posts SET status = 'cancelled' WHERE id = :id AND author_id = :u AND status = 'pending'",
                new MapSqlParameterSource().addValue("id", id).addValue("u", authorId));
        if (changed == 0) {
            throw new NotFoundException("no such pending scheduled post");
        }
    }

    // Called by job.ScheduledPostJob. Claims due rows with FOR UPDATE SKIP LOCKED, then publishes each in its
    // own transaction so one failing post (rolled back by PostService.create) can't take the batch with it.
    public int publishDue() {
        record Due(UUID id, UUID authorId, UUID communityId, String payload) {
        }
        List<Due> due = requiresNew.execute(status -> jdbc.query("""
                SELECT id, author_id, community_id, payload::text AS payload FROM scheduled_posts
                WHERE status = 'pending' AND publish_at <= now() ORDER BY publish_at LIMIT :n FOR UPDATE SKIP LOCKED
                """, new MapSqlParameterSource("n", BATCH), (rs, i) -> new Due(rs.getObject("id", UUID.class),
                rs.getObject("author_id", UUID.class), rs.getObject("community_id", UUID.class), rs.getString("payload"))));
        if (due == null) {
            return 0;
        }
        for (Due d : due) {
            try {
                Post created = requiresNew.execute(status -> {
                    requireCommunityStillActive(d.communityId());
                    return postService.create(d.authorId(), d.communityId(), json.readValue(d.payload(), CreatePostRequest.class),
                                "scheduled-" + d.id());
                });
                markDone(d.id(), "published", null, created == null ? null : created.getId());
            } catch (RuntimeException e) {
                log.warn("scheduled post {} failed to publish: {}", d.id(), e.getMessage());
                markDone(d.id(), "failed", truncate(e.getMessage()), null);
            }
        }
        return due.size();
    }

    // Re-checks the community immediately before publishing (it may have been deleted since the post was scheduled), inside the
    // same transaction that publishes. FOR SHARE makes it race-proof: a concurrent delete either committed first (we see
    // deleted_at and refuse) or has to wait for this transaction to finish before its UPDATE can proceed. Refusing throws, so the
    // caller records the row as failed with this reason, the existing publish-time-failure path; no post is created.
    private void requireCommunityStillActive(UUID communityId) {
        List<Integer> active = jdbc.query("SELECT 1 FROM communities WHERE id = :c AND deleted_at IS NULL FOR SHARE",
                new MapSqlParameterSource("c", communityId), (rs, i) -> rs.getInt(1));
        if (active.isEmpty()) {
            throw new NotFoundException("community was deleted");
        }
    }

    private void markDone(UUID id, String status, String error, UUID postId) {
        requiresNew.executeWithoutResult(s -> jdbc.update("""
                UPDATE scheduled_posts SET status = :s, error = :e, post_id = :p WHERE id = :id AND status = 'pending'
                """, new MapSqlParameterSource().addValue("s", status).addValue("e", error).addValue("p", postId).addValue("id", id)));
    }

    private static String truncate(String s) {
        return s == null ? "unknown error" : s.length() > 300 ? s.substring(0, 300) : s;
    }
}
