package org.mifos.connector.mpesa.flowcomponents.transaction;

import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.ERROR_CODE;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.ERROR_DESCRIPTION;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.ERROR_INFORMATION;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.SERVER_TRANSACTION_ID;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.TRANSACTION_FAILED;

class TransactionResponseProcessorTest {

    private final TransactionResponseProcessor processor = new TransactionResponseProcessor();

    @Test
    void failedTransaction_shouldExtractErrorCodeAndMessage() {
        String body = "{\"requestId\":\"22749-38515563-2\",\"errorCode\":\"404.001.03\",\"errorMessage\":\"Invalid Access Token\"}";
        Exchange exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.setProperty(TRANSACTION_FAILED, true);
        exchange.getIn().setBody(body);

        processor.process(exchange);

        assertEquals(true, exchange.getProperty(TRANSACTION_FAILED));
        assertEquals(body, exchange.getProperty(ERROR_INFORMATION));
        assertEquals("404.001.03", exchange.getProperty(ERROR_CODE));
        assertEquals("Invalid Access Token", exchange.getProperty(ERROR_DESCRIPTION));
    }

    @Test
    void successfulTransaction_shouldKeepServerIdAndMarkNotFailed() {
        Exchange exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.setProperty(SERVER_TRANSACTION_ID, "ws_CO_1");

        processor.process(exchange);

        assertEquals(false, exchange.getProperty(TRANSACTION_FAILED));
        assertEquals("ws_CO_1", exchange.getProperty(SERVER_TRANSACTION_ID));
    }
}
