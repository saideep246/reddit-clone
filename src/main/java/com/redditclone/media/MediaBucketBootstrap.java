package com.redditclone.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// Startup checks for media storage. "Local" means the fake S3 (s3mock/localhost); anything else is a real bucket
// (e.g. Cloudflare R2). The two are treated differently on purpose:
//
//   Local:  convenience. Creates the bucket if missing; problems only warn (the fake S3 may still be starting).
//   Remote: read-only. The bucket is never created or reconfigured by the application — that is infrastructure — and
//           no bucket-admin permission is needed to start. We only verify the configuration is usable and, if it
//           clearly is not (bucket unreachable/forbidden, or MEDIA_PUBLIC_BASE_URL still pointing at the local fake
//           storage), FAIL STARTUP with a clear message rather than boot into a state where every upload or image
//           breaks. app.storage.fail-fast=false downgrades those to errors in the log.
//
// Bucket CORS is never touched unless app.storage.configure-cors=true (off by default, off in production); even then
// an existing CORS policy is left alone.
@Component
public class MediaBucketBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MediaBucketBootstrap.class);

    private final StorageService storage;
    private final boolean configureCors;
    private final boolean failFast;
    private final List<String> corsOrigins;
    private final String endpoint;
    private final String publicBaseUrl;

    public MediaBucketBootstrap(StorageService storage,
                                @Value("${app.storage.configure-cors:false}") boolean configureCors,
                                @Value("${app.storage.fail-fast:true}") boolean failFast,
                                @Value("${app.cors.allowed-origins}") String allowedOrigins,
                                @Value("${app.storage.endpoint}") String endpoint,
                                @Value("${app.media.public-base-url}") String publicBaseUrl) {
        this.storage = storage;
        this.configureCors = configureCors;
        this.failFast = failFast;
        this.corsOrigins = Arrays.stream(allowedOrigins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        this.endpoint = endpoint;
        this.publicBaseUrl = publicBaseUrl;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (isLocalStorage()) {
            runLocal();
        } else {
            runRemote();
        }
    }

    private void runLocal() {
        try {
            storage.createBucketIfMissing();
        } catch (RuntimeException e) {
            log.warn("storage: local fake S3 not ready ({}). Uploads will fail until it is reachable.", e.getMessage());
        }
        if (configureCors) {
            log.info("storage: configure-cors is set, but the local fake S3 already allows browser uploads from any origin");
        }
    }

    private void runRemote() {
        List<String> problems = new ArrayList<>();
        if (publicBaseUrl.contains("localhost") || publicBaseUrl.contains("127.0.0.1")) {
            problems.add("MEDIA_PUBLIC_BASE_URL (" + publicBaseUrl + ") points at local storage but STORAGE_ENDPOINT is a "
                    + "remote service, so uploaded images and videos would not load. Set it to the bucket's public address "
                    + "(for R2: the r2.dev URL or a custom domain, which does NOT include the bucket name).");
        }
        try {
            storage.verifyBucketAccessible();
        } catch (RuntimeException e) {
            problems.add("the storage bucket cannot be accessed with the configured endpoint/bucket/credentials ("
                    + e.getMessage() + ").");
        }
        if (!problems.isEmpty()) {
            String message = "Media storage is misconfigured: " + String.join(" ", problems);
            if (failFast) {
                throw new IllegalStateException(message + " (Set STORAGE_FAIL_FAST=false to start anyway.)");
            }
            log.error("storage: {}", message);
        }
        if (configureCors) {
            try {
                boolean created = storage.configureCorsIfAbsent(corsOrigins);
                log.info(created ? "storage: bucket had no CORS policy; set one allowing browser uploads from {}"
                        : "storage: bucket already has a CORS policy; left unchanged", corsOrigins);
            } catch (RuntimeException e) {
                log.warn("storage: could not read/set the bucket CORS policy ({}); configure it on the bucket instead "
                        + "(see docs/MEDIA_STORAGE.md)", e.getMessage());
            }
        }
    }

    private boolean isLocalStorage() {
        return endpoint.contains("localhost") || endpoint.contains("127.0.0.1") || endpoint.contains("s3mock");
    }
}
