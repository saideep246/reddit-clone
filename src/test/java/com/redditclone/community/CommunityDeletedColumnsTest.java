package com.redditclone.community;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// V37 (community soft delete, step 1): the columns exist, the entity maps them, and nothing about existing behaviour
// changed. Runs against the local PostgreSQL like RedditCloneApplicationTests (start the docker compose stack first); the
// context load itself already proves Flyway validated every migration and Hibernate (ddl-auto: validate) accepted the
// entity against the schema. Each test runs in a rolled-back transaction, so no rows are left behind.
@SpringBootTest
@Transactional
class CommunityDeletedColumnsTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CommunityRepository communities;
    @Autowired
    private EntityManager em;
    @Autowired
    private ObjectMapper json;

    private UUID user;

    @BeforeEach
    void createUser() {
        user = UUID.randomUUID();
        String name = "cdel_" + user.toString().substring(0, 8);
        jdbc.update("INSERT INTO users (id, username, email, password_hash) VALUES (?, ?, ?, 'x')",
                user, name, name + "@example.com");
    }

    private Community newCommunity() {
        Community c = new Community();
        c.setId(UUID.randomUUID());
        c.setName("cd_" + c.getId().toString().substring(0, 8));
        c.setCreatorId(user);
        return c;
    }

    // ---- the migration ----

    @Test
    void migration37WasAppliedSuccessfully() {
        Boolean success = jdbc.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '37'", Boolean.class);
        assertEquals(Boolean.TRUE, success);
    }

    @Test
    void theTwoColumnsExistAsNullableTimestamptzAndUuid() {
        List<String> columns = jdbc.queryForList("""
                SELECT column_name || ':' || data_type || ':' || is_nullable || ':' || coalesce(column_default, '-')
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'communities' AND column_name IN ('deleted_at', 'deleted_by')
                ORDER BY column_name""", String.class);
        assertEquals(List.of("deleted_at:timestamp with time zone:YES:-", "deleted_by:uuid:YES:-"), columns);
    }

    @Test
    void noStatusColumnWasAdded() {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'communities' AND column_name = 'status'""", Integer.class);
        assertEquals(0, n);
    }

    @Test
    void deletedByIsAForeignKeyToUsersAndTheConsistencyCheckExists() {
        String fk = jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conrelid = 'communities'::regclass AND contype = 'f' AND conkey = ARRAY[
                    (SELECT attnum FROM pg_attribute WHERE attrelid = 'communities'::regclass AND attname = 'deleted_by')]::smallint[]""",
                String.class);
        assertEquals("FOREIGN KEY (deleted_by) REFERENCES users(id)", fk);
        String check = jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conrelid = 'communities'::regclass AND conname = 'communities_deleted_consistency'""", String.class);
        assertTrue(check.contains("deleted_at IS NULL") && check.contains("deleted_by IS NULL"), check);
    }

    @Test
    void thePartitionedTablesWereNotTouched() {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name IN ('reports', 'moderation_actions')
                  AND column_name IN ('deleted_at', 'deleted_by')""", Integer.class);
        assertEquals(0, n);
    }

    // ---- the entity ----

    @Test
    void aNewCommunityHasBothColumnsNullAsBefore() {
        Community c = newCommunity();
        communities.saveAndFlush(c);

        assertNull(jdbc.queryForObject("SELECT deleted_at FROM communities WHERE id = ?", Object.class, c.getId()));
        assertNull(jdbc.queryForObject("SELECT deleted_by FROM communities WHERE id = ?", Object.class, c.getId()));
        em.clear();
        Community reloaded = communities.findById(c.getId()).orElseThrow();
        assertNull(reloaded.getDeletedAt());
        assertNull(reloaded.getDeletedBy());
    }

    @Test
    void deletedAtAndDeletedByPersistAndReadBack() {
        Instant when = Instant.now().truncatedTo(ChronoUnit.MICROS); // PostgreSQL keeps microseconds
        Community c = newCommunity();
        c.setDeletedAt(when);
        c.setDeletedBy(user);
        communities.saveAndFlush(c);
        em.clear(); // force a real read from the database, not the first-level cache

        Community reloaded = communities.findById(c.getId()).orElseThrow();
        assertEquals(when, reloaded.getDeletedAt());
        assertEquals(user, reloaded.getDeletedBy());
        assertEquals(when, jdbc.queryForObject("SELECT deleted_at FROM communities WHERE id = ?",
                java.sql.Timestamp.class, c.getId()).toInstant());
        assertEquals(user, jdbc.queryForObject("SELECT deleted_by FROM communities WHERE id = ?", UUID.class, c.getId()));
    }

    @Test
    void theDeletedFieldsCanBeClearedAgain() {
        Community c = newCommunity();
        c.setDeletedAt(Instant.now());
        c.setDeletedBy(user);
        communities.saveAndFlush(c);
        c.setDeletedAt(null);
        c.setDeletedBy(null);
        communities.saveAndFlush(c);
        em.clear();

        Community reloaded = communities.findById(c.getId()).orElseThrow();
        assertNull(reloaded.getDeletedAt());
        assertNull(reloaded.getDeletedBy());
    }

    // ---- the constraints (one violation per test: PostgreSQL aborts the transaction after a failed statement) ----

    @Test
    void deletedAtWithoutDeletedByIsRejected() {
        Community c = newCommunity();
        communities.saveAndFlush(c);
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE communities SET deleted_at = now() WHERE id = ?", c.getId()));
    }

    @Test
    void deletedByWithoutDeletedAtIsRejected() {
        Community c = newCommunity();
        communities.saveAndFlush(c);
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE communities SET deleted_by = ? WHERE id = ?", user, c.getId()));
    }

    @Test
    void deletedByMustBeAnExistingUser() {
        Community c = newCommunity();
        communities.saveAndFlush(c);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE communities SET deleted_at = now(), deleted_by = ? WHERE id = ?", UUID.randomUUID(), c.getId()));
    }

    // ---- nothing user-visible changed ----

    @Test
    void theNewFieldsNeverAppearInApiJson() throws Exception {
        Community c = newCommunity();
        c.setDeletedAt(Instant.now());
        c.setDeletedBy(user);

        String body = json.writeValueAsString(c);

        assertFalse(body.contains("deletedAt") || body.contains("deleted_at"), body);
        assertFalse(body.contains("deletedBy") || body.contains("deleted_by"), body);
        assertTrue(body.contains("\"name\""), "sanity: the community itself still serializes: " + body);
    }

    @Test
    void existingReadsAndTheNameUniquenessStillWork() {
        Community c = newCommunity();
        communities.saveAndFlush(c);

        assertTrue(communities.findByName(c.getName()).isPresent());
        assertTrue(communities.existsByName(c.getName().toUpperCase()), "name is still case-insensitive");
        assertNotNull(communities.findPopularPage(Integer.MAX_VALUE, new UUID(-1L, -1L), org.springframework.data.domain.Pageable.ofSize(1)));
    }
}
