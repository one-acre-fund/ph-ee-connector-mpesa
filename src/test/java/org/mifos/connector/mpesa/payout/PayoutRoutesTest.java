package org.mifos.connector.mpesa.payout;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mifos.connector.mpesa.resilience.MpesaCircuitOpenException;
import org.mifos.connector.mpesa.resilience.MpesaHttpCaller;
import org.mifos.connector.mpesa.resilience.MpesaJobDeadlineException;
import org.mifos.connector.mpesa.utility.MpesaProps;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.ACCESS_TOKEN;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.ERROR_CODE;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.ERROR_DESCRIPTION;
import static org.mifos.connector.mpesa.payout.PayoutVariables.Outcome;
import static org.mifos.connector.mpesa.payout.PayoutVariables.PAYOUT_ACCOUNT;
import static org.mifos.connector.mpesa.payout.PayoutVariables.PAYOUT_CONVERSATION_ID;
import static org.mifos.connector.mpesa.payout.PayoutVariables.PAYOUT_OUTCOME;
import static org.mockito.Mockito.mock;

class PayoutRoutesTest {

    private PayoutAccessTokens tokens;
    private PayoutRoutes routes;
    private MpesaProps.MPESA account;

    @BeforeEach
    void setUp() {
        tokens = new PayoutAccessTokens();
        account = PayoutTestData.account();
        routes = new PayoutRoutes(new ObjectMapper(), mock(MpesaHttpCaller.class), tokens,
                PayoutTestData.requestBuilder(), mock(PayoutResultHandler.class));
    }

    @Test
    void classifyPaymentResponse_accepted_shouldKeepConversationId() {
        Exchange exchange = response(200, "{\"ConversationID\":\"AG_1\",\"OriginatorConversationID\":\"tx\","
                + "\"ResponseCode\":\"0\",\"ResponseDescription\":\"Accept the service request successfully.\"}");

        routes.classifyPaymentResponse(exchange);

        assertEquals(Outcome.ACCEPTED, exchange.getProperty(PAYOUT_OUTCOME));
        assertEquals("AG_1", exchange.getProperty(PAYOUT_CONVERSATION_ID));
        assertNull(exchange.getProperty(ERROR_CODE));
    }

    @Test
    void classifyPaymentResponse_nonZeroResponseCode_shouldFail() {
        Exchange exchange = response(200, "{\"ResponseCode\":\"1\",\"ResponseDescription\":\"Rejected\"}");

        routes.classifyPaymentResponse(exchange);

        assertEquals(Outcome.FAILED, exchange.getProperty(PAYOUT_OUTCOME));
        assertEquals("1", exchange.getProperty(ERROR_CODE));
        assertEquals("Rejected", exchange.getProperty(ERROR_DESCRIPTION));
    }

    @Test
    void classifyPaymentResponse_duplicateOriginatorConversationId_shouldWaitForTheFirstResult() {
        Exchange exchange = response(500, "{\"requestId\":\"r\",\"errorCode\":\"500.002.1001\","
                + "\"errorMessage\":\"Duplicate OriginatorConversationID.\"}");

        routes.classifyPaymentResponse(exchange);

        assertEquals(Outcome.ACCEPTED, exchange.getProperty(PAYOUT_OUTCOME));
    }

    @Test
    void classifyPaymentResponse_badRequest_shouldFailWithSafaricomErrorCode() {
        Exchange exchange = response(400, "{\"requestId\":\"r\",\"errorCode\":\"400.002.02\","
                + "\"errorMessage\":\"Bad Request - Invalid Amount\"}");

        routes.classifyPaymentResponse(exchange);

        assertEquals(Outcome.FAILED, exchange.getProperty(PAYOUT_OUTCOME));
        assertEquals("400.002.02", exchange.getProperty(ERROR_CODE));
        assertEquals("Bad Request - Invalid Amount", exchange.getProperty(ERROR_DESCRIPTION));
    }

