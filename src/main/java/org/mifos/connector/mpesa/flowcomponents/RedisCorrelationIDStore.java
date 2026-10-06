package org.mifos.connector.mpesa.flowcomponents;

import org.mifos.connector.mpesa.config.RedisStoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.time.Duration;

@Component
@ConditionalOnExpression("!'${mpesa-connector.redis.type:redis}'.equalsIgnoreCase('memory')")
public class RedisCorrelationIDStore implements CorrelationIDStore {

    private static final Logger log = LoggerFactory.getLogger(RedisCorrelationIDStore.class);

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;
    private final long correlationTtlSeconds;

    public RedisCorrelationIDStore(StringRedisTemplate redisTemplate, RedisStoreProperties props) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix = props.getKeyPrefix() + ":correlation:";
        this.correlationTtlSeconds = props.getTtl().getCorrelationSeconds();
    }

    @PostConstruct
    void logStoreBackend() {
        log.info("Correlation id store backend: redis");
    }

    @Override
    public void addMapping(String serverCorrelation, String clientCorrelation) {
        redisTemplate.opsForValue().set(keyPrefix + serverCorrelation, clientCorrelation, Duration.ofSeconds(correlationTtlSeconds));
    }

    @Override
    public String getClientCorrelation(String serverCorrelation) {
        return redisTemplate.opsForValue().get(keyPrefix + serverCorrelation);
    }
}
