package org.mifos.connector.mpesa.flowcomponents;

import org.mifos.connector.mpesa.config.RedisStoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnExpression("'${mpesa-connector.redis.type:redis}'.equalsIgnoreCase('memory')")
public class InMemoryPaybillStateStore implements PaybillStateStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryPaybillStateStore.class);
    private static final String RECONCILED_KEY_PREFIX = "paybill:reconciled:";
    private static final String WORKFLOW_KEY_PREFIX = "paybill:workflow:";

    private final Map<String, String> reconciledStore = new ConcurrentHashMap<>();
    private final Map<String, String> workflowInstanceStore = new ConcurrentHashMap<>();
    private final String keyPrefix;

    public InMemoryPaybillStateStore(RedisStoreProperties redisStoreProperties) {
        this.keyPrefix = redisStoreProperties.getKeyPrefix();
    }

    @PostConstruct
    void logStoreBackend() {
        log.info("Paybill state store backend: memory");
    }

    @Override
    public void putReconciled(String mpesaTxnId, Boolean reconciled) {
        reconciledStore.put(reconciledKey(mpesaTxnId), reconciled.toString());
    }

    @Override
    public Boolean getReconciled(String mpesaTxnId) {
        String value = reconciledStore.get(reconciledKey(mpesaTxnId));
        return value != null ? Boolean.valueOf(value) : null;
    }

    @Override
    public void removeReconciled(String mpesaTxnId) {
        reconciledStore.remove(reconciledKey(mpesaTxnId));
    }

    @Override
    public void putWorkflowInstance(String mpesaTxnId, String workflowInstanceKey) {
        workflowInstanceStore.put(workflowKey(mpesaTxnId), workflowInstanceKey);
    }

    @Override
    public String getWorkflowInstance(String mpesaTxnId) {
        return workflowInstanceStore.get(workflowKey(mpesaTxnId));
    }

    @Override
    public void removeWorkflowInstance(String mpesaTxnId) {
        workflowInstanceStore.remove(workflowKey(mpesaTxnId));
    }

    private String reconciledKey(String mpesaTxnId) {
        return keyPrefix + ":" + RECONCILED_KEY_PREFIX + mpesaTxnId;
    }

    private String workflowKey(String mpesaTxnId) {
        return keyPrefix + ":" + WORKFLOW_KEY_PREFIX + mpesaTxnId;
    }
}
