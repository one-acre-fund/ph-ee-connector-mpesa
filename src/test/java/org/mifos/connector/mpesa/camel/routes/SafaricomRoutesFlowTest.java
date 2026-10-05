package org.mifos.connector.mpesa.camel.routes;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mifos.connector.mpesa.auth.AccessTokenStore;
import org.mifos.connector.mpesa.dto.BuyGoodsPaymentRequestDTO;
import org.mifos.connector.mpesa.flowcomponents.CorrelationIDStore;
import org.mifos.connector.mpesa.flowcomponents.mpesa.MpesaGenericProcessor;
import org.mifos.connector.mpesa.flowcomponents.transaction.CollectionResponseProcessor;
import org.mifos.connector.mpesa.flowcomponents.transaction.TransactionResponseProcessor;
import org.mifos.connector.mpesa.utility.MpesaProps;
import org.mifos.connector.mpesa.utility.MpesaUtils;
import org.mifos.connector.mpesa.utility.SafaricomUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.ACCESS_TOKEN;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.BUY_GOODS_REQUEST_BODY;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.BUY_GOODS_TRANSACTION_STATUS_BODY;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.CORRELATION_ID;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.ERROR_CODE;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.ERROR_DESCRIPTION;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.IS_ERROR_RECOVERABLE;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.IS_RETRY_EXCEEDED;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.IS_TRANSACTION_PENDING;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.LAST_RESPONSE_BODY;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.AMS;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.CALLBACK;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.CALLBACK_RECEIVED;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.PARTY_LOOKUP_FSP_ID;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.SERVER_TRANSACTION_ID;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.SERVER_TRANSACTION_RECEIPT_NUMBER;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.SERVER_TRANSACTION_STATUS_RETRY_COUNT;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.TRANSACTION_FAILED;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.TRANSACTION_ID;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SafaricomRoutesFlowTest {

    private static final String SUCCESS_CALLBACK = "{\"Body\":{\"stkCallback\":{\"MerchantRequestID\":\"m-1\","
            + "\"CheckoutRequestID\":\"ws_CO_1\",\"ResultCode\":0,\"ResultDesc\":\"Processed\","
            + "\"CallbackMetadata\":{\"Item\":[{\"Name\":\"MpesaReceiptNumber\",\"Value\":\"RKTQDM7W6S\"}]}}}}";
    private static final String CANCELLED_CALLBACK = "{\"Body\":{\"stkCallback\":{\"MerchantRequestID\":\"m-1\","
            + "\"CheckoutRequestID\":\"ws_CO_1\",\"ResultCode\":1032,\"ResultDesc\":\"Request cancelled by user.\"}}}";

    private final CollectionResponseProcessor collectionResponseProcessor = mock(CollectionResponseProcessor.class);
    private final TransactionResponseProcessor transactionResponseProcessor = mock(TransactionResponseProcessor.class);
    private final AccessTokenStore accessTokenStore = mock(AccessTokenStore.class);
    private final CorrelationIDStore correlationIDStore = mock(CorrelationIDStore.class);
    private final SafaricomUtils safaricomUtils = mock(SafaricomUtils.class);
    private final MpesaUtils mpesaUtils = mock(MpesaUtils.class);

    private CamelContext context;
    private ProducerTemplate template;
    private MockEndpoint mpesa;
    private MockEndpoint errorCodeFilter;
    private Boolean errorRecoverable;

    @BeforeEach
    void setUp() throws Exception {
        MpesaProps.MPESA props = new MpesaProps.MPESA();
        props.setName("roster");
        props.setApiHost("http://mpesa");
        props.setBusinessShortCode("174379");
        props.setTill("600000");
        props.setPassKey("passkey");
        when(mpesaUtils.setMpesaProperties()).thenReturn(props);
        when(mpesaUtils.getMpesaProperties(any(), any())).thenReturn(props);
        when(accessTokenStore.getAccessToken()).thenReturn("token-123456");
        when(safaricomUtils.getPassword(anyString(), anyString(), anyString())).thenReturn("generated-password");
        when(safaricomUtils.getTimestamp()).thenReturn(20261005094948L);

        SafaricomRoutesBuilder builder = new SafaricomRoutesBuilder(new ObjectMapper(), collectionResponseProcessor,
                transactionResponseProcessor, new MpesaGenericProcessor(), accessTokenStore, correlationIDStore,
                safaricomUtils, mpesaUtils);
        ReflectionTestUtils.setField(builder, "buyGoodsLipanaUrl", "/stkpush");
        ReflectionTestUtils.setField(builder, "transactionStatusUrl", "/stkpushquery");
        ReflectionTestUtils.setField(builder, "maxRetryCount", 3);
        ReflectionTestUtils.setField(builder, "mpesaTimeout", 1000);

        context = new DefaultCamelContext();
        context.addRoutes(builder);
        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:get-access-token").id("stub-get-access-token").log("access token stub");
                from("direct:filter-by-error-code").id("stub-filter-by-error-code")
                        .to("mock:error-code-filter")
                        .process(e -> e.setProperty(IS_ERROR_RECOVERABLE, errorRecoverable));
            }
        });

        AdviceWith.adviceWith(context, "buy-goods-callback", a -> a.replaceFromWith("direct:rest-callback"));
        AdviceWith.adviceWith(context, "buy-goods-online", a -> a.replaceFromWith("direct:rest-buygoods"));
        AdviceWith.adviceWith(context, "buy-goods-transaction-status",
                a -> a.replaceFromWith("direct:rest-transactionstatus"));
        adviceRouteFrom("lipana-buy-goods");
        adviceRouteFrom("lipana-transaction-status");

        context.start();
        template = context.createProducerTemplate();
        mpesa = context.getEndpoint("mock:mpesa", MockEndpoint.class);
        errorCodeFilter = context.getEndpoint("mock:error-code-filter", MockEndpoint.class);
    }

    @AfterEach
    void tearDown() {
        context.stop();
    }

    // ---- transaction status response handling ----

    @Test
    void statusResponse_success_shouldMarkSucceededWithReceipt() throws Exception {
        Exchange exchange = statusResponse(200,
                "{\"CheckoutRequestID\":\"ws_CO_1\",\"ResultCode\":\"0\",\"MpesaReceiptNumber\":\"RKTQDM7W6S\"}");

        assertNull(exchange.getException());
        assertEquals(false, exchange.getProperty(TRANSACTION_FAILED));
        assertEquals("RKTQDM7W6S", exchange.getProperty(SERVER_TRANSACTION_RECEIPT_NUMBER));
        assertEquals("ws_CO_1", exchange.getProperty(SERVER_TRANSACTION_ID));
        assertEquals("tx-1", exchange.getProperty(TRANSACTION_ID));
        assertNotNull(exchange.getProperty(LAST_RESPONSE_BODY));
        errorCodeFilter.expectedMessageCount(0);
        errorCodeFilter.assertIsSatisfied();
        verify(collectionResponseProcessor).process(any());
    }

    @Test
    void statusResponse_successWithoutCheckoutId_shouldKeepExistingServerId() throws Exception {
        Exchange exchange = statusResponse(200, "{\"ResultCode\":0}");

        assertNull(exchange.getException());
        assertEquals("ws_CO_existing", exchange.getProperty(SERVER_TRANSACTION_ID));
        assertNull(exchange.getProperty(SERVER_TRANSACTION_RECEIPT_NUMBER));
        verify(collectionResponseProcessor).process(any());
    }

    @Test
    void statusResponse_nonRecoverableError_shouldMarkFailed() throws Exception {
        errorRecoverable = false;

        Exchange exchange = statusResponse(200,
                "{\"CheckoutRequestID\":\"ws_CO_1\",\"ResultCode\":\"1032\",\"ResultDesc\":\"Request cancelled by user.\"}");

        assertNull(exchange.getException());
        assertEquals("1032", exchange.getProperty(ERROR_CODE));
        assertEquals("Request cancelled by user.", exchange.getProperty(ERROR_DESCRIPTION));
        assertEquals(true, exchange.getProperty(TRANSACTION_FAILED));
        verify(collectionResponseProcessor).process(any());
    }

    @Test
    void statusResponse_recoverableError_shouldStayPending() throws Exception {
        errorRecoverable = true;

        Exchange exchange = statusResponse(200, "{\"CheckoutRequestID\":\"ws_CO_1\",\"ResultCode\":\"1037\"}");

        assertNull(exchange.getException());
        assertEquals(true, exchange.getProperty(IS_TRANSACTION_PENDING));
        assertNull(exchange.getProperty(TRANSACTION_FAILED));
        verify(collectionResponseProcessor).process(any());
    }

    @Test
    void statusResponse_filterDidNotSetFlag_shouldDefaultToFailed() throws Exception {
        errorRecoverable = null;

        Exchange exchange = statusResponse(200, "{\"CheckoutRequestID\":\"ws_CO_1\",\"ResultCode\":\"1037\"}");

        assertEquals(true, exchange.getProperty(TRANSACTION_FAILED));
    }

    @Test
    void statusResponse_withoutResultCode_shouldStayPending() throws Exception {
        Exchange exchange = statusResponse(200,
                "{\"requestId\":\"r-1\",\"errorCode\":\"500.001.1001\",\"errorMessage\":\"The transaction is being processed\"}");

        assertNull(exchange.getException());
        assertEquals(true, exchange.getProperty(IS_TRANSACTION_PENDING));
        verify(collectionResponseProcessor).process(any());
    }

    @Test
    void statusResponse_500_shouldStayPendingWithErrorDetails() throws Exception {
        Exchange exchange = statusResponse(500,
                "{\"requestId\":\"r-1\",\"errorCode\":\"500.001.1001\",\"errorMessage\":\"The transaction is being processed\"}");

        assertNull(exchange.getException());
        assertEquals(true, exchange.getProperty(IS_TRANSACTION_PENDING));
        assertEquals("500.001.1001", exchange.getProperty(ERROR_CODE));
        assertEquals("The transaction is being processed", exchange.getProperty(ERROR_DESCRIPTION));
        assertEquals("tx-1", exchange.getProperty(TRANSACTION_ID));
        verify(collectionResponseProcessor).process(any());
    }

    @Test
    void statusResponse_otherStatus_shouldMarkFailed() throws Exception {
        Exchange exchange = statusResponse(400, "{\"errorCode\":\"400.002.02\"}");

        assertNull(exchange.getException());
        assertEquals(true, exchange.getProperty(TRANSACTION_FAILED));
        assertEquals("tx-1", exchange.getProperty(TRANSACTION_ID));
        verify(collectionResponseProcessor).process(any());
    }

    // ---- transaction status base flow ----

    @Test
    void statusBase_withinRetryLimit_shouldQueryMpesaAndHandleResponse() throws Exception {
        respondFromMpesa(200, "{\"CheckoutRequestID\":\"ws_CO_1\",\"ResultCode\":\"0\"}");
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty(SERVER_TRANSACTION_STATUS_RETRY_COUNT, 1);
        exchange.setProperty(CORRELATION_ID, "tx-1");
        exchange.setProperty(SERVER_TRANSACTION_ID, "ws_CO_1");
        exchange.setProperty(BUY_GOODS_REQUEST_BODY, buyGoodsRequest());

        template.send("direct:get-transaction-status-base", exchange);

        assertNull(exchange.getException());
        assertEquals("token-123456", exchange.getProperty(ACCESS_TOKEN));
        assertEquals(false, exchange.getProperty(TRANSACTION_FAILED));
        Exchange sent = mpesa.getReceivedExchanges().get(0);
        assertEquals("Bearer token-123456", sent.getIn().getHeader("Authorization"));
        assertEquals("POST", sent.getIn().getHeader(Exchange.HTTP_METHOD));
        Map<?, ?> body = new ObjectMapper().readValue(sent.getIn().getBody(String.class), Map.class);
        assertEquals("ws_CO_1", body.get("CheckoutRequestID"));
        assertEquals("generated-password", body.get("Password"));
        assertEquals("20261005094948", body.get("Timestamp"));
        verify(collectionResponseProcessor).process(any());
    }

    @Test
    void statusResponse_emptyBody_shouldStayPending() throws Exception {
        Exchange exchange = statusResponse(200, "");

        assertNull(exchange.getException());
        assertEquals(true, exchange.getProperty(IS_TRANSACTION_PENDING));
        assertEquals("tx-1", exchange.getProperty(TRANSACTION_ID));
        errorCodeFilter.expectedMessageCount(0);
        errorCodeFilter.assertIsSatisfied();
        verify(collectionResponseProcessor).process(any());
    }

    @Test
    void statusBase_emptyBody_shouldCountAttemptAndStayPending() throws Exception {
        respondFromMpesa(200, "");

        Exchange exchange = statusBaseExchange();
        template.send("direct:get-transaction-status-base", exchange);

        assertNull(exchange.getException());
        assertEquals(true, exchange.getProperty(IS_TRANSACTION_PENDING));
        verify(collectionResponseProcessor).process(any());
    }

    @Test
    void statusBase_unparseableBody_shouldStillCountAttemptAndStayPending() throws Exception {
        respondFromMpesa(200, "<html>Bad Gateway</html>");

        Exchange exchange = statusBaseExchange();
        template.send("direct:get-transaction-status-base", exchange);

        assertNull(exchange.getException());
        assertEquals(true, exchange.getProperty(IS_TRANSACTION_PENDING));
        assertEquals("<html>Bad Gateway</html>", exchange.getProperty(LAST_RESPONSE_BODY));
        assertEquals("tx-1", exchange.getProperty(TRANSACTION_ID));
        verify(collectionResponseProcessor).process(any());
    }

    @Test
    void statusBase_retryLimitExceeded_shouldFailWithoutQueryingMpesa() throws Exception {
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty(SERVER_TRANSACTION_STATUS_RETRY_COUNT, 4);

        template.send("direct:get-transaction-status-base", exchange);

        assertEquals(true, exchange.getProperty(IS_RETRY_EXCEEDED));
        assertEquals(true, exchange.getProperty(TRANSACTION_FAILED));
        mpesa.expectedMessageCount(0);
        mpesa.assertIsSatisfied();
        verify(collectionResponseProcessor).process(any());
    }

    @Test
    void statusBase_ioError_shouldStayPending() throws Exception {
        mpesa.whenAnyExchangeReceived(e -> {
            throw new IOException("Connection reset");
        });
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty(SERVER_TRANSACTION_STATUS_RETRY_COUNT, 1);
        exchange.setProperty(CORRELATION_ID, "tx-1");
        exchange.setProperty(BUY_GOODS_REQUEST_BODY, buyGoodsRequest());

        template.send("direct:get-transaction-status-base", exchange);

        assertNull(exchange.getException());
        assertEquals("CONNECTION_ERROR", exchange.getProperty(ERROR_CODE));
        assertEquals("Connection reset", exchange.getProperty(ERROR_DESCRIPTION));
        assertEquals(true, exchange.getProperty(IS_TRANSACTION_PENDING));
        assertEquals("tx-1", exchange.getProperty(TRANSACTION_ID));
        verify(collectionResponseProcessor).process(any());
    }

    // ---- buy goods flow ----

    @Test
    void buyGoodsBase_success_shouldSendStkPushAndSaveCorrelation() throws Exception {
        respondFromMpesa(200, "{\"MerchantRequestID\":\"m-1\",\"CheckoutRequestID\":\"ws_CO_1\",\"ResponseCode\":\"0\"}");
        Exchange exchange = buyGoodsExchange();

        template.send("direct:buy-goods-base", exchange);

        assertNull(exchange.getException());
        assertEquals("ws_CO_1", exchange.getProperty(SERVER_TRANSACTION_ID));
        assertEquals(600000L, exchange.getProperty(PARTY_LOOKUP_FSP_ID));
        assertNotNull(exchange.getProperty("mpesaApiResponse"));
        verify(correlationIDStore).addMapping("ws_CO_1", "tx-1");
        verify(transactionResponseProcessor).process(any());

        Map<?, ?> body = new ObjectMapper().readValue(
                mpesa.getReceivedExchanges().get(0).getIn().getBody(String.class), Map.class);
        assertEquals("generated-password", body.get("Password"));
        assertEquals("CustomerBuyGoodsOnline", body.get("TransactionType"));
    }

    @Test
    void buyGoodsBase_failure_shouldMarkFailed() throws Exception {
        respondFromMpesa(400, "{\"errorCode\":\"400.002.02\",\"errorMessage\":\"Bad Request\"}");
        Exchange exchange = buyGoodsExchange();

        template.send("direct:buy-goods-base", exchange);

        assertEquals(true, exchange.getProperty(TRANSACTION_FAILED));
        assertEquals("tx-1", exchange.getProperty(TRANSACTION_ID));
        verify(correlationIDStore, never()).addMapping(any(), any());
        verify(transactionResponseProcessor).process(any());
    }

    @Test
    void buyGoodsBase_ioError_shouldMarkFailedWithoutCallingCollectionProcessor() throws Exception {
        mpesa.whenAnyExchangeReceived(e -> {
            throw new IOException("Connection reset");
        });
        Exchange exchange = buyGoodsExchange();

        template.send("direct:buy-goods-base", exchange);

        assertNull(exchange.getException());
        assertEquals(true, exchange.getProperty(TRANSACTION_FAILED));
        assertEquals("CONNECTION_ERROR", exchange.getProperty(ERROR_CODE));
        verify(collectionResponseProcessor, never()).process(any());
    }

    @Test
    void restBuyGoods_shouldParseBodyAndStartBuyGoodsFlow() throws Exception {
        respondFromMpesa(200, "{\"CheckoutRequestID\":\"ws_CO_1\"}");

        Exchange exchange = template.send("direct:rest-buygoods", e -> {
            e.setProperty(CORRELATION_ID, "tx-1");
            e.getIn().setBody("{\"BusinessShortCode\":174379,\"Amount\":1,\"PartyA\":254708374149,"
                    + "\"PartyB\":174379,\"PhoneNumber\":254708374149,\"Timestamp\":\"20261005094948\"}");
        });

        assertNull(exchange.getException());
        BuyGoodsPaymentRequestDTO dto = exchange.getProperty(BUY_GOODS_REQUEST_BODY, BuyGoodsPaymentRequestDTO.class);
        assertEquals(174379L, dto.getBusinessShortCode());
        assertEquals(1, mpesa.getReceivedCounter());
    }

    @Test
    void restTransactionStatus_shouldParseBodyIntoStatusRequest() {
        Exchange exchange = template.send("direct:rest-transactionstatus", e -> {
            e.setProperty(BUY_GOODS_REQUEST_BODY, buyGoodsRequest());
            e.getIn().setBody("{\"BusinessShortCode\":174379,\"Timestamp\":\"20261005094948\","
                    + "\"CheckoutRequestID\":\"ws_CO_1\"}");
        });

        assertNull(exchange.getException());
        assertNotNull(exchange.getProperty(BUY_GOODS_TRANSACTION_STATUS_BODY));
        assertEquals(1, mpesa.getReceivedCounter());
    }

    // ---- callbacks ----

    @Test
    void callback_success_shouldMarkSucceededWithReceiptAndAccept() throws Exception {
        when(correlationIDStore.getClientCorrelation("ws_CO_1")).thenReturn("tx-1");

        Exchange exchange = template.send("direct:rest-callback", e -> e.getIn().setBody(SUCCESS_CALLBACK));

        assertNull(exchange.getException());
        assertEquals(false, exchange.getProperty(TRANSACTION_FAILED));
        assertEquals("tx-1", exchange.getProperty(TRANSACTION_ID));
        assertEquals("RKTQDM7W6S", exchange.getProperty(SERVER_TRANSACTION_RECEIPT_NUMBER));
        assertEquals(true, exchange.getProperty(CALLBACK_RECEIVED));
        assertNotNull(exchange.getProperty(CALLBACK));
        assertEquals(202, exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE));
        verify(collectionResponseProcessor).process(any());
    }

    @Test
    void callback_nonRecoverableError_shouldMarkFailed() throws Exception {
        errorRecoverable = false;
        when(correlationIDStore.getClientCorrelation("ws_CO_1")).thenReturn("tx-1");

        Exchange exchange = template.send("direct:rest-callback", e -> e.getIn().setBody(CANCELLED_CALLBACK));

        assertNull(exchange.getException());
        assertEquals("1032", exchange.getProperty(ERROR_CODE));
        assertEquals(true, exchange.getProperty(TRANSACTION_FAILED));
        verify(collectionResponseProcessor).process(any());
    }

    @Test
    void callback_recoverableError_shouldWaitForStatusCheck() throws Exception {
        errorRecoverable = true;
        when(correlationIDStore.getClientCorrelation("ws_CO_1")).thenReturn("tx-1");

        Exchange exchange = template.send("direct:rest-callback", e -> e.getIn().setBody(CANCELLED_CALLBACK));

        assertNull(exchange.getException());
        assertNull(exchange.getProperty(TRANSACTION_FAILED));
        verify(collectionResponseProcessor, never()).process(any());
    }

    // ---- helpers ----

    private Exchange statusResponse(int status, String body) {
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty(CORRELATION_ID, "tx-1");
        exchange.setProperty(SERVER_TRANSACTION_ID, "ws_CO_existing");
        exchange.getIn().setHeader(Exchange.HTTP_RESPONSE_CODE, status);
        exchange.getIn().setBody(body);
        return template.send("direct:transaction-status-response-handler", exchange);
    }

    private Exchange statusBaseExchange() {
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty(SERVER_TRANSACTION_STATUS_RETRY_COUNT, 1);
        exchange.setProperty(CORRELATION_ID, "tx-1");
        exchange.setProperty(SERVER_TRANSACTION_ID, "ws_CO_1");
        exchange.setProperty(BUY_GOODS_REQUEST_BODY, buyGoodsRequest());
        return exchange;
    }

    private Exchange buyGoodsExchange() {
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty(CORRELATION_ID, "tx-1");
        exchange.setProperty(AMS, "roster");
        exchange.setProperty(BUY_GOODS_REQUEST_BODY, buyGoodsRequest());
        return exchange;
    }

    private void respondFromMpesa(int status, String body) {
        mpesa.whenAnyExchangeReceived(e -> {
            e.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, status);
            e.getMessage().setBody(body);
        });
    }

    private void adviceRouteFrom(String directName) throws Exception {
        for (RouteDefinition route : context.adapt(ModelCamelContext.class).getRouteDefinitions()) {
            if (route.getInput().getEndpointUri().matches("direct:(//)?" + directName)) {
                AdviceWith.adviceWith(route, context, new org.apache.camel.builder.AdviceWithRouteBuilder() {
                    @Override
                    public void configure() {
                        weaveByToString("DynamicTo.*").replace().to("mock:mpesa");
                    }
                });
                return;
            }
        }
        throw new IllegalStateException("No route from direct:" + directName);
    }

    private static BuyGoodsPaymentRequestDTO buyGoodsRequest() {
        BuyGoodsPaymentRequestDTO dto = new BuyGoodsPaymentRequestDTO();
        dto.setBusinessShortCode(174379L);
        dto.setTimestamp("20261005094948");
        dto.setAmount(1L);
        dto.setPartyA(254708374149L);
        dto.setPassword("old-password");
        return dto;
    }
}
