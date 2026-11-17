package org.mifos.connector.mpesa.payout;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.json.JSONException;
import org.json.JSONObject;
import org.mifos.connector.mpesa.resilience.MpesaCircuitOpenException;
import org.mifos.connector.mpesa.resilience.MpesaEndpoint;
import org.mifos.connector.mpesa.resilience.MpesaHttpCaller;
import org.mifos.connector.mpesa.resilience.MpesaJobDeadlineException;
import org.mifos.connector.mpesa.utility.MpesaProps;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Objects;

import static org.mifos.connector.mpesa.camel.config.CamelProperties.ACCESS_TOKEN;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.CORRELATION_ID;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.ERROR_CODE;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.ERROR_DESCRIPTION;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.ERROR_INFORMATION;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.MPESA_API_HOST;
import static org.mifos.connector.mpesa.payout.PayoutVariables.B2C_REQUEST_BODY;
import static org.mifos.connector.mpesa.payout.PayoutVariables.Outcome;
import static org.mifos.connector.mpesa.payout.PayoutVariables.PAYOUT_ACCOUNT;
import static org.mifos.connector.mpesa.payout.PayoutVariables.PAYOUT_CONVERSATION_ID;
import static org.mifos.connector.mpesa.payout.PayoutVariables.PAYOUT_OUTCOME;

