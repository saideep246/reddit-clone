package com.redditclone.comment;

import com.redditclone.comment.dto.CommentEditView;
import com.redditclone.comment.dto.CommentSearchResult;
import com.redditclone.comment.dto.CommentView;
import com.redditclone.comment.dto.EditCommentRequest;
import com.redditclone.comment.dto.PostWithCommentsView;
import com.redditclone.comment.dto.ReplyRequest;
import com.redditclone.comment.dto.UserCommentView;
import com.redditclone.common.exception.NotFoundException;
import com.redditclone.common.paging.Cursor;
import com.redditclone.common.paging.CursorCodec;
import com.redditclone.common.paging.Listing;
import com.redditclone.common.paging.Thing;
import com.redditclone.community.CommunityService;
import com.redditclone.post.PostService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class CommentController {

    private final CommentService commentService;
    private final PostService postService;
    private final CommunityService communityService;

    public CommentController(CommentService commentService, PostService postService, CommunityService communityService) {
        this.commentService = commentService;
        this.postService = postService;
        this.communityService = communityService;
    }

    // API design lists comment creation as POST /api/comment (postId/parentId in the body); the class
    // reference in the source plan instead nests it under /r/{communityName}/comments/{postId} — the two
    // are inconsistent, so this follows the API design table since it's the documented public contract.
    @PostMapping("/api/comment")
    public Comment reply(@AuthenticationPrincipal UUID userId, @Valid @RequestBody ReplyRequest req) {
        return commentService.reply(userId, req.postId(), req.parentId(), req.body());
    }

    @PatchMapping("/api/comment/{commentId}")
    public CommentView editComment(@AuthenticationPrincipal UUID userId, @PathVariable UUID commentId,
                                    @Valid @RequestBody EditCommentRequest req) {
        return commentService.editBody(userId, commentId, req.body());
    }

    // Public like the post search. `all` is the sitewide pseudo-community (see PostController.isAllFeed).
    @GetMapping("/r/{communityName}/search/comments")
    public List<CommentSearchResult> searchComments(@AuthenticationPrincipal UUID viewerId,
                                                     @PathVariable String communityName, @RequestParam("q") String query) {
        if ("all".equalsIgnoreCase(communityName)) {
            return commentService.search(null, query, viewerId);
        }
        UUID communityId = communityService.findByName(communityName).getId();
        communityService.requireViewAccess(viewerId, communityId);
        return commentService.search(communityId, query, viewerId);
    }

    @GetMapping("/api/comment/{commentId}/history")
    public List<CommentEditView> commentHistory(@AuthenticationPrincipal UUID userId, @PathVariable UUID commentId) {
        return commentService.history(userId, commentId);
    }

    @DeleteMapping("/api/comment/{commentId}")
    public void deleteComment(@AuthenticationPrincipal UUID userId, @PathVariable UUID commentId) {
        commentService.delete(userId, commentId);
    }

    @GetMapping("/r/{communityName}/comments/{postId}")
    public PostWithCommentsView getPostWithComments(@AuthenticationPrincipal UUID viewerId,
                                                      @PathVariable String communityName, @PathVariable UUID postId,
                                                      @RequestParam(required = false, defaultValue = "best") String sort,
                                                      @RequestParam(required = false) String after) {
        UUID communityId = communityService.findByName(communityName).getId();
        communityService.requireViewAccess(viewerId, communityId);
        var post = postService.findByIdWithMedia(postId);
        // The URL's communityName must actually own this post, and a removed post is hidden here the
        // same way it's hidden from /new — otherwise the community segment is decorative and "removed"
        // content stays readable by ID.
        if (post.isRemoved() || !post.getCommunityId().equals(communityId)) {
            throw new NotFoundException("post not found");
        }
        var comments = commentService.findCommentTree(postId, viewerId, sort, after);
        return new PostWithCommentsView(post, comments);
    }

    // No communityName in this route at all — findMoreChildren looks the post's community up itself via
    // postId for the view-access check, the same shape as CommentService.reply already does for the same
    // reason (it needs communityId anyway, so a second path segment would be pure redundancy here).
    @GetMapping("/api/morechildren")
    public Listing<CommentView> moreChildren(@AuthenticationPrincipal UUID viewerId,
                                              @RequestParam UUID postId, @RequestParam UUID parentId,
                                              @RequestParam String sort, @RequestParam(required = false) String after) {
        return commentService.findMoreChildren(postId, parentId, viewerId, sort, after);
    }

    private static final String COMMENT_KIND = "t1";
    private static final int PROFILE_PAGE_SIZE = 25;

    // A user's "comments" profile tab (F7). No class-level @RequestMapping on this controller, so a
    // /user/{username}/... route sits alongside /api/comment and /r/{communityName}/comments/{postId}
    // without conflict.
    @GetMapping("/user/{username}/comments")
    public Listing<UserCommentView> userComments(@AuthenticationPrincipal UUID viewerId, @PathVariable String username,
                                                  @RequestParam(required = false) String after) {
        Cursor cursor = CursorCodec.decode(after);
        List<UserCommentView> page = commentService.findByAuthor(username, cursor.createdAt(), cursor.id(),
                viewerId, PROFILE_PAGE_SIZE);

        List<Thing<UserCommentView>> children = page.stream().map(c -> new Thing<>(COMMENT_KIND, c)).toList();
        String next = page.isEmpty() ? null
                : CursorCodec.encode(page.getLast().createdAt(), page.getLast().id());
        return Listing.of(children, next);
    }
}
