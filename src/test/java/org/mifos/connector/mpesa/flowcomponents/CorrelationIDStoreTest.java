package org.mifos.connector.mpesa.flowcomponents;

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

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CorrelationIDStoreTest {

    private static final String KEY_PREFIX = "test-prefix";
    private static final long CORRELATION_TTL_SECONDS = 120;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void redisAddMapping_shouldStoreCorrelationWithTtl() {
        redisStore().addMapping("server-1", "client-1");

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(
                org.mockito.ArgumentMatchers.eq(KEY_PREFIX + ":correlation:server-1"),
                org.mockito.ArgumentMatchers.eq("client-1"),
                ttlCaptor.capture()
        );
        assertEquals(CORRELATION_TTL_SECONDS, ttlCaptor.getValue().getSeconds());
    }

    @Test
    void redisGetClientCorrelation_shouldReturnMappedValue() {
        when(valueOperations.get(KEY_PREFIX + ":correlation:server-1")).thenReturn("client-1");

        assertEquals("client-1", redisStore().getClientCorrelation("server-1"));
    }

    @Test
    void memoryStore_shouldPutGetWithoutRedis() {
        InMemoryCorrelationIDStore store = memoryStore();

        store.addMapping("server-1", "client-1");
        assertEquals("client-1", store.getClientCorrelation("server-1"));
        assertNull(store.getClientCorrelation("missing"));

        verify(redisTemplate, never()).opsForValue();
        verifyNoInteractions(valueOperations);
    }

    @Test
    void logStoreBackend_shouldRunForBothBackends() {
        redisStore().logStoreBackend();
        memoryStore().logStoreBackend();
    }

    private RedisCorrelationIDStore redisStore() {
        return new RedisCorrelationIDStore(redisTemplate, redisProperties());
    }

    private InMemoryCorrelationIDStore memoryStore() {
        return new InMemoryCorrelationIDStore(redisProperties());
    }

    private RedisStoreProperties redisProperties() {
        RedisStoreProperties properties = new RedisStoreProperties();
        properties.setKeyPrefix(KEY_PREFIX);
        RedisStoreProperties.Ttl ttl = new RedisStoreProperties.Ttl();
        ttl.setCorrelationSeconds(CORRELATION_TTL_SECONDS);
        properties.setTtl(ttl);
        return properties;
    }
}
