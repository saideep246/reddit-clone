package com.redditclone.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

// Thin wrapper around Brevo's transactional email HTTP API (https://api.brevo.com/v3/smtp/email) — chosen
// over SMTP relay so no new mail-client dependency is needed; RestClient already comes from
// spring-boot-starter-webmvc (see media.StorageConfig for the same "reuse what's already on the
// classpath" reasoning applied to the S3-compatible client).
@Component
public class BrevoClient {

    private final RestClient brevoClient;
    private final String fromAddress;
    private final String fromName;

    public BrevoClient(RestClient brevoClient,
                        @Value("${app.mail.from.address}") String fromAddress,
                        @Value("${app.mail.from.name}") String fromName) {
        this.brevoClient = brevoClient;
        this.fromAddress = fromAddress;
        this.fromName = fromName;
    }

    // Throws on any non-2xx response (RestClient's default handler) — EmailOutboxWorker catches that
    // per-row to decide whether to retry.
    public void send(EmailMessage message) {
        Map<String, Object> body = Map.of(
                "sender", Map.of("name", fromName, "email", fromAddress),
                "to", List.of(Map.of("email", message.to(), "name", message.toName())),
                "subject", message.subject(),
                "htmlContent", message.html()
        );
        brevoClient.post()
                .uri("/v3/smtp/email")
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }
}
