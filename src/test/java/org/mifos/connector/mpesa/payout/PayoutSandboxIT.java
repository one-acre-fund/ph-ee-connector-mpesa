package org.mifos.connector.mpesa.payout;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.spi.RestConfiguration;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mifos.connector.mpesa.resilience.MpesaHttpCaller;
import org.mifos.connector.mpesa.resilience.MpesaResilienceProperties;
import org.mifos.connector.mpesa.utility.MpesaProps;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.ACCESS_TOKEN;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.CORRELATION_ID;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.ERROR_CODE;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.ERROR_DESCRIPTION;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.MPESA_API_HOST;
import static org.mifos.connector.mpesa.payout.PayoutVariables.B2C_REQUEST_BODY;
import static org.mifos.connector.mpesa.payout.PayoutVariables.Outcome;
import static org.mifos.connector.mpesa.payout.PayoutVariables.PAYOUT_ACCOUNT;
import static org.mifos.connector.mpesa.payout.PayoutVariables.PAYOUT_CONVERSATION_ID;
import static org.mifos.connector.mpesa.payout.PayoutVariables.PAYOUT_OUTCOME;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Sends real B2C payouts to the Safaricom Daraja sandbox through the connector's own routes, HTTP caller, token cache
 * and request builder. Not part of {@code ./gradlew test}; run it with {@code ./gradlew sandboxTest}.
 *
 * <p>Environment:
 * <ul>
 *   <li>{@code MPESA_SANDBOX_CONSUMER_KEY}, {@code MPESA_SANDBOX_CONSUMER_SECRET}: a Daraja sandbox app with the
 *   M-Pesa Sandbox (B2C) product</li>
 *   <li>{@code MPESA_SANDBOX_SECURITY_CREDENTIAL}: the sandbox initiator password encrypted with the sandbox
 *   certificate (Daraja portal, "Test credentials")</li>
 *   <li>optional {@code MPESA_SANDBOX_SHORTCODE} (default 600000), {@code MPESA_SANDBOX_INITIATOR} (default testapi),
 *   {@code MPESA_SANDBOX_MSISDN} (default 254708374149)</li>
 *   <li>optional {@code MPESA_SANDBOX_CALLBACK_HOST}: a public https URL forwarding to
 *   {@code MPESA_SANDBOX_CALLBACK_PORT} (default 5055), e.g. {@code ngrok http 5055}; when set, Safaricom's result
 *   callbacks are awaited and checked too</li>
 * </ul>
 */
@Tag("sandbox")
@EnabledIfEnvironmentVariable(named = "MPESA_SANDBOX_SECURITY_CREDENTIAL", matches = ".+")
class PayoutSandboxIT {

    private static final Logger log = LoggerFactory.getLogger(PayoutSandboxIT.class);

    private static final String SANDBOX = "https://sandbox.safaricom.co.ke";
    // The sandbox can take minutes to post results
    private static final Duration CALLBACK_WAIT = Duration.ofSeconds(
            Long.parseLong(env("MPESA_SANDBOX_CALLBACK_WAIT_SECONDS").orElse("300")));

    private final String callbackHost = env("MPESA_SANDBOX_CALLBACK_HOST").orElse(null);
    private final PayoutResultHandler resultHandler = mock(PayoutResultHandler.class);
    private final MpesaProps.MPESA account = sandboxAccount();
    private DefaultCamelContext camel;
    private PayoutRequestBuilder requestBuilder;

