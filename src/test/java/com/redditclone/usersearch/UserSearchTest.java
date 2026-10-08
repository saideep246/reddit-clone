package com.redditclone.usersearch;

import com.jayway.jsonpath.JsonPath;
import com.redditclone.auth.JwtService;
import com.redditclone.auth.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// GET /api/users/search: the shared picker search. Shared Spring context, rolled-back transactions. Each test builds its names
// from a random token so it can't collide with other rows in the local database.
@SpringBootTest
@Transactional
class UserSearchTest {

    @Autowired private WebApplicationContext webContext;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwt;
    @Autowired private UserRepository users;

    private MockMvc mvc;
    private String t; // random token, lowercase letters only

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        StringBuilder sb = new StringBuilder("q");
        for (char c : UUID.randomUUID().toString().replace("-", "").substring(0, 8).toCharArray()) {
            sb.append(Character.isDigit(c) ? (char) ('g' + (c - '0')) : c);
        }
        t = sb.toString();
    }

    private UUID user(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, username, email, password_hash) VALUES (?, ?, ?, 'x')", id, name, name + "@example.com");
        return id;
    }

    private String token(UUID id) {
        return "Bearer " + jwt.generateAccessToken(users.findById(id).orElseThrow());
    }

    private List<String> names(UUID caller, String q, String purpose) throws Exception {
        String url = "/api/users/search?q=" + q + (purpose == null ? "" : "&purpose=" + purpose);
        String body = mvc.perform(get(url).header("Authorization", token(caller))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$[*].username");
    }

    @Test
    void ranksExactThenPrefixThenSubstring() throws Exception {
        UUID caller = user("caller_" + t);
        user("aa_" + t);         // substring
        user(t + "zz");          // prefix
        user(t);                 // exact
        user("bb" + t + "yy");   // substring
        assertEquals(List.of(t, t + "zz"), names(caller, t, null).subList(0, 2));
        List<String> all = names(caller, t, null);
        assertEquals(4, all.size(), "four other users match; the caller (whose name also contains the token) is excluded");
        assertTrue(all.indexOf(t + "zz") < all.indexOf("aa_" + t));
        assertTrue(all.indexOf(t + "zz") < all.indexOf("bb" + t + "yy"));
    }

    @Test
    void isCaseInsensitiveBothWays() throws Exception {
        UUID caller = user("c_" + t);
        user(t + "Mixed");
        assertTrue(names(caller, t.toUpperCase(), null).contains(t + "Mixed"));
        assertTrue(names(caller, (t + "MIXED").toLowerCase(), null).contains(t + "Mixed"));
    }

    @Test
    void returnsAtMostTenAndExcludesTheCaller() throws Exception {
        UUID caller = user(t + "_me");
        for (int i = 0; i < 14; i++) {
            user(t + "n" + String.format("%02d", i));
        }
        List<String> found = names(caller, t, null);
        assertEquals(10, found.size());
        assertFalse(found.contains(t + "_me"), "the caller never sees themselves");
    }

    @Test
    void onlyActiveUsersAreSearchable() throws Exception {
        UUID caller = user("c2_" + t);
        user(t + "active");
        UUID banned = user(t + "banned");
        UUID deleted = user(t + "deleted");
        jdbc.update("UPDATE users SET status = 'banned' WHERE id = ?", banned);
        jdbc.update("UPDATE users SET status = 'deleted' WHERE id = ?", deleted);
        List<String> found = names(caller, t, null);
        assertTrue(found.contains(t + "active"));
        assertFalse(found.contains(t + "banned"));
        assertFalse(found.contains(t + "deleted"));
    }

    @Test
    void likeWildcardsInTheQueryMatchLiterally() throws Exception {
        UUID caller = user("c3_" + t);
        user(t + "_x");
        user(t + "zx");
        assertEquals(List.of(t + "_x"), names(caller, t + "_x", null));
        assertTrue(names(caller, "%25" + t, null).isEmpty(), "a percent sign is not a wildcard");
    }

    @Test
    void theQueryMustBeBetweenTwoAndThirtyTwoCharacters() throws Exception {
        UUID caller = user("c4_" + t);
        mvc.perform(get("/api/users/search?q=a").header("Authorization", token(caller))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/users/search?q={q}", "  a ").header("Authorization", token(caller))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/users/search?q=").header("Authorization", token(caller))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/users/search?q=" + "x".repeat(33)).header("Authorization", token(caller))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/users/search?q=zz").header("Authorization", token(caller))).andExpect(status().isOk());
        mvc.perform(get("/api/users/search?q=" + t + "&purpose=nonsense").header("Authorization", token(caller))).andExpect(status().isBadRequest());
    }

    @Test
    void theUMentionPrefixIsAccepted() throws Exception {
        UUID caller = user("c5_" + t);
        user(t + "ment");
        assertTrue(names(caller, "u/" + t, null).contains(t + "ment"));
    }

    @Test
    void resultsCarryOnlyIdAndUsername() throws Exception {
        UUID caller = user("c6_" + t);
        UUID other = user(t + "pub");
        jdbc.update("UPDATE users SET is_site_admin = true, email_verified_at = now(), karma_post = 99 WHERE id = ?", other);
        String body = mvc.perform(get("/api/users/search?q=" + t).header("Authorization", token(caller))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(other.toString())).andExpect(jsonPath("$[0].username").value(t + "pub"))
                .andReturn().getResponse().getContentAsString();
        java.util.Map<String, Object> row = JsonPath.read(body, "$[0]");
        assertEquals(Set.of("id", "username"), row.keySet());
        for (String secret : List.of("email", "password", "admin", "karma", "status", "verified")) {
            assertFalse(body.toLowerCase().contains(secret), secret + " must never appear in search output: " + body);
        }
    }

    @Test
    void anonymousCallersAreRefused() throws Exception {
        mvc.perform(get("/api/users/search?q=" + t)).andExpect(status().isUnauthorized());
    }

    @Test
    void chatSearchLeavesOutPeopleTheCallerBlockedButModeratorSearchDoesNot() throws Exception {
        UUID caller = user("c7_" + t);
        UUID blocked = user(t + "blocked");
        UUID blocker = user(t + "blocker");
        user(t + "fine");
        jdbc.update("INSERT INTO user_blocks (blocker_id, blocked_id) VALUES (?, ?)", caller, blocked);
        jdbc.update("INSERT INTO user_blocks (blocker_id, blocked_id) VALUES (?, ?)", blocker, caller);
        List<String> chat = names(caller, t, "chat");
        assertFalse(chat.contains(t + "blocked"));
        assertTrue(chat.contains(t + "fine"));
        assertTrue(chat.contains(t + "blocker"), "someone who blocked the caller is not hidden: chat creation decides that, so search leaks nothing");
        assertTrue(names(caller, t, "moderator").contains(t + "blocked"));
        assertTrue(names(caller, t, null).contains(t + "blocked"));
    }

    @Test
    void theSixtyFirstSearchInAMinuteIsRateLimited() throws Exception {
        UUID caller = user("c8_" + t);
        String auth = token(caller);
        for (int i = 0; i < 60; i++) {
            mvc.perform(get("/api/users/search?q=" + t).header("Authorization", auth)).andExpect(status().isOk());
        }
        mvc.perform(get("/api/users/search?q=" + t).header("Authorization", auth)).andExpect(status().isTooManyRequests());
        UUID someoneElse = user("c9_" + t);
        mvc.perform(get("/api/users/search?q=" + t).header("Authorization", token(someoneElse))).andExpect(status().isOk());
    }
}
