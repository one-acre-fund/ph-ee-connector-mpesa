package org.mifos.connector.mpesa.flowcomponents.transaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.http.base.HttpOperationFailedException;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mifos.connector.mpesa.zeebe.ZeebeMocks;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.ERROR_CODE;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.ERROR_DESCRIPTION;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.ERROR_INFORMATION;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.IS_RETRY_EXCEEDED;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.IS_TRANSACTION_PENDING;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.LAST_RESPONSE_BODY;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.ZEEBE_ELEMENT_INSTANCE_KEY;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.CALLBACK;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.CALLBACK_RECEIVED;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.GET_TRANSACTION_STATUS_RESPONSE;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.GET_TRANSACTION_STATUS_RESPONSE_CODE;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.SERVER_TRANSACTION_ID;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.SERVER_TRANSACTION_RECEIPT_NUMBER;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.SERVER_TRANSACTION_STATUS_RETRY_COUNT;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.TIMER;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.TRANSACTION_FAILED;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.TRANSACTION_ID;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.TRANSFER_CREATE_FAILED;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.TRANSFER_MESSAGE;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.TRANSFER_RESPONSE_CREATE;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class CollectionResponseProcessorTest {

    private ZeebeMocks zeebe;
    private CollectionResponseProcessor processor;
    private Exchange exchange;

    @BeforeEach
    void setUp() {
        zeebe = new ZeebeMocks();
        processor = new CollectionResponseProcessor(zeebe.client);
        ReflectionTestUtils.setField(processor, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(processor, "timeToLive", 30000);
        ReflectionTestUtils.setField(processor, "requestTimeout", Duration.ofSeconds(5));
        exchange = new DefaultExchange(new DefaultCamelContext());
    }

    @Test
    void pendingTransaction_shouldSetVariablesWithNextTimerAndStatusResponse() throws Exception {
        exchange.setProperty(SERVER_TRANSACTION_STATUS_RETRY_COUNT, 2);
        exchange.setProperty(IS_TRANSACTION_PENDING, true);
        exchange.setProperty(TIMER, "PT30S");
        exchange.setProperty(LAST_RESPONSE_BODY, "{\"ResultCode\":\"1037\"}");
        exchange.setProperty(ZEEBE_ELEMENT_INSTANCE_KEY, 99L);
        exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);

        processor.process(exchange);

        verify(zeebe.client).newSetVariablesCommand(99L);
        Map<String, Object> variables = zeebe.setVariablesSent();
        assertEquals("PT32S", variables.get(TIMER));
        assertEquals(2, variables.get(SERVER_TRANSACTION_STATUS_RETRY_COUNT));
        assertEquals("{\"ResultCode\":\"1037\"}", variables.get(GET_TRANSACTION_STATUS_RESPONSE));
        assertEquals(200, variables.get(GET_TRANSACTION_STATUS_RESPONSE_CODE));
        verify(zeebe.client, never()).newPublishMessageCommand();
    }

    @Test
    void pendingTransaction_shouldFallBackToResponseTextAndExceptionStatusCode() throws Exception {
        exchange.setProperty(SERVER_TRANSACTION_STATUS_RETRY_COUNT, 1);
        exchange.setProperty(IS_TRANSACTION_PENDING, true);
        exchange.setProperty(IS_RETRY_EXCEEDED, false);
        exchange.setProperty(TIMER, "PT2S");
        exchange.setProperty(ZEEBE_ELEMENT_INSTANCE_KEY, 99L);
        exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_TEXT, "Service Unavailable");
        exchange.setProperty(Exchange.EXCEPTION_CAUGHT, new HttpOperationFailedException(
                "http://mpesa", 503, "Service Unavailable", null, Collections.emptyMap(), ""));

        processor.process(exchange);

        Map<String, Object> variables = zeebe.setVariablesSent();
        assertEquals("Service Unavailable", variables.get(GET_TRANSACTION_STATUS_RESPONSE));
        assertEquals("PT4S", variables.get(TIMER));
    }

    @Test
    void pendingTransaction_withNonHttpException_shouldStillSetVariables() throws Exception {
        exchange.setProperty(SERVER_TRANSACTION_STATUS_RETRY_COUNT, 1);
        exchange.setProperty(IS_TRANSACTION_PENDING, true);
        exchange.setProperty(TIMER, "PT2S");
        exchange.setProperty(ZEEBE_ELEMENT_INSTANCE_KEY, 99L);
        exchange.setProperty(Exchange.EXCEPTION_CAUGHT, new IllegalStateException("boom"));

        processor.process(exchange);

        Map<String, Object> variables = zeebe.setVariablesSent();
        assertNull(variables.get(GET_TRANSACTION_STATUS_RESPONSE_CODE));
    }

    @Test
    void failedTransaction_shouldPublishErrorDetails() throws Exception {
        exchange.setProperty(TRANSACTION_FAILED, true);
        exchange.setProperty(ERROR_INFORMATION, "{\"error\":true}");
        exchange.setProperty(ERROR_CODE, "1032");
        exchange.setProperty(ERROR_DESCRIPTION, "Request cancelled by user.");
        exchange.setProperty(TRANSACTION_ID, "tx-1");

        processor.process(exchange);

        verify(zeebe.publishStep1).messageName(TRANSFER_MESSAGE);
        verify(zeebe.publishStep2).correlationKey("tx-1");
        verify(zeebe.publish).timeToLive(Duration.ofMillis(30000));
        Map<String, Object> variables = zeebe.publishedVariables();
        assertEquals(true, variables.get(TRANSACTION_FAILED));
        assertEquals(true, variables.get(TRANSFER_CREATE_FAILED));
        assertEquals("{\"error\":true}", variables.get(ERROR_INFORMATION));
        assertEquals("1032", variables.get(ERROR_CODE));
        assertEquals("Request cancelled by user.", variables.get(ERROR_DESCRIPTION));
        assertTrue(variables.containsKey(TRANSFER_RESPONSE_CREATE));
    }

    @Test
    void retryExceeded_shouldPublishFailureWithoutErrorDetailsOrStatusResponse() throws Exception {
        exchange.setProperty(SERVER_TRANSACTION_STATUS_RETRY_COUNT, 5);
        exchange.setProperty(IS_RETRY_EXCEEDED, true);
        exchange.setProperty(IS_TRANSACTION_PENDING, true);
        exchange.setProperty(TRANSACTION_FAILED, true);
        exchange.setProperty(ERROR_CODE, "1032");
        exchange.setProperty(TRANSACTION_ID, "tx-1");

        processor.process(exchange);

        verify(zeebe.client, never()).newSetVariablesCommand(anyLong());
        Map<String, Object> variables = zeebe.publishedVariables();
        assertEquals(true, variables.get(TRANSACTION_FAILED));
        assertEquals(5, variables.get(SERVER_TRANSACTION_STATUS_RETRY_COUNT));
        assertFalse(variables.containsKey(ERROR_CODE));
        assertFalse(variables.containsKey(GET_TRANSACTION_STATUS_RESPONSE));
    }

    @Test
    void successfulTransaction_shouldPublishReceiptAndCallback() throws Exception {
        exchange.setProperty(TRANSACTION_FAILED, false);
        exchange.setProperty(IS_TRANSACTION_PENDING, false);
        exchange.setProperty(SERVER_TRANSACTION_ID, "ws_CO_1");
        exchange.setProperty(SERVER_TRANSACTION_RECEIPT_NUMBER, "RKTQDM7W6S");
        exchange.setProperty(CALLBACK, "{callback}");
        exchange.setProperty(CALLBACK_RECEIVED, true);
        exchange.setProperty(TRANSACTION_ID, "tx-1");

        processor.process(exchange);

        Map<String, Object> variables = zeebe.publishedVariables();
        assertEquals(false, variables.get(TRANSACTION_FAILED));
        assertEquals(false, variables.get(TRANSFER_CREATE_FAILED));
        assertEquals("ws_CO_1", variables.get(SERVER_TRANSACTION_ID));
        assertEquals("RKTQDM7W6S", variables.get(SERVER_TRANSACTION_RECEIPT_NUMBER));
        assertEquals("{callback}", variables.get(CALLBACK));
        assertEquals(true, variables.get(CALLBACK_RECEIVED));
    }

    @Test
    void successfulTransaction_withoutReceiptOrCallback_shouldOmitThem() throws Exception {
        exchange.setProperty(TRANSACTION_ID, "tx-1");

        processor.process(exchange);

        Map<String, Object> variables = zeebe.publishedVariables();
        assertEquals(false, variables.get(TRANSACTION_FAILED));
        assertFalse(variables.containsKey(SERVER_TRANSACTION_RECEIPT_NUMBER));
        assertFalse(variables.containsKey(CALLBACK));
    }

    @Test
    void missingCorrelationId_shouldRespond404WithoutPublishing() throws Exception {
        exchange.setProperty(SERVER_TRANSACTION_ID, "ws_CO_unknown");

        processor.process(exchange);

        assertEquals(404, exchange.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE));
        String body = exchange.getIn().getBody(String.class);
        assertTrue(body.contains("ws_CO_unknown"));
        assertTrue(body.contains("zeebeVariables"));
        verify(zeebe.client, never()).newPublishMessageCommand();
    }
}
