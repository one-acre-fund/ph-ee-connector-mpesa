package org.mifos.connector.mpesa.camel.config;

import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class HttpClientConfigurationTest {

    @Test
    void createConnectionManager_appliesTtlIdleValidationAndPoolLimits() {
        HttpClientConfiguration configuration = new HttpClientConfiguration();
        ReflectionTestUtils.setField(configuration, "connectionTimeToLiveMs", 120_000L);
        ReflectionTestUtils.setField(configuration, "connectionIdleEvictMs", 60_000L);
        ReflectionTestUtils.setField(configuration, "validateAfterInactivityMs", 1_000);
        ReflectionTestUtils.setField(configuration, "maxTotalConnections", 200);
        ReflectionTestUtils.setField(configuration, "connectionsPerRoute", 50);

        PoolingHttpClientConnectionManager manager = configuration.createConnectionManager();

        assertNotNull(manager);
        assertEquals(200, manager.getMaxTotal());
        assertEquals(50, manager.getDefaultMaxPerRoute());
        assertEquals(1_000, manager.getValidateAfterInactivity());
        manager.close();
    }
}
