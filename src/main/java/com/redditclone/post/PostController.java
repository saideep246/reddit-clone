package com.redditclone.post;

import com.redditclone.common.exception.BadRequestException;
import com.redditclone.common.paging.Cursor;
import com.redditclone.common.paging.CursorCodec;
import com.redditclone.common.paging.Listing;
import com.redditclone.common.paging.RankCursor;
import com.redditclone.common.paging.RankCursorCodec;
import com.redditclone.common.paging.Thing;
import com.redditclone.community.CommunityService;
import com.redditclone.community.Flair;
import com.redditclone.post.dto.CreatePostRequest;
import com.redditclone.post.dto.EditPostRequest;
import com.redditclone.post.dto.PollVoteRequest;
import com.redditclone.post.dto.PollView;
import com.redditclone.post.dto.PostEditView;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.ToDoubleFunction;

@RestController
@RequestMapping("/r/{communityName}")
public class PostController {

    private static final String POST_KIND = "t3";
    private static final int PAGE_SIZE = 25;

    private final PostService postService;
    private final CommunityService communityService;
    private final FeedCacheService feedCache;
    private final ObjectMapper json;

    public PostController(PostService postService, CommunityService communityService,
                           FeedCacheService feedCache, ObjectMapper json) {
        this.postService = postService;
        this.communityService = communityService;
        this.feedCache = feedCache;
        this.json = json;
    }

    @PostMapping("/submit")
    public Post submit(@AuthenticationPrincipal UUID userId, @PathVariable String communityName,
                        @Valid @RequestBody CreatePostRequest req,
                        @RequestHeader("Idempotency-Key") String idempotencyKey) {
        UUID communityId = communityService.findByName(communityName).getId();
        return postService.create(userId, communityId, req, idempotencyKey);
    }

    @PatchMapping("/posts/{postId}")
    public Post editPost(@AuthenticationPrincipal UUID userId, @PathVariable String communityName,
                          @PathVariable UUID postId, @Valid @RequestBody EditPostRequest req) {
        UUID communityId = communityService.findByName(communityName).getId();
        return postService.edit(userId, communityId, postId, req.body(), req.title(), req.url());
    }

    @GetMapping("/posts/{postId}/poll")
    public PollView poll(@AuthenticationPrincipal UUID viewerId, @PathVariable String communityName, @PathVariable UUID postId) {
        return postService.getPoll(viewerId, communityService.findByName(communityName).getId(), postId);
    }

    @PostMapping("/posts/{postId}/poll/vote")
    public PollView votePoll(@AuthenticationPrincipal UUID userId, @PathVariable String communityName,
                              @PathVariable UUID postId, @Valid @RequestBody PollVoteRequest req) {
        return postService.votePoll(userId, communityService.findByName(communityName).getId(), postId, req.optionId());
    }

    @GetMapping("/posts/{postId}/history")
    public List<PostEditView> postHistory(@AuthenticationPrincipal UUID userId, @PathVariable String communityName,
                                          @PathVariable UUID postId) {
        UUID communityId = communityService.findByName(communityName).getId();
        return postService.history(userId, communityId, postId);
    }

    @DeleteMapping("/posts/{postId}")
    public void deletePost(@AuthenticationPrincipal UUID userId, @PathVariable String communityName,
                            @PathVariable UUID postId) {
        UUID communityId = communityService.findByName(communityName).getId();
        postService.delete(userId, communityId, postId);
    }

    @GetMapping("/new")
    public Listing<Post> listNew(@AuthenticationPrincipal UUID viewerId, @PathVariable String communityName,
                                  @RequestParam(required = false) String after) {
        Cursor cursor = CursorCodec.decode(after);
        List<Post> page;
        if (isAllFeed(communityName)) {
            page = postService.findNewAllPage(cursor.createdAt(), cursor.id(), viewerId, PAGE_SIZE);
        } else {
            UUID communityId = communityService.findByName(communityName).getId();
            communityService.requireViewAccess(viewerId, communityId);
            page = postService.findNewPage(communityId, cursor.createdAt(), cursor.id(), viewerId, PAGE_SIZE);
        }

        List<Thing<Post>> children = page.stream().map(p -> new Thing<>(POST_KIND, p)).toList();
        String next = page.isEmpty() ? null
                : CursorCodec.encode(page.getLast().getCreatedAt(), page.getLast().getId());
        return Listing.of(children, next);
    }

