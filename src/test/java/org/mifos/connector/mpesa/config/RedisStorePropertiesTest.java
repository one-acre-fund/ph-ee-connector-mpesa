package org.mifos.connector.mpesa.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisStorePropertiesTest {

    @Test
    void defaults_shouldProvideExpectedValues() {
        RedisStoreProperties properties = new RedisStoreProperties();

        assertEquals("redis", properties.getType());
        assertFalse(properties.isMemoryStore());
        assertEquals("mpesa-connector", properties.getKeyPrefix());
        assertEquals(259200, properties.getTtl().getCorrelationSeconds());
        assertEquals(900, properties.getTtl().getPaybillReconciledSeconds());
        assertEquals(172800, properties.getTtl().getPaybillWorkflowSeconds());
        assertEquals(3600, properties.getTtl().getErrorCodeFreshSeconds());
        assertEquals(2592000, properties.getTtl().getErrorCodeRetentionSeconds());
    }

    @Test
    void setters_shouldUpdateConfiguration() {
        RedisStoreProperties properties = new RedisStoreProperties();
        RedisStoreProperties.Ttl ttl = new RedisStoreProperties.Ttl();
        ttl.setCorrelationSeconds(100);
        ttl.setPaybillReconciledSeconds(200);
        ttl.setPaybillWorkflowSeconds(300);
        ttl.setErrorCodeFreshSeconds(400);
        ttl.setErrorCodeRetentionSeconds(500);

        properties.setType("memory");
        properties.setKeyPrefix("custom-prefix");
        properties.setTtl(ttl);

        assertEquals("memory", properties.getType());
        assertTrue(properties.isMemoryStore());
        assertEquals("custom-prefix", properties.getKeyPrefix());
        assertEquals(100, properties.getTtl().getCorrelationSeconds());
        assertEquals(200, properties.getTtl().getPaybillReconciledSeconds());
        assertEquals(300, properties.getTtl().getPaybillWorkflowSeconds());
        assertEquals(400, properties.getTtl().getErrorCodeFreshSeconds());
        assertEquals(500, properties.getTtl().getErrorCodeRetentionSeconds());
    }

    @ParameterizedTest
    @ValueSource(strings = { "memory", "MEMORY", "Memory", "MeMoRy" })
    void isMemoryStore_shouldBeTrueForMemoryTypeIgnoringCase(String type) {
        RedisStoreProperties properties = new RedisStoreProperties();
        properties.setType(type);

        assertTrue(properties.isMemoryStore());
    }

    @ParameterizedTest
    @CsvSource({ "redis", "REDIS", "Redis", "in-memory", "mem", "mongo" })
    void isMemoryStore_shouldBeFalseForNonMemoryTypes(String type) {
        RedisStoreProperties properties = new RedisStoreProperties();
        properties.setType(type);

        assertFalse(properties.isMemoryStore());
    }

    @ParameterizedTest
    @NullAndEmptySource
    void isMemoryStore_shouldBeFalseForNullOrEmptyType(String type) {
        RedisStoreProperties properties = new RedisStoreProperties();
        properties.setType(type);

        assertFalse(properties.isMemoryStore());
    }
}
