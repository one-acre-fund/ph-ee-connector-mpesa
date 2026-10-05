package org.mifos.connector.mpesa.camel.routes;

import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.ZeebeFuture;
import io.camunda.zeebe.client.api.command.CancelProcessInstanceCommandStep1;
import io.camunda.zeebe.client.api.command.ResolveIncidentCommandStep1;
import io.camunda.zeebe.client.api.command.UpdateRetriesJobCommandStep1;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mifos.connector.mpesa.zeebe.ZeebeMocks;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mifos.connector.mpesa.zeebe.ZeebeMessages.OPERATOR_MANUAL_RECOVERY;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.TRANSACTION_ID;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OperationsRouteBuilderFlowTest {

    private ZeebeMocks zeebe;
    private ZeebeClient client;
    private CamelContext context;
    private ProducerTemplate template;
    private ResolveIncidentCommandStep1 resolveIncident;
    private UpdateRetriesJobCommandStep1.UpdateRetriesJobCommandStep2 updateRetries;
    private CancelProcessInstanceCommandStep1 cancel;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        zeebe = new ZeebeMocks();
        client = zeebe.client;
        ZeebeFuture<?> future = mock(ZeebeFuture.class);

        resolveIncident = mock(ResolveIncidentCommandStep1.class);
        when(client.newResolveIncidentCommand(anyLong())).thenReturn(resolveIncident);
        when(resolveIncident.send()).thenReturn((ZeebeFuture) future);

        UpdateRetriesJobCommandStep1 retriesStep1 = mock(UpdateRetriesJobCommandStep1.class);
        updateRetries = mock(UpdateRetriesJobCommandStep1.UpdateRetriesJobCommandStep2.class);
        when(client.newUpdateRetriesCommand(anyLong())).thenReturn(retriesStep1);
        when(retriesStep1.retries(anyInt())).thenReturn(updateRetries);
        when(updateRetries.send()).thenReturn((ZeebeFuture) future);

        cancel = mock(CancelProcessInstanceCommandStep1.class);
        when(client.newCancelInstanceCommand(anyLong())).thenReturn(cancel);
        when(cancel.send()).thenReturn((ZeebeFuture) future);

        OperationsRouteBuilder builder = new OperationsRouteBuilder();
        ReflectionTestUtils.setField(builder, "zeebeClient", client);
        ReflectionTestUtils.setField(builder, "requestTimeout", Duration.ofSeconds(5));

        context = new DefaultCamelContext();
        context.addRoutes(builder);
        AdviceWith.adviceWith(context, "transaction-resolve", a -> a.replaceFromWith("direct:transaction-resolve"));
        AdviceWith.adviceWith(context, "job-resolve", a -> a.replaceFromWith("direct:job-resolve"));
        AdviceWith.adviceWith(context, "workflow-resolve", a -> a.replaceFromWith("direct:workflow-resolve"));
        AdviceWith.adviceWith(context, "workflow-cancel", a -> a.replaceFromWith("direct:workflow-cancel"));
        context.start();
        template = context.createProducerTemplate();
    }

    @AfterEach
    void tearDown() {
        context.stop();
    }

    @Test
    void transactionResolve_shouldPublishManualRecoveryMessage() {
        Exchange exchange = template.send("direct:transaction-resolve", e -> {
            e.getIn().setHeader(TRANSACTION_ID, "tx-1");
            e.getIn().setBody("{\"transactionFailed\":false,\"note\":\"ok\"}");
        });

        assertNull(exchange.getException());
        verify(zeebe.publishStep1).messageName(OPERATOR_MANUAL_RECOVERY);
        verify(zeebe.publishStep2).correlationKey("tx-1");
        verify(zeebe.publish).timeToLive(Duration.ofMillis(30000));
        Map<String, Object> variables = zeebe.publishedVariables();
        assertEquals(false, variables.get("transactionFailed"));
        assertEquals("ok", variables.get("note"));
    }

    @Test
    void jobResolve_shouldSetVariablesUpdateRetriesAndResolveIncident() {
        Exchange exchange = template.send("direct:job-resolve", e -> e.getIn().setBody(
                "{\"incident\":{\"elementInstanceKey\":12345,\"jobKey\":67890,\"newRetries\":3,\"key\":11111},"
                        + "\"variables\":{\"var1\":\"value1\"}}"));

        assertNull(exchange.getException());
        verify(client).newSetVariablesCommand(12345L);
        assertEquals("value1", zeebe.setVariablesSent().get("var1"));
        verify(client).newUpdateRetriesCommand(67890L);
        verify(client).newResolveIncidentCommand(11111L);
    }

    @Test
    void workflowResolve_shouldSetVariablesAndResolveIncident() {
        Exchange exchange = template.send("direct:workflow-resolve", e -> e.getIn().setBody(
                "{\"incident\":{\"elementInstanceKey\":12345,\"key\":11111},\"variables\":{\"var2\":\"value2\"}}"));

        assertNull(exchange.getException());
        verify(client).newSetVariablesCommand(12345L);
        assertEquals("value2", zeebe.setVariablesSent().get("var2"));
        verify(client).newResolveIncidentCommand(11111L);
    }

    @Test
    void workflowCancel_shouldCancelInstanceAndClearBody() {
        Exchange exchange = template.send("direct:workflow-cancel",
                e -> e.getIn().setHeader("workflowInstanceKey", "2251799813685249"));

        assertNull(exchange.getException());
        verify(client).newCancelInstanceCommand(2251799813685249L);
        assertNull(exchange.getMessage().getBody());
    }
}
