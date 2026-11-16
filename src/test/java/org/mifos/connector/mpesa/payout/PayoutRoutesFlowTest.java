package org.mifos.connector.mpesa.payout;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.Expression;
import org.apache.camel.Processor;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.spi.RestConfiguration;
import org.apache.camel.support.DefaultExchange;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mifos.connector.mpesa.resilience.MpesaEndpoint;
import org.mifos.connector.mpesa.resilience.MpesaHttpCaller;
import org.mifos.connector.mpesa.utility.MpesaProps;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.ACCESS_TOKEN;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.CORRELATION_ID;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.ERROR_CODE;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.MPESA_API_HOST;
import static org.mifos.connector.mpesa.payout.PayoutTestData.TRANSACTION_ID;
import static org.mifos.connector.mpesa.payout.PayoutVariables.B2C_REQUEST_BODY;
import static org.mifos.connector.mpesa.payout.PayoutVariables.Outcome;
import static org.mifos.connector.mpesa.payout.PayoutVariables.PAYOUT_ACCOUNT;
import static org.mifos.connector.mpesa.payout.PayoutVariables.PAYOUT_CONVERSATION_ID;
import static org.mifos.connector.mpesa.payout.PayoutVariables.PAYOUT_OUTCOME;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Runs the payout routes in a real Camel context, with a fake Safaricom behind the HTTP caller and the callback
 * endpoints served over HTTP.
 */
class PayoutRoutesFlowTest {

    private final MpesaHttpCaller httpCaller = mock(MpesaHttpCaller.class);
    private final PayoutResultHandler resultHandler = mock(PayoutResultHandler.class);
    private final PayoutAccessTokens tokens = new PayoutAccessTokens();
    private final MpesaProps.MPESA account = PayoutTestData.account();
    private final FakeSafaricom safaricom = new FakeSafaricom();
    private DefaultCamelContext camel;
    private int port;

    @BeforeEach
    void startRoutes() throws Exception {
        for (MpesaEndpoint endpoint : MpesaEndpoint.values()) {
            when(httpCaller.call(eq(endpoint), any(Expression.class))).thenAnswer(invocation -> {
                Expression uri = invocation.getArgument(1);
                return (Processor) exchange -> safaricom.answer(endpoint, uri.evaluate(exchange, String.class),
                        exchange);
            });
        }
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        camel = new DefaultCamelContext();
        camel.setStreamCaching(true);
        var rest = new RestConfiguration();
        rest.setComponent("undertow");
        rest.setPort(port);
        rest.setBindingMode(RestConfiguration.RestBindingMode.json);
        rest.setDataFormatProperties(new HashMap<>());
        rest.setScheme("http");
        camel.setRestConfiguration(rest);

        var routes = new PayoutRoutes(new ObjectMapper(), httpCaller, tokens, PayoutTestData.requestBuilder(),
                resultHandler);
        ReflectionTestUtils.setField(routes, "b2cPath", "/mpesa/b2c/v3/paymentrequest");
        ReflectionTestUtils.setField(routes, "b2cStatusPath", "/mpesa/transactionstatus/v1/query");
        ReflectionTestUtils.setField(routes, "resultPath", "/payout/result");
        ReflectionTestUtils.setField(routes, "timeoutPath", "/payout/timeout");
        ReflectionTestUtils.setField(routes, "statusResultPath", "/payout/status/result");
        ReflectionTestUtils.setField(routes, "statusTimeoutPath", "/payout/status/timeout");
        camel.addRoutes(routes);
        camel.start();
    }

    @AfterEach
    void stop() {
        camel.stop();
    }