/**
 * Routes of the B2C payout: the payment request, the status query and the callbacks Safaricom posts their results to.
 *
 * <p>The payment request is classified into an {@link Outcome}. A payout is only {@code FAILED} when Safaricom
 * rejected it or it was never sent; when it may have reached Safaricom it is {@code UNKNOWN} and resolved by the
 * result callback or a status query, never by sending it again.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PayoutRoutes extends RouteBuilder {

    // Safaricom's answer to a second request with the same OriginatorConversationID: the payout was already sent
    static final String DUPLICATE_ORIGINATOR_CONVERSATION_ID = "500.002.1001";
    static final String PAYOUT_AUTH_HOST = "payoutAuthHost";

    private static final String ACKNOWLEDGEMENT = "{\"ResultCode\":0,\"ResultDesc\":\"Accepted\"}";
    private static final String CALL_OPTIONS = "?bridgeEndpoint=true&throwExceptionOnFailure=false";
    private static final String ACCESS_TOKEN_ROUTE = "direct:payout-access-token";
    private static final String AUTHORIZATION = "Authorization";
    private static final String APPLICATION_JSON = "application/json";
    private static final String REST_POST = "rest:POST:";
    // Last segment of every callback URL: the token issued for the payout (see PayoutCallbackSigner)
    static final String CALLBACK_TOKEN = "callbackToken";
    private static final String CALLBACK_TOKEN_SEGMENT = "/{" + CALLBACK_TOKEN + "}";

    private final ObjectMapper objectMapper;
    private final MpesaHttpCaller mpesaHttpCaller;
    private final PayoutAccessTokens accessTokens;
    private final PayoutRequestBuilder requestBuilder;
    private final PayoutResultHandler resultHandler;

    @Value("${mpesa.api.b2c}")
    private String b2cPath;

    @Value("${mpesa.api.b2c-status}")
    private String b2cStatusPath;

    @Value("${mpesa.local.payout-result}")
    private String resultPath;

    @Value("${mpesa.local.payout-timeout}")
    private String timeoutPath;

    @Value("${mpesa.local.payout-status-result}")
    private String statusResultPath;

    @Value("${mpesa.local.payout-status-timeout}")
    private String statusTimeoutPath;

    @Override
    public void configure() {

        /*
         * Sets ACCESS_TOKEN to a token for the payout account (PAYOUT_ACCOUNT), fetching one when none is cached.
         * Leaves it unset when no token could be fetched.
         */
        from(ACCESS_TOKEN_ROUTE)
                .id("payout-access-token")
                .process(exchange -> {
                    exchange.removeProperty(ACCESS_TOKEN);
                    MpesaProps.MPESA account = exchange.getProperty(PAYOUT_ACCOUNT, MpesaProps.MPESA.class);
                    String token = accessTokens.get(account);
                    if (token != null) {
                        exchange.setProperty(ACCESS_TOKEN, token);
                    } else {
                        exchange.setProperty(PAYOUT_AUTH_HOST, account.getAuthHost());
                    }
                })
                .filter(exchangeProperty(ACCESS_TOKEN).isNull())
                    .log(LoggingLevel.INFO, "Fetching payout access token for transaction "
                            + property(CORRELATION_ID))
                    .removeHeaders("*")
                    .process(exchange -> {
                        exchange.getMessage().setBody(null);
                        exchange.getMessage().setHeader(AUTHORIZATION, PayoutAccessTokens.basicAuthorization(
                                exchange.getProperty(PAYOUT_ACCOUNT, MpesaProps.MPESA.class)));
                    })
                    .setHeader(Exchange.HTTP_METHOD, constant("GET"))
                    .setHeader(Exchange.HTTP_RAW_QUERY, constant("grant_type=client_credentials"))
                    .doTry()
                        .process(mpesaHttpCaller.call(MpesaEndpoint.TOKEN,
                                simple(property(PAYOUT_AUTH_HOST) + CALL_OPTIONS)))
                        .process(this::saveAccessToken)
                    .doCatch(IOException.class)
                        .log(LoggingLevel.ERROR, "Payout access token fetch failed for transaction "
                                + property(CORRELATION_ID) + ": ${exception.message}")
                    .end()
                .end();

        /*
         * Sends the B2C payment request (B2C_REQUEST_BODY) and sets PAYOUT_OUTCOME.
         */
        from("direct:b2c-payment-base")
                .id("b2c-payment-base")
                .to(ACCESS_TOKEN_ROUTE)
                .choice()
                    .when(exchangeProperty(ACCESS_TOKEN).isNull())
                        .process(exchange -> outcome(exchange, Outcome.FAILED, "TOKEN_ERROR",
                                "Could not get a Safaricom access token, the payout was not sent", null))
                    .otherwise()
                        .to("direct:b2c-payment-call")
                .end();

        from("direct:b2c-payment-call")
                .id("b2c-payment-call")
                .removeHeaders("*")
                .setHeader(Exchange.HTTP_METHOD, constant("POST"))
                .setHeader("Content-Type", constant(APPLICATION_JSON))
                .setHeader(AUTHORIZATION, simple("Bearer " + property(ACCESS_TOKEN)))
                .process(exchange -> {
                    Object request = exchange.getProperty(B2C_REQUEST_BODY);
                    log.info("Sending B2C payment request for transaction {}: {}",
                            exchange.getProperty(CORRELATION_ID), request);
                    exchange.getMessage().setBody(objectMapper.writeValueAsString(request));
                })
                .doTry()
                    .process(mpesaHttpCaller.call(MpesaEndpoint.B2C,
                            simple(property(MPESA_API_HOST) + b2cPath + CALL_OPTIONS)))
                    .log(LoggingLevel.INFO, "B2C payment response for transaction " + property(CORRELATION_ID)
                            + ": ${header.CamelHttpResponseCode} ${body}")
                    .process(this::classifyPaymentResponse)
                .doCatch(IOException.class)
                    .process(PayoutRoutes::classifyPaymentFailure)
                .end();

        /*
         * Asks Safaricom for the status of a payout. The answer arrives on the status ResultURL; a failed query only
         * means the payout is checked again later.
         */
        from("direct:b2c-status-base")
                .id("b2c-status-base")
                .to(ACCESS_TOKEN_ROUTE)
                .choice()
                    .when(exchangeProperty(ACCESS_TOKEN).isNull())
                        .log(LoggingLevel.WARN, "No access token for the payout status check of "
                                + property(CORRELATION_ID) + ", checking again later")
                    .otherwise()
                        .removeHeaders("*")
                        .setHeader(Exchange.HTTP_METHOD, constant("POST"))
                        .setHeader("Content-Type", constant(APPLICATION_JSON))
                        .setHeader(AUTHORIZATION, simple("Bearer " + property(ACCESS_TOKEN)))
                        .process(exchange -> {
                            B2CTransactionStatusRequestDTO query = requestBuilder.buildStatusQuery(
                                    exchange.getProperty(CORRELATION_ID, String.class),
                                    exchange.getProperty(PAYOUT_ACCOUNT, MpesaProps.MPESA.class));
                            log.info("Sending payout status query: {}", query);
                            exchange.getMessage().setBody(objectMapper.writeValueAsString(query));
                        })
                        .doTry()
                            .process(mpesaHttpCaller.call(MpesaEndpoint.B2C_STATUS,
                                    simple(property(MPESA_API_HOST) + b2cStatusPath + CALL_OPTIONS)))
                            .log(LoggingLevel.INFO, "Payout status query for transaction "
                                    + property(CORRELATION_ID) + " answered ${header.CamelHttpResponseCode}: ${body}")
                            .process(this::evictRejectedToken)
                        .doCatch(IOException.class)
                            .log(LoggingLevel.WARN, "Payout status query for transaction "
                                    + property(CORRELATION_ID) + " failed, checking again later: ${exception.message}")
                        .end()
                .end();

        from(REST_POST + resultPath + CALLBACK_TOKEN_SEGMENT)
                .id("payout-result-callback")
                .log(LoggingLevel.INFO, "Payout result received: ${body}")
                .process(exchange -> resultHandler.onPaymentResult(
                        exchange.getIn().getBody(String.class),
                        exchange.getIn().getHeader(CALLBACK_TOKEN, String.class)))
                .process(PayoutRoutes::acknowledge);

        from(REST_POST + statusResultPath + CALLBACK_TOKEN_SEGMENT)
                .id("payout-status-result-callback")
                .log(LoggingLevel.INFO, "Payout status result received: ${body}")
                .process(exchange -> resultHandler.onStatusResult(
                        exchange.getIn().getBody(String.class),
                        exchange.getIn().getHeader(CALLBACK_TOKEN, String.class)))
                .process(PayoutRoutes::acknowledge);

        // A queue timeout says nothing final about the payout: the status checks resolve it
        from(REST_POST + timeoutPath + CALLBACK_TOKEN_SEGMENT)
                .id("payout-timeout-callback")
                .log(LoggingLevel.WARN, "Payout queue timeout received, the status check will resolve it: ${body}")
                .process(PayoutRoutes::acknowledge);

        from(REST_POST + statusTimeoutPath + CALLBACK_TOKEN_SEGMENT)
                .id("payout-status-timeout-callback")
                .log(LoggingLevel.WARN, "Payout status query timed out in the queue: ${body}")
                .process(PayoutRoutes::acknowledge);
    }

    void saveAccessToken(Exchange exchange) {
        int status = exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE, 0, Integer.class);
        String body = exchange.getMessage().getBody(String.class);
        MpesaProps.MPESA account = exchange.getProperty(PAYOUT_ACCOUNT, MpesaProps.MPESA.class);
        JSONObject json = status == 200 ? json(body) : null;
        String token = json == null ? null : json.optString("access_token", null);
        if (token == null || token.isBlank()) {
            log.error("Payout access token fetch for account {} failed with {}: {}", account.getName(), status, body);
            return;
        }
        accessTokens.save(account, token, json.optLong("expires_in", 3599));
        exchange.setProperty(ACCESS_TOKEN, token);
    }

    /**
     * A 401 means Safaricom no longer accepts the cached token; drop it so the next call fetches a new one.
     */
    void evictRejectedToken(Exchange exchange) {
        if (exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE, 0, Integer.class) == 401) {
            accessTokens.evict(exchange.getProperty(PAYOUT_ACCOUNT, MpesaProps.MPESA.class));
        }
    }

    void classifyPaymentResponse(Exchange exchange) {
        int status = exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE, 0, Integer.class);
        String body = exchange.getMessage().getBody(String.class);
        JSONObject json = json(body);
        if (status == 200 && json != null && json.has("ResponseCode")) {
            classifyAcknowledgement(exchange, json, body);
            return;
        }

        String errorCode = json == null ? null : json.optString("errorCode", null);
        if (DUPLICATE_ORIGINATOR_CONVERSATION_ID.equals(errorCode)) {
            // Sent before, e.g. by a retried job: wait for its result instead of sending again
            log.warn("B2C payout {} was already sent, waiting for its result", exchange.getProperty(CORRELATION_ID));
            outcome(exchange, Outcome.ACCEPTED, null, null, body);
            return;
        }
        evictRejectedToken(exchange);
        // A 4xx means Safaricom refused the request, so nothing was paid; anything else may have reached it
        var outcome = status >= 400 && status < 500 ? Outcome.FAILED : Outcome.UNKNOWN;
        String errorMessage = json == null ? body : json.optString("errorMessage", body);
        outcome(exchange, outcome, Objects.requireNonNullElse(errorCode, "HTTP_" + status), errorMessage, body);
    }

    /**
     * A 200 with a ResponseCode: Safaricom accepted the request (0) or refused it, in which case nothing was paid.
     */
    private static void classifyAcknowledgement(Exchange exchange, JSONObject json, String body) {
        String responseCode = String.valueOf(json.get("ResponseCode"));
        if ("0".equals(responseCode)) {
            outcome(exchange, Outcome.ACCEPTED, null, null, body);
            exchange.setProperty(PAYOUT_CONVERSATION_ID, json.optString("ConversationID", null));
        } else {
            outcome(exchange, Outcome.FAILED, responseCode, json.optString("ResponseDescription", ""), body);
        }
    }

    static void classifyPaymentFailure(Exchange exchange) {
        Exception cause = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
        String message = cause == null ? "I/O error calling Safaricom" : cause.getMessage();
        String information = cause == null ? message : cause.toString();
        if (cause instanceof MpesaCircuitOpenException) {
            outcome(exchange, Outcome.FAILED, "CIRCUIT_OPEN", message, information);
        } else if (cause instanceof MpesaJobDeadlineException) {
            outcome(exchange, Outcome.FAILED, "JOB_DEADLINE", message, information);
        } else if (cause != null && MpesaHttpCaller.neverSent(cause)) {
            outcome(exchange, Outcome.FAILED, "CONNECTION_ERROR", message, information);
        } else {
            outcome(exchange, Outcome.UNKNOWN, "NO_RESPONSE", message, information);
        }
    }

    private static void outcome(Exchange exchange, Outcome outcome, String errorCode, String errorDescription,
                                String errorInformation) {
        exchange.setProperty(PAYOUT_OUTCOME, outcome);
        exchange.setProperty(ERROR_CODE, errorCode);
        exchange.setProperty(ERROR_DESCRIPTION, errorDescription);
        exchange.setProperty(ERROR_INFORMATION, errorInformation);
    }

    /**
     * A Camel simple-language reference to an exchange property.
     */
    static String property(String name) {
        return "${exchangeProperty." + name + "}";
    }

    private static void acknowledge(Exchange exchange) {
        exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, APPLICATION_JSON);
        exchange.getMessage().setBody(ACKNOWLEDGEMENT);
    }

    private static JSONObject json(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return new JSONObject(body);
        } catch (JSONException e) {
            return null;
        }
    }
}