    // "all" is a reserved pseudo-community name (CommunityService.create rejects a real community ever
    // using it) meaning "every community, sitewide" — reddit.com's own r/all convention. Routing it
    // through the same /r/{communityName}/{sort} paths means the frontend's feed-fetching code needs zero
    // special-casing between the home feed and a real community's feed (see F2's plan).
    private boolean isAllFeed(String communityName) {
        return "all".equalsIgnoreCase(communityName);
    }

    // Page 1 only (no `after`) is cache-eligible — see FeedCacheService. Uniformly returns a raw JSON
    // string (via ResponseEntity) for both the cache-hit and freshly-computed paths, rather than
    // deserializing a cached body back into a Listing<Post> only to reserialize it identically.
    // The cache is per-community, not per-viewer, so it's fundamentally incompatible with a per-viewer
    // hidden-items filter — only used at all when viewerId == null (unauthenticated); an authenticated
    // request (which may have a hide-list) always computes fresh.
    @GetMapping("/hot")
    public ResponseEntity<String> listHot(@AuthenticationPrincipal UUID viewerId, @PathVariable String communityName,
                                           @RequestParam(required = false) String after) {
        // requireViewAccess runs before any cache read/write, not after: for a private community an
        // anonymous (viewerId == null) request is always rejected here, before ever touching the cache —
        // so a private community's /hot is never cached from an unapproved request, and an approved
        // member's viewerId != null already bypasses the cache under the existing hidden-items rule below.
        // No new caching-leak path is introduced by adding this check at this exact position.
        boolean isAll = isAllFeed(communityName);
        UUID communityId = null;
        if (!isAll) {
            communityId = communityService.findByName(communityName).getId();
            communityService.requireViewAccess(viewerId, communityId);
        }
        boolean firstPage = after == null || after.isBlank();
        if (firstPage && viewerId == null) {
            // Keyed purely by the communityName string (FeedCacheService.key) — "all" gets its own cache
            // entry (feed:hot:all) with zero changes to that service, matching real Reddit's own heavy
            // caching of r/all and r/popular.
            Optional<String> cached = feedCache.getHotPage(communityName);
            if (cached.isPresent()) {
                return jsonResponse(cached.get());
            }
        }
        RankCursor cursor = RankCursorCodec.decode(after, "hot");
        List<Post> page = isAll
                ? postService.findHotAllPage(cursor.rank(), cursor.id(), viewerId, PAGE_SIZE)
                : postService.findHotPage(communityId, cursor.rank(), cursor.id(), viewerId, PAGE_SIZE);
        Listing<Post> listing = rankListing("hot", page, Post::getHotRank, null);
        String body = json.writeValueAsString(listing);
        if (firstPage && viewerId == null) {
            feedCache.putHotPage(communityName, body);
        }
        return jsonResponse(body);
    }

    private ResponseEntity<String> jsonResponse(String body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }

    // Public — a community's flair list is needed to render the submit-post flair picker, or a self-assign
    // user-flair picker, before the caller has necessarily even logged in. type is optional; when present
    // it must be "post" or "user", matching the flairs.type CHECK constraint.
    @GetMapping("/flairs")
    public List<Flair> flairs(@PathVariable String communityName, @RequestParam(required = false) String type) {
        if (type != null && !type.equals("user") && !type.equals("post")) {
            throw new BadRequestException("invalid flair type");
        }
        UUID communityId = communityService.findByName(communityName).getId();
        return communityService.listFlairs(communityId, type);
    }

    // Public, small, uncapped-pagination list — deliberately not folded into /new or /hot's own sort order
    // (see the pin/lock feature's plan: doing so would require converting those queries' proven JPQL
    // keyset pagination to native SQL for a 3-column tuple cursor comparison, real risk for cosmetic gain).
    @GetMapping("/pinned")
    public List<Post> pinned(@PathVariable String communityName) {
        UUID communityId = communityService.findByName(communityName).getId();
        return postService.findPinned(communityId);
    }

    // No pagination — a relevance ranking (ts_rank) isn't a stable keyset sort key, see PostService.search.
    @GetMapping("/search")
    public Listing<Post> search(@AuthenticationPrincipal UUID viewerId, @PathVariable String communityName,
                                 @RequestParam("q") String query) {
        List<Post> results;
        if (isAllFeed(communityName)) {
            results = postService.searchAll(query, viewerId);
        } else {
            UUID communityId = communityService.findByName(communityName).getId();
            communityService.requireViewAccess(viewerId, communityId);
            results = postService.search(communityId, query);
        }
        List<Thing<Post>> children = results.stream().map(p -> new Thing<>(POST_KIND, p)).toList();
        return Listing.of(children, null);
    }

