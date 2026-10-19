package org.mifos.connector.mpesa.flowcomponents;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mifos.connector.mpesa.config.RedisStoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Component
@ConditionalOnExpression("!'${mpesa-connector.redis.type:redis}'.equalsIgnoreCase('memory')")
public class RedisPaymentAttemptStore implements PaymentAttemptStore {

    private static final Logger log = LoggerFactory.getLogger(RedisPaymentAttemptStore.class);
    private static final String KEY_PREFIX = "payment:attempt:";
    private static final String PENDING = "pending:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String keyPrefix;
    private final Duration ttl;
    private final Clock clock;

    @Autowired
    public RedisPaymentAttemptStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                                    RedisStoreProperties redisStoreProperties) {
        this(redisTemplate, objectMapper, redisStoreProperties, Clock.systemUTC());
    }

    RedisPaymentAttemptStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
                             RedisStoreProperties redisStoreProperties, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.keyPrefix = redisStoreProperties.getKeyPrefix();
        this.ttl = Duration.ofSeconds(redisStoreProperties.getTtl().getPaymentAttemptSeconds());
        this.clock = clock;
    }

    @PostConstruct
    void logStoreBackend() {
        log.info("Payment attempt store backend: redis");
    }

    @Override
    public boolean claim(String transactionId) {
        String pending = PENDING + clock.millis();
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key(transactionId), pending, ttl));
    }

    @Override
    public void release(String transactionId) {
        redisTemplate.delete(key(transactionId));
    }

    @Override
    public void recordResult(String transactionId, PaymentAttempt result) {
        try {
            redisTemplate.opsForValue().set(key(transactionId), objectMapper.writeValueAsString(result), ttl);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise payment attempt for " + transactionId, e);
        }
    }

    @Override
    public Optional<PaymentAttempt> result(String transactionId) {
        String value = redisTemplate.opsForValue().get(key(transactionId));
        if (value == null || value.startsWith(PENDING)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(value, PaymentAttempt.class));
        } catch (JsonProcessingException e) {
            log.warn("Unreadable payment attempt for {}; treating as pending", transactionId, e);
            return Optional.empty();
        }
    }

    @Override
    public Optional<Instant> pendingSince(String transactionId) {
        String value = redisTemplate.opsForValue().get(key(transactionId));
        if (value == null || !value.startsWith(PENDING)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.ofEpochMilli(Long.parseLong(value.substring(PENDING.length()))));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private String key(String transactionId) {
        return keyPrefix + ":" + KEY_PREFIX + transactionId;
    }
}
