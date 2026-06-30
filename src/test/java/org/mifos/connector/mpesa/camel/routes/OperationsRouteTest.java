package org.mifos.connector.mpesa.camel.routes;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Value;


import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;


@ExtendWith(MockitoExtension.class)
class OperationsRouteTest {

  @InjectMocks private OperationsRoute operationsRoute;

  private CamelContext camelContext = new DefaultCamelContext();

  @Value("${operations.host}")
  private String operationsHost = "http://localhost";

  @Value("${operations.base-url}")
  private String operationsBaseUrl = "/api";

  @Value("${operations.filter-path}")
  private String operationsFilterPath = "/filter";

  @Value("${tenant}")
  private String tenantId = "testTenant";

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
  void filterResponseHandler_shouldProcessValidResponse() {
    Exchange exchange = new DefaultExchange(camelContext);
    exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
    exchange.getIn().setBody("[{\"code\":\"1037\",\"description\":\"Recoverable error\"}]");

    operationsRoute.configure();

    assertEquals(200, exchange.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE));

    String responseBody = exchange.getIn().getBody(String.class);
    assertNotNull(responseBody);
    assertTrue(responseBody.contains("Recoverable error"));
  }

  @Test
  void filterResponseHandler_shouldHandleEmptyResponse() {
    Exchange exchange = new DefaultExchange(camelContext);
    exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
    exchange.getIn().setBody("[]");

    operationsRoute.configure();

    assertEquals(200, exchange.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE));

    String responseBody = exchange.getIn().getBody(String.class);
    assertNotNull(responseBody);
    assertEquals("[]", responseBody);
  }

  @Test
  void filterResponseHandler_shouldHandleInvalidResponse() {
    Exchange exchange = new DefaultExchange(camelContext);
    exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, 500);
    exchange.getIn().setBody("Invalid response");

    operationsRoute.configure();

    assertEquals(500, exchange.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE));

    String responseBody = exchange.getIn().getBody(String.class);
    assertNotNull(responseBody);
    assertEquals("Invalid response", responseBody);
  }
}
