package org.mifos.connector.mpesa;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;
import org.mifos.connector.mpesa.camel.config.CustomHeaderFilterStrategy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MpesaConnectorApplicationTest {

    private final MpesaConnectorApplication application = new MpesaConnectorApplication();

    @Test
    void objectMapper_shouldBeLenientAndWriteIsoDates() throws Exception {
        ObjectMapper mapper = application.objectMapper();

        assertEquals("\"2026-10-05\"", mapper.writeValueAsString(LocalDate.of(2026, 10, 5)));
        assertEquals("{}", mapper.writeValueAsString(new Holder()));
        Holder holder = mapper.readValue("{\"values\":\"single\",\"unknown\":1}", Holder.class);
        assertEquals(List.of("single"), holder.values);
    }

    @Test
    void pojoToString_shouldSerialiseBody() throws Exception {
        Exchange exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.getIn().setBody(Map.of("key", "value"));

        application.pojoToString(application.objectMapper()).process(exchange);

        assertEquals("{\"key\":\"value\"}", exchange.getIn().getBody(String.class));
    }

    @Test
    void headerFilterStrategy_shouldBeCustomStrategy() {
        CustomHeaderFilterStrategy strategy = application.headerFilterStrategy();

        assertNotNull(strategy);
    }

    static class Holder {
        public List<String> values;
    }
}
