package org.mifos.connector.mpesa.camel.routes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.mifos.connector.mpesa.flowcomponents.ErrorCodeCache;
import org.mifos.connector.mpesa.flowcomponents.transaction.ErrorProcessor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;


@ExtendWith(MockitoExtension.class)
class OperationsRouteTest {

  @InjectMocks private OperationsRoute operationsRoute;

  @Mock private ErrorProcessor errorProcessor;

  @Mock private ErrorCodeCache errorCodeCache;

  private CamelContext camelContext;

  @BeforeEach
  void setUp() throws Exception {
    camelContext = new DefaultCamelContext();

    RestConfiguration restConfiguration = new RestConfiguration();
    restConfiguration.setComponent("jetty");
    restConfiguration.setHost("localhost");
    camelContext.setRestConfiguration(restConfiguration);

    // Nothing listens on port 1, so any call to operations fails at the transport level
    ReflectionTestUtils.setField(operationsRoute, "operationsHost", "http://localhost:1");
    ReflectionTestUtils.setField(operationsRoute, "operationsBaseUrl", "/api/v1/errorcode");
    ReflectionTestUtils.setField(operationsRoute, "operationsFilterPath", "/filter");
    ReflectionTestUtils.setField(operationsRoute, "operationsTimeout", 1000);
    ReflectionTestUtils.setField(operationsRoute, "tenantId", "kenya");

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

  @Test
  void filterResponseHandler_shouldHandleNonJsonErrorResponse() throws Exception {
    Exchange exchange = new DefaultExchange(camelContext);
    exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, 500);
    exchange.getIn().setBody("<!doctype html><html><body><h1>HTTP Status 500 – Internal Server Error</h1></body></html>");

    camelContext.createProducerTemplate().send("direct:filter-response-handler", exchange);

    assertNull(exchange.getException());
    verify(errorProcessor, never()).process(any());
    assertFalse((Boolean) exchange.getProperty("isErrorRecoverable"));
  }

  @Test
  void filterResponseHandler_shouldCacheResultFromOperations() throws Exception {
    doAnswer(invocation -> {
      invocation.<Exchange>getArgument(0).setProperty("isErrorRecoverable", true);
      return null;
    }).when(errorProcessor).process(any());

    Exchange exchange = new DefaultExchange(camelContext);
    exchange.setProperty("errorCode", "1037");
    exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
    exchange.getIn().setBody("[{\"errorCode\":\"1037\",\"recoverable\":true}]");

    camelContext.createProducerTemplate().send("direct:filter-response-handler", exchange);

    verify(errorCodeCache).put("1037", true);
  }

  @Test
  void filterByErrorCode_shouldUseFreshCacheWithoutCallingOperations() {
    ErrorCodeCache.Entry entry = new ErrorCodeCache.Entry(true, System.currentTimeMillis());
    when(errorCodeCache.get("1037")).thenReturn(entry);
    when(errorCodeCache.isFresh(entry)).thenReturn(true);

    Exchange exchange = new DefaultExchange(camelContext);
    exchange.setProperty("errorCode", "1037");

    camelContext.createProducerTemplate().send("direct:filter-by-error-code", exchange);

    assertNull(exchange.getException());
    assertTrue((Boolean) exchange.getProperty("isErrorRecoverable"));
    verify(errorCodeCache, never()).put(anyString(), anyBoolean());
  }

  @Test
  void filterByErrorCode_shouldUseStaleCacheWhenOperationsUnreachable() {
    ErrorCodeCache.Entry entry = new ErrorCodeCache.Entry(true, 0L);
    when(errorCodeCache.get("1037")).thenReturn(entry);
    when(errorCodeCache.isFresh(entry)).thenReturn(false);

    Exchange exchange = new DefaultExchange(camelContext);
    exchange.setProperty("errorCode", "1037");

    camelContext.createProducerTemplate().send("direct:filter-by-error-code", exchange);

    assertNull(exchange.getException());
    assertTrue((Boolean) exchange.getProperty("isErrorRecoverable"));
    verify(errorCodeCache, never()).put(anyString(), anyBoolean());
  }

  @Test
  void filterByErrorCode_shouldDefaultToNonRecoverableWhenOperationsUnreachableAndNotCached() {
    Exchange exchange = new DefaultExchange(camelContext);
    exchange.setProperty("errorCode", "1037");

    camelContext.createProducerTemplate().send("direct:filter-by-error-code", exchange);

    assertNull(exchange.getException());
    assertFalse((Boolean) exchange.getProperty("isErrorRecoverable"));
  }

  @Test
  void filterTestEndpoint_shouldReturnRecoverabilityAsBody() throws Exception {
    camelContext.stop();
    camelContext = new DefaultCamelContext();
    camelContext.addRoutes(operationsRoute);
    org.apache.camel.builder.AdviceWith.adviceWith(camelContext, "filter-test",
        a -> a.replaceFromWith("direct:filter-test"));
    camelContext.start();
    ErrorCodeCache.Entry entry = new ErrorCodeCache.Entry(true, System.currentTimeMillis());
    when(errorCodeCache.get("1037")).thenReturn(entry);
    when(errorCodeCache.isFresh(entry)).thenReturn(true);

    Exchange exchange = camelContext.createProducerTemplate().send("direct:filter-test", e -> { });

    assertEquals("true", exchange.getMessage().getBody(String.class));
  }

  @Test
  void filterByErrorCode_shouldRestoreCallerMessageAfterOperationsResponse() throws Exception {
    camelContext.stop();
    camelContext = new DefaultCamelContext();
    camelContext.addRoutes(operationsRoute);
    org.apache.camel.builder.AdviceWith.adviceWith(camelContext, "fetch-error-code-from-operations",
        a -> a.weaveByToString("DynamicTo.*").replace().to("mock:operations"));
    org.apache.camel.builder.AdviceWith.adviceWith(camelContext, "filter-test",
        a -> a.replaceFromWith("direct:filter-test"));
    camelContext.start();
    camelContext.getEndpoint("mock:operations", org.apache.camel.component.mock.MockEndpoint.class)
        .whenAnyExchangeReceived(e -> {
          e.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
          e.getMessage().setBody("[]");
        });

    doAnswer(invocation -> {
      new ErrorProcessor().process(invocation.getArgument(0));
      return null;
    }).when(errorProcessor).process(any());

    Exchange exchange = new DefaultExchange(camelContext);
    exchange.setProperty("errorCode", "1");
    exchange.getIn().setHeader("X-Caller", "kept");
    exchange.getIn().setBody("callback-body");

    camelContext.createProducerTemplate().send("direct:filter-by-error-code", exchange);

    assertNull(exchange.getException());
    assertFalse((Boolean) exchange.getProperty("isErrorRecoverable"));
    assertEquals("callback-body", exchange.getIn().getBody());
    assertEquals("kept", exchange.getIn().getHeader("X-Caller"));
    assertNull(exchange.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE));
    assertNull(exchange.getProperty("filterOriginalHeaders"));
    verify(errorCodeCache).put("1", false);
  }
}
