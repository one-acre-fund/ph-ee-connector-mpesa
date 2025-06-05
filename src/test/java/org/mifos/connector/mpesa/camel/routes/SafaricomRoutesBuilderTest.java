package org.mifos.connector.mpesa.camel.routes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.mifos.connector.mpesa.auth.AccessTokenStore;
import org.mifos.connector.mpesa.dto.BuyGoodsPaymentRequestDTO;
import org.mifos.connector.mpesa.flowcomponents.CorrelationIDStore;
import org.mifos.connector.mpesa.flowcomponents.mpesa.MpesaGenericProcessor;
import org.mifos.connector.mpesa.flowcomponents.transaction.CollectionResponseProcessor;
import org.mifos.connector.mpesa.flowcomponents.transaction.TransactionResponseProcessor;
import org.mifos.connector.mpesa.utility.MpesaProps;
import org.mifos.connector.mpesa.utility.MpesaUtils;
import org.mifos.connector.mpesa.utility.SafaricomUtils;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class SafaricomRoutesBuilderTest {

  @Mock private ObjectMapper objectMapper;

  @Mock private CollectionResponseProcessor collectionResponseProcessor;

  @Mock private TransactionResponseProcessor transactionResponseProcessor;

  @Mock private MpesaGenericProcessor mpesaGenericProcessor;

  @Mock private AccessTokenStore accessTokenStore;

  @Mock private CorrelationIDStore correlationIDStore;

  @Mock private SafaricomUtils safaricomUtils;

  @Mock private MpesaUtils mpesaUtils;

  @InjectMocks private SafaricomRoutesBuilder routesBuilder;

  private CamelContext context;

  @BeforeEach
  void setUp() throws Exception {
    routesBuilder =
        new SafaricomRoutesBuilder(
            objectMapper,
            collectionResponseProcessor,
            transactionResponseProcessor,
            mpesaGenericProcessor,
            accessTokenStore,
            correlationIDStore,
            safaricomUtils,
            mpesaUtils);

    ReflectionTestUtils.setField(routesBuilder, "mpesaTimeout", 5000);
    ReflectionTestUtils.setField(routesBuilder, "buyGoodsLipanaUrl", "http://mock-lipana-url");
    ReflectionTestUtils.setField(
        routesBuilder, "transactionStatusUrl", "http://mock-transaction-status-url");

    // Setup MPESA props mock
    MpesaProps.MPESA mockMpesaProps = new MpesaProps.MPESA();
    mockMpesaProps.setName("TestMpesa");
    mockMpesaProps.setClientKey("testKey");
    mockMpesaProps.setClientSecret("testSecret");
    mockMpesaProps.setAuthHost("http://test-host");
    Mockito.when(mpesaUtils.setMpesaProperties()).thenReturn(mockMpesaProps);

    context = new DefaultCamelContext();
    RestConfiguration restConfig = new RestConfiguration();
    restConfig.setComponent("jetty");
    context.setRestConfiguration(restConfig);

    context.addRoutes(routesBuilder);
    context.start();
  }

  @AfterEach
  void tearDown() {
    if (context != null) {
      context.stop();
    }
  }

  @Test
  void testBuyGoodsTransactionStatusRoute() throws JsonProcessingException {
    String requestBody =
        "{\"BusinessShortCode\":174379,\"Timestamp\":\"20231010\",\"CheckoutRequestId\":\"12345\"}";

    Exchange exchange = new DefaultExchange(context);
    exchange.getIn().setBody(requestBody);

    ProducerTemplate template = context.createProducerTemplate();
    template.send("direct:lipana-transaction-status", exchange);
    assertNotNull(exchange.getMessage().getBody());
  }

  @Test
  void testBuyGoodsCallbackRoute() {
    String callbackBody =
        "{\"ResultCode\":0,\"ResultDesc\":\"Success\",\"CheckoutRequestID\":\"12345\"}";

    Exchange exchange = new DefaultExchange(context);
    exchange.getIn().setBody(callbackBody);

    ProducerTemplate template = context.createProducerTemplate();
    template.send("direct:callback-handler", exchange);

    assertNotNull(exchange.getProperty("CamelFailureRouteId"));
  }

  @Test
  void testLipanaTransactionStatusRoute() {
    Exchange exchange = new DefaultExchange(context);
    BuyGoodsPaymentRequestDTO requestDTO = new BuyGoodsPaymentRequestDTO();
    requestDTO.setBusinessShortCode(174379L);
    requestDTO.setTimestamp("20231010");
    exchange.setProperty("BUY_GOODS_REQUEST_BODY", requestDTO);
    exchange.setProperty("SERVER_TRANSACTION_ID", "12345");
    exchange.setProperty("ACCESS_TOKEN", "mockAccessToken");

    ProducerTemplate template = context.createProducerTemplate();
    template.send("direct:lipana-transaction-status", exchange);

    assertEquals("POST", exchange.getIn().getHeader(Exchange.HTTP_METHOD));
    assertEquals("application/json", exchange.getIn().getHeader("Content-Type"));
  }

  @Test
  void testLipanaBuyGoodsRoute() {
    Exchange exchange = new DefaultExchange(context);
    BuyGoodsPaymentRequestDTO requestDTO = new BuyGoodsPaymentRequestDTO();
    requestDTO.setBusinessShortCode(174379L);
    requestDTO.setTimestamp("20231010");
    exchange.setProperty("BUY_GOODS_REQUEST_BODY", requestDTO);
    exchange.setProperty("ACCESS_TOKEN", "mockAccessToken");

    ProducerTemplate template = context.createProducerTemplate();
    template.send("direct:lipana-buy-goods", exchange);

    assertEquals("POST", exchange.getIn().getHeader(Exchange.HTTP_METHOD));
    assertEquals("application/json", exchange.getIn().getHeader("Content-Type"));
  }

  @Test
  void testCallbackRoute() {
    String callbackBody =
        "{\"ResultCode\":0,\"ResultDesc\":\"Success\",\"CheckoutRequestID\":\"12345\"}";

    Exchange exchange = new DefaultExchange(context);
    exchange.getIn().setBody(callbackBody);

    ProducerTemplate template = context.createProducerTemplate();
    template.send("http:POST:/buygoods/callback", exchange);

    assertNotNull(exchange.getProperty("CamelToEndpoint"));
    assertEquals("http://POST/buygoods/callback", exchange.getProperty("CamelToEndpoint"));
  }

  @Test
  void testTransactionStatusRoute() {
    String requestBody =
        "{\"BusinessShortCode\":174379,\"Timestamp\":\"20231010\",\"CheckoutRequestId\":\"12345\"}";

    Exchange exchange = new DefaultExchange(context);
    exchange.getIn().setBody(requestBody);

    ProducerTemplate template = context.createProducerTemplate();
    template.send("http://POST/buygoods/transactionstatus", exchange);

    assertEquals("http://POST/buygoods/transactionstatus", exchange.getProperty("CamelToEndpoint"));
  }
}