    @BeforeEach
    void startConnectorRoutes() throws Exception {
        camel = new DefaultCamelContext();
        camel.setStreamCaching(true);
        var rest = new RestConfiguration();
        rest.setComponent("undertow");
        rest.setPort(Integer.parseInt(env("MPESA_SANDBOX_CALLBACK_PORT").orElse("5055")));
        rest.setBindingMode(RestConfiguration.RestBindingMode.json);
        rest.setDataFormatProperties(new HashMap<>());
        rest.setScheme("http");
        camel.setRestConfiguration(rest);

        var host = callbackHost != null ? callbackHost : "https://example.invalid";
        requestBuilder = new PayoutRequestBuilder(host, "/payout/result", "/payout/timeout",
                "/payout/status/result", "/payout/status/timeout",
                new PayoutCallbackSigner(env("MPESA_PAYOUT_CALLBACK_SECRET").orElse("sandbox-callback-secret")));
        var meterRegistry = new DefaultListableBeanFactory().getBeanProvider(MeterRegistry.class);
        var httpCaller = new MpesaHttpCaller(camel.createProducerTemplate(), new MpesaResilienceProperties(),
                meterRegistry);
        var routes = new PayoutRoutes(new ObjectMapper(), httpCaller, new PayoutAccessTokens(), requestBuilder,
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
    void payout_shouldBeAcceptedByTheSandboxAndNeverSentTwice() {
        var transactionId = UUID.randomUUID().toString();
        var request = requestBuilder.build(transactionId, PayoutTestData.payoutRequest(msisdn(), "10"), account);
        log.info("Sandbox payout {}: {}", transactionId, request);

        // 1. The payment request is accepted
        var sent = send("direct:b2c-payment-base", transactionId, exchange ->
                exchange.setProperty(B2C_REQUEST_BODY, request));
        assertEquals(Outcome.ACCEPTED, sent.getProperty(PAYOUT_OUTCOME), () -> describe("payment", sent));
        assertNotNull(sent.getProperty(ACCESS_TOKEN), "no access token was fetched");
        assertNotNull(sent.getProperty(PAYOUT_CONVERSATION_ID), () -> describe("payment", sent));

        // 2. Resending the same OriginatorConversationID must never fail the payout (it may already be paid)
        var resent = send("direct:b2c-payment-base", transactionId, exchange ->
                exchange.setProperty(B2C_REQUEST_BODY, request));
        log.info("Sandbox answer to the resent payout {}: outcome={} code={} body={}", transactionId,
                resent.getProperty(PAYOUT_OUTCOME), resent.getProperty(ERROR_CODE), resent.getMessage().getBody());
        assertNotEquals(Outcome.FAILED, resent.getProperty(PAYOUT_OUTCOME), () -> describe("resend", resent));

        // 3. The status query is accepted (its answer is posted to the status ResultURL)
        var status = send("direct:b2c-status-base", transactionId, exchange -> { });
        assertNull(status.getException(), () -> describe("status", status));
        assertEquals(200, status.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE, Integer.class),
                () -> describe("status", status));
        log.info("Sandbox status query answer: {}", status.getMessage().getBody(String.class));

        // 4. Safaricom posts the payment result and the status result to their ResultURLs
        if (callbackHost != null) {
            var result = awaitCallback(true);
            assertEquals(transactionId, result.originatorConversationId(), result::toString);
            log.info("Sandbox payout result: code={} description={} receipt={}", result.resultCode(),
                    result.resultDesc(), result.parameter("TransactionReceipt"));
            var statusResult = awaitCallback(false);
            log.info("Sandbox status result: code={} description={} status={}", statusResult.resultCode(),
                    statusResult.resultDesc(), statusResult.parameter("TransactionStatus"));
        }
    }

    private Exchange send(String route, String transactionId, Consumer<Exchange> properties) {
        var exchange = new DefaultExchange(camel);
        exchange.setProperty(CORRELATION_ID, transactionId);
        exchange.setProperty(PAYOUT_ACCOUNT, account);
        exchange.setProperty(MPESA_API_HOST, account.getApiHost());
        properties.accept(exchange);
        return camel.createProducerTemplate().send(route, exchange);
    }

    private B2CResult awaitCallback(boolean payment) {
        var body = ArgumentCaptor.forClass(String.class);
        if (payment) {
            verify(resultHandler, timeout(CALLBACK_WAIT.toMillis())).onPaymentResult(body.capture(), org.mockito.ArgumentMatchers.anyString());
        } else {
            verify(resultHandler, timeout(CALLBACK_WAIT.toMillis())).onStatusResult(body.capture(), org.mockito.ArgumentMatchers.anyString());
        }
        log.info("Sandbox {} callback: {}", payment ? "payment" : "status", body.getValue());
        return B2CResult.parse(body.getValue());
    }

    private static String describe(String step, Exchange exchange) {
        return step + ": outcome=" + exchange.getProperty(PAYOUT_OUTCOME) + " code=" + exchange.getProperty(ERROR_CODE)
                + " description=" + exchange.getProperty(ERROR_DESCRIPTION) + " http="
                + exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE) + " body="
                + exchange.getMessage().getBody(String.class) + " exception=" + exchange.getException();
    }

    private static MpesaProps.MPESA sandboxAccount() {
        var account = new MpesaProps.MPESA();
        account.setName("sandbox");
        account.setAuthHost(SANDBOX + "/oauth/v1/generate");
        account.setApiHost(SANDBOX);
        account.setClientKey(required("MPESA_SANDBOX_CONSUMER_KEY"));
        account.setClientSecret(required("MPESA_SANDBOX_CONSUMER_SECRET"));
        var b2c = new MpesaProps.B2C();
        b2c.setEnabled(true);
        b2c.setShortCode(env("MPESA_SANDBOX_SHORTCODE").orElse("600000"));
        b2c.setInitiatorName(env("MPESA_SANDBOX_INITIATOR").orElse("testapi"));
        b2c.setSecurityCredential(required("MPESA_SANDBOX_SECURITY_CREDENTIAL"));
        account.setB2c(b2c);
        return account;
    }

    private static String msisdn() {
        return env("MPESA_SANDBOX_MSISDN").orElse("254708374149");
    }

    private static String required(String name) {
        return env(name).orElseThrow(() -> new IllegalStateException(name + " is not set"));
    }

    private static Optional<String> env(String name) {
        return Optional.ofNullable(System.getenv(name)).filter(value -> !value.isBlank());
    }
}
