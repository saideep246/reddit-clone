package com.redditclone.comment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import org.hibernate.annotations.ColumnTransformer;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "comments")
public class Comment {

    @Id
    private UUID id;

    @Column(name = "post_id", nullable = false)
    private UUID postId;

    @Column(name = "parent_id")
    private UUID parentId; // null = top-level

    // ltree labels only allow [A-Za-z0-9_] — CommentService strips a UUID's hyphens before using it as a
    // path label. Two things had to be verified against a live insert, not assumed from the doc's snippet:
    // (1) @JdbcTypeCode(SqlTypes.OTHER) binds via setObject(Types.OTHER), which pgjdbc sends as `bytea`
    // over the wire regardless of INSERT vs SELECT context ("column path is of type ltree but expression
    // is of type bytea") — same failure citext hit, fixed the same way (plain String, columnDefinition
    // only, no JdbcTypeCode). (2) Unlike citext, ltree has no implicit/assignment cast from varchar
    // ("...but expression is of type character varying"), so plain String binding alone still isn't
    // enough — @ColumnTransformer's write expression wraps the bind parameter in an explicit ::ltree cast,
    // which Postgres accepts.
    @ColumnTransformer(write = "?::ltree")
    @Column(nullable = false, columnDefinition = "ltree")
    private String path;

    @Column(nullable = false)
    private short depth;

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    // Populated by CommentService.attachAuthorUsernames(), same pattern/placement as Post.authorUsername.
    @Transient
    private String authorUsername;

    @Column(nullable = false)
    private String body;

    @Column(nullable = false)
    private int score = 0;

    @Column(nullable = false)
    private int ups = 0;

    @Column(nullable = false)
    private int downs = 0;

    @Column(name = "best_rank", nullable = false)
    private double bestRank = 0;

    @Column(name = "controversial_rank", nullable = false)
    private double controversialRank = 0;

    @Column(name = "child_count", nullable = false)
    private int childCount = 0;

    // Moderator-pinned top-level comment, shown first in its thread (see CommentService.findCommentTree).
    @Column(nullable = false)
    private boolean sticky = false;

    // "moderator" when a moderator posted this in an official capacity, else null.
    private String distinguished;

    @Column(nullable = false)
    private boolean removed;

    @Column(nullable = false)
    private boolean deleted;

    // Full-row saves from a stale snapshot must fail, not silently rewrite columns a concurrent
    // moderator removal or author delete already changed.
    @Version
    private int version;

    @Column(name = "edited_at")
    private Instant editedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getPostId() {
        return postId;
    }

    public void setPostId(UUID postId) {
        this.postId = postId;
    }

    public UUID getParentId() {
        return parentId;
    }

    public void setParentId(UUID parentId) {
        this.parentId = parentId;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public short getDepth() {
        return depth;
    }

    public void setDepth(short depth) {
        this.depth = depth;
    }

    public UUID getAuthorId() {
        return authorId;
    }

    public void setAuthorId(UUID authorId) {
        this.authorId = authorId;
    }

    public String getAuthorUsername() {
        return authorUsername;
    }

    public void setAuthorUsername(String authorUsername) {
        this.authorUsername = authorUsername;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public int getScore() {
        return score;
    }

    // No setter: score/ups/downs/best_rank are exclusively mutated via CommentService.applyVoteDeltas's
    // raw-SQL bulk update, same reasoning as childCount — Hibernate still hydrates these fields on read
    // via its own field access, no setter needed for that.

    public int getUps() {
        return ups;
    }

    public int getDowns() {
        return downs;
    }

    public double getBestRank() {
        return bestRank;
    }

    public double getControversialRank() {
        return controversialRank;
    }

    public boolean isSticky() {
        return sticky;
    }

    public void setSticky(boolean sticky) {
        this.sticky = sticky;
    }

    public String getDistinguished() {
        return distinguished;
    }

    public void setDistinguished(String distinguished) {
        this.distinguished = distinguished;
    }

    public int getChildCount() {
        return childCount;
    }

    // No setter: childCount is exclusively mutated via CommentRepository.incrementChildCount's atomic
    // bulk UPDATE. A setter here would invite calling it on a stale loaded entity and silently
    // clobbering that update on the next dirty-checking flush.

    public boolean isRemoved() {
        return removed;
    }

    public void setRemoved(boolean removed) {
        this.removed = removed;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    public Instant getEditedAt() {
        return editedAt;
    }

    public void setEditedAt(Instant editedAt) {
        this.editedAt = editedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
