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
public class InMemoryCorrelationIDStore implements CorrelationIDStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryCorrelationIDStore.class);

    private final Map<String, String> correlations = new ConcurrentHashMap<>();
    private final String keyPrefix;

    public InMemoryCorrelationIDStore(RedisStoreProperties props) {
        this.keyPrefix = props.getKeyPrefix() + ":correlation:";
    }

    @PostConstruct
    void logStoreBackend() {
        log.info("Correlation id store backend: memory");
    }

    @Override
    public void addMapping(String serverCorrelation, String clientCorrelation) {
        correlations.put(keyPrefix + serverCorrelation, clientCorrelation);
    }

    @Override
    public String getClientCorrelation(String serverCorrelation) {
        return correlations.get(keyPrefix + serverCorrelation);
    }
}
