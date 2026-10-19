package org.mifos.connector.mpesa.flowcomponents;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mifos.connector.mpesa.config.RedisStoreProperties;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentAttemptStoreTest {

    private static final String KEY_PREFIX = "test-prefix";
    private static final String TXN_ID = "tx-1";
    private static final String KEY = KEY_PREFIX + ":payment:attempt:" + TXN_ID;
    private static final long TTL_SECONDS = 86400;
    private static final Instant CLAIMED_AT = Instant.parse("2026-10-20T08:00:00Z");

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void redisClaim_isTrueOnlyWhenTheKeyWasAbsent() {
        when(valueOperations.setIfAbsent(eq(KEY), any(String.class), any(Duration.class))).thenReturn(true, false);

        assertTrue(redisStore().claim(TXN_ID));
        assertFalse(redisStore().claim(TXN_ID));
    }

    @Test
    void redisClaim_usesTheConfiguredTtl() {
        when(valueOperations.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true);

        redisStore().claim(TXN_ID);

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).setIfAbsent(eq(KEY), eq("pending:" + CLAIMED_AT.toEpochMilli()), ttl.capture());
        assertEquals(TTL_SECONDS, ttl.getValue().getSeconds());
    }

    @Test
    void redisRecordResult_storesJsonUnderTheSameKey() throws Exception {
        redisStore().recordResult(TXN_ID, new PaymentAttempt(false, "ws_CO_1", null, null, null));

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(KEY), json.capture(), any(Duration.class));
        PaymentAttempt stored = objectMapper.readValue(json.getValue(), PaymentAttempt.class);
        assertFalse(stored.isFailed());
        assertEquals("ws_CO_1", stored.getServerTransactionId());
    }

    @Test
    void redisResult_isEmptyWhileStillPendingOrMissing() {
        when(valueOperations.get(KEY)).thenReturn("pending:" + CLAIMED_AT.toEpochMilli(), (String) null);

        assertTrue(redisStore().result(TXN_ID).isEmpty());
        assertTrue(redisStore().result(TXN_ID).isEmpty());
    }

    @Test
    void redisResult_readsTheRecordedOutcome() {
        when(valueOperations.get(KEY)).thenReturn("{\"failed\":true,\"errorCode\":\"500.001.1001\"}");

        Optional<PaymentAttempt> result = redisStore().result(TXN_ID);

        assertTrue(result.isPresent());
        assertTrue(result.get().isFailed());
        assertEquals("500.001.1001", result.get().getErrorCode());
    }

    @Test
    void redisResult_treatsUnreadableValueAsPending() {
        when(valueOperations.get(KEY)).thenReturn("not json");

        assertTrue(redisStore().result(TXN_ID).isEmpty());
    }

    @Test
    void redisPendingSince_readsTheClaimTimeOnlyWhileStillPending() {
        when(valueOperations.get(KEY)).thenReturn("pending:" + CLAIMED_AT.toEpochMilli(), "{\"failed\":false}", "pending:garbage", (String) null);

        assertEquals(Optional.of(CLAIMED_AT), redisStore().pendingSince(TXN_ID));
        assertTrue(redisStore().pendingSince(TXN_ID).isEmpty());
        assertTrue(redisStore().pendingSince(TXN_ID).isEmpty());
        assertTrue(redisStore().pendingSince(TXN_ID).isEmpty());
    }

    @Test
    void redisRelease_deletesTheKey() {
        redisStore().release(TXN_ID);

        verify(redisTemplate).delete(KEY);
    }

    @Test
    void inMemory_claimRecordAndRelease() {
        InMemoryPaymentAttemptStore store = new InMemoryPaymentAttemptStore(properties(), Clock.systemUTC());

        assertTrue(store.claim(TXN_ID));
        assertFalse(store.claim(TXN_ID));
        assertTrue(store.result(TXN_ID).isEmpty());
        assertTrue(store.pendingSince(TXN_ID).isPresent());

        store.recordResult(TXN_ID, new PaymentAttempt(false, "ws_CO_1", null, null, null));
        assertEquals("ws_CO_1", store.result(TXN_ID).orElseThrow().getServerTransactionId());
        assertTrue(store.pendingSince(TXN_ID).isEmpty());

        store.release(TXN_ID);
        assertTrue(store.claim(TXN_ID));
    }

    @Test
    void inMemory_entriesExpireAfterTheTtl() {
        Instant start = Instant.parse("2026-10-20T08:00:00Z");
        Clock[] clock = {Clock.fixed(start, ZoneOffset.UTC)};
        Clock ticking = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return clock[0].instant(); }
        };
        InMemoryPaymentAttemptStore store = new InMemoryPaymentAttemptStore(properties(), ticking);

        assertTrue(store.claim(TXN_ID));
        store.recordResult(TXN_ID, new PaymentAttempt(true, null, "500.001.1001", "Unable to lock subscriber", "{}"));
        clock[0] = Clock.fixed(start.plusSeconds(TTL_SECONDS - 1), ZoneOffset.UTC);
        assertTrue(store.result(TXN_ID).isPresent());

        clock[0] = Clock.fixed(start.plusSeconds(TTL_SECONDS + 1), ZoneOffset.UTC);
        assertTrue(store.result(TXN_ID).isEmpty());
        assertTrue(store.claim(TXN_ID));
    }

    @Test
    void redisRecordResult_keepsTheErrorInformation() throws Exception {
        redisStore().recordResult(TXN_ID, new PaymentAttempt(true, null, "500.001.1001", "Unable to lock subscriber", "{\"errorCode\":\"500.001.1001\"}"));

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(KEY), json.capture(), any(Duration.class));
        assertEquals("{\"errorCode\":\"500.001.1001\"}", objectMapper.readValue(json.getValue(), PaymentAttempt.class).getErrorInformation());
    }

    private RedisPaymentAttemptStore redisStore() {
        return new RedisPaymentAttemptStore(redisTemplate, objectMapper, properties(), Clock.fixed(CLAIMED_AT, ZoneOffset.UTC));
    }

    private static RedisStoreProperties properties() {
        RedisStoreProperties properties = new RedisStoreProperties();
        properties.setKeyPrefix(KEY_PREFIX);
        properties.getTtl().setPaymentAttemptSeconds(TTL_SECONDS);
        return properties;
    }
}