    @Test
    void classifyPaymentResponse_unauthorized_shouldFailAndDropTheCachedToken() {
        tokens.save(account, "stale", 3600);
        Exchange exchange = response(401, "{\"errorCode\":\"404.001.03\",\"errorMessage\":\"Invalid Access Token\"}");

        routes.classifyPaymentResponse(exchange);

        assertEquals(Outcome.FAILED, exchange.getProperty(PAYOUT_OUTCOME));
        assertNull(tokens.get(account));
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 504, 0})
    void classifyPaymentResponse_serverOrGatewayError_shouldBeUnknown(int status) {
        Exchange exchange = response(status, "<html>Gateway Timeout</html>");

        routes.classifyPaymentResponse(exchange);

        assertEquals(Outcome.UNKNOWN, exchange.getProperty(PAYOUT_OUTCOME));
    }

    @Test
    void classifyPaymentResponse_okWithoutResponseCode_shouldBeUnknown() {
        Exchange exchange = response(200, "");

        routes.classifyPaymentResponse(exchange);

        assertEquals(Outcome.UNKNOWN, exchange.getProperty(PAYOUT_OUTCOME));
    }

    @Test
    void classifyPaymentResponse_okJsonWithoutResponseCode_shouldBeUnknown() {
        Exchange exchange = response(200, "{\"ConversationID\":\"AG_1\"}");

        routes.classifyPaymentResponse(exchange);

        assertEquals(Outcome.UNKNOWN, exchange.getProperty(PAYOUT_OUTCOME));
        assertEquals("HTTP_200", exchange.getProperty(ERROR_CODE));
    }

    @Test
    void classifyPaymentResponse_clientErrorWithoutErrorCode_shouldFailWithTheHttpStatus() {
        Exchange exchange = response(403, "Forbidden");

        routes.classifyPaymentResponse(exchange);

        assertEquals(Outcome.FAILED, exchange.getProperty(PAYOUT_OUTCOME));
        assertEquals("HTTP_403", exchange.getProperty(ERROR_CODE));
        assertEquals("Forbidden", exchange.getProperty(ERROR_DESCRIPTION));
    }

    @Test
    void classifyPaymentResponse_serverErrorWithErrorCode_shouldBeUnknownWithThatCode() {
        Exchange exchange = response(500, "{\"errorCode\":\"500.003.02\",\"errorMessage\":\"System is busy\"}");

        routes.classifyPaymentResponse(exchange);

        assertEquals(Outcome.UNKNOWN, exchange.getProperty(PAYOUT_OUTCOME));
        assertEquals("500.003.02", exchange.getProperty(ERROR_CODE));
        assertEquals("System is busy", exchange.getProperty(ERROR_DESCRIPTION));
    }

    @Test
    void classifyPaymentResponse_noBody_shouldBeUnknown() {
        Exchange exchange = response(504, null);

        routes.classifyPaymentResponse(exchange);

        assertEquals(Outcome.UNKNOWN, exchange.getProperty(PAYOUT_OUTCOME));
    }

    @Test
    void classifyPaymentFailure_withoutCause_shouldBeUnknown() {
        Exchange exchange = new DefaultExchange(new DefaultCamelContext());

        PayoutRoutes.classifyPaymentFailure(exchange);

        assertEquals(Outcome.UNKNOWN, exchange.getProperty(PAYOUT_OUTCOME));
        assertEquals("I/O error calling Safaricom", exchange.getProperty(ERROR_DESCRIPTION));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"access_token\":\"  \"}"})
    void saveAccessToken_okWithoutToken_shouldLeaveTokenUnset(String body) {
        Exchange exchange = response(200, body);

        routes.saveAccessToken(exchange);

        assertNull(exchange.getProperty(ACCESS_TOKEN));
        assertNull(tokens.get(account));
    }

    @Test
    void classifyPaymentFailure_neverSent_shouldFail() {
        assertFailure(new IOException("wrapped", new ConnectException("Connection refused")), Outcome.FAILED,
                "CONNECTION_ERROR");
        assertFailure(new MpesaCircuitOpenException("mpesa-b2c-host"), Outcome.FAILED, "CIRCUIT_OPEN");
        assertFailure(new MpesaJobDeadlineException("mpesa-b2c-host"), Outcome.FAILED, "JOB_DEADLINE");
    }

    @Test
    void classifyPaymentFailure_noResponse_shouldBeUnknown() {
        assertFailure(new SocketTimeoutException("Read timed out"), Outcome.UNKNOWN, "NO_RESPONSE");
    }

    @Test
    void saveAccessToken_shouldCacheTokenForTheAccount() {
        Exchange exchange = response(200, "{\"access_token\":\"abc\",\"expires_in\":\"3599\"}");

        routes.saveAccessToken(exchange);

        assertEquals("abc", exchange.getProperty(ACCESS_TOKEN));
        assertEquals("abc", tokens.get(account));
    }

    @Test
    void saveAccessToken_onError_shouldLeaveTokenUnset() {
        Exchange exchange = response(400, "{\"errorCode\":\"400.008.01\",\"errorMessage\":\"Invalid Authentication\"}");

        routes.saveAccessToken(exchange);

        assertNull(exchange.getProperty(ACCESS_TOKEN));
        assertNull(tokens.get(account));
    }

    private void assertFailure(Exception cause, Outcome outcome, String code) {
        Exchange exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.setProperty(Exchange.EXCEPTION_CAUGHT, cause);

        PayoutRoutes.classifyPaymentFailure(exchange);

        assertEquals(outcome, exchange.getProperty(PAYOUT_OUTCOME));
        assertEquals(code, exchange.getProperty(ERROR_CODE));
    }

    private Exchange response(int status, String body) {
        Exchange exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.setProperty(PAYOUT_ACCOUNT, account);
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, status);
        exchange.getMessage().setBody(body);
        return exchange;
    }
}
