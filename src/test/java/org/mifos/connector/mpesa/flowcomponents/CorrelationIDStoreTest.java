package org.mifos.connector.mpesa.flowcomponents;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mifos.connector.mpesa.config.RedisStoreProperties;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CorrelationIDStoreTest {

    private static final String KEY_PREFIX = "test-prefix";
    private static final long CORRELATION_TTL_SECONDS = 120;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private CorrelationIDStore correlationIDStore;

    @BeforeEach
    void setUp() {
        RedisStoreProperties properties = new RedisStoreProperties();
        properties.setKeyPrefix(KEY_PREFIX);
        RedisStoreProperties.Ttl ttl = new RedisStoreProperties.Ttl();
        ttl.setCorrelationSeconds(CORRELATION_TTL_SECONDS);
        properties.setTtl(ttl);

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        correlationIDStore = new CorrelationIDStore(redisTemplate, properties);
    }

    @Test
    void addMapping_shouldStoreCorrelationWithTtl() {
        correlationIDStore.addMapping("server-1", "client-1");

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(
                org.mockito.ArgumentMatchers.eq(KEY_PREFIX + ":correlation:server-1"),
                org.mockito.ArgumentMatchers.eq("client-1"),
                ttlCaptor.capture()
        );
        assertEquals(CORRELATION_TTL_SECONDS, ttlCaptor.getValue().getSeconds());
    }

    @Test
    void getClientCorrelation_shouldReturnMappedValue() {
        when(valueOperations.get(KEY_PREFIX + ":correlation:server-1")).thenReturn("client-1");

        assertEquals("client-1", correlationIDStore.getClientCorrelation("server-1"));
    }
}
