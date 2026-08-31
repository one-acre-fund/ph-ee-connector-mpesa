package org.mifos.connector.mpesa.flowcomponents;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class CorrelationIDStore {

    static final String KEY_PREFIX = "mpesa:correlation:";

    private final StringRedisTemplate redisTemplate;

    @Value("${redis.ttl.correlation-hours}")
    private long correlationTtlHours;

    public CorrelationIDStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void addMapping(String serverCorrelation, String clientCorrelation) {
        redisTemplate.opsForValue().set(KEY_PREFIX + serverCorrelation, clientCorrelation, correlationTtlHours, TimeUnit.HOURS);
    }

    public String getClientCorrelation(String serverCorrelation) {
        return redisTemplate.opsForValue().get(KEY_PREFIX + serverCorrelation);
    }
}
