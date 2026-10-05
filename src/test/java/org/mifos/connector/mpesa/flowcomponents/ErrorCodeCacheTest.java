package org.mifos.connector.mpesa.flowcomponents;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mifos.connector.mpesa.config.RedisStoreProperties;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@ExtendWith(MockitoExtension.class)
class ErrorCodeCacheTest {

    private static final String KEY_PREFIX = "test-prefix";
    private static final String KEY = KEY_PREFIX + ":errorcode:kenya:1037";
    private static final long FRESH_SECONDS = 60;
    private static final long RETENTION_SECONDS = 600;
    private static final long NOW = 1_000_000_000L;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private ErrorCodeCache errorCodeCache;

    @BeforeEach
    void setUp() {
        RedisStoreProperties properties = new RedisStoreProperties();
        properties.setKeyPrefix(KEY_PREFIX);
        RedisStoreProperties.Ttl ttl = new RedisStoreProperties.Ttl();
        ttl.setErrorCodeFreshSeconds(FRESH_SECONDS);
        ttl.setErrorCodeRetentionSeconds(RETENTION_SECONDS);
        properties.setTtl(ttl);

        Clock clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC);
        errorCodeCache = new ErrorCodeCache(redisTemplate, properties, "kenya", clock);
    }

    @Test
    void put_shouldStoreValueWithFetchTimeAndRetentionTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        errorCodeCache.put("1037", true);

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(eq(KEY), eq("true," + NOW), ttlCaptor.capture());
        assertEquals(RETENTION_SECONDS, ttlCaptor.getValue().getSeconds());
    }

    @Test
    void get_shouldParseStoredValue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(KEY)).thenReturn("false,12345");

        ErrorCodeCache.Entry entry = errorCodeCache.get("1037");

        assertFalse(entry.recoverable());
        assertEquals(12345L, entry.fetchedAtMillis());
    }

    @Test
    void get_shouldReturnNullWhenMissing() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        assertNull(errorCodeCache.get("1037"));
    }

    @Test
    void get_shouldTreatRedisFailureAsMiss() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertNull(errorCodeCache.get("1037"));
    }

    @Test
    void put_shouldSwallowRedisFailure() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        doThrow(new RedisConnectionFailureException("down"))
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        assertDoesNotThrow(() -> errorCodeCache.put("1037", true));
        verify(valueOperations).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void isFresh_shouldCompareAgeAgainstFreshTtl() {
        assertTrue(errorCodeCache.isFresh(new ErrorCodeCache.Entry(true, NOW - FRESH_SECONDS * 1000 + 1)));
        assertFalse(errorCodeCache.isFresh(new ErrorCodeCache.Entry(true, NOW - FRESH_SECONDS * 1000)));
        assertFalse(errorCodeCache.isFresh(null));
    }

    @Test
    void get_shouldTreatValueWithoutTimestampAsStale() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(KEY)).thenReturn("true");

        ErrorCodeCache.Entry entry = errorCodeCache.get("1037");

        assertTrue(entry.recoverable());
        assertFalse(errorCodeCache.isFresh(entry));
    }

    @Test
    void springConstructor_shouldUseSystemClock() {
        RedisStoreProperties properties = new RedisStoreProperties();
        ErrorCodeCache cache = new ErrorCodeCache(redisTemplate, properties, "kenya");

        assertTrue(cache.isFresh(new ErrorCodeCache.Entry(true, System.currentTimeMillis())));
    }
}
