package org.mifos.connector.mpesa.payout;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@ConditionalOnExpression("'${mpesa-connector.redis.type:redis}'.equalsIgnoreCase('memory')")
public class InMemoryPayoutStore implements PayoutStore {

    private final Map<String, PayoutRecord> payouts = new ConcurrentHashMap<>();

    @PostConstruct
    void logStoreBackend() {
        log.info("Payout store backend: memory");
    }

    @Override
    public void save(PayoutRecord record) {
        payouts.put(record.transactionId(), record);
    }

    @Override
    public PayoutRecord find(String transactionId) {
        return payouts.get(transactionId);
    }
}
