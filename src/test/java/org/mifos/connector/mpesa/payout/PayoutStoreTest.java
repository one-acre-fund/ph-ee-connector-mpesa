package org.mifos.connector.mpesa.payout;

import org.junit.jupiter.api.Test;
import org.mifos.connector.mpesa.config.RedisStoreProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PayoutStoreTest {

    private static final PayoutRecord RECORD = new PayoutRecord("tx-1", "fineract", 1500, 254708374149L, 42L);

    @Test
    void inMemory_shouldFindWhatWasSaved() {
        InMemoryPayoutStore store = PayoutTestData.memoryStore();
        store.logStoreBackend();
        store.save(RECORD);

        assertRecord(store.find("tx-1"));
        assertNull(store.find("tx-2"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void redis_shouldStoreAsJsonUnderPrefixedKeyWithTtl() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(values);
        RedisStoreProperties props = new RedisStoreProperties();
        props.getTtl().setPayoutSeconds(600);
        RedisPayoutStore store = new RedisPayoutStore(template, props);
        store.logStoreBackend();

        store.save(RECORD);

        verify(values).set("mpesa-connector:payout:tx-1", RECORD.toJson(), Duration.ofSeconds(600));
        when(values.get("mpesa-connector:payout:tx-1")).thenReturn(RECORD.toJson());
        when(values.get("mpesa-connector:payout:tx-2")).thenReturn(null);
        assertRecord(store.find("tx-1"));
        assertNull(store.find("tx-2"));
    }

    private static void assertRecord(PayoutRecord payout) {
        assertEquals("tx-1", payout.transactionId());
        assertEquals("fineract", payout.account());
        assertEquals(1500, payout.amount());
        assertEquals(254708374149L, payout.msisdn());
        assertEquals(42L, payout.sentAtMillis());
    }

    @Test
    void inMemory_shouldForgetPayoutsAfterTheRetentionPeriod() {
        var clock = new MutableClock();
        var store = new InMemoryPayoutStore(Duration.ofHours(1), clock);
        store.save(RECORD);

        clock.advance(Duration.ofMinutes(59));
        assertRecord(store.find("tx-1"));
        clock.advance(Duration.ofMinutes(1));
        assertNull(store.find("tx-1"));
    }

    @Test
    void inMemory_shouldSweepExpiredPayoutsWithoutLookingThemUp() {
        var clock = new MutableClock();
        var store = new InMemoryPayoutStore(Duration.ofHours(1), clock);
        for (int i = 0; i < 100; i++) {
            store.save(new PayoutRecord("old-" + i, "fineract", 10, 254708374149L, 0));
        }
        clock.advance(Duration.ofHours(2));

        store.save(RECORD);

        assertEquals(1, store.size());
        assertRecord(store.find("tx-1"));
    }

    private static final class MutableClock extends java.time.Clock {
        private java.time.Instant now = java.time.Instant.parse("2026-11-16T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public java.time.Instant instant() {
            return now;
        }
    }
}
