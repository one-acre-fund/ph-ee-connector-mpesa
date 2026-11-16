package org.mifos.connector.mpesa.payout;

import io.camunda.zeebe.client.api.worker.JobClient;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mifos.connector.mpesa.utility.MpesaUtils;
import org.mifos.connector.mpesa.zeebe.ZeebeMocks;
import org.mifos.connector.mpesa.zeebe.ZeebeVariables;
import org.mifos.connector.mpesa.zeebe.ZeebeWorkerDrain;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.CORRELATION_ID;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.ERROR_CODE;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.ERROR_DESCRIPTION;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.MPESA_API_HOST;
import static org.mifos.connector.mpesa.payout.PayoutTestData.TRANSACTION_ID;
import static org.mifos.connector.mpesa.payout.PayoutTestData.payoutRequest;
import static org.mifos.connector.mpesa.payout.PayoutVariables.B2C_REQUEST_BODY;
import static org.mifos.connector.mpesa.payout.PayoutVariables.Outcome;
import static org.mifos.connector.mpesa.payout.PayoutVariables.PAYOUT_ACCEPTED;
import static org.mifos.connector.mpesa.payout.PayoutVariables.PAYOUT_ACCOUNT;
import static org.mifos.connector.mpesa.payout.PayoutVariables.PAYOUT_CONVERSATION_ID;
import static org.mifos.connector.mpesa.payout.PayoutVariables.PAYOUT_FAILED;
import static org.mifos.connector.mpesa.payout.PayoutVariables.PAYOUT_OUTCOME;
import static org.mifos.connector.mpesa.payout.PayoutVariables.PAYOUT_REQUEST;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.AMS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PayoutWorkerTest {

    private ZeebeMocks zeebe;
    private ProducerTemplate producerTemplate;
    private PayoutStore store;
    private JobClient jobClient;
    private PayoutWorker worker;

    @BeforeEach
    void setUp() {
        zeebe = new ZeebeMocks();
        producerTemplate = mock(ProducerTemplate.class);
        store = new InMemoryPayoutStore();
        jobClient = ZeebeMocks.jobClient();
        MpesaUtils mpesaUtils = mock(MpesaUtils.class);
        when(mpesaUtils.getMpesaProperties("fineract", TRANSACTION_ID)).thenReturn(PayoutTestData.account());
        worker = new PayoutWorker(zeebe.client, new ZeebeWorkerDrain(Duration.ofSeconds(5), Duration.ofSeconds(5)),
                producerTemplate, new DefaultCamelContext(), mpesaUtils, PayoutTestData.requestBuilder(), store);
        ReflectionTestUtils.setField(worker, "workerMaxJobs", 10);
        ReflectionTestUtils.setField(worker, "requestTimeout", Duration.ofSeconds(5));
    }

    @Test
    void setupWorkers_shouldRegisterInitPayout() {
        worker.setupWorkers();

        verify(zeebe.workerStep1).jobType("init-mpesa-payout");
        verify(zeebe.workerStep3).name("init-mpesa-payout");
    }

    @Test
    void initPayout_accepted_shouldSaveThePayoutBeforeSendingAndWaitForTheResult() throws Exception {
        doAnswer(invocation -> {
            Exchange exchange = invocation.getArgument(1);
            assertNotNull(store.find(TRANSACTION_ID), "payout must be saved before it is sent");
            B2CPaymentRequestDTO request = exchange.getProperty(B2C_REQUEST_BODY, B2CPaymentRequestDTO.class);
            assertEquals(TRANSACTION_ID, request.getOriginatorConversationId());
            assertEquals(TRANSACTION_ID, exchange.getProperty(CORRELATION_ID));
            assertEquals("https://sandbox.safaricom.co.ke", exchange.getProperty(MPESA_API_HOST));
            assertNotNull(exchange.getProperty(PAYOUT_ACCOUNT));
            exchange.setProperty(PAYOUT_OUTCOME, Outcome.ACCEPTED);
            exchange.setProperty(PAYOUT_CONVERSATION_ID, "AG_1");
            return exchange;
        }).when(producerTemplate).send(eq("direct:b2c-payment-base"), any(Exchange.class));

        handle(variables(payoutRequest("254708374149", "1500")));

        Map<String, Object> completed = completedVariables();
        assertEquals(false, completed.get(PAYOUT_FAILED));
        assertEquals(true, completed.get(PAYOUT_ACCEPTED));
        assertEquals("AG_1", completed.get(PAYOUT_CONVERSATION_ID));
        PayoutRecord saved = store.find(TRANSACTION_ID);
        assertEquals(1500, saved.amount());
        assertEquals(254708374149L, saved.msisdn());
        assertEquals("fineract", saved.account());
    }

    @Test
    void initPayout_unknown_shouldWaitForTheResultWithoutFailing() throws Exception {
        doAnswer(invocation -> {
            Exchange exchange = invocation.getArgument(1);
            exchange.setProperty(PAYOUT_OUTCOME, Outcome.UNKNOWN);
            return exchange;
        }).when(producerTemplate).send(eq("direct:b2c-payment-base"), any(Exchange.class));

        handle(variables(payoutRequest("254708374149", "1500")));

        Map<String, Object> completed = completedVariables();
        assertEquals(false, completed.get(PAYOUT_FAILED));
        assertEquals(false, completed.get(PAYOUT_ACCEPTED));
    }

    @Test
    void initPayout_failed_shouldCompleteAsFailedWithReason() throws Exception {
        doAnswer(invocation -> {
            Exchange exchange = invocation.getArgument(1);
            exchange.setProperty(PAYOUT_OUTCOME, Outcome.FAILED);
            exchange.setProperty(ERROR_CODE, "400.002.02");
            exchange.setProperty(ERROR_DESCRIPTION, "Bad Request - Invalid Amount");
            return exchange;
        }).when(producerTemplate).send(eq("direct:b2c-payment-base"), any(Exchange.class));

        handle(variables(payoutRequest("254708374149", "1500")));

        Map<String, Object> completed = completedVariables();
        assertEquals(true, completed.get(PAYOUT_FAILED));
        assertEquals("400.002.02", completed.get(ERROR_CODE));
    }

    @Test
    void initPayout_invalidRequest_shouldFailWithoutCallingMpesa() throws Exception {
        handle(variables(payoutRequest("0708374149", "1500.5")));

        verify(producerTemplate, never()).send(anyString(), any(Exchange.class));
        verify(jobClient, never()).newFailCommand(anyLong());
        Map<String, Object> completed = completedVariables();
        assertEquals(true, completed.get(PAYOUT_FAILED));
        assertTrue(((String) completed.get(ERROR_CODE)).startsWith("INVALID_"));
    }

    @Test
    void initPayout_routeException_shouldRaiseIncidentBecauseThePayoutMayHaveBeenSent() throws Exception {
        doAnswer(invocation -> {
            Exchange exchange = invocation.getArgument(1);
            exchange.setException(new IllegalStateException("Redis timed out"));
            return exchange;
        }).when(producerTemplate).send(eq("direct:b2c-payment-base"), any(Exchange.class));

        handle(variables(payoutRequest("254708374149", "1500")));

        verify(jobClient.newFailCommand(1L)).retries(0);
        verify(jobClient, never()).newCompleteCommand(anyLong());
    }

    private void handle(Map<String, Object> variables) throws Exception {
        worker.setupWorkers();
        zeebe.handlers().get(0).handle(jobClient, ZeebeMocks.job(variables));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> completedVariables() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(jobClient.newCompleteCommand(1L)).variables(captor.capture());
        return captor.getValue();
    }

    private static Map<String, Object> variables(String payoutRequest) {
        Map<String, Object> variables = new HashMap<>();
        variables.put(ZeebeVariables.TRANSACTION_ID, TRANSACTION_ID);
        variables.put(AMS, "fineract");
        variables.put(PAYOUT_REQUEST, payoutRequest);
        return variables;
    }
}