    @Test
    void payment_shouldFetchATokenOnceAndSendTheRequestWithIt() {
        safaricom.respond(MpesaEndpoint.TOKEN, 200, "{\"access_token\":\"tok-1\",\"expires_in\":\"3599\"}");
        safaricom.respond(MpesaEndpoint.B2C, 200, "{\"ConversationID\":\"AG_1\",\"OriginatorConversationID\":\""
                + TRANSACTION_ID + "\",\"ResponseCode\":\"0\",\"ResponseDescription\":\"Accepted\"}");

        var first = pay();
        var second = pay();

        assertEquals(Outcome.ACCEPTED, first.getProperty(PAYOUT_OUTCOME));
        assertEquals("AG_1", first.getProperty(PAYOUT_CONVERSATION_ID));
        assertEquals(Outcome.ACCEPTED, second.getProperty(PAYOUT_OUTCOME));
        assertEquals(1, safaricom.calls(MpesaEndpoint.TOKEN).size(), "the token must be cached");

        var tokenCall = safaricom.calls(MpesaEndpoint.TOKEN).get(0);
        assertTrue(tokenCall.uri.startsWith(account.getAuthHost()), tokenCall.uri);
        assertEquals("GET", tokenCall.method);
        assertEquals("grant_type=client_credentials", tokenCall.query);
        assertEquals(PayoutAccessTokens.basicAuthorization(account), tokenCall.authorization);

        var paymentCall = safaricom.calls(MpesaEndpoint.B2C).get(0);
        assertTrue(paymentCall.uri.startsWith("https://sandbox.safaricom.co.ke/mpesa/b2c/v3/paymentrequest"),
                paymentCall.uri);
        assertEquals("POST", paymentCall.method);
        assertEquals("Bearer tok-1", paymentCall.authorization);
        var body = new JSONObject(paymentCall.body);
        assertEquals(TRANSACTION_ID, body.getString("OriginatorConversationID"));
        assertEquals(1500, body.getInt("Amount"));
        assertEquals("encrypted-credential", body.getString("SecurityCredential"));
    }

    @Test
    void payment_tokenRejected_shouldFailWithoutSending() {
        safaricom.respond(MpesaEndpoint.TOKEN, 400, "{\"errorCode\":\"400.008.01\"}");

        var exchange = pay();

        assertEquals(Outcome.FAILED, exchange.getProperty(PAYOUT_OUTCOME));
        assertEquals("TOKEN_ERROR", exchange.getProperty(ERROR_CODE));
        assertTrue(safaricom.calls(MpesaEndpoint.B2C).isEmpty());
    }

    @Test
    void payment_tokenUnreachable_shouldFailWithoutSending() {
        safaricom.fail(MpesaEndpoint.TOKEN, new SocketTimeoutException("connect timed out"));

        var exchange = pay();

        assertEquals(Outcome.FAILED, exchange.getProperty(PAYOUT_OUTCOME));
        assertEquals("TOKEN_ERROR", exchange.getProperty(ERROR_CODE));
        assertNull(exchange.getException());
        assertTrue(safaricom.calls(MpesaEndpoint.B2C).isEmpty());
    }

    @Test
    void payment_noResponse_shouldBeUnknown() {
        tokens.save(account, "tok-1", 3599);
        safaricom.fail(MpesaEndpoint.B2C, new SocketTimeoutException("Read timed out"));

        var exchange = pay();

        assertEquals(Outcome.UNKNOWN, exchange.getProperty(PAYOUT_OUTCOME));
        assertEquals("NO_RESPONSE", exchange.getProperty(ERROR_CODE));
        assertNull(exchange.getException());
    }

    @Test
    void statusQuery_shouldSendTheQueryForThePayout() {
        tokens.save(account, "tok-1", 3599);
        safaricom.respond(MpesaEndpoint.B2C_STATUS, 200, "{\"ResponseCode\":\"0\"}");

        var exchange = queryStatus();

        assertNull(exchange.getException());
        var call = safaricom.calls(MpesaEndpoint.B2C_STATUS).get(0);
        assertTrue(call.uri.startsWith("https://sandbox.safaricom.co.ke/mpesa/transactionstatus/v1/query"), call.uri);
        assertEquals("Bearer tok-1", call.authorization);
        var body = new JSONObject(call.body);
        assertEquals(TRANSACTION_ID, body.getString("OriginalConversationID"));
        assertEquals("TransactionStatusQuery", body.getString("CommandID"));
        assertEquals(TRANSACTION_ID, body.getString("Occasion"));
    }

    @Test
    void statusQuery_withoutToken_shouldSkipTheQuery() {
        safaricom.respond(MpesaEndpoint.TOKEN, 500, "");

        var exchange = queryStatus();

        assertNull(exchange.getException());
        assertNull(exchange.getProperty(ACCESS_TOKEN));
        assertTrue(safaricom.calls(MpesaEndpoint.B2C_STATUS).isEmpty());
    }

    @Test
    void statusQuery_unreachable_shouldBeCheckedAgainLater() {
        tokens.save(account, "tok-1", 3599);
        safaricom.fail(MpesaEndpoint.B2C_STATUS, new IOException("Connection reset"));

        var exchange = queryStatus();

        assertNull(exchange.getException());
        assertEquals(1, safaricom.calls(MpesaEndpoint.B2C_STATUS).size());
    }

    @Test
    void statusQuery_rejectedToken_shouldBeEvictedSoTheNextCheckFetchesANewOne() {
        tokens.save(account, "revoked", 3599);
        safaricom.respond(MpesaEndpoint.B2C_STATUS, 401, "{\"errorCode\":\"404.001.03\"}");

        queryStatus();

        assertNull(tokens.get(account));
    }

