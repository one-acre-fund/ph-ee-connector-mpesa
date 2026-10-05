package org.mifos.connector.mpesa.flowcomponents.mpesa;

import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.MPESA_API_RESPONSE;

class MpesaGenericProcessorTest {

    @Test
    void process_shouldStoreBodyAsApiResponse() throws Exception {
        Exchange exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.getIn().setBody("{\"ResponseCode\":\"0\"}");

        new MpesaGenericProcessor().process(exchange);

        assertEquals("{\"ResponseCode\":\"0\"}", exchange.getProperty(MPESA_API_RESPONSE));
    }
}
