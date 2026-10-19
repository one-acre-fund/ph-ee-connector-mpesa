package org.mifos.connector.mpesa.flowcomponents;

import org.mifos.connector.mpesa.config.RedisStoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single-pod fallback for local runs. Entries live only in this process, so this store gives no
 * duplicate-payment protection across a pod restart or between pods; production uses Redis.
 */
@Component
@ConditionalOnExpression("'${mpesa-connector.redis.type:redis}'.equalsIgnoreCase('memory')")
public class InMemoryPaymentAttemptStore implements PaymentAttemptStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryPaymentAttemptStore.class);

    private final Map<String, Entry> attempts = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Clock clock;

    @Autowired
    public InMemoryPaymentAttemptStore(RedisStoreProperties redisStoreProperties) {
        this(redisStoreProperties, Clock.systemUTC());
    }

    InMemoryPaymentAttemptStore(RedisStoreProperties redisStoreProperties, Clock clock) {
        this.ttl = Duration.ofSeconds(redisStoreProperties.getTtl().getPaymentAttemptSeconds());
        this.clock = clock;
    }

    @PostConstruct
    void logStoreBackend() {
        log.info("Payment attempt store backend: memory (single pod, not shared, lost on restart)");
    }

    @Override
    public boolean claim(String transactionId) {
        long now = clock.millis();
        attempts.entrySet().removeIf(e -> e.getValue().expiresAt <= now);
        return attempts.putIfAbsent(transactionId, new Entry(null, now, now + ttl.toMillis())) == null;
    }

    @Override
    public void release(String transactionId) {
        attempts.remove(transactionId);
    }

    @Override
    public void recordResult(String transactionId, PaymentAttempt result) {
        long now = clock.millis();
        attempts.put(transactionId, new Entry(result, now, now + ttl.toMillis()));
    }

    @Override
    public Optional<PaymentAttempt> result(String transactionId) {
        Entry entry = live(transactionId);
        return entry == null ? Optional.empty() : Optional.ofNullable(entry.result);
    }

    @Override
    public Optional<Instant> pendingSince(String transactionId) {
        Entry entry = live(transactionId);
        return entry == null || entry.result != null ? Optional.empty() : Optional.of(Instant.ofEpochMilli(entry.claimedAt));
    }

    /** The current entry, or null; an expired entry is removed only if it is still the one we saw. */
    private Entry live(String transactionId) {
        Entry entry = attempts.get(transactionId);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAt <= clock.millis()) {
            attempts.remove(transactionId, entry);
            return null;
        }
        return entry;
    }

    private static final class Entry {
        private final PaymentAttempt result;
        private final long claimedAt;
        private final long expiresAt;

        private Entry(PaymentAttempt result, long claimedAt, long expiresAt) {
            this.result = result;
            this.claimedAt = claimedAt;
            this.expiresAt = expiresAt;
        }
    }
}