    @Test
    void statusQuery_answered_shouldKeepTheToken() {
        tokens.save(account, "tok-1", 3599);
        safaricom.respond(MpesaEndpoint.B2C_STATUS, 200, "{\"ResponseCode\":\"0\"}");

        queryStatus();

        assertEquals("tok-1", tokens.get(account));
    }

    @Test
    void paymentResultCallback_shouldPassTheBodyAndTheUrlTokenToTheHandler() throws Exception {
        var body = PayoutTestData.paymentResult(TRANSACTION_ID, 0, "1500");

        var response = post("/payout/result/" + PayoutTestData.token(TRANSACTION_ID), body);

        assertEquals(200, response.statusCode());
        assertEquals(0, new JSONObject(response.body()).getInt("ResultCode"));
        verify(resultHandler).onPaymentResult(contains(TRANSACTION_ID), eq(PayoutTestData.token(TRANSACTION_ID)));
    }

    @Test
    void statusResultCallback_shouldPassTheBodyAndTheUrlTokenToTheHandler() throws Exception {
        var response = post("/payout/status/result/forged", PayoutTestData.statusResult(TRANSACTION_ID,
                "Completed", "1500"));

        assertEquals(200, response.statusCode());
        verify(resultHandler).onStatusResult(contains(TRANSACTION_ID), eq("forged"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/payout/result", "/payout/status/result"})
    void resultCallbacks_withoutToken_shouldNotReachTheHandler(String path) throws Exception {
        var response = post(path, PayoutTestData.paymentResult(TRANSACTION_ID, 2001, null));

        assertEquals(404, response.statusCode());
        verifyNoInteractions(resultHandler);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/payout/timeout/any", "/payout/status/timeout/any"})
    void timeoutCallbacks_shouldOnlyBeAcknowledged(String path) throws Exception {
        var response = post(path, "{\"Result\":{\"ResultCode\":1}}");

        assertEquals(200, response.statusCode());
        assertEquals(0, new JSONObject(response.body()).getInt("ResultCode"));
        verifyNoInteractions(resultHandler);
    }

    private Exchange pay() {
        var request = PayoutTestData.requestBuilder().build(TRANSACTION_ID,
                PayoutTestData.payoutRequest("254708374149", "1500"), account);
        return send("direct:b2c-payment-base", exchange -> exchange.setProperty(B2C_REQUEST_BODY, request));
    }

    private Exchange queryStatus() {
        return send("direct:b2c-status-base", exchange -> { });
    }

    private Exchange send(String route, java.util.function.Consumer<Exchange> setup) {
        var exchange = new DefaultExchange(camel);
        exchange.setProperty(CORRELATION_ID, TRANSACTION_ID);
        exchange.setProperty(PAYOUT_ACCOUNT, account);
        exchange.setProperty(MPESA_API_HOST, account.getApiHost());
        setup.accept(exchange);
        return camel.createProducerTemplate().send(route, exchange);
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Answers the calls the routes make through the HTTP caller and records them.
     */
    private static final class FakeSafaricom {

        private final Map<MpesaEndpoint, Object> answers = new EnumMap<>(MpesaEndpoint.class);
        private final Map<MpesaEndpoint, List<Call>> calls = new EnumMap<>(MpesaEndpoint.class);

        void respond(MpesaEndpoint endpoint, int status, String body) {
            answers.put(endpoint, new Object[] {status, body});
        }

        void fail(MpesaEndpoint endpoint, IOException failure) {
            answers.put(endpoint, failure);
        }

        List<Call> calls(MpesaEndpoint endpoint) {
            return calls.getOrDefault(endpoint, List.of());
        }

        void answer(MpesaEndpoint endpoint, String uri, Exchange exchange) throws IOException {
            var message = exchange.getMessage();
            calls.computeIfAbsent(endpoint, e -> new ArrayList<>()).add(new Call(uri,
                    message.getHeader(Exchange.HTTP_METHOD, String.class),
                    message.getHeader(Exchange.HTTP_RAW_QUERY, String.class),
                    message.getHeader("Authorization", String.class), message.getBody(String.class)));
            var answer = answers.get(endpoint);
            if (answer instanceof IOException failure) {
                throw failure;
            }
            var response = (Object[]) answer;
            message.setHeader(Exchange.HTTP_RESPONSE_CODE, response[0]);
            message.setBody(response[1]);
        }
    }

    private record Call(String uri, String method, String query, String authorization, String body) {
    }
}
