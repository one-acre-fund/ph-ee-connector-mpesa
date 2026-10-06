package org.mifos.connector.mpesa.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mifos.connector.mpesa.config.RedisStoreProperties;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccessTokenStoreTest {

    private static final String KEY_PREFIX = "test-prefix";
    private static final String ACCESS_TOKEN_KEY = KEY_PREFIX + ":access_token";
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void redisSaveToken_shouldStoreTokenWithExpiry() {
        redisStore().saveToken("token-123", 3600);

        verify(valueOperations).set(ACCESS_TOKEN_KEY, "token-123", 3600, TimeUnit.SECONDS);
    }

    @Test
    void redisGetAccessToken_shouldReturnStoredToken() {
        when(valueOperations.get(ACCESS_TOKEN_KEY)).thenReturn("token-123");

        assertEquals("token-123", redisStore().getAccessToken());
    }

    @Test
    void redisIsValid_shouldReturnTrueWhenKeyExists() {
        when(redisTemplate.hasKey(ACCESS_TOKEN_KEY)).thenReturn(true);

        assertTrue(redisStore().isValid());
    }

    @Test
    void redisIsValid_shouldReturnFalseWhenKeyMissing() {
        when(redisTemplate.hasKey(ACCESS_TOKEN_KEY)).thenReturn(false);

        assertFalse(redisStore().isValid());
    }

    @Test
    void redisIsValid_shouldReturnFalseWhenHasKeyReturnsNull() {
        when(redisTemplate.hasKey(ACCESS_TOKEN_KEY)).thenReturn(null);

        assertFalse(redisStore().isValid());
    }

    @Test
    void memoryStore_shouldPutGetAndExpireWithoutRedis() {
        MutableClock clock = new MutableClock(START);
        InMemoryAccessTokenStore store = new InMemoryAccessTokenStore(clock);

        store.saveToken("token-123", 30);
        assertEquals("token-123", store.getAccessToken());
        assertTrue(store.isValid());

        clock.advance(Duration.ofSeconds(30));
        assertNull(store.getAccessToken());
        assertFalse(store.isValid());

        verify(redisTemplate, never()).opsForValue();
        verifyNoInteractions(valueOperations);
    }

    @Test
    void logStoreBackend_shouldRunForBothBackends() {
        redisStore().logStoreBackend();
        new InMemoryAccessTokenStore().logStoreBackend();
    }

    private RedisAccessTokenStore redisStore() {
        RedisStoreProperties properties = new RedisStoreProperties();
        properties.setKeyPrefix(KEY_PREFIX);
        return new RedisAccessTokenStore(redisTemplate, properties);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