    // t = hour|day|week|month|year|all (default all), matching Reddit's own /top query param. The cutoff
    // is anchored to the instant page 1 was requested and carried forward in the cursor (see RankCursor's
    // anchorEpochSecond) rather than recomputed from Instant.now() on every page — otherwise a client
    // paging over several minutes gets a moving window that can skip or duplicate rows at the boundary.
    @GetMapping("/top")
    public Listing<Post> listTop(@AuthenticationPrincipal UUID viewerId, @PathVariable String communityName,
                                  @RequestParam(required = false) String after,
                                  @RequestParam(name = "t", required = false, defaultValue = "all") String period) {
        RankCursor cursor = RankCursorCodec.decode(after, "top");
        Instant anchor = cursor.anchorEpochSecond() != null
                ? Instant.ofEpochSecond(cursor.anchorEpochSecond())
                : Instant.now();
        Instant since = periodCutoff(period, anchor);
        List<Post> page;
        if (isAllFeed(communityName)) {
            page = postService.findTopAllPage(since, cursor.rank(), cursor.id(), viewerId, PAGE_SIZE);
        } else {
            UUID communityId = communityService.findByName(communityName).getId();
            communityService.requireViewAccess(viewerId, communityId);
            page = postService.findTopPage(communityId, since, cursor.rank(), cursor.id(), viewerId, PAGE_SIZE);
        }
        return rankListing("top", page, p -> (double) p.getScore(), anchor.getEpochSecond());
    }

    @GetMapping("/rising")
    public Listing<Post> listRising(@AuthenticationPrincipal UUID viewerId, @PathVariable String communityName,
                                     @RequestParam(required = false) String after) {
        RankCursor cursor = RankCursorCodec.decode(after, "rising");
        List<Post> page;
        if (isAllFeed(communityName)) {
            page = postService.findRisingAllPage(cursor.rank(), cursor.id(), viewerId, PAGE_SIZE);
        } else {
            UUID communityId = communityService.findByName(communityName).getId();
            communityService.requireViewAccess(viewerId, communityId);
            page = postService.findRisingPage(communityId, cursor.rank(), cursor.id(), viewerId, PAGE_SIZE);
        }
        return rankListing("rising", page, Post::getRisingRank, null);
    }

    @GetMapping("/controversial")
    public Listing<Post> listControversial(@AuthenticationPrincipal UUID viewerId, @PathVariable String communityName,
                                            @RequestParam(required = false) String after) {
        RankCursor cursor = RankCursorCodec.decode(after, "controversial");
        List<Post> page;
        if (isAllFeed(communityName)) {
            page = postService.findControversialAllPage(cursor.rank(), cursor.id(), viewerId, PAGE_SIZE);
        } else {
            UUID communityId = communityService.findByName(communityName).getId();
            communityService.requireViewAccess(viewerId, communityId);
            page = postService.findControversialPage(communityId, cursor.rank(), cursor.id(), viewerId, PAGE_SIZE);
        }
        return rankListing("controversial", page, Post::getControversialRank, null);
    }

    private Listing<Post> rankListing(String sort, List<Post> page, ToDoubleFunction<Post> rankOf, Long anchorEpochSecond) {
        List<Thing<Post>> children = page.stream().map(p -> new Thing<>(POST_KIND, p)).toList();
        String next = page.isEmpty() ? null
                : RankCursorCodec.encode(sort, rankOf.applyAsDouble(page.getLast()), page.getLast().getId(), anchorEpochSecond);
        return Listing.of(children, next);
    }

    private Instant periodCutoff(String period, Instant anchor) {
        Duration window = switch (period) {
            case "hour" -> Duration.ofHours(1);
            case "day" -> Duration.ofDays(1);
            case "week" -> Duration.ofDays(7);
            case "month" -> Duration.ofDays(30);
            case "year" -> Duration.ofDays(365);
            case "all" -> null;
            default -> throw new BadRequestException("invalid period");
        };
        return window == null ? Instant.EPOCH : anchor.minus(window);
    }
}
