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
import static org.mifos.connector.mpesa.camel.config.CamelProperties.CORRELATION_ID;
import static org.mifos.connector.mpesa.payout.PayoutTestData.TRANSACTION_ID;
import static org.mifos.connector.mpesa.payout.PayoutVariables.PAYOUT_STATUS_CHECKS_EXHAUSTED;
import static org.mifos.connector.mpesa.payout.PayoutVariables.PAYOUT_STATUS_RETRY_COUNT;
import static org.mifos.connector.mpesa.payout.PayoutVariables.PAYOUT_TIMER;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.AMS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PayoutStatusWorkerTest {

    private ZeebeMocks zeebe;
    private ProducerTemplate producerTemplate;
    private JobClient jobClient;
    private PayoutStatusWorker worker;

    @BeforeEach
    void setUp() {
        zeebe = new ZeebeMocks();
        producerTemplate = mock(ProducerTemplate.class);
        jobClient = ZeebeMocks.jobClient();
        MpesaUtils mpesaUtils = mock(MpesaUtils.class);
        when(mpesaUtils.getMpesaProperties("fineract", TRANSACTION_ID)).thenReturn(PayoutTestData.account());
        worker = new PayoutStatusWorker(zeebe.client, new ZeebeWorkerDrain(Duration.ofSeconds(5), Duration.ofSeconds(5)),
                producerTemplate, new DefaultCamelContext(), mpesaUtils);
        ReflectionTestUtils.setField(worker, "workerMaxJobs", 10);
        ReflectionTestUtils.setField(worker, "requestTimeout", Duration.ofSeconds(5));
        ReflectionTestUtils.setField(worker, "maxChecks", 3);
        ReflectionTestUtils.setField(worker, "maxTimer", Duration.ofMinutes(30));
    }

    @Test
    void checkStatus_shouldQueryCountTheCheckAndDoubleTheTimer() throws Exception {
        doAnswer(invocation -> {
            Exchange exchange = invocation.getArgument(1);
            assertEquals(TRANSACTION_ID, exchange.getProperty(CORRELATION_ID));
            return exchange;
        }).when(producerTemplate).send(eq("direct:b2c-status-base"), any(Exchange.class));

        handle(variables(1, "PT2M"));

        verify(producerTemplate).send(eq("direct:b2c-status-base"), any(Exchange.class));
        Map<String, Object> completed = completedVariables();
        assertEquals(2, completed.get(PAYOUT_STATUS_RETRY_COUNT));
        assertEquals(false, completed.get(PAYOUT_STATUS_CHECKS_EXHAUSTED));
        assertEquals("PT4M", completed.get(PAYOUT_TIMER));
    }

    @Test
    void checkStatus_whenQueryFails_shouldStillCompleteSoTheNextCheckRuns() throws Exception {
        doAnswer(invocation -> {
            Exchange exchange = invocation.getArgument(1);
            exchange.setException(new IllegalStateException("boom"));
            return exchange;
        }).when(producerTemplate).send(anyString(), any(Exchange.class));

        handle(variables(0, "PT2M"));

        assertEquals(1, completedVariables().get(PAYOUT_STATUS_RETRY_COUNT));
    }

    @Test
    void checkStatus_afterTheLastCheck_shouldHandTheUnknownPayoutToAnOperator() throws Exception {
        handle(variables(3, "PT30M"));

        verify(producerTemplate, never()).send(anyString(), any(Exchange.class));
        Map<String, Object> completed = completedVariables();
        assertEquals(true, completed.get(PAYOUT_STATUS_CHECKS_EXHAUSTED));
        assertEquals(4, completed.get(PAYOUT_STATUS_RETRY_COUNT));
    }

    @Test
    void nextTimer_shouldDoubleUpToTheMaximum() {
        assertEquals(Duration.ofMinutes(4), PayoutStatusWorker.nextTimer("PT2M", Duration.ofMinutes(30)));
        assertEquals(Duration.ofMinutes(30), PayoutStatusWorker.nextTimer("PT20M", Duration.ofMinutes(30)));
        assertEquals(Duration.ofMinutes(30), PayoutStatusWorker.nextTimer(null, Duration.ofMinutes(30)));
        assertEquals(Duration.ofMinutes(30), PayoutStatusWorker.nextTimer("soon", Duration.ofMinutes(30)));
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

    private static Map<String, Object> variables(int checks, String timer) {
        Map<String, Object> variables = new HashMap<>();
        variables.put(ZeebeVariables.TRANSACTION_ID, TRANSACTION_ID);
        variables.put(AMS, "fineract");
        variables.put(PAYOUT_STATUS_RETRY_COUNT, checks);
        variables.put(PAYOUT_TIMER, timer);
        return variables;
    }
}
