package org.mifos.connector.mpesa.camel.routes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.Mockito.mock;

import io.camunda.zeebe.client.ZeebeClient;
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
import org.mifos.connector.mpesa.dto.PaybillRequestDTO;
import org.mifos.connector.mpesa.dto.PaybillResponseDTO;
import org.mifos.connector.mpesa.utility.MpesaUtils;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;


@ExtendWith(MockitoExtension.class)
class PaybillRouteTest {

  @InjectMocks
  private PaybillRoute paybillRoute;

  @Mock
  private MpesaUtils mpesaUtils;

  @Mock
  private ZeebeClient zeebeClient;

  private CamelContext camelContext;

  @BeforeEach
  void setUp() throws Exception {
    camelContext = new DefaultCamelContext();

    RestConfiguration restConfiguration = new RestConfiguration();
    restConfiguration.setComponent("jetty");
    restConfiguration.setHost("localhost");
//    restConfiguration.setPort(8080);

    camelContext.setRestConfiguration(restConfiguration);

    camelContext.addRoutes(paybillRoute);
    camelContext.start();
  }

  @AfterEach
  void tearDown() {
    camelContext.stop();
    mpesaUtils = mock(MpesaUtils.class);
  }

  @Test
  void mpesaValidation_shouldRouteToSuccessWhenReconciled() {
    String requestBody =
        "{\"transactionID\":\"12345\",\"shortCode\":\"600000\",\"msisdn\":\"254700000000\",\"transactionAmount\":100,\"billRefNo\":\"123\"}";
    PaybillResponseDTO responseDTO = new PaybillResponseDTO();
    responseDTO.setReconciled(true);
    responseDTO.setTransactionId("12345");

    Exchange exchange = new DefaultExchange(camelContext);
    exchange.getIn().setBody(requestBody);

    ProducerTemplate template = camelContext.createProducerTemplate();
    template.send("http:POST:/validation", exchange);

    assertTrue(exchange.getMessage().getBody(String.class).contains("12345"));
  }

  @Test
  void startPaybillWorkflow_shouldProcessReconciledTransaction() {
    PaybillResponseDTO responseDTO = new PaybillResponseDTO();
    responseDTO.setReconciled(true);
    responseDTO.setTransactionId("12345");
    responseDTO.setAccountHoldingInstitutionId("institution123");
    responseDTO.setAmsName("AMSName");

    Exchange exchange = new DefaultExchange(camelContext);
    exchange.getIn().setBody(responseDTO);

    ProducerTemplate template = camelContext.createProducerTemplate();
    template.send("direct:start-paybill-workflow", exchange);

    assertEquals("institution123", exchange.getIn().getHeader("accountHoldingInstitutionId"));
    assertNotNull(exchange.getIn().getBody(String.class));
  }

  @Test
  void accountStatus_shouldValidateAccount() {
    PaybillRequestDTO requestDTO = new PaybillRequestDTO();
    requestDTO.setTransactionID("12345");
    requestDTO.setShortCode("600000");
    requestDTO.setMsisdn("254700000000");

    Exchange exchange = new DefaultExchange(camelContext);
    exchange.getIn().setBody(requestDTO);

    ProducerTemplate template = camelContext.createProducerTemplate();
    template.send("direct:account-status", exchange);

    assertNotNull(exchange.getIn().getBody(String.class));
  }

  @Test
  void paybillResponseSuccess_shouldStoreWorkflowInstance() {
    String responseBody = "{\"transactionId\":\"workflow123\"}";
    Exchange exchange = new DefaultExchange(camelContext);
    exchange.getIn().setBody(responseBody);
    exchange.getIn().setHeader("clientCorrelationId", "12345");

    ProducerTemplate template = camelContext.createProducerTemplate();
    template.send("direct:paybill-response-success", exchange);

    assertTrue(exchange.getIn().getBody(String.class).contains("workflow123"));
  }

  @Test
  void paybillResponseFailure_shouldReturnRejectedResponse() {
    String responseBody = "{\"error\":\"Invalid transaction\"}";
    Exchange exchange = new DefaultExchange(camelContext);
    exchange.getIn().setBody(responseBody);

    ProducerTemplate template = camelContext.createProducerTemplate();
    template.send("direct:paybill-response-failure", exchange);

    assertTrue(exchange.getIn().getBody(String.class).contains("Rejected"));
  }

  @Test
  void confirmation_shouldPublishZeebeMessage() {
    String requestBody =
        "{\"transactionID\":\"12345\",\"shortCode\":\"600000\",\"msisdn\":\"254700000000\",\"transactionAmount\":100,\"billRefNo\":\"123\"}";
    PaybillRoute.workflowInstanceStore.put("12345", "workflow123");

    Exchange exchange = new DefaultExchange(camelContext);
    exchange.getIn().setBody(requestBody);

    ProducerTemplate template = camelContext.createProducerTemplate();
    template.send("http:POST:/confirmation", exchange);

    assertTrue(exchange.getIn().getBody(String.class).contains("123"));
  }

}
