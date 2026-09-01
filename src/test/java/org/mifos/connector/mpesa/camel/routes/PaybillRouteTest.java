package org.mifos.connector.mpesa.camel.routes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.CLIENT_CORRELATION_ID;

import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.command.PublishMessageCommandStep1;
import io.camunda.zeebe.client.api.command.PublishMessageCommandStep1.PublishMessageCommandStep2;
import io.camunda.zeebe.client.api.command.PublishMessageCommandStep1.PublishMessageCommandStep3;
import io.camunda.zeebe.client.api.ZeebeFuture;
import io.camunda.zeebe.client.api.response.PublishMessageResponse;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.spi.RestConfiguration;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mifos.connector.common.gsma.dto.GsmaTransfer;
import org.mifos.connector.mpesa.dto.ChannelSettlementRequestDTO;
import org.mifos.connector.mpesa.dto.PaybillRequestDTO;
import org.mifos.connector.mpesa.dto.PaybillResponseDTO;
import org.mifos.connector.mpesa.flowcomponents.PaybillStateStore;
import org.mifos.connector.mpesa.utility.MpesaPaybillProp;
import org.mifos.connector.mpesa.utility.MpesaUtils;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaybillRouteTest {

  @InjectMocks
  private PaybillRoute paybillRoute;

  @Mock
  private MpesaUtils mpesaUtils;

  @Mock
  private ZeebeClient zeebeClient;

  @Mock
  private PaybillStateStore paybillStateStore;

  @Mock
  private MpesaPaybillProp mpesaPaybillProp;

  private CamelContext camelContext;

  @BeforeEach
  void setUp() throws Exception {
    ReflectionTestUtils.setField(paybillRoute, "channelUrl", "http://localhost");
    ReflectionTestUtils.setField(paybillRoute, "tenantId", "test-tenant");

    camelContext = new DefaultCamelContext();

    RestConfiguration restConfiguration = new RestConfiguration();
    restConfiguration.setComponent("jetty");
    restConfiguration.setHost("localhost");
    camelContext.setRestConfiguration(restConfiguration);

    camelContext.addRoutes(paybillRoute);

    AdviceWith.adviceWith(camelContext, "mpesa-confirmation", route ->
        route.replaceFromWith("direct:confirmation"));
    AdviceWith.adviceWith(camelContext, "start-paybill-workflow", route ->
        route.weaveByToString("DynamicTo.*").replace().to("mock:channel"));
    AdviceWith.adviceWith(camelContext, "account-status-channel", route ->
        route.weaveByToString("DynamicTo.*").replace().to("mock:account-status"));

    camelContext.start();
  }

  @AfterEach
  void tearDown() {
    camelContext.stop();
  }

  @Test
  void startPaybillWorkflow_shouldProcessReconciledTransaction() {
    PaybillResponseDTO responseDTO = new PaybillResponseDTO();
    responseDTO.setReconciled(true);
    responseDTO.setTransactionId("12345");
    responseDTO.setAccountHoldingInstitutionId("institution123");
    responseDTO.setAmsName("AMSName");

    when(mpesaUtils.createGsmaTransferDTO(any(), eq("12345"), any())).thenReturn(new GsmaTransfer());

    Exchange exchange = new DefaultExchange(camelContext);
    exchange.getIn().setBody(responseDTO);

    camelContext.createProducerTemplate().send("direct:start-paybill-workflow", exchange);

    verify(paybillStateStore).putReconciled("12345", true);
    assertEquals("institution123", exchange.getIn().getHeader("accountHoldingInstitutionId"));
    assertNotNull(exchange.getIn().getBody(String.class));
  }

  @Test
  void accountStatus_shouldValidateAccount() {
    when(mpesaPaybillProp.getAMSFromShortCode("600000")).thenReturn("ams");
    when(mpesaPaybillProp.getCurrencyFromShortCode("600000")).thenReturn("KES");
    when(mpesaPaybillProp.getAccountHoldingInstitutionId()).thenReturn("institution123");
    when(mpesaUtils.getAMSUrl("ams")).thenReturn("http://ams");

    String requestBody =
        "{\"TransID\":\"12345\",\"BusinessShortCode\":\"600000\",\"MSISDN\":\"254700000000\"}";
    Exchange exchange = new DefaultExchange(camelContext);
    exchange.getIn().setBody(requestBody);

    camelContext.createProducerTemplate().send("direct:account-status", exchange);

    assertNotNull(exchange.getIn().getBody(String.class));
  }

  @Test
  void paybillResponseSuccess_shouldBuildAcceptedResponseWhenReconciled() {
    when(paybillStateStore.getReconciled("12345")).thenReturn(true);

    String responseBody = "{\"transactionId\":\"workflow123\"}";
    Exchange exchange = new DefaultExchange(camelContext);
    exchange.getIn().setBody(responseBody);
    exchange.getIn().setHeader(CLIENT_CORRELATION_ID, "12345");

    camelContext.createProducerTemplate().send("direct:paybill-response-success", exchange);

    verify(paybillStateStore).putWorkflowInstance("12345", "workflow123");
    verify(paybillStateStore).removeReconciled("12345");

    String body = exchange.getIn().getBody(String.class);
    assertNotNull(body);
    assertTrue(body.contains("\"ResultCode\":0"));
    assertTrue(body.contains("Accepted"));
  }

  @Test
  void paybillResponseSuccess_shouldDefaultToRejectedWhenReconciledMissing() {
    when(paybillStateStore.getReconciled("12345")).thenReturn(null);

    String responseBody = "{\"transactionId\":\"workflow123\"}";
    Exchange exchange = new DefaultExchange(camelContext);
    exchange.getIn().setBody(responseBody);
    exchange.getIn().setHeader(CLIENT_CORRELATION_ID, "12345");

    camelContext.createProducerTemplate().send("direct:paybill-response-success", exchange);

    String body = exchange.getIn().getBody(String.class);
    assertTrue(body.contains("\"ResultCode\":1"));
    assertTrue(body.contains("Rejected"));
  }

  @Test
  void paybillResponseFailure_shouldReturnRejectedResponse() {
    String responseBody = "{\"error\":\"Invalid transaction\"}";
    Exchange exchange = new DefaultExchange(camelContext);
    exchange.getIn().setBody(responseBody);

    camelContext.createProducerTemplate().send("direct:paybill-response-failure", exchange);

    assertTrue(exchange.getIn().getBody(String.class).contains("Rejected"));
  }

  @Test
  void confirmation_shouldPublishZeebeMessageWhenWorkflowExists() {
    PaybillRequestDTO requestDTO = new PaybillRequestDTO();
    requestDTO.setTransactionID("12345");
    requestDTO.setShortCode("600000");
    requestDTO.setMsisdn("254700000000");
    requestDTO.setTransactionAmount("100");
    requestDTO.setBillRefNo("123");

    ChannelSettlementRequestDTO settlementRequest = new ChannelSettlementRequestDTO();
    when(mpesaPaybillProp.getAMSFromShortCode("600000")).thenReturn("ams");
    when(mpesaPaybillProp.getCurrencyFromShortCode("600000")).thenReturn("KES");
    when(mpesaUtils.getAMSUrl("ams")).thenReturn("http://ams");
    when(mpesaUtils.convertPaybillToChannelPayload(any(PaybillRequestDTO.class), eq("ams"), eq("KES")))
        .thenReturn(settlementRequest);
    when(paybillStateStore.getWorkflowInstance("12345")).thenReturn("workflow123");

    PublishMessageCommandStep1 step1 = mock(PublishMessageCommandStep1.class);
    PublishMessageCommandStep2 step2 = mock(PublishMessageCommandStep2.class);
    PublishMessageCommandStep3 step3 = mock(PublishMessageCommandStep3.class);
    when(zeebeClient.newPublishMessageCommand()).thenReturn(step1);
    when(step1.messageName("pendingConfirmation")).thenReturn(step2);
    when(step2.correlationKey("workflow123")).thenReturn(step3);
    when(step3.timeToLive(any())).thenReturn(step3);
    when(step3.variables(anyMap())).thenReturn(step3);
    @SuppressWarnings("unchecked")
    ZeebeFuture<PublishMessageResponse> publishFuture = mock(ZeebeFuture.class);
    when(step3.send()).thenReturn(publishFuture);

    String requestBody =
        "{\"TransID\":\"12345\",\"BusinessShortCode\":\"600000\",\"MSISDN\":\"254700000000\",\"TransAmount\":\"100\",\"BillRefNumber\":\"123\"}";
    Exchange exchange = new DefaultExchange(camelContext);
    exchange.getIn().setBody(requestBody);

    camelContext.createProducerTemplate().send("direct:confirmation", exchange);

    verify(paybillStateStore).removeWorkflowInstance("12345");
    verify(step3).send();
    assertEquals(202, exchange.getIn().getHeader(Exchange.HTTP_RESPONSE_CODE));
    assertEquals("", exchange.getIn().getBody(String.class));
  }

  @Test
  void confirmation_shouldSkipPublishWhenWorkflowMissing() {
    PaybillRequestDTO requestDTO = new PaybillRequestDTO();
    requestDTO.setTransactionID("12345");
    requestDTO.setShortCode("600000");
    requestDTO.setMsisdn("254700000000");
    requestDTO.setTransactionAmount("100");
    requestDTO.setBillRefNo("123");

    ChannelSettlementRequestDTO settlementRequest = new ChannelSettlementRequestDTO();
    when(mpesaPaybillProp.getAMSFromShortCode("600000")).thenReturn("ams");
    when(mpesaPaybillProp.getCurrencyFromShortCode("600000")).thenReturn("KES");
    when(mpesaUtils.getAMSUrl("ams")).thenReturn("http://ams");
    when(mpesaUtils.convertPaybillToChannelPayload(any(PaybillRequestDTO.class), eq("ams"), eq("KES")))
        .thenReturn(settlementRequest);
    when(paybillStateStore.getWorkflowInstance("12345")).thenReturn(null);

    String requestBody =
        "{\"TransID\":\"12345\",\"BusinessShortCode\":\"600000\",\"MSISDN\":\"254700000000\",\"TransAmount\":\"100\",\"BillRefNumber\":\"123\"}";
    Exchange exchange = new DefaultExchange(camelContext);
    exchange.getIn().setBody(requestBody);

    camelContext.createProducerTemplate().send("direct:confirmation", exchange);

    verify(zeebeClient, never()).newPublishMessageCommand();
    verify(paybillStateStore).removeWorkflowInstance("12345");
  }
}
