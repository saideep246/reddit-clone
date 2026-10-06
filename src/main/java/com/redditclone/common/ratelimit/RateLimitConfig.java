package com.redditclone.common.ratelimit;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

// A second, independent connection to the same Redis instance FeedCacheService already talks to via
// Spring's own StringRedisTemplate abstraction — Bucket4j's Lettuce integration needs the raw
// io.lettuce.core.RedisClient to run its native Lua-script bucket operations, not Spring's wrapper around
// it, so this can't just reuse the auto-configured LettuceConnectionFactory bean.
@Configuration
public class RateLimitConfig {

    @Bean
    RedisClient rateLimitRedisClient(@Value("${spring.data.redis.url}") String redisUrl) {
        return RedisClient.create(RedisURI.create(redisUrl));
    }

    // expirationAfterWrite puts a TTL on each bucket's Redis key (one key per distinct rate-limited
    // identity, e.g. per IP) so abandoned buckets don't accumulate in Redis forever — 1 hour comfortably
    // exceeds the longest configured window (register's, at 60 minutes) today.
    @Bean
    ProxyManager<byte[]> bucketProxyManager(RedisClient rateLimitRedisClient) {
        return Bucket4jLettuce.casBasedBuilder(rateLimitRedisClient)
                .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofHours(1)))
                .build();
    }
}
