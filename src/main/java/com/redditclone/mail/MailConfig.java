package com.redditclone.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

// Same @Value-per-bean style as media.StorageConfig's S3Client — api-key is Brevo's own auth header
// (not Bearer/Basic), set once here so BrevoClient's call sites never have to know the credential exists.
@Configuration
public class MailConfig {

    // Named brevoRestClient, not brevoClient — the latter collides with the BrevoClient @Component's own
    // auto-registered bean name (Spring decapitalizes the simple class name), which fails startup with a
    // BeanDefinitionOverrideException. Built from the static RestClient.builder() rather than an injected
    // RestClient.Builder bean — this app's webmvc starter doesn't bring in RestClientAutoConfiguration's
    // builder bean, and a standalone client needs nothing from it anyway.
    @Bean
    public RestClient brevoRestClient(@Value("${app.mail.brevo.api-key}") String apiKey) {
        return RestClient.builder()
                .baseUrl("https://api.brevo.com")
                .defaultHeader("api-key", apiKey)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
