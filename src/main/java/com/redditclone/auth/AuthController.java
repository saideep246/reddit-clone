package com.redditclone.auth;

import com.redditclone.auth.dto.AuthResponse;
import com.redditclone.auth.dto.LoginRequest;
import com.redditclone.auth.dto.RegisterRequest;
import com.redditclone.common.ratelimit.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1")
public class AuthController {

    private static final String REFRESH_COOKIE = "refresh_token";

    // Local dev (http, same-site localhost) keeps Secure=false/SameSite=Strict. Production sets app.auth.cookie.* in
    // application-prod.yml: Secure=true (TLS terminates at the platform proxy) and, while the SPA and API sit on
    // different registrable domains (e.g. *.pages.dev -> *.onrender.com), SameSite=None, which browsers only accept
    // together with Secure. Behind a shared parent domain (app.example.com / api.example.com) Strict/Lax works again.

    private final AuthService auth;
    private final RateLimiter rateLimiter;
    private final int loginCapacity;
    private final int loginPeriodMinutes;
    private final int registerCapacity;
    private final int registerPeriodMinutes;
    private final boolean cookieSecure;
    private final String cookieSameSite;

    public AuthController(AuthService auth, RateLimiter rateLimiter,
                           @Value("${app.rate-limit.login.capacity}") int loginCapacity,
                           @Value("${app.rate-limit.login.period-minutes}") int loginPeriodMinutes,
                           @Value("${app.rate-limit.register.capacity}") int registerCapacity,
                           @Value("${app.rate-limit.register.period-minutes}") int registerPeriodMinutes,
                           @Value("${app.auth.cookie.secure:false}") boolean cookieSecure,
                           @Value("${app.auth.cookie.same-site:Strict}") String cookieSameSite) {
        this.auth = auth;
        this.rateLimiter = rateLimiter;
        this.loginCapacity = loginCapacity;
        this.loginPeriodMinutes = loginPeriodMinutes;
        this.registerCapacity = registerCapacity;
        this.registerPeriodMinutes = registerPeriodMinutes;
        this.cookieSecure = cookieSecure;
        this.cookieSameSite = cookieSameSite;
    }

    // Keyed by IP, not by the submitted username — keying by username would let an attacker lock a victim
    // out by deliberately failing login as them from many different IPs, a self-inflicted DoS vector this
    // avoids. getRemoteAddr() only (no X-Forwarded-For): this app has no reverse-proxy layer anywhere in
    // its dev or documented prod config today.
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest req, HttpServletRequest request) {
        rateLimiter.checkLimit("register", request.getRemoteAddr(), registerCapacity, Duration.ofMinutes(registerPeriodMinutes));
        TokenPair tokens = auth.register(req.username(), req.email(), req.password());
        return withRefreshCookie(tokens);
    }

    @PostMapping("/access_token")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest req, HttpServletRequest request) {
        rateLimiter.checkLimit("login", request.getRemoteAddr(), loginCapacity, Duration.ofMinutes(loginPeriodMinutes));
        TokenPair tokens = auth.login(req.username(), req.password());
        return withRefreshCookie(tokens);
    }

    @PostMapping("/access_token/refresh")
    public ResponseEntity<AuthResponse> refresh(@CookieValue(REFRESH_COOKIE) String refreshToken) {
        TokenPair tokens = auth.refresh(refreshToken);
        return withRefreshCookie(tokens);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        if (refreshToken != null) {
            auth.logout(refreshToken);
        }
        ResponseCookie expired = ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite(cookieSameSite)
                .path("/api/v1")
                .maxAge(0)
                .build();
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, expired.toString()).build();
    }

    private ResponseEntity<AuthResponse> withRefreshCookie(TokenPair tokens) {
        ResponseCookie cookie = ResponseCookie.from(REFRESH_COOKIE, tokens.rawRefreshToken())
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite(cookieSameSite)
                .path("/api/v1")
                .maxAge(Duration.ofDays(30))
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(AuthResponse.bearer(tokens.accessToken()));
    }
}
