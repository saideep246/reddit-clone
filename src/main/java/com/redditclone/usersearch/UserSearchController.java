package com.redditclone.usersearch;

import com.redditclone.auth.dto.UserSearchHit;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

// Authenticated by the default anyRequest rule (anonymous callers get 401). Distinct from the public GET /user/search, which
// returns full public profiles for the search page and is unchanged.
@RestController
public class UserSearchController {

    private final UserSearchService search;

    public UserSearchController(UserSearchService search) {
        this.search = search;
    }

    @GetMapping("/api/users/search")
    public List<UserSearchHit> search(@AuthenticationPrincipal UUID userId, @RequestParam("q") String query,
                                      @RequestParam(value = "purpose", required = false) String purpose) {
        return search.search(userId, query, purpose);
    }
}
