package com.redditclone.usersearch;

import com.redditclone.auth.AuthService;
import com.redditclone.auth.dto.UserSearchHit;
import com.redditclone.block.BlockService;
import com.redditclone.common.exception.BadRequestException;
import com.redditclone.common.ratelimit.RateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

// The one user search behind every "pick a person" control (the moderator-invite picker, the chat picker). PostgreSQL/pg_trgm
// today (see UserRepository.searchForPicker); callers depend only on this class, so another engine can replace the lookup
// later without touching them. Finding a user grants nothing: whether you may invite or message them is decided where that
// action happens, never here.
@Service
public class UserSearchService {

    public static final int MIN_QUERY_LENGTH = 2;
    public static final int MAX_QUERY_LENGTH = 32;
    public static final int MAX_RESULTS = 10;

    public enum Purpose {
        MODERATOR, CHAT;

        static Purpose parse(String value) {
            if (value == null || value.isBlank() || "moderator".equalsIgnoreCase(value)) {
                return MODERATOR;
            }
            if ("chat".equalsIgnoreCase(value)) {
                return CHAT;
            }
            throw new BadRequestException("unknown search purpose");
        }
    }

    private final AuthService auth;
    private final BlockService blocks;
    private final RateLimiter rateLimiter;
    private final int capacity;
    private final int periodMinutes;

    public UserSearchService(AuthService auth, BlockService blocks, RateLimiter rateLimiter,
                             @Value("${app.rate-limit.user-search.capacity}") int capacity,
                             @Value("${app.rate-limit.user-search.period-minutes}") int periodMinutes) {
        this.auth = auth;
        this.blocks = blocks;
        this.rateLimiter = rateLimiter;
        this.capacity = capacity;
        this.periodMinutes = periodMinutes;
    }

    public List<UserSearchHit> search(UUID callerId, String rawQuery, String purposeName) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.startsWith("u/")) {
            query = query.substring(2); // people type the mention form
        }
        if (query.length() < MIN_QUERY_LENGTH) {
            throw new BadRequestException("type at least " + MIN_QUERY_LENGTH + " characters to search");
        }
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new BadRequestException("search text is too long");
        }
        Purpose purpose = Purpose.parse(purposeName);
        rateLimiter.checkLimit("user-search", callerId.toString(), capacity, Duration.ofMinutes(periodMinutes));

        // Chat results leave out people the caller has blocked. Nobody else is hidden here: the existing chat rules still
        // decide at room creation (including a user who blocked the caller), so search reveals nothing about blocks.
        Set<UUID> excluded = purpose == Purpose.CHAT ? blocks.blockedIds(callerId) : Set.of();
        return auth.searchActiveUsers(callerId, query, excluded, MAX_RESULTS);
    }
}
