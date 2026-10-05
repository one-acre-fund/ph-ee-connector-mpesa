package org.mifos.connector.mpesa.camel.config;

import org.apache.camel.CamelContext;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.spi.RestConfiguration;
import org.apache.camel.spring.boot.CamelContextConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CamelConfigTest {

    @Test
    void customHeaderFilterStrategy_shouldFilterHopByHopAndCamelHeaders() {
        CustomHeaderFilterStrategy strategy = new CustomHeaderFilterStrategy();

        assertTrue(strategy.getOutFilter().contains("host"));
        assertTrue(strategy.getOutFilter().contains("transfer-encoding"));
        assertTrue(strategy.isLowerCase());
        assertTrue(strategy.applyFilterToCamelHeaders("CamelHttpUri", "x", null));
        assertFalse(strategy.applyFilterToCamelHeaders("X-Custom", "x", null));
    }

    @Test
    void camelContextConfig_shouldConfigureUndertowJsonRest() {
        CamelContextConfig config = new CamelContextConfig();
        ReflectionTestUtils.setField(config, "serverPort", 5000);
        CamelContextConfiguration configuration = config.contextConfiguration();
        CamelContext context = new DefaultCamelContext();

        configuration.beforeApplicationStart(context);
        configuration.afterApplicationStart(context);

        RestConfiguration rest = context.getRestConfiguration();
        assertEquals("undertow", rest.getComponent());
        assertEquals("undertow", rest.getProducerComponent());
        assertEquals(5000, rest.getPort());
        assertEquals(RestConfiguration.RestBindingMode.json, rest.getBindingMode());
        assertEquals("true", rest.getDataFormatProperties().get("prettyPrint"));
        assertEquals("http", rest.getScheme());
        assertTrue(context.isStreamCaching());
        assertFalse(context.isTracing());
    }

    @Test
    void camelProperties_shouldBeInstantiable() {
        assertNotNull(new CamelProperties());
        assertNotNull(new OperationsProperties());
    }
}
