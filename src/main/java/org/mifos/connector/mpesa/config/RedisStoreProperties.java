package org.mifos.connector.mpesa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "mpesa-connector.redis")
public class RedisStoreProperties {

    private String keyPrefix = "mpesa-connector";
    private Ttl ttl = new Ttl();

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public Ttl getTtl() {
        return ttl;
    }

    public void setTtl(Ttl ttl) {
        this.ttl = ttl;
    }

    public static class Ttl {
        private long correlationSeconds = 259200;
        private long paybillReconciledSeconds = 900;
        private long paybillWorkflowSeconds = 172800;

        public long getCorrelationSeconds() {
            return correlationSeconds;
        }

        public void setCorrelationSeconds(long correlationSeconds) {
            this.correlationSeconds = correlationSeconds;
        }

        public long getPaybillReconciledSeconds() {
            return paybillReconciledSeconds;
        }

        public void setPaybillReconciledSeconds(long paybillReconciledSeconds) {
            this.paybillReconciledSeconds = paybillReconciledSeconds;
        }

        public long getPaybillWorkflowSeconds() {
            return paybillWorkflowSeconds;
        }

        public void setPaybillWorkflowSeconds(long paybillWorkflowSeconds) {
            this.paybillWorkflowSeconds = paybillWorkflowSeconds;
        }
    }
}
