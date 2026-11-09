package org.mifos.connector.mpesa.camel.routes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
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
import org.mifos.connector.mpesa.resilience.MpesaResilienceProperties;
import org.mifos.connector.mpesa.resilience.TestCallers;
import org.mifos.connector.mpesa.utility.MpesaProps;
import org.mifos.connector.mpesa.utility.MpesaUtils;
import org.mifos.connector.mpesa.utility.SafaricomUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.BUY_GOODS_REQUEST_BODY;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.CORRELATION_ID;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.ERROR_CODE;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.IS_TRANSACTION_PENDING;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.AMS;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.SERVER_TRANSACTION_ID;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.SERVER_TRANSACTION_STATUS_RETRY_COUNT;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.TRANSACTION_FAILED;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Runs the Safaricom routes against a simulated Safaricom that is slow or unavailable.
 */
class SafaricomResilienceFlowTest {

    private static final String STK_PUSH = "/mpesa/stkpush/v1/processrequest";
    private static final String STK_QUERY = "/mpesa/stkpushquery/v2/query";
    private static final String ACCEPTED = "{\"CheckoutRequestID\":\"ws_CO_1\",\"ResponseCode\":\"0\"}";

    private final CollectionResponseProcessor collectionResponseProcessor = mock(CollectionResponseProcessor.class);
    private final MpesaUtils mpesaUtils = mock(MpesaUtils.class);
    private final SafaricomUtils safaricomUtils = mock(SafaricomUtils.class);
    private final AccessTokenStore accessTokenStore = mock(AccessTokenStore.class);

    private WireMockServer safaricom;
    private CamelContext context;
    private ProducerTemplate template;

