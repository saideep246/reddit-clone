package com.redditclone.community;

import com.redditclone.common.exception.BadRequestException;
import com.redditclone.common.paging.Cursor;
import com.redditclone.common.paging.CursorCodec;
import com.redditclone.common.paging.Listing;
import com.redditclone.common.paging.RankCursor;
import com.redditclone.common.paging.RankCursorCodec;
import com.redditclone.common.paging.Thing;
import com.redditclone.community.dto.CommunityRule;
import com.redditclone.community.dto.CreateCommunityRequest;
import com.redditclone.community.dto.DeleteCommunityRequest;
import com.redditclone.community.dto.SetFlairRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/r")
public class CommunityController {

    private static final String COMMUNITY_KIND = "t5"; // Reddit's own kind code for a subreddit
    private static final int PAGE_SIZE = 25;

    private final CommunityService communities;

    public CommunityController(CommunityService communities) {
        this.communities = communities;
    }

    @PostMapping
    public Community create(@AuthenticationPrincipal UUID userId, @Valid @RequestBody CreateCommunityRequest req) {
        return communities.create(userId, req.name(), req.description(), req.type());
    }

    // Soft-deletes the community; only its creator may. 204 on success; 400 if confirmName is missing or wrong, 403 if the caller
    // is not the creator, 404 if the community does not exist or is already deleted.
    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal UUID userId, @PathVariable String name,
                       @Valid @RequestBody DeleteCommunityRequest req) {
        communities.deleteCommunity(userId, communities.findByName(name).getId(), req.confirmName());
    }

    @PostMapping("/{name}/subscribe")
    public void subscribe(@AuthenticationPrincipal UUID userId, @PathVariable String name) {
        communities.join(userId, communities.findByName(name).getId());
    }

    @DeleteMapping("/{name}/subscribe")
    public void unsubscribe(@AuthenticationPrincipal UUID userId, @PathVariable String name) {
        communities.leave(userId, communities.findByName(name).getId());
    }

    @PatchMapping("/{name}/me/flair")
    public void setOwnFlair(@AuthenticationPrincipal UUID userId, @PathVariable String name, @RequestBody SetFlairRequest req) {
        communities.setOwnFlair(userId, communities.findByName(name).getId(), req.flairId());
    }

    @GetMapping("/{name}/rules")
    public List<CommunityRule> rules(@PathVariable String name) {
        return communities.getRules(communities.findByName(name).getId());
    }

    @PostMapping("/{name}/join-requests")
    public void requestToJoin(@AuthenticationPrincipal UUID userId, @PathVariable String name) {
        communities.requestToJoin(userId, communities.findByName(name).getId());
    }

    // Public, every community regardless of type — existence/description/subscriber count are metadata,
    // the same category GET /flairs/GET /rules/GET /pinned already treat as public even for private
    // communities. No path collision with PostController's /r/{communityName}/search: that pattern needs
    // a community-name segment AND a trailing /search segment, this is /r/ followed by the single literal
    // segment "search".
    @GetMapping("/search")
    public List<Community> search(@AuthenticationPrincipal UUID viewerId, @RequestParam("q") String query) {
        List<Community> results = communities.searchByName(query);
        communities.attachViewerContextBatch(results, viewerId);
        return results;
    }

    // The caller's own joined communities, for the Manage communities page. Authenticated (not in the permit-all list), so a
    // missing token is a 401. "/mine" is a single literal segment after /r/, like "/search" above, so it cannot collide with any
    // /{name}/... route.
    @GetMapping("/mine")
    public List<Community> mine(@AuthenticationPrincipal UUID userId) {
        List<Community> joined = communities.listJoined(userId);
        communities.attachViewerContextBatch(joined, userId);
        return joined;
    }

    // sort=popular (default) keyed on subscriber_count, sort=new keyed on created_at — reuses
    // RankCursor/RankCursorCodec and Cursor/CursorCodec rather than inventing new pagination machinery,
    // the same two cursor shapes PostController's own multi-sort feeds already share.
    @GetMapping
    public Listing<Community> browse(@AuthenticationPrincipal UUID viewerId, @RequestParam(required = false) String after,
                                      @RequestParam(name = "sort", required = false, defaultValue = "popular") String sort) {
        List<Community> page;
        String next;
        if ("new".equals(sort)) {
            Cursor cursor = CursorCodec.decode(after);
            page = communities.browseNew(cursor.createdAt(), cursor.id(), PAGE_SIZE);
            next = page.isEmpty() ? null : CursorCodec.encode(page.getLast().getCreatedAt(), page.getLast().getId());
        } else if ("popular".equals(sort)) {
            RankCursor cursor = RankCursorCodec.decode(after, "popular");
            page = communities.browsePopular(cursor.rank(), cursor.id(), PAGE_SIZE);
            next = page.isEmpty() ? null
                    : RankCursorCodec.encode("popular", page.getLast().getSubscriberCount(), page.getLast().getId(), null);
        } else {
            throw new BadRequestException("invalid sort");
        }
        communities.attachViewerContextBatch(page, viewerId);
        List<Thing<Community>> children = page.stream().map(c -> new Thing<>(COMMUNITY_KIND, c)).toList();
        return Listing.of(children, next);
    }

    @GetMapping("/{name}/about")
    public Community about(@AuthenticationPrincipal UUID viewerId, @PathVariable String name) {
        Community c = communities.findByName(name);
        communities.attachViewerContext(c, viewerId);
        return c;
    }
}
