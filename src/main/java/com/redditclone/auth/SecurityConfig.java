package com.redditclone.auth;

import com.redditclone.common.correlation.CorrelationIdFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new Argon2PasswordEncoder(16, 32, 1, 19456, 2); // tuned for ~100-200ms
    }

    // The frontend SPA runs on a different origin (Vite dev server) than the API, and sends the
    // httpOnly refresh-token cookie on the refresh call — allowCredentials requires an explicit origin
    // allowlist, a wildcard "*" is rejected by browsers once credentials are involved.
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins}") String allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(allowedOrigins.split(",")));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // Idempotency-Key added in F6: PostController.submit requires it, and unlike Authorization/
        // Content-Type, a browser blocks the actual request client-side if a custom header isn't
        // explicitly allowed here, even when the preflight itself responds 200 — found by actually
        // submitting a post from the browser, not just curling the endpoint directly.
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", CorrelationIdFilter.HEADER));
        // Without this, browser JS can never read a custom response header regardless of the allow-rule
        // above — allowedHeaders governs the request direction, exposedHeaders governs the response
        // direction, and no exposedHeaders call existed in this app before the correlation-id filter.
        config.setExposedHeaders(List.of(CorrelationIdFilter.HEADER));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtFilter,
                                            CorrelationIdFilter correlationIdFilter,
                                            CorsConfigurationSource corsConfigurationSource) throws Exception {
        http.csrf(csrf -> csrf.disable()) // bearer-token API, not cookie-session based
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Spring Security's default anonymous-authentication + access-denied handling returns 403
                // for a missing/invalid token on an authenticated-only route; a bearer-token API should
                // return 401 there instead (403 is reserved for an authenticated principal lacking
                // permission, which Phase 1 doesn't have yet).
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(auth -> auth
                        // Without this, an unhandled exception on an unauthenticated request triggers a
                        // servlet-container forward to /error, which then re-enters this same filter chain
                        // as a second, unauthenticated request — anyRequest().authenticated() denies THAT
                        // one and masks the real 500 behind a misleading 401 (found by hitting exactly this
                        // case: an ArithmeticException deep in a query surfaced as a bare 401 to the client).
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/api/v1/register", "/api/v1/access_token", "/api/v1/access_token/refresh",
                                "/api/v1/logout")
                        .permitAll()
                        // Reddit's real API lets anyone browse without a token — only actions (vote, submit,
                        // comment, subscribe, save, chat, delete) require one. jwtFilter still runs on these
                        // and populates the principal when a token IS present.
                        .requestMatchers(HttpMethod.GET, "/r/*/new", "/r/*/hot", "/r/*/top", "/r/*/rising",
                                "/r/*/controversial", "/r/*/search", "/r/*/search/comments", "/r/*/posts/*/poll", "/r/*/comments/*", "/user/*/about",
                                "/user/*/submitted", "/user/*/comments", "/r/*/flairs",
                                "/r/*/pinned", "/r/*/rules", "/r", "/r/search", "/r/*/about", "/user/search",
                                "/api/morechildren", "/api/p/*", "/api/media/*", "/user/*/followers", "/user/*/following")
                        .permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        // The WebSocket handshake is a plain HTTP GET that a browser-native WebSocket
                        // client cannot attach an Authorization header to — real auth happens one layer up,
                        // at the first STOMP frame (see ChatWebSocketConfig's inbound-channel interceptor).
                        .requestMatchers("/ws/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                // First filter in the whole chain: wraps everything, including auth failures, so every
                // response (success or error) is taggable back to the same id.
                .addFilterBefore(correlationIdFilter, JwtAuthFilter.class);
        return http.build();
    }
}
