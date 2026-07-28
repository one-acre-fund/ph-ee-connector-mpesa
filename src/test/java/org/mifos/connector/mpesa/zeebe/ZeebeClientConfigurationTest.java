package org.mifos.connector.mpesa.zeebe;

import io.camunda.zeebe.client.ZeebeClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ZeebeClientConfigurationTest {

    @Test
    void setup_buildsClientWithConfiguredKeepAliveAndRequestTimeout() {
        ZeebeClientConfiguration configuration = new ZeebeClientConfiguration();
        ReflectionTestUtils.setField(configuration, "zeebeBrokerContactpoint", "localhost:26500");
        ReflectionTestUtils.setField(configuration, "zeebeClientMaxThreads", 10);
        ReflectionTestUtils.setField(configuration, "keepAlive", Duration.ofSeconds(30));
        ReflectionTestUtils.setField(configuration, "requestTimeout", Duration.ofSeconds(30));

        try (ZeebeClient client = configuration.setup()) {
            assertNotNull(client);
            assertEquals(Duration.ofSeconds(30), client.getConfiguration().getKeepAlive());
            assertEquals(Duration.ofSeconds(30), client.getConfiguration().getDefaultRequestTimeout());
            assertEquals(10, client.getConfiguration().getNumJobWorkerExecutionThreads());
            assertEquals("localhost:26500", client.getConfiguration().getGatewayAddress());
        }
    }
}
