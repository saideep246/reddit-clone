package com.redditclone.vote;

import com.redditclone.comment.CommentService;
import com.redditclone.common.OutboxWriter;
import com.redditclone.common.exception.BadRequestException;
import com.redditclone.common.exception.NotFoundException;
import com.redditclone.comment.Comment;
import com.redditclone.community.CommunityService;
import com.redditclone.post.Post;
import com.redditclone.post.PostService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class VoteService {

    private final PostVoteRepository postVotes;
    private final CommentVoteRepository commentVotes;
    private final PostService postService;
    private final CommentService commentService;
    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;
    private final CommunityService communityService;

    public VoteService(PostVoteRepository postVotes, CommentVoteRepository commentVotes,
                        PostService postService, CommentService commentService,
                        JdbcTemplate jdbc, OutboxWriter outbox, CommunityService communityService) {
        this.postVotes = postVotes;
        this.commentVotes = commentVotes;
        this.postService = postService;
        this.commentService = commentService;
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.communityService = communityService;
    }

    // Votes are keyed by post/comment id, so they never pass through a community-name lookup: check the target's community
    // here. A vote (cast OR removal) is a write into the community, so a deleted community rejects both with 404.
    private void requireCommunityActive(UUID postId) {
        communityService.requireActive(postService.findById(postId).getCommunityId());
    }

    @Transactional
    public void castPostVote(UUID userId, UUID postId, short direction) {
        requireDirection(direction);
        Post post = postService.findById(postId); // 404s on a nonexistent post instead of creating an orphan vote
        if (post.isDeleted() || post.isRemoved()) {
            throw new NotFoundException("post not found");
        }
        communityService.requireActive(post.getCommunityId());
        lockVoteKey("post", userId, postId);
        Optional<PostVote> existing = postVotes.findById(new PostVoteId(userId, postId));
        if (existing.isPresent() && existing.get().getDirection() == direction) {
            return; // already voted this way — upsert would be a no-op, nothing for the worker to apply
        }
        Integer oldDirection = existing.map(v -> (int) v.getDirection()).orElse(null);
        postVotes.upsert(userId, postId, direction);
        outbox.writeEvent("post_vote_cast", new VoteEventPayload(postId, oldDirection, (int) direction));
    }

    @Transactional
    public void removePostVote(UUID userId, UUID postId) {
        requireCommunityActive(postId);
        lockVoteKey("post", userId, postId);
        PostVote existing = postVotes.findById(new PostVoteId(userId, postId))
                .orElseThrow(() -> new NotFoundException("vote not found"));
        postVotes.delete(existing); // unvoting deletes the row — no neutral "0" value
        outbox.writeEvent("post_vote_removed", new VoteEventPayload(postId, (int) existing.getDirection(), null));
    }

    @Transactional
    public void castCommentVote(UUID userId, UUID commentId, short direction) {
        requireDirection(direction);
        Comment comment = commentService.findById(commentId); // 404s on a nonexistent comment instead of creating an orphan vote
        if (comment.isDeleted() || comment.isRemoved()) {
            throw new NotFoundException("comment not found");
        }
        requireCommunityActive(comment.getPostId());
        lockVoteKey("comment", userId, commentId);
        Optional<CommentVote> existing = commentVotes.findById(new CommentVoteId(userId, commentId));
        if (existing.isPresent() && existing.get().getDirection() == direction) {
            return;
        }
        Integer oldDirection = existing.map(v -> (int) v.getDirection()).orElse(null);
        commentVotes.upsert(userId, commentId, direction);
        outbox.writeEvent("comment_vote_cast", new VoteEventPayload(commentId, oldDirection, (int) direction));
    }

    @Transactional
    public void removeCommentVote(UUID userId, UUID commentId) {
        requireCommunityActive(commentService.findById(commentId).getPostId());
        lockVoteKey("comment", userId, commentId);
        CommentVote existing = commentVotes.findById(new CommentVoteId(userId, commentId))
                .orElseThrow(() -> new NotFoundException("vote not found"));
        commentVotes.delete(existing);
        outbox.writeEvent("comment_vote_removed", new VoteEventPayload(commentId, (int) existing.getDirection(), null));
    }

    // Read by VoteController.myVotes so the frontend can render vote-arrow state correctly after a page
    // reload. Deliberately NOT exposed through PostService/Post itself — vote already depends on post
    // (castPostVote above calls postService.findById), so post depending back on vote to attach this would
    // create a cycle ModuleBoundaryTest.modules_are_free_of_cycles forbids. A separate endpoint here, with
    // the frontend composing the two responses client-side, keeps the one-directional boundary intact.
    public Map<UUID, Short> getMyPostVotes(UUID userId, Collection<UUID> postIds) {
        if (postIds.isEmpty()) {
            return Map.of();
        }
        return postVotes.findByUserIdAndPostIdIn(userId, postIds).stream()
                .collect(Collectors.toMap(PostVote::getPostId, PostVote::getDirection));
    }

    // Comment counterpart of getMyPostVotes above, same reasoning — needed now that F3's comment tree
    // renders vote arrows for comments too.
    public Map<UUID, Short> getMyCommentVotes(UUID userId, Collection<UUID> commentIds) {
        if (commentIds.isEmpty()) {
            return Map.of();
        }
        return commentVotes.findByUserIdAndCommentIdIn(userId, commentIds).stream()
                .collect(Collectors.toMap(CommentVote::getCommentId, CommentVote::getDirection));
    }

    private void requireDirection(short direction) {
        if (direction != 1 && direction != -1) {
            throw new BadRequestException("dir must be 1 or -1");
        }
    }

    // Postgres defaults to READ COMMITTED, and there's no existing row to SELECT ... FOR UPDATE on a
    // user's first-ever vote, so a plain findById-then-write is a TOCTOU race: two concurrent requests for
    // the same (userId, targetId) can both read "no vote yet" before either commits, and both would then
    // write an outbox event double-applying the score/karma delta. A transaction-scoped advisory lock
    // serializes concurrent calls for the same key (auto-released at commit/rollback) without needing a
    // row to lock, closing the race with no schema change.
    private void lockVoteKey(String kind, UUID userId, UUID targetId) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", Object.class, kind + ":" + userId + ":" + targetId);
    }
}
