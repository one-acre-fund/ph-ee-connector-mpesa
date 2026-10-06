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
public class RedisPaybillStateStore implements PaybillStateStore {

    private static final Logger log = LoggerFactory.getLogger(RedisPaybillStateStore.class);
    private static final String RECONCILED_KEY_PREFIX = "paybill:reconciled:";
    private static final String WORKFLOW_KEY_PREFIX = "paybill:workflow:";

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;
    private final long reconciledTtlSeconds;
    private final long workflowTtlSeconds;

    public RedisPaybillStateStore(StringRedisTemplate redisTemplate, RedisStoreProperties redisStoreProperties) {
        this.redisTemplate = redisTemplate;
        this.keyPrefix = redisStoreProperties.getKeyPrefix();
        this.reconciledTtlSeconds = redisStoreProperties.getTtl().getPaybillReconciledSeconds();
        this.workflowTtlSeconds = redisStoreProperties.getTtl().getPaybillWorkflowSeconds();
    }

    @PostConstruct
    void logStoreBackend() {
        log.info("Paybill state store backend: redis");
    }

    @Override
    public void putReconciled(String mpesaTxnId, Boolean reconciled) {
        redisTemplate.opsForValue().set(
                reconciledKey(mpesaTxnId),
                reconciled.toString(),
                Duration.ofSeconds(reconciledTtlSeconds)
        );
    }

    @Override
    public Boolean getReconciled(String mpesaTxnId) {
        String value = redisTemplate.opsForValue().get(reconciledKey(mpesaTxnId));
        return value != null ? Boolean.valueOf(value) : null;
    }

    @Override
    public void removeReconciled(String mpesaTxnId) {
        redisTemplate.delete(reconciledKey(mpesaTxnId));
    }

    @Override
    public void putWorkflowInstance(String mpesaTxnId, String workflowInstanceKey) {
        redisTemplate.opsForValue().set(
                workflowKey(mpesaTxnId),
                workflowInstanceKey,
                Duration.ofSeconds(workflowTtlSeconds)
        );
    }

    @Override
    public String getWorkflowInstance(String mpesaTxnId) {
        return redisTemplate.opsForValue().get(workflowKey(mpesaTxnId));
    }

    @Override
    public void removeWorkflowInstance(String mpesaTxnId) {
        redisTemplate.delete(workflowKey(mpesaTxnId));
    }

    private String reconciledKey(String mpesaTxnId) {
        return keyPrefix + ":" + RECONCILED_KEY_PREFIX + mpesaTxnId;
    }

    private String workflowKey(String mpesaTxnId) {
        return keyPrefix + ":" + WORKFLOW_KEY_PREFIX + mpesaTxnId;
    }
}