    @BeforeEach
    void setUp() throws Exception {
        safaricom = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        safaricom.start();

        MpesaProps.MPESA props = new MpesaProps.MPESA();
        props.setName("roster");
        props.setApiHost(safaricom.baseUrl());
        props.setBusinessShortCode("174379");
        props.setTill("600000");
        props.setPassKey("passkey");
        when(mpesaUtils.setMpesaProperties()).thenReturn(props);
        when(mpesaUtils.getMpesaProperties(any(), any())).thenReturn(props);
        when(accessTokenStore.getAccessToken()).thenReturn("token-123456");
        when(safaricomUtils.getPassword(anyString(), anyString(), anyString())).thenReturn("generated-password");
        when(safaricomUtils.getTimestamp()).thenReturn(20261005094948L);

        MpesaResilienceProperties resilience = new MpesaResilienceProperties();
        for (MpesaResilienceProperties.EndpointSettings settings : new MpesaResilienceProperties.EndpointSettings[] {
                resilience.getStkPush(), resilience.getStkQuery() }) {
            settings.setReadTimeout(Duration.ofMillis(300));
            settings.getRetry().setInitialBackoff(Duration.ofMillis(10));
            settings.getCircuitBreaker().setWaitDurationInOpenState(Duration.ofMillis(500));
            settings.getCircuitBreaker().setPermittedCallsInHalfOpenState(1);
        }
        resilience.getStkPush().getCircuitBreaker().setSlidingWindowSize(4);
        resilience.getStkPush().getCircuitBreaker().setMinimumNumberOfCalls(2);

        context = new DefaultCamelContext();
        template = context.createProducerTemplate();
        SafaricomRoutesBuilder builder = new SafaricomRoutesBuilder(new ObjectMapper(), collectionResponseProcessor,
                mock(TransactionResponseProcessor.class), new MpesaGenericProcessor(), accessTokenStore,
                mock(CorrelationIDStore.class), safaricomUtils, mpesaUtils,
                TestCallers.caller(template, resilience));
        ReflectionTestUtils.setField(builder, "buyGoodsLipanaUrl", STK_PUSH);
        ReflectionTestUtils.setField(builder, "transactionStatusUrl", STK_QUERY);
        ReflectionTestUtils.setField(builder, "maxRetryCount", 3);
        context.addRoutes(builder);
        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:get-access-token").log("access token stub");
            }
        });
        AdviceWith.adviceWith(context, "buy-goods-callback", a -> a.replaceFromWith("direct:rest-callback"));
        AdviceWith.adviceWith(context, "buy-goods-online", a -> a.replaceFromWith("direct:rest-buygoods"));
        AdviceWith.adviceWith(context, "buy-goods-transaction-status",
                a -> a.replaceFromWith("direct:rest-transactionstatus"));
        context.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        template.close();
        context.close();
        safaricom.stop();
    }

    @Test
    void stkPushWithoutResponseIsFailedAndNeverSentTwice() {
        safaricom.stubFor(post(STK_PUSH).willReturn(aResponse().withStatus(200).withBody(ACCEPTED)
                .withFixedDelay(600)));

        Exchange exchange = buyGoods("tx-1");

        assertEquals(true, exchange.getProperty(TRANSACTION_FAILED));
        assertEquals("CONNECTION_ERROR", exchange.getProperty(ERROR_CODE));
        verifyEventually(1, STK_PUSH);
    }

    @Test
    void unavailableSafaricomOpensTheCircuitThenFailsFastAndRecovers() {
        safaricom.stubFor(post(STK_PUSH).willReturn(aResponse().withStatus(503)));
        assertEquals(true, buyGoods("tx-1").getProperty(TRANSACTION_FAILED));
        assertEquals(true, buyGoods("tx-2").getProperty(TRANSACTION_FAILED));

        long start = System.nanoTime();
        Exchange failedFast = buyGoods("tx-3");
        final long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(true, failedFast.getProperty(TRANSACTION_FAILED));
        assertEquals("CIRCUIT_OPEN", failedFast.getProperty(ERROR_CODE));
        safaricom.verify(2, postRequestedFor(urlEqualTo(STK_PUSH)));
        assertTrue(elapsedMs < 200, "failed fast in " + elapsedMs + " ms");

        await().pollDelay(Duration.ofMillis(600)).atMost(Duration.ofSeconds(2)).until(() -> true);
        safaricom.resetAll();
        safaricom.stubFor(post(STK_PUSH).willReturn(aResponse().withStatus(200).withBody(ACCEPTED)));

        Exchange recovered = buyGoods("tx-4");

        assertNotEquals(true, recovered.getProperty(TRANSACTION_FAILED));
        assertEquals("ws_CO_1", recovered.getProperty(SERVER_TRANSACTION_ID));
        safaricom.verify(1, postRequestedFor(urlEqualTo(STK_PUSH)));
    }

    @Test
    void statusCheckRetriesGatewayErrorsThenStaysPending() throws Exception {
        safaricom.stubFor(post(STK_QUERY).willReturn(aResponse().withStatus(503)));

        Exchange exchange = statusCheck();

        assertEquals(true, exchange.getProperty(IS_TRANSACTION_PENDING));
        assertNull(exchange.getProperty(TRANSACTION_FAILED));
        safaricom.verify(3, postRequestedFor(urlEqualTo(STK_QUERY)));
        verify(collectionResponseProcessor).process(any());
    }

    @Test
    void statusCheckWithoutResponseStaysPending() throws Exception {
        safaricom.stubFor(post(STK_QUERY).willReturn(aResponse().withStatus(200).withFixedDelay(600)));

        Exchange exchange = statusCheck();

        assertEquals(true, exchange.getProperty(IS_TRANSACTION_PENDING));
        assertNull(exchange.getProperty(TRANSACTION_FAILED));
        verifyEventually(3, STK_QUERY);
        verify(collectionResponseProcessor).process(any());
    }

    // WireMock logs a delayed request only once its delay has passed, after the client gave up.
    private void verifyEventually(int count, String path) {
        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> safaricom.verify(count, postRequestedFor(urlEqualTo(path))));
    }

    private Exchange buyGoods(String transactionId) {
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty(CORRELATION_ID, transactionId);
        exchange.setProperty(AMS, "roster");
        exchange.setProperty(BUY_GOODS_REQUEST_BODY, buyGoodsRequest());
        return template.send("direct:buy-goods-base", exchange);
    }

    private Exchange statusCheck() {
        Exchange exchange = new DefaultExchange(context);
        exchange.setProperty(SERVER_TRANSACTION_STATUS_RETRY_COUNT, 1);
        exchange.setProperty(CORRELATION_ID, "tx-1");
        exchange.setProperty(SERVER_TRANSACTION_ID, "ws_CO_1");
        exchange.setProperty(BUY_GOODS_REQUEST_BODY, buyGoodsRequest());
        return template.send("direct:get-transaction-status-base", exchange);
    }

    private static BuyGoodsPaymentRequestDTO buyGoodsRequest() {
        BuyGoodsPaymentRequestDTO dto = new BuyGoodsPaymentRequestDTO();
        dto.setBusinessShortCode(174379L);
        dto.setTimestamp("20261005094948");
        dto.setAmount(1L);
        dto.setPartyA(254708374149L);
        return dto;
    }
}
