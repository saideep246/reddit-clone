package com.redditclone.media;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

// Same client/credentials/endpoint work against s3mock (local dev) or Cloudflare R2 (prod) — both
// S3-compatible, only app.storage.* config differs between environments. pathStyleAccessEnabled is
// required for a non-AWS S3-compatible endpoint: without it the SDK defaults to virtual-hosted-style
// (bucket-name.endpoint-host), which s3mock/MinIO/R2 don't serve the same way AWS S3 does.
@Configuration
public class StorageConfig {

    @Bean
    public S3Client s3Client(@Value("${app.storage.endpoint}") String endpoint,
                              @Value("${app.storage.access-key}") String accessKey,
                              @Value("${app.storage.secret-key}") String secretKey,
                              @Value("${app.storage.region}") String region) {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    @Bean
    public S3Presigner s3Presigner(@Value("${app.storage.public-endpoint:${app.storage.endpoint}}") String endpoint,
                                    @Value("${app.storage.access-key}") String accessKey,
                                    @Value("${app.storage.secret-key}") String secretKey,
                                    @Value("${app.storage.region}") String region) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }
}
