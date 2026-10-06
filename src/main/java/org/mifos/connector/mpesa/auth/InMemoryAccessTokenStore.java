package org.mifos.connector.mpesa.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;

@Component
@ConditionalOnExpression("'${mpesa-connector.redis.type:redis}'.equalsIgnoreCase('memory')")
public class InMemoryAccessTokenStore implements AccessTokenStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryAccessTokenStore.class);

    private final AtomicReference<Entry> token = new AtomicReference<>();
    private final Clock clock;

    public InMemoryAccessTokenStore() {
        this(Clock.systemUTC());
    }

    InMemoryAccessTokenStore(Clock clock) {
        this.clock = clock;
    }

    @PostConstruct
    void logStoreBackend() {
        log.info("Access token store backend: memory");
    }

    @Override
    public void saveToken(String accessToken, int expiresInSeconds) {
        long expiresAtMillis = clock.millis() + Math.max(expiresInSeconds, 0) * 1000L;
        token.set(new Entry(accessToken, expiresAtMillis));
    }

    @Override
    public String getAccessToken() {
        Entry entry = current();
        return entry != null ? entry.accessToken : null;
    }

    @Override
    public boolean isValid() {
        return current() != null;
    }

    private Entry current() {
        Entry entry = token.get();
        if (entry == null) {
            return null;
        }
        if (clock.millis() >= entry.expiresAtMillis) {
            token.compareAndSet(entry, null);
            return null;
        }
        return entry;
    }

    private static final class Entry {
        private final String accessToken;
        private final long expiresAtMillis;

        private Entry(String accessToken, long expiresAtMillis) {
            this.accessToken = accessToken;
            this.expiresAtMillis = expiresAtMillis;
        }
    }
}
