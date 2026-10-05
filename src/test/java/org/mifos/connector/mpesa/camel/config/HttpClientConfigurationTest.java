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

    @Test
    void contextConfiguration_shouldApplyPoolSettingsToHttpComponents() {
        HttpClientConfiguration configuration = configured();
        org.apache.camel.CamelContext context = new org.apache.camel.impl.DefaultCamelContext();

        org.apache.camel.spring.boot.CamelContextConfiguration contextConfiguration =
                configuration.httpClientContextConfiguration();
        contextConfiguration.beforeApplicationStart(context);
        contextConfiguration.afterApplicationStart(context);

        org.apache.camel.component.http.HttpComponent http =
                context.getComponent("http", org.apache.camel.component.http.HttpComponent.class);
        assertEquals(120_000L, http.getConnectionTimeToLive());
        assertEquals(200, http.getMaxTotalConnections());
        assertEquals(50, http.getConnectionsPerRoute());
        assertNotNull(http.getClientConnectionManager());
        assertNotNull(http.getHttpClientConfigurer());
        http.getHttpClientConfigurer().configureHttpClient(org.apache.http.impl.client.HttpClientBuilder.create());
        assertNotNull(context.getComponent("https", org.apache.camel.component.http.HttpComponent.class)
                .getClientConnectionManager());
    }

    @Test
    void configureComponent_shouldIgnoreMissingComponent() {
        ReflectionTestUtils.invokeMethod(configured(), "configureComponent",
                null, new PoolingHttpClientConnectionManager());
    }

    private static HttpClientConfiguration configured() {
        HttpClientConfiguration configuration = new HttpClientConfiguration();
        ReflectionTestUtils.setField(configuration, "connectionTimeToLiveMs", 120_000L);
        ReflectionTestUtils.setField(configuration, "connectionIdleEvictMs", 60_000L);
        ReflectionTestUtils.setField(configuration, "validateAfterInactivityMs", 1_000);
        ReflectionTestUtils.setField(configuration, "maxTotalConnections", 200);
        ReflectionTestUtils.setField(configuration, "connectionsPerRoute", 50);
        return configuration;
    }
}
