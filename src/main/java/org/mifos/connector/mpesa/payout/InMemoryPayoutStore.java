package org.mifos.connector.mpesa.payout;

import lombok.extern.slf4j.Slf4j;
import org.mifos.connector.mpesa.config.RedisStoreProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Payouts kept in memory (single pod, {@code mpesa-connector.redis.type=memory}). Like the Redis store, each payout is
 * kept for {@code ttl.payout-seconds}; expired payouts are swept out at most once a minute, on save, so memory follows
 * the retention period rather than the number of payouts ever made.
 */
@Slf4j
@Component
@ConditionalOnExpression("'${mpesa-connector.redis.type:redis}'.equalsIgnoreCase('memory')")
public class InMemoryPayoutStore implements PayoutStore {

    private static final long SWEEP_INTERVAL_MILLIS = 60_000;

    private final Map<String, Entry> payouts = new ConcurrentHashMap<>();
    private final AtomicLong nextSweepMillis = new AtomicLong();
    private final Duration ttl;
    private final Clock clock;

    @Autowired
    public InMemoryPayoutStore(RedisStoreProperties props) {
        this(Duration.ofSeconds(props.getTtl().getPayoutSeconds()), Clock.systemUTC());
    }

    InMemoryPayoutStore(Duration ttl, Clock clock) {
        this.ttl = ttl;
        this.clock = clock;
    }

    @PostConstruct
    void logStoreBackend() {
        log.info("Payout store backend: memory, payouts kept for {}", ttl);
    }

    @Override
    public void save(PayoutRecord payout) {
        long now = clock.millis();
        sweep(now);
        payouts.put(payout.transactionId(), new Entry(payout, now + ttl.toMillis()));
    }

    @Override
    public PayoutRecord find(String transactionId) {
        var entry = payouts.get(transactionId);
        return entry == null || entry.expiresAtMillis() <= clock.millis() ? null : entry.payout();
    }

    int size() {
        return payouts.size();
    }

    private void sweep(long now) {
        long due = nextSweepMillis.get();
        if (now >= due && nextSweepMillis.compareAndSet(due, now + SWEEP_INTERVAL_MILLIS)) {
            payouts.values().removeIf(entry -> entry.expiresAtMillis() <= now);
        }
    }

    private record Entry(PayoutRecord payout, long expiresAtMillis) {
    }
}
