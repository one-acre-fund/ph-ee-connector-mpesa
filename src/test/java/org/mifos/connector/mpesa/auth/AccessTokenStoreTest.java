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

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccessTokenStoreTest {

    private static final String KEY_PREFIX = "test-prefix";
    private static final String ACCESS_TOKEN_KEY = KEY_PREFIX + ":access_token";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private AccessTokenStore accessTokenStore;

    @BeforeEach
    void setUp() {
        RedisStoreProperties properties = new RedisStoreProperties();
        properties.setKeyPrefix(KEY_PREFIX);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        accessTokenStore = new AccessTokenStore(redisTemplate, properties);
    }

    @Test
    void saveToken_shouldStoreTokenWithExpiry() {
        accessTokenStore.saveToken("token-123", 3600);

        verify(valueOperations).set(ACCESS_TOKEN_KEY, "token-123", 3600, TimeUnit.SECONDS);
    }

    @Test
    void getAccessToken_shouldReturnStoredToken() {
        when(valueOperations.get(ACCESS_TOKEN_KEY)).thenReturn("token-123");

        assertEquals("token-123", accessTokenStore.getAccessToken());
    }

    @Test
    void isValid_shouldReturnTrueWhenKeyExists() {
        when(redisTemplate.hasKey(ACCESS_TOKEN_KEY)).thenReturn(true);

        assertTrue(accessTokenStore.isValid());
    }

    @Test
    void isValid_shouldReturnFalseWhenKeyMissing() {
        when(redisTemplate.hasKey(ACCESS_TOKEN_KEY)).thenReturn(false);

        assertFalse(accessTokenStore.isValid());
    }

    @Test
    void isValid_shouldReturnFalseWhenHasKeyReturnsNull() {
        when(redisTemplate.hasKey(ACCESS_TOKEN_KEY)).thenReturn(null);

        assertFalse(accessTokenStore.isValid());
    }
}
