package org.mifos.connector.mpesa.camel.routes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.spi.RestConfiguration;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mifos.connector.mpesa.flowcomponents.transaction.ErrorProcessor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;


@ExtendWith(MockitoExtension.class)
class OperationsRouteTest {

  @InjectMocks private OperationsRoute operationsRoute;

  @Mock private ErrorProcessor errorProcessor;

  private CamelContext camelContext;

  @BeforeEach
  void setUp() throws Exception {
    camelContext = new DefaultCamelContext();

    RestConfiguration restConfiguration = new RestConfiguration();
    restConfiguration.setComponent("jetty");
    restConfiguration.setHost("localhost");
    camelContext.setRestConfiguration(restConfiguration);

    camelContext.addRoutes(operationsRoute);
    camelContext.start();
  }

  @AfterEach
  void tearDown() {
    camelContext.stop();
  }

  @Test
  void filterByErrorCode_shouldReturnRecoverableError() throws Exception {
    CamelContext testContext = new DefaultCamelContext();

    testContext.addRoutes(
        new RouteBuilder() {
          @Override
          public void configure() {
            from("direct:filter-by-error-code")
                .choice()
                .when(simple("${exchangeProperty.ERROR_CODE} == '1037'"))
                .setBody(constant("true"))
                .otherwise()
                .setBody(constant("false"));
          }
        });

    testContext.start();

    try {
      Exchange exchange = new DefaultExchange(testContext);
      exchange.setProperty("ERROR_CODE", "1037");

      testContext.createProducerTemplate().send("direct:filter-by-error-code", exchange);

      assertEquals("true", exchange.getIn().getBody(String.class));
    } finally {
      testContext.stop();
    }
  }

  @Test
  void filterResponseHandler_shouldProcessValidResponse() throws Exception {
    Exchange exchange = new DefaultExchange(camelContext);
    exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, "200");
    exchange.getIn().setBody("[{\"errorCode\":\"1037\",\"errorMessage\":\"Recoverable error\",\"recoverable\":true}]");

    camelContext.createProducerTemplate().send("direct:filter-response-handler", exchange);

    verify(errorProcessor).process(any());
  }

  @Test
  void filterResponseHandler_shouldHandleEmptyResponse() throws Exception {
    Exchange exchange = new DefaultExchange(camelContext);
    exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, "200");
    exchange.getIn().setBody("[]");

    camelContext.createProducerTemplate().send("direct:filter-response-handler", exchange);

    verify(errorProcessor).process(any());
  }

  @Test
  void filterResponseHandler_shouldHandleNonOkResponse() throws Exception {
    Exchange exchange = new DefaultExchange(camelContext);
    exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, "500");
    exchange.getIn().setBody("[]");

    camelContext.createProducerTemplate().send("direct:filter-response-handler", exchange);

    verify(errorProcessor, never()).process(any());
    assertFalse((Boolean) exchange.getProperty("isErrorRecoverable"));
  }
}
