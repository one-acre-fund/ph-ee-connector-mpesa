package org.mifos.connector.mpesa.flowcomponents.transaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.zeebe.client.api.worker.JobClient;
import io.camunda.zeebe.client.api.worker.JobHandler;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mifos.connector.mpesa.dto.BuyGoodsPaymentRequestDTO;
import org.mifos.connector.mpesa.flowcomponents.PaybillStateStore;
import org.mifos.connector.mpesa.utility.MpesaUtils;
import org.mifos.connector.mpesa.utility.SafaricomUtils;
import org.mifos.connector.mpesa.zeebe.ZeebeMocks;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.BUY_GOODS_REQUEST_BODY;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.CORRELATION_ID;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.DEPLOYED_PROCESS;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.ERROR_CODE;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.ERROR_DESCRIPTION;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.ERROR_INFORMATION;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.MPESA_API_RESPONSE;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.AMS;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.PARTY_LOOKUP_FSP_ID;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.SERVER_TRANSACTION_ID;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.TRANSACTION_FAILED;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.TRANSACTION_ID;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.TRANSFER_CREATE_FAILED;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.TRANSFER_RESPONSE_CREATE;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MpesaWorkerTest {

    private static final String CHANNEL_REQUEST = "{\"payer\":[{\"key\":\"MSISDN\",\"value\":\"254708374149\"},"
            + "{\"key\":\"ACCOUNTID\",\"value\":\"12345\"}],\"amount\":{\"amount\":\"10\",\"currency\":\"KES\"}}";

    private ZeebeMocks zeebe;
    private MpesaWorker worker;
    private ProducerTemplate producerTemplate;
    private SafaricomUtils safaricomUtils;
    private MpesaUtils mpesaUtils;
    private PaybillStateStore paybillStateStore;
    private JobClient jobClient;

    @BeforeEach
    void setUp() {
        zeebe = new ZeebeMocks();
        producerTemplate = mock(ProducerTemplate.class);
        safaricomUtils = mock(SafaricomUtils.class);
        mpesaUtils = mock(MpesaUtils.class);
        paybillStateStore = mock(PaybillStateStore.class);
        jobClient = ZeebeMocks.jobClient();
        CamelContext camelContext = new DefaultCamelContext();

        worker = new MpesaWorker();
        ReflectionTestUtils.setField(worker, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(worker, "zeebeClient", zeebe.client);
        ReflectionTestUtils.setField(worker, "producerTemplate", producerTemplate);
        ReflectionTestUtils.setField(worker, "camelContext", camelContext);
        ReflectionTestUtils.setField(worker, "safaricomUtils", safaricomUtils);
        ReflectionTestUtils.setField(worker, "mpesaUtils", mpesaUtils);
        ReflectionTestUtils.setField(worker, "paybillStateStore", paybillStateStore);
        ReflectionTestUtils.setField(worker, "workerMaxJobs", 10);
        ReflectionTestUtils.setField(worker, "initTransferWaitTimer", 0);
        ReflectionTestUtils.setField(worker, "skipMpesa", false);
        ReflectionTestUtils.setField(worker, "requestTimeout", Duration.ofSeconds(5));
    }

    @Test
    void setupWorkers_shouldRegisterInitTransferAndCleanupWorkers() {
        worker.setupWorkers();

        verify(zeebe.workerStep1).jobType("init-transfer");
        verify(zeebe.workerStep1).jobType("delete-workflow-instancekey");
        verify(zeebe.workerStep3).name("init-transfer");
        verify(zeebe.workerStep3).name("Cleanup");
        assertEquals(2, zeebe.handlers().size());
    }

    @Test
    void initTransfer_skipMpesa_shouldCompleteAsSuccessWithoutCallingMpesa() throws Exception {
        ReflectionTestUtils.setField(worker, "skipMpesa", true);
        Map<String, Object> variables = variables();

        initTransferHandler().handle(jobClient, ZeebeMocks.job(variables));

        verify(producerTemplate, never()).send(any(String.class), any(Exchange.class));
        Map<String, Object> completed = completedVariables();
        assertEquals(false, completed.get(TRANSACTION_FAILED));
        assertEquals(false, completed.get(TRANSFER_CREATE_FAILED));
    }

    @Test
    void initTransfer_success_shouldSendBuyGoodsAndCompleteWithServerId() throws Exception {
        BuyGoodsPaymentRequestDTO dto = buyGoodsRequest();
        when(safaricomUtils.channelRequestConvertor(any(), eq("tx-1"), eq("roster"))).thenReturn(dto);
        doAnswer(invocation -> {
            Exchange exchange = invocation.getArgument(1);
            assertSame(dto, exchange.getProperty(BUY_GOODS_REQUEST_BODY));
            assertEquals("tx-1", exchange.getProperty(CORRELATION_ID));
            assertEquals("mpesa_flow_roster-kenya", exchange.getProperty(DEPLOYED_PROCESS));
            assertEquals("roster", exchange.getProperty(AMS));
            exchange.setProperty(TRANSACTION_FAILED, false);
            exchange.setProperty(SERVER_TRANSACTION_ID, "ws_CO_1");
            exchange.setProperty(MPESA_API_RESPONSE, "{\"ResponseCode\":\"0\"}");
            exchange.setProperty(PARTY_LOOKUP_FSP_ID, 174379L);
            return exchange;
        }).when(producerTemplate).send(eq("direct:buy-goods-base"), any(Exchange.class));

        initTransferHandler().handle(jobClient, ZeebeMocks.job(variables()));

        verify(mpesaUtils).setProcess("mpesa_flow_roster-kenya");
        Map<String, Object> completed = completedVariables();
        assertEquals(false, completed.get(TRANSACTION_FAILED));
        assertEquals("ws_CO_1", completed.get(SERVER_TRANSACTION_ID));
        assertEquals("{\"ResponseCode\":\"0\"}", completed.get(MPESA_API_RESPONSE));
        assertEquals(174379L, completed.get(PARTY_LOOKUP_FSP_ID));
    }

    @Test
    void initTransfer_failure_shouldCompleteWithErrorDetails() throws Exception {
        when(safaricomUtils.channelRequestConvertor(any(), any(), any())).thenReturn(buyGoodsRequest());
        doAnswer(invocation -> {
            Exchange exchange = invocation.getArgument(1);
            exchange.setProperty(TRANSACTION_FAILED, true);
            exchange.setProperty(ERROR_INFORMATION, "{\"errorCode\":\"500.001.1001\"}");
            exchange.setProperty(ERROR_CODE, "500.001.1001");
            exchange.setProperty(ERROR_DESCRIPTION, "Unable to lock subscriber");
            return exchange;
        }).when(producerTemplate).send(eq("direct:buy-goods-base"), any(Exchange.class));

        initTransferHandler().handle(jobClient, ZeebeMocks.job(variables()));

        Map<String, Object> completed = completedVariables();
        assertEquals(true, completed.get(TRANSACTION_FAILED));
        assertEquals(true, completed.get(TRANSFER_CREATE_FAILED));
        assertEquals("500.001.1001", completed.get(ERROR_CODE));
        assertEquals("Unable to lock subscriber", completed.get(ERROR_DESCRIPTION));
        assertEquals("{\"errorCode\":\"500.001.1001\"}", completed.get(ERROR_INFORMATION));
        assertTrue(completed.containsKey(TRANSFER_RESPONSE_CREATE));
    }

    @Test
    void cleanup_shouldRemoveWorkflowInstanceAndComplete() throws Exception {
        worker.setupWorkers();
        JobHandler cleanup = zeebe.handlers().get(1);
        Map<String, Object> variables = new HashMap<>();
        variables.put("mpesaTxnId", "RKTQDM7W6S");

        cleanup.handle(jobClient, ZeebeMocks.job(variables));

        verify(paybillStateStore).removeWorkflowInstance("RKTQDM7W6S");
        assertEquals(true, completedVariables().get(TRANSFER_CREATE_FAILED));
    }

    private JobHandler initTransferHandler() {
        worker.setupWorkers();
        List<JobHandler> handlers = zeebe.handlers();
        return handlers.get(0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> completedVariables() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(jobClient.newCompleteCommand(1L)).variables(captor.capture());
        return captor.getValue();
    }

    private static Map<String, Object> variables() {
        Map<String, Object> variables = new HashMap<>();
        variables.put(TRANSACTION_ID, "tx-1");
        variables.put(AMS, "roster");
        variables.put("mpesaChannelRequest", CHANNEL_REQUEST);
        return variables;
    }

    private static BuyGoodsPaymentRequestDTO buyGoodsRequest() {
        BuyGoodsPaymentRequestDTO dto = new BuyGoodsPaymentRequestDTO();
        dto.setBusinessShortCode(174379L);
        dto.setPassword("secret-password");
        return dto;
    }
}
