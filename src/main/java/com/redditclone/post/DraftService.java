package com.redditclone.post;

import com.redditclone.common.UuidV7Generator;
import com.redditclone.common.exception.BadRequestException;
import com.redditclone.common.exception.NotFoundException;
import com.redditclone.post.dto.DraftView;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

// Per-user server-side drafts of the submit form. Deliberately schema-light (a JSON object) so the form can
// grow without migrations; the server only enforces ownership, "is an object", and a size/count cap.
@Service
public class DraftService {

    private static final int MAX_DRAFTS_PER_USER = 25;
    private static final int MAX_PAYLOAD_CHARS = 50_000;

    private final NamedParameterJdbcTemplate jdbc;
    private final UuidV7Generator ids;
    private final ObjectMapper json;

    public DraftService(NamedParameterJdbcTemplate jdbc, UuidV7Generator ids, ObjectMapper json) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.json = json;
    }

    public List<DraftView> list(UUID userId) {
        return jdbc.query("""
                SELECT id, community_name, payload::text AS payload, updated_at FROM post_drafts
                WHERE user_id = :u ORDER BY updated_at DESC
                """, new MapSqlParameterSource("u", userId),
                (rs, i) -> new DraftView(rs.getObject("id", UUID.class), rs.getString("community_name"),
                        json.readTree(rs.getString("payload")), rs.getTimestamp("updated_at").toInstant()));
    }

    @Transactional
    public DraftView create(UUID userId, String communityName, JsonNode payload) {
        String serialized = validate(payload);
        Integer count = jdbc.queryForObject("SELECT count(*) FROM post_drafts WHERE user_id = :u",
                new MapSqlParameterSource("u", userId), Integer.class);
        if (count != null && count >= MAX_DRAFTS_PER_USER) {
            throw new BadRequestException("you can keep at most " + MAX_DRAFTS_PER_USER + " drafts");
        }
        UUID id = ids.nextId();
        jdbc.update("INSERT INTO post_drafts (id, user_id, community_name, payload) VALUES (:id, :u, :c, CAST(:p AS jsonb))",
                new MapSqlParameterSource().addValue("id", id).addValue("u", userId)
                        .addValue("c", blankToNull(communityName)).addValue("p", serialized));
        return get(userId, id);
    }

    @Transactional
    public DraftView update(UUID userId, UUID draftId, String communityName, JsonNode payload) {
        String serialized = validate(payload);
        int changed = jdbc.update("""
                UPDATE post_drafts SET community_name = :c, payload = CAST(:p AS jsonb), updated_at = now()
                WHERE id = :id AND user_id = :u
                """, new MapSqlParameterSource().addValue("id", draftId).addValue("u", userId)
                .addValue("c", blankToNull(communityName)).addValue("p", serialized));
        if (changed == 0) {
            throw new NotFoundException("draft not found");
        }
        return get(userId, draftId);
    }

    @Transactional
    public void delete(UUID userId, UUID draftId) {
        if (jdbc.update("DELETE FROM post_drafts WHERE id = :id AND user_id = :u",
                new MapSqlParameterSource().addValue("id", draftId).addValue("u", userId)) == 0) {
            throw new NotFoundException("draft not found");
        }
    }

    private DraftView get(UUID userId, UUID draftId) {
        return list(userId).stream().filter(d -> d.id().equals(draftId)).findFirst()
                .orElseThrow(() -> new NotFoundException("draft not found"));
    }

    private String validate(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            throw new BadRequestException("payload must be a JSON object");
        }
        String serialized = json.writeValueAsString(payload);
        if (serialized.length() > MAX_PAYLOAD_CHARS) {
            throw new BadRequestException("draft is too large");
        }
        return serialized;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
