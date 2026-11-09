package org.mifos.connector.mpesa.resilience;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MpesaResiliencePropertiesTest {

    @Test
    void applicationYmlBindsTheDocumentedDefaults() throws IOException {
        MpesaResilienceProperties properties = bind(Map.of());

        assertEquals(Duration.ofSeconds(60), properties.getStkPush().getReadTimeout());
        assertEquals(List.of(), properties.getStkPush().getRetry().getRetryStatusCodes());
        assertFalse(properties.getStkPush().getRetry().isRetryWhenOutcomeUnknown());
        assertEquals(List.of(502, 503, 504), properties.getStkPush().getCircuitBreaker().getFailureStatusCodes());
        assertEquals(List.of(502, 503, 504), properties.getStkQuery().getRetry().getRetryStatusCodes());
        assertTrue(properties.getStkQuery().getRetry().isRetryWhenOutcomeUnknown());
        assertEquals(List.of(500, 502, 503, 504), properties.getToken().getCircuitBreaker().getFailureStatusCodes());
        assertEquals(Duration.ofSeconds(30), properties.getToken().getCircuitBreaker().getWaitDurationInOpenState());
    }

    @Test
    void readTimeoutsFollowTheExistingApiTimeoutAndCanBeOverriddenPerEndpoint() throws IOException {
        MpesaResilienceProperties properties = bind(Map.of("MPESA_API_TIMEOUT", "6000",
                "MPESA_RESILIENCE_STKQUERY_READTIMEOUT", "20s"));

        assertEquals(Duration.ofSeconds(6), properties.getStkPush().getReadTimeout());
        assertEquals(Duration.ofSeconds(6), properties.getToken().getReadTimeout());
        assertEquals(Duration.ofSeconds(20), properties.getStkQuery().getReadTimeout());
    }

    @Test
    void settingsAreLookedUpByEndpoint() {
        MpesaResilienceProperties properties = new MpesaResilienceProperties();

        assertSame(properties.getToken(), properties.settingsFor(MpesaEndpoint.TOKEN));
        assertSame(properties.getStkPush(), properties.settingsFor(MpesaEndpoint.STK_PUSH));
        assertSame(properties.getStkQuery(), properties.settingsFor(MpesaEndpoint.STK_QUERY));
        properties.getStkPush().getRetry().setRetryStatusCodes(null);
        properties.getStkPush().getCircuitBreaker().setFailureStatusCodes(null);
        assertEquals(List.of(), properties.getStkPush().getRetry().getRetryStatusCodes());
        assertEquals(List.of(), properties.getStkPush().getCircuitBreaker().getFailureStatusCodes());
    }

    @Test
    void newEndpointSettingsRetryOnlyGatewayErrors() {
        MpesaResilienceProperties.EndpointSettings settings = new MpesaResilienceProperties.EndpointSettings();

        assertEquals(List.of(502, 503, 504), settings.getRetry().getRetryStatusCodes());
        assertEquals(List.of(502, 503, 504), settings.getCircuitBreaker().getFailureStatusCodes());
        assertTrue(settings.getRetry().isRetryWhenOutcomeUnknown());
    }

    private static MpesaResilienceProperties bind(Map<String, Object> environment) throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        environment));
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(env.getPropertySources()::addLast);
        return Binder.get(env).bind("mpesa.resilience", MpesaResilienceProperties.class)
                .orElseThrow(IllegalStateException::new);
    }
}
