package org.mifos.connector.mpesa.camel.routes;

import static org.junit.jupiter.api.Assertions.*;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.TRANSACTION_ID;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.spi.RestConfiguration;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OperationsRouteBuilderTest {

  @InjectMocks
  private OperationsRouteBuilder operationsRouteBuilder;

  private CamelContext camelContext;

  @BeforeEach
  void setUp() throws Exception {
    camelContext = new DefaultCamelContext();
    RestConfiguration restConfig = new RestConfiguration();
    restConfig.setComponent("jetty");
    restConfig.setHost("localhost");
    restConfig.setPort(8080);
    camelContext.setRestConfiguration(restConfig);
    camelContext.addRoutes(operationsRouteBuilder);
    camelContext.start();
  }

  @AfterEach
  void tearDown() {
    if (camelContext != null) {
      camelContext.stop();
    }
  }

  @Test
  void transactionResolve_shouldPublishMessageWithCorrectVariables() {
    String requestBody = "{\"key1\":\"value1\",\"key2\":\"value2\"}";
    Exchange exchange = new DefaultExchange(camelContext);
    exchange.getIn().setBody(requestBody);
    exchange.getIn().setHeader(TRANSACTION_ID, "transaction123");

    ProducerTemplate template = camelContext.createProducerTemplate();
    template.send("http:POST:/channel/transaction/{transaction123}/resolve", exchange);

    assertNotNull(exchange.getMessage().getBody());
    assertTrue(exchange.getMessage().getBody(String.class).contains("key1"));

  }

  @Test
  void jobResolve_shouldUpdateRetriesAndResolveIncident() {
    String requestBody =
        "{\"incident\":{\"elementInstanceKey\":12345,\"jobKey\":67890,\"newRetries\":3,\"key\":11111},\"variables\":{\"var1\":\"value1\",\"var2\":\"value2\"}}";
    Exchange exchange = new DefaultExchange(camelContext);
    exchange.getIn().setBody(requestBody);

    ProducerTemplate template = camelContext.createProducerTemplate();
    template.send("http:POST:/channel/job/resolve", exchange);

    assertNotNull(exchange.getMessage().getBody());
    assertTrue(exchange.getMessage().getBody(String.class).contains("value2"));
  }

  @Test
  void workflowResolve_shouldSetVariablesAndResolveIncident() {
    String requestBody =
        "{\"incident\":{\"elementInstanceKey\":12345,\"key\":11111},\"variables\":{\"var1\":\"value1\",\"var2\":\"value2\"}}";
    Exchange exchange = new DefaultExchange(camelContext);
    exchange.getIn().setBody(requestBody);

    ProducerTemplate template = camelContext.createProducerTemplate();
    template.send("http:POST:/channel/workflow/resolve", exchange);

    assertNotNull(exchange.getMessage().getBody());
    assertTrue(exchange.getMessage().getBody(String.class).contains("value2"));
  }

  @Test
  void workflowCancel_shouldCancelWorkflowInstance() {
    Exchange exchange = new DefaultExchange(camelContext);
    exchange.getIn().setHeader("workflowInstanceKey", "12345");

    ProducerTemplate template = camelContext.createProducerTemplate();
    template.send("http:POST:/channel/workflow/{workflowInstanceKey}/cancel", exchange);

    assertNull(exchange.getMessage().getBody(String.class));
  }
}
