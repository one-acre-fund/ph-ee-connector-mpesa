package org.mifos.connector.mpesa.flowcomponents.transaction;

import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;
import org.mifos.connector.mpesa.dto.ErrorCode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.IS_ERROR_RECOVERABLE;

class ErrorProcessorTest {

    private final ErrorProcessor processor = new ErrorProcessor();

    @Test
    void emptyList_shouldBeNonRecoverable() throws Exception {
        Exchange exchange = exchangeWith(new ArrayList<>());

        processor.process(exchange);

        assertFalse(exchange.getProperty(IS_ERROR_RECOVERABLE, Boolean.class));
    }

    @Test
    void firstMatch_shouldDecideRecoverabilityAndClearBody() throws Exception {
        ErrorCode recoverable = new ErrorCode();
        recoverable.setErrorCode("1037");
        recoverable.setRecoverable(true);
        ErrorCode other = new ErrorCode();
        other.setRecoverable(false);
        Exchange exchange = exchangeWith(List.of(recoverable, other));

        processor.process(exchange);

        assertTrue(exchange.getProperty(IS_ERROR_RECOVERABLE, Boolean.class));
        assertEquals("", exchange.getIn().getBody());
    }

    private static Exchange exchangeWith(List<ErrorCode> codes) {
        Exchange exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.getIn().setBody(codes);
        return exchange;
    }
}
