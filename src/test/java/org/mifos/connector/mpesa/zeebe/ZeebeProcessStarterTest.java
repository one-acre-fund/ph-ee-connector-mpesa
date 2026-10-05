package org.mifos.connector.mpesa.zeebe;

import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.ZeebeFuture;
import io.camunda.zeebe.client.api.command.CreateProcessInstanceCommandStep1;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ZeebeProcessStarterTest {

    @Test
    void zeebeVariablesToCamelHeaders_shouldCopyValuesIncludingMissingOnes() {
        Exchange exchange = new DefaultExchange(new DefaultCamelContext());
        Map<String, Object> variables = new HashMap<>();
        variables.put("transactionId", "tx-1");

        ZeebeProcessStarter.zeebeVariablesToCamelHeaders(variables, exchange, "transactionId", "missing");

        assertEquals("tx-1", exchange.getIn().getHeader("transactionId"));
        assertNull(exchange.getIn().getHeader("missing"));
    }

    @Test
    void camelHeadersToZeebeVariables_shouldCopyHeadersIncludingMissingOnes() {
        Exchange exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.getIn().setHeader("transactionId", "tx-1");
        Map<String, Object> variables = new HashMap<>();

        ZeebeProcessStarter.camelHeadersToZeebeVariables(exchange, variables, "transactionId", "missing");

        assertEquals("tx-1", variables.get("transactionId"));
        assertNull(variables.get("missing"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void startZeebeWorkflow_shouldCreateLatestVersionInstanceWithVariables() {
        ZeebeClient client = mock(ZeebeClient.class);
        CreateProcessInstanceCommandStep1 step1 = mock(CreateProcessInstanceCommandStep1.class);
        CreateProcessInstanceCommandStep1.CreateProcessInstanceCommandStep2 step2 =
                mock(CreateProcessInstanceCommandStep1.CreateProcessInstanceCommandStep2.class);
        CreateProcessInstanceCommandStep1.CreateProcessInstanceCommandStep3 step3 =
                mock(CreateProcessInstanceCommandStep1.CreateProcessInstanceCommandStep3.class);
        when(client.newCreateInstanceCommand()).thenReturn(step1);
        when(step1.bpmnProcessId(anyString())).thenReturn(step2);
        when(step2.latestVersion()).thenReturn(step3);
        when(step3.variables(anyMap())).thenReturn(step3);
        when(step3.send()).thenReturn(mock(ZeebeFuture.class));

        ZeebeProcessStarter starter = new ZeebeProcessStarter();
        ReflectionTestUtils.setField(starter, "zeebeClient", client);
        ReflectionTestUtils.setField(starter, "requestTimeout", Duration.ofSeconds(5));

        starter.startZeebeWorkflow("mpesa_flow", Map.of("amount", "10"));

        verify(step1).bpmnProcessId("mpesa_flow");
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(step3).variables(captor.capture());
        assertEquals("10", captor.getValue().get("amount"));
    }
}
