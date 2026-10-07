package com.redditclone.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

// Thin wrapper around the S3 client/presigner — keeps every other class in this module ignorant of the
// AWS SDK's own types. Works identically against s3mock (local dev) and Cloudflare R2 (prod).
@Component
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    public StorageService(S3Client s3, S3Presigner presigner, @Value("${app.storage.bucket}") String bucket) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = bucket;
    }

    public String presignPut(String key, String contentType, Duration expiry) {
        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(expiry)
                .putObjectRequest(b -> b.bucket(bucket).key(key).contentType(contentType))
                .build();
        PresignedPutObjectRequest presigned = presigner.presignPutObject(presignRequest);
        return presigned.url().toString();
    }

    // Confirms the object genuinely exists before a completeUpload() call flips a media row eligible for
    // processing — defends against a client claiming "done" without ever having PUT the bytes.
    public boolean exists(String key) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            // Not every S3-compatible server returns the exact NoSuchKey error code on a 404 — fall back
            // to the raw status code rather than assuming AWS's own error taxonomy everywhere.
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    public byte[] get(String key) {
        return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
    }

    public void put(String key, byte[] content, String contentType) {
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
                RequestBody.fromBytes(content));
    }

    // Best effort: used to discard an oversized or abandoned upload. A failure here must never fail the request
    // that triggered it — the object is merely orphaned, not harmful.
    // File-based counterparts of get()/put(byte[]) for large objects (videos): the object is streamed between the bucket
    // and a local file, never held in the Java heap. get()/put(byte[]) copy the whole object at least twice, which is fine
    // for a 20MB image but not for a video of up to 200MB. `target` must not already exist (the SDK refuses to overwrite).
    public void downloadToFile(String key, Path target) {
        s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build(), ResponseTransformer.toFile(target));
    }

    public void putFile(String key, Path source, String contentType) {
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
                RequestBody.fromFile(source));
    }

    public void deleteQuietly(String key) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (RuntimeException e) {
            log.warn("could not delete object {} from storage: {}", key, e.getMessage());
        }
    }

    // Browsers PUT uploads straight to the bucket from the website's origin, which the bucket must explicitly allow
    // (CORS) — without a rule the preflight is rejected and every browser upload fails even though the same request
    // works from curl. CORS is INFRASTRUCTURE configuration and is normally set once by hand on the bucket; this exists
    // only as an opt-in convenience (app.storage.configure-cors) for dev environments. It never overwrites: if the bucket
    // already has any CORS rules it changes nothing and returns false. Returns true when it created the rule.
    public boolean configureCorsIfAbsent(List<String> origins) {
        try {
            var existing = s3.getBucketCors(b -> b.bucket(bucket));
            if (existing.hasCorsRules() && !existing.corsRules().isEmpty()) {
                return false;
            }
        } catch (S3Exception e) {
            if (e.statusCode() != 404) { // 404 = "no CORS configuration yet", the case we want to fill in
                throw e;
            }
        }
        s3.putBucketCors(b -> b.bucket(bucket).corsConfiguration(c -> c.corsRules(r -> r
                .allowedOrigins(origins)
                .allowedMethods("PUT", "GET", "HEAD")
                .allowedHeaders("*")
                .exposeHeaders("ETag")
                .maxAgeSeconds(3600))));
        return true;
    }

    // Read-only reachability/credentials check used at startup against a real (remote) bucket: needs only the
    // permissions the application token already has for normal uploads — never bucket-admin rights, never mutates
    // anything. Throws if the bucket can't be reached or accessed.
    public void verifyBucketAccessible() {
        s3.headBucket(b -> b.bucket(bucket));
    }

    // Single HEAD request serving both existence and actual size — completeUpload() uses this to verify
    // what was really PUT, not just that something is there, since the client's earlier declared byteSize
    // is never otherwise checked against reality.
    public Optional<Long> headObjectContentLength(String key) {
        try {
            return Optional.of(s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()).contentLength());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    // Idempotent — called by MediaBucketBootstrap on every startup. Convenient for local dev (s3mock
    // already pre-creates the bucket via its own env var, so this is a harmless no-op there); in prod the
    // R2 bucket is created once out-of-band, not on every boot, but this still makes a fresh environment
    // self-sufficient. Only a genuine "bucket doesn't exist" signal triggers createBucket — anything else
    // (a connectivity blip while s3mock is still starting, a 403 from bad credentials) propagates instead
    // of being masked as "let's just try to create it," which would otherwise throw its own confusing,
    // uncaught error and fail application startup outright.
    public void createBucketIfMissing() {
        try {
            s3.headBucket(b -> b.bucket(bucket));
        } catch (NoSuchBucketException e) {
            s3.createBucket(b -> b.bucket(bucket));
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                s3.createBucket(b -> b.bucket(bucket));
            } else {
                throw e;
            }
        }
    }
}
