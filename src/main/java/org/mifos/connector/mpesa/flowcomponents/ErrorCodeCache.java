package org.mifos.connector.mpesa.flowcomponents;

import org.mifos.connector.mpesa.config.RedisStoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * Redis cache of error code
 */
@Component
public class ErrorCodeCache {

    private static final Logger logger = LoggerFactory.getLogger(ErrorCodeCache.class);

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;
    private final long freshSeconds;
    private final long retentionSeconds;
    private final Clock clock;

    @Autowired
    public ErrorCodeCache(StringRedisTemplate redisTemplate, RedisStoreProperties props,
                          @Value("${tenant}") String tenantId) {
        this(redisTemplate, props, tenantId, Clock.systemUTC());
    }

    ErrorCodeCache(StringRedisTemplate redisTemplate, RedisStoreProperties props, String tenantId, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix = props.getKeyPrefix() + ":errorcode:" + tenantId + ":";
        this.freshSeconds = props.getTtl().getErrorCodeFreshSeconds();
        this.retentionSeconds = props.getTtl().getErrorCodeRetentionSeconds();
        this.clock = clock;
    }

    public Entry get(String errorCode) {
        try {
            String value = redisTemplate.opsForValue().get(keyPrefix + errorCode);
            return value != null ? Entry.parse(value) : null;
        } catch (RuntimeException e) {
            logger.warn("Failed to read error code {} from Redis cache: {}", errorCode, e.getMessage());
            return null;
        }
    }

    public void put(String errorCode, boolean recoverable) {
        try {
            redisTemplate.opsForValue().set(keyPrefix + errorCode,
                    new Entry(recoverable, clock.millis()).serialize(),
                    Duration.ofSeconds(retentionSeconds));
        } catch (RuntimeException e) {
            logger.warn("Failed to write error code {} to Redis cache: {}", errorCode, e.getMessage());
        }
    }

    public boolean isFresh(Entry entry) {
        return entry != null && clock.millis() - entry.fetchedAtMillis() < freshSeconds * 1000;
    }

    public record Entry(boolean recoverable, long fetchedAtMillis) {

        String serialize() {
            return recoverable + "," + fetchedAtMillis;
        }

        static Entry parse(String value) {
            String[] parts = value.split(",", 2);
            long fetchedAt = parts.length > 1 ? Long.parseLong(parts[1]) : 0L;
            return new Entry(Boolean.parseBoolean(parts[0]), fetchedAt);
        }
    }
}
