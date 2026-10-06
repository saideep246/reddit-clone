package com.redditclone.chat;

import com.redditclone.auth.JwtService;
import io.jsonwebtoken.JwtException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.Arrays;

// The WebSocket handshake itself (SecurityConfig permits /ws/** at the HTTP layer) is a plain HTTP GET
// that a browser-native WebSocket client cannot attach an Authorization header to — real authentication
// happens one layer up, at the first STOMP frame (CONNECT), which does carry its own headers. This is the
// STOMP-layer mirror of JwtAuthFilter: same JwtService.parseUserId call, same "bad/missing token" outcome,
// except here it must actively reject the CONNECT (throwing from a ChannelInterceptor.preSend during
// CONNECT is Spring's documented way to fail the handshake — the client never receives a CONNECTED frame,
// so it can never subscribe or send) rather than silently falling through to an HTTP 401 the way
// JwtAuthFilter's HTTP-layer equivalent does.
@Configuration
@EnableWebSocketMessageBroker
public class ChatWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtService jwtService;
    private final String[] allowedOrigins;

    public ChatWebSocketConfig(JwtService jwtService, @Value("${app.cors.allowed-origins}") String allowedOrigins) {
        this.jwtService = jwtService;
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(o -> !o.isEmpty()).toArray(String[]::new);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // No SockJS: no legacy-browser fallback requirement for this backend-only build; a real future
        // frontend would use the native WebSocket API directly.
        // setAllowedOrigins is required here, same as SecurityConfig's REST CorsConfigurationSource bean:
        // without it, Spring applies its default same-origin policy to the handshake itself, which a
        // frontend running on a different origin (the Vite dev server) can never pass — confirmed via a
        // raw curl WebSocket-upgrade probe (Origin: http://localhost:5174 -> 403 before this fix).
        registry.addEndpoint("/ws").setAllowedOrigins(allowedOrigins);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // In-memory simple broker, not a relay to an external broker — this app runs as a single process
        // today (see OutboxWorker's own comment to the same effect), so no cross-instance fan-out is
        // needed yet. /topic is intentionally not registered: every delivery in this design is per-user
        // (/queue), not a room-topic broadcast.
        registry.enableSimpleBroker("/queue");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                // MessageHeaderAccessor.getAccessor(...), not StompHeaderAccessor.wrap(...): wrap() builds
                // a fresh wrapper whose mutations never propagate back into the message, while getAccessor
                // returns the actual mutable accessor Spring's WebSocket->STOMP conversion layer already
                // threads through inbound messages for exactly this purpose — accessor.setUser(...) below
                // only sticks to the session (and is visible to later frames/@MessageMapping's injected
                // Principal on the same connection) when retrieved this way.
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (StompCommand.CONNECT.equals(accessor.getCommand())) {
                    String header = accessor.getFirstNativeHeader("Authorization");
                    if (header == null || !header.startsWith("Bearer ")) {
                        throw new IllegalArgumentException("missing Authorization header");
                    }
                    try {
                        accessor.setUser(new StompPrincipal(jwtService.parseUserId(header.substring(7))));
                    } catch (JwtException | IllegalArgumentException e) {
                        throw new IllegalArgumentException("invalid or expired token", e);
                    }
                }
                return message;
            }
        });
    }
}
