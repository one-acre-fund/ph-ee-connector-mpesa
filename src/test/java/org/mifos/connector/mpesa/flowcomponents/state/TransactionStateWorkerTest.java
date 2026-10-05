package org.mifos.connector.mpesa.flowcomponents.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.zeebe.client.api.worker.JobClient;
import io.camunda.zeebe.client.api.worker.JobHandler;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mifos.connector.mpesa.dto.BuyGoodsPaymentRequestDTO;
import org.mifos.connector.mpesa.utility.SafaricomUtils;
import org.mifos.connector.mpesa.zeebe.ZeebeMocks;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.BUY_GOODS_REQUEST_BODY;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.CORRELATION_ID;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.DEPLOYED_PROCESS;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.ZEEBE_ELEMENT_INSTANCE_KEY;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.AMS;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.SERVER_TRANSACTION_ID;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.SERVER_TRANSACTION_STATUS_RETRY_COUNT;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.TIMER;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.TRANSACTION_FAILED;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.TRANSACTION_ID;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.TRANSFER_MESSAGE;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransactionStateWorkerTest {

    private static final String CHANNEL_REQUEST = "{\"payer\":[{\"key\":\"MSISDN\",\"value\":\"254708374149\"},"
            + "{\"key\":\"ACCOUNTID\",\"value\":\"12345\"}],\"amount\":{\"amount\":\"10\",\"currency\":\"KES\"}}";

    private ZeebeMocks zeebe;
    private TransactionStateWorker worker;
    private ProducerTemplate producerTemplate;
    private SafaricomUtils safaricomUtils;
    private JobClient jobClient;

    @BeforeEach
    void setUp() {
        zeebe = new ZeebeMocks();
        producerTemplate = mock(ProducerTemplate.class);
        safaricomUtils = mock(SafaricomUtils.class);
        jobClient = ZeebeMocks.jobClient();

        worker = new TransactionStateWorker();
        ReflectionTestUtils.setField(worker, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(worker, "zeebeClient", zeebe.client);
        ReflectionTestUtils.setField(worker, "producerTemplate", producerTemplate);
        ReflectionTestUtils.setField(worker, "camelContext", new DefaultCamelContext());
        ReflectionTestUtils.setField(worker, "safaricomUtils", safaricomUtils);
        ReflectionTestUtils.setField(worker, "workerMaxJobs", 10);
        ReflectionTestUtils.setField(worker, "skipMpesa", false);
        ReflectionTestUtils.setField(worker, "requestTimeout", Duration.ofSeconds(5));
    }

    @Test
    void setupWorkers_shouldRegisterTransactionStatusWorker() {
        worker.setupWorkers();

        verify(zeebe.workerStep1).jobType("get-transaction-status");
        verify(zeebe.workerStep3).name("get-transaction-status");
        verify(zeebe.workerStep3).maxJobsActive(10);
    }

    @Test
    void statusCheck_shouldIncrementRetryCountAndSendStatusRequest() throws Exception {
        BuyGoodsPaymentRequestDTO dto = new BuyGoodsPaymentRequestDTO();
        dto.setPassword("secret-password");
        when(safaricomUtils.channelRequestConvertor(any(), eq("tx-1"), eq("roster"))).thenReturn(dto);
        doAnswer(invocation -> {
            Exchange exchange = invocation.getArgument(1);
            assertEquals("tx-1", exchange.getProperty(CORRELATION_ID));
            assertEquals("tx-1", exchange.getProperty(TRANSACTION_ID));
            assertEquals("ws_CO_1", exchange.getProperty(SERVER_TRANSACTION_ID));
            assertSame(dto, exchange.getProperty(BUY_GOODS_REQUEST_BODY));
            assertEquals(3, exchange.getProperty(SERVER_TRANSACTION_STATUS_RETRY_COUNT));
            assertEquals(99L, exchange.getProperty(ZEEBE_ELEMENT_INSTANCE_KEY));
            assertEquals("PT30S", exchange.getProperty(TIMER));
            assertEquals("mpesa_flow_roster-kenya", exchange.getProperty(DEPLOYED_PROCESS));
            return exchange;
        }).when(producerTemplate).send(eq("direct:get-transaction-status-base"), any(Exchange.class));
        Map<String, Object> variables = variables();
        variables.put(SERVER_TRANSACTION_STATUS_RETRY_COUNT, 2);

        handler().handle(jobClient, ZeebeMocks.job(variables));

        verify(producerTemplate).send(eq("direct:get-transaction-status-base"), any(Exchange.class));
        verify(jobClient).newCompleteCommand(1L);
    }

    @Test
    void statusCheck_withoutRetryCount_shouldStartAtOne() throws Exception {
        when(safaricomUtils.channelRequestConvertor(any(), any(), any())).thenReturn(new BuyGoodsPaymentRequestDTO());
        doAnswer(invocation -> {
            Exchange exchange = invocation.getArgument(1);
            assertEquals(1, exchange.getProperty(SERVER_TRANSACTION_STATUS_RETRY_COUNT));
            return exchange;
        }).when(producerTemplate).send(anyString(), any(Exchange.class));

        handler().handle(jobClient, ZeebeMocks.job(variables()));

        verify(producerTemplate).send(eq("direct:get-transaction-status-base"), any(Exchange.class));
    }

    @Test
    void skipMpesa_shouldPublishSuccessMessageAndComplete() throws Exception {
        ReflectionTestUtils.setField(worker, "skipMpesa", true);

        handler().handle(jobClient, ZeebeMocks.job(variables()));

        verify(zeebe.publishStep1).messageName(TRANSFER_MESSAGE);
        verify(zeebe.publishStep2).correlationKey("tx-1");
        Map<String, Object> published = zeebe.publishedVariables();
        assertEquals(false, published.get(TRANSACTION_FAILED));
        assertEquals(1, published.get(SERVER_TRANSACTION_STATUS_RETRY_COUNT));
        verify(producerTemplate, never()).send(anyString(), any(Exchange.class));
        verify(jobClient).newCompleteCommand(1L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void skipMpesa_whenPublishFails_shouldFailJobInsteadOfCompleting() throws Exception {
        ReflectionTestUtils.setField(worker, "skipMpesa", true);
        when(zeebe.future.join(anyLong(), any(TimeUnit.class))).thenThrow(new RuntimeException("broker down"));

        handler().handle(jobClient, ZeebeMocks.job(variables()));

        ArgumentCaptor<Long> key = ArgumentCaptor.forClass(Long.class);
        verify(jobClient).newFailCommand(key.capture());
        assertEquals(1L, key.getValue());
        verify(jobClient, never()).newCompleteCommand(anyLong());
    }

    private JobHandler handler() {
        worker.setupWorkers();
        return zeebe.handlers().get(0);
    }

    private static Map<String, Object> variables() {
        Map<String, Object> variables = new HashMap<>();
        variables.put(TRANSACTION_ID, "tx-1");
        variables.put(AMS, "roster");
        variables.put(SERVER_TRANSACTION_ID, "ws_CO_1");
        variables.put(TIMER, "PT30S");
        variables.put("mpesaChannelRequest", CHANNEL_REQUEST);
        return variables;
    }
}
