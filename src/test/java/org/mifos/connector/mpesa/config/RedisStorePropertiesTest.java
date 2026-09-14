package org.mifos.connector.mpesa.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RedisStorePropertiesTest {

    @Test
    void defaults_shouldProvideExpectedValues() {
        RedisStoreProperties properties = new RedisStoreProperties();

        assertEquals("mpesa-connector", properties.getKeyPrefix());
        assertEquals(259200, properties.getTtl().getCorrelationSeconds());
        assertEquals(900, properties.getTtl().getPaybillReconciledSeconds());
        assertEquals(172800, properties.getTtl().getPaybillWorkflowSeconds());
    }

    @Test
    void setters_shouldUpdateConfiguration() {
        RedisStoreProperties properties = new RedisStoreProperties();
        RedisStoreProperties.Ttl ttl = new RedisStoreProperties.Ttl();
        ttl.setCorrelationSeconds(100);
        ttl.setPaybillReconciledSeconds(200);
        ttl.setPaybillWorkflowSeconds(300);

        properties.setKeyPrefix("custom-prefix");
        properties.setTtl(ttl);

        assertEquals("custom-prefix", properties.getKeyPrefix());
        assertEquals(100, properties.getTtl().getCorrelationSeconds());
        assertEquals(200, properties.getTtl().getPaybillReconciledSeconds());
        assertEquals(300, properties.getTtl().getPaybillWorkflowSeconds());
    }
}
