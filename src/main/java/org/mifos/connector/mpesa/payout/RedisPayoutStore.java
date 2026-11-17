package org.mifos.connector.mpesa.payout;

import lombok.extern.slf4j.Slf4j;
import org.mifos.connector.mpesa.config.RedisStoreProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.time.Duration;

@Slf4j
@Component
@Primary
@ConditionalOnExpression("!'${mpesa-connector.redis.type:redis}'.equalsIgnoreCase('memory')")
public class RedisPayoutStore implements PayoutStore {

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;
    private final Duration ttl;

    public RedisPayoutStore(StringRedisTemplate redisTemplate, RedisStoreProperties props) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix = props.getKeyPrefix() + ":payout:";
        this.ttl = Duration.ofSeconds(props.getTtl().getPayoutSeconds());
    }

    @PostConstruct
    void logStoreBackend() {
        log.info("Payout store backend: redis");
    }

    @Override
    public void save(PayoutRecord payout) {
        redisTemplate.opsForValue().set(keyPrefix + payout.transactionId(), payout.toJson(), ttl);
    }

    @Override
    public PayoutRecord find(String transactionId) {
        var json = redisTemplate.opsForValue().get(keyPrefix + transactionId);
        return json == null ? null : PayoutRecord.fromJson(json);
    }
}
