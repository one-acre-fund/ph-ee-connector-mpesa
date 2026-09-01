package org.mifos.connector.mpesa.flowcomponents;

import org.mifos.connector.mpesa.config.RedisStoreProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class CorrelationIDStore {

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;
    private final long correlationTtlSeconds;

    public CorrelationIDStore(StringRedisTemplate redisTemplate, RedisStoreProperties props) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix = props.getKeyPrefix() + ":correlation:";
        this.correlationTtlSeconds = props.getTtl().getCorrelationSeconds();
    }

    public void addMapping(String serverCorrelation, String clientCorrelation) {
        redisTemplate.opsForValue().set(keyPrefix + serverCorrelation, clientCorrelation, Duration.ofSeconds(correlationTtlSeconds));
    }

    public String getClientCorrelation(String serverCorrelation) {
        return redisTemplate.opsForValue().get(keyPrefix + serverCorrelation);
    }
}
