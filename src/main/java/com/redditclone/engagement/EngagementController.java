package com.redditclone.engagement;

import com.redditclone.common.paging.Cursor;
import com.redditclone.common.paging.CursorCodec;
import com.redditclone.common.paging.Listing;
import com.redditclone.common.paging.Thing;
import com.redditclone.engagement.dto.TargetRequest;
import com.redditclone.post.Post;
import com.redditclone.post.PostService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class EngagementController {

    private static final String POST_KIND = "t3";
    private static final int PAGE_SIZE = 25;

    private final SavedItemService savedItems;
    private final HiddenItemService hiddenItems;
    private final PostService postService;

    public EngagementController(SavedItemService savedItems, HiddenItemService hiddenItems, PostService postService) {
        this.savedItems = savedItems;
        this.hiddenItems = hiddenItems;
        this.postService = postService;
    }

    @PostMapping("/api/save")
    public void save(@AuthenticationPrincipal UUID userId, @Valid @RequestBody TargetRequest req) {
        savedItems.save(userId, req.targetType(), req.targetId());
    }

    @DeleteMapping("/api/save")
    public void unsave(@AuthenticationPrincipal UUID userId, @Valid @RequestBody TargetRequest req) {
        savedItems.unsave(userId, req.targetType(), req.targetId());
    }

    // The viewer's own "Saved" page — same keyset-pagination/Listing shape as every other post listing,
    // sorted by most-recently-saved rather than most-recently-created.
    @GetMapping("/api/save")
    public Listing<Post> listSaved(@AuthenticationPrincipal UUID userId,
                                    @RequestParam(required = false) String after) {
        Cursor cursor = CursorCodec.decode(after);
        List<Post> page = postService.findSavedByUser(userId, cursor.createdAt(), cursor.id(), PAGE_SIZE);
        List<Thing<Post>> children = page.stream().map(p -> new Thing<>(POST_KIND, p)).toList();
        String next = page.isEmpty() ? null
                : CursorCodec.encode(savedItems.savedAtOf(userId, "post", page.getLast().getId()), page.getLast().getId());
        return Listing.of(children, next);
    }

    @PostMapping("/api/hide")
    public void hide(@AuthenticationPrincipal UUID userId, @Valid @RequestBody TargetRequest req) {
        hiddenItems.hide(userId, req.targetType(), req.targetId());
    }

    @DeleteMapping("/api/hide")
    public void unhide(@AuthenticationPrincipal UUID userId, @Valid @RequestBody TargetRequest req) {
        hiddenItems.unhide(userId, req.targetType(), req.targetId());
    }
}
