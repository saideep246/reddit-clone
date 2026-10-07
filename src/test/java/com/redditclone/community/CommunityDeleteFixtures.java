package com.redditclone.community;

import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

// Builds the users, communities and moderators the deletion tests need, using the real CommunityService.create (which also
// gives the creator their membership and full-permission moderator row, exactly as in production).
final class CommunityDeleteFixtures {

    static final int SETTINGS_ONLY = CommunityModerator.PERM_MANAGE_SETTINGS;
    static final int ALL_BITS = CommunityModerator.OWNER_PERMISSIONS;

    private final JdbcTemplate jdbc;
    private final CommunityService communityService;
    private final EntityManager em;

    CommunityDeleteFixtures(JdbcTemplate jdbc, CommunityService communityService, EntityManager em) {
        this.jdbc = jdbc;
        this.communityService = communityService;
        this.em = em;
    }

    UUID user(String prefix) {
        UUID id = UUID.randomUUID();
        String name = prefix + "_" + id.toString().substring(0, 8);
        jdbc.update("INSERT INTO users (id, username, email, password_hash) VALUES (?, ?, ?, 'x')", id, name, name + "@example.com");
        return id;
    }

    UUID siteAdmin() {
        UUID id = user("cdadmin");
        jdbc.update("UPDATE users SET is_site_admin = true WHERE id = ?", id);
        return id;
    }

    UUID bannedUser() {
        UUID id = user("cdbanned");
        jdbc.update("UPDATE users SET status = 'banned' WHERE id = ?", id);
        return id;
    }

    // create() writes through JPA; flushing here makes the rows visible to the plain JDBC statements the tests use.
    // (Inside a test transaction nothing reaches the database until a flush; the concurrency test commits for real.)
    Community community(UUID creator) {
        Community c = communityService.create(creator, "cdel_" + UUID.randomUUID().toString().substring(0, 8), "a test community", "public");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            em.flush(); // outside a test transaction create() has already committed
        }
        return c;
    }

    void moderator(UUID communityId, UUID userId, int permissions, UUID addedBy) {
        jdbc.update("INSERT INTO community_moderators (community_id, user_id, permissions, added_by) VALUES (?, ?, ?, ?)",
                communityId, userId, permissions, addedBy);
    }

    boolean isDeleted(UUID communityId) {
        return jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM communities WHERE id = ?", Boolean.class, communityId);
    }

    UUID deletedBy(UUID communityId) {
        return jdbc.queryForObject("SELECT deleted_by FROM communities WHERE id = ?", UUID.class, communityId);
    }

    int auditRows(UUID communityId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM moderation_actions WHERE community_id = ? AND action = 'delete_community'", Integer.class, communityId);
    }
}
