package org.mifos.connector.mpesa.resilience;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.RuntimeCamelException;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.apache.http.conn.ConnectTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MpesaHttpCallerTest {

    private static final String PATH = "/mpesa/stkpush";

    private WireMockServer safaricom;
    private CamelContext camelContext;
    private ProducerTemplate producerTemplate;
    private MpesaResilienceProperties properties;
    private MeterRegistry meterRegistry;
    private MpesaHttpCaller caller;

    @BeforeEach
    void setUp() {
        safaricom = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        safaricom.start();
        camelContext = new DefaultCamelContext();
        camelContext.start();
        producerTemplate = camelContext.createProducerTemplate();
        meterRegistry = new SimpleMeterRegistry();
        properties = new MpesaResilienceProperties();
        for (MpesaEndpoint endpoint : MpesaEndpoint.values()) {
            MpesaResilienceProperties.EndpointSettings settings = properties.settingsFor(endpoint);
            settings.setConnectTimeout(Duration.ofMillis(500));
            settings.setReadTimeout(Duration.ofMillis(300));
            settings.getRetry().setInitialBackoff(Duration.ofMillis(10));
            settings.getCircuitBreaker().setWaitDurationInOpenState(Duration.ofMillis(300));
            settings.getCircuitBreaker().setPermittedCallsInHalfOpenState(1);
        }
        caller = newCaller();
    }

    @AfterEach
    void tearDown() throws Exception {
        producerTemplate.close();
        camelContext.close();
        safaricom.stop();
    }

    @Test
    void stkPushIsSentOnceWhenSafaricomAnswers() throws Exception {
        stub(200);

        Exchange exchange = call(MpesaEndpoint.STK_PUSH);

        assertEquals(200, status(exchange));
        safaricom.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void stkPushIsNotRetriedOnGatewayError() throws Exception {
        stub(503);

        Exchange exchange = call(MpesaEndpoint.STK_PUSH);

        assertEquals(503, status(exchange));
        safaricom.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void stkPushIsNotRetriedWhenNoResponseComesBack() {
        safaricom.stubFor(post(PATH).willReturn(aResponse().withStatus(200).withFixedDelay(600)));

        Exchange exchange = newExchange();

        assertThrows(SocketTimeoutException.class, () -> callWith(MpesaEndpoint.STK_PUSH, url(), exchange));
        verifyEventually(1);
        assertNull(exchange.getMessage().getHeader("Authorization"), "the request must not be left on the message");
        assertNull(exchange.getMessage().getBody());
    }

    @Test
    void requestThatNeverLeftIsRetriedThenThrown() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        Exchange exchange = newExchange();

        assertThrows(ConnectException.class, () -> callWith(MpesaEndpoint.STK_PUSH,
                "http://localhost:" + closedPort + PATH + "?throwExceptionOnFailure=false", exchange));
        assertEquals(1.0, meterRegistry.get("resilience4j.retry.calls").tag("name", "mpesa-stk-push")
                .tag("kind", "failed_with_retry").functionCounter().count());
    }

    @Test
    void stkQueryRetriesGatewayErrorsUntilItSucceeds() throws Exception {
        safaricom.stubFor(post(PATH).inScenario("flaky").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("recovered"));
        safaricom.stubFor(post(PATH).inScenario("flaky").whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200)));

        Exchange exchange = call(MpesaEndpoint.STK_QUERY);

        assertEquals(200, status(exchange));
        safaricom.verify(2, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void stkQueryRetriesWhenNoResponseComesBack() {
        safaricom.stubFor(post(PATH).willReturn(aResponse().withStatus(200).withFixedDelay(600)));

        assertThrows(SocketTimeoutException.class, () -> call(MpesaEndpoint.STK_QUERY));
        verifyEventually(3);
    }

    @Test
    void stkQueryStillProcessingIsNeitherRetriedNorAFailure() throws Exception {
        properties.getStkQuery().getCircuitBreaker().setMinimumNumberOfCalls(2);
        properties.getStkQuery().getCircuitBreaker().setSlidingWindowSize(2);
        caller = newCaller();
        stub(500);

        for (int i = 0; i < 4; i++) {
            assertEquals(500, status(call(MpesaEndpoint.STK_QUERY)));
        }

        safaricom.verify(4, postRequestedFor(urlEqualTo(PATH)));
        assertEquals(1.0, circuitState("mpesa-stk-query", "closed"));
    }

    @Test
    void tokenRetriesServerErrors() throws Exception {
        stub(500);

        assertEquals(500, status(call(MpesaEndpoint.TOKEN)));
        safaricom.verify(3, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void circuitOpensFailsFastThenClosesAfterSuccessfulProbe() throws Exception {
        smallWindow();
        stub(503);
        call(MpesaEndpoint.STK_PUSH);
        call(MpesaEndpoint.STK_PUSH);

        assertThrows(MpesaCircuitOpenException.class, () -> call(MpesaEndpoint.STK_PUSH));
        safaricom.verify(2, postRequestedFor(urlEqualTo(PATH)));
        assertEquals(1.0, circuitState("mpesa-stk-push", "open"));

        waitForOpenStateToEnd();
        safaricom.resetAll();
        stub(200);

        assertEquals(200, status(call(MpesaEndpoint.STK_PUSH)));
        assertEquals(1.0, circuitState("mpesa-stk-push", "closed"));
    }

    @Test
    void failedProbeReopensTheCircuit() throws Exception {
        smallWindow();
        stub(503);
        call(MpesaEndpoint.STK_PUSH);
        call(MpesaEndpoint.STK_PUSH);
        waitForOpenStateToEnd();

        call(MpesaEndpoint.STK_PUSH);

        assertEquals(1.0, circuitState("mpesa-stk-push", "open"));
        assertThrows(MpesaCircuitOpenException.class, () -> call(MpesaEndpoint.STK_PUSH));
        safaricom.verify(3, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void halfOpenLetsOnlyThePermittedProbesThrough() throws Exception {
        properties.getStkPush().getCircuitBreaker().setPermittedCallsInHalfOpenState(2);
        smallWindow();
        stub(503);
        call(MpesaEndpoint.STK_PUSH);
        call(MpesaEndpoint.STK_PUSH);
        waitForOpenStateToEnd();
        safaricom.resetAll();
        stub(200);

        call(MpesaEndpoint.STK_PUSH);

        assertEquals(1.0, circuitState("mpesa-stk-push", "half_open"));
        call(MpesaEndpoint.STK_PUSH);
        assertEquals(1.0, circuitState("mpesa-stk-push", "closed"));
    }

    @Test
    void eachEndpointHasItsOwnCircuit() throws Exception {
        smallWindow();
        stub(503);
        call(MpesaEndpoint.STK_PUSH);
        call(MpesaEndpoint.STK_PUSH);
        safaricom.resetAll();
        stub(200);

        assertThrows(MpesaCircuitOpenException.class, () -> call(MpesaEndpoint.STK_PUSH));
        assertEquals(200, status(call(MpesaEndpoint.STK_QUERY)));
    }

    @Test
    void businessErrorsDoNotOpenTheCircuit() throws Exception {
        smallWindow();
        stub(400);
        for (int i = 0; i < 5; i++) {
            call(MpesaEndpoint.STK_PUSH);
        }

        assertEquals(1.0, circuitState("mpesa-stk-push", "closed"));
        safaricom.verify(5, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void uriWithoutQueryStillGetsTheTimeouts() throws Exception {
        stub(200);
        properties.getStkPush().setReadTimeout(Duration.ofSeconds(7));
        caller = newCaller();

        Exchange exchange = newExchange();
        callWith(MpesaEndpoint.STK_PUSH, "http://localhost:" + safaricom.port() + PATH, exchange);

        assertEquals(200, status(exchange));
        assertTrue(camelContext.getEndpoints().stream()
                .anyMatch(e -> e.getEndpointUri().contains("httpClient.socketTimeout=7000")));
    }

    @Test
    void failuresBeforeTheRequestLeftAreRetried() {
        MpesaResilienceProperties.EndpointSettings settings = properties.getStkPush();
        List<Exception> neverSent = List.of(new ConnectException(), new ConnectTimeoutException(),
                new UnknownHostException(), new NoRouteToHostException(), new SSLHandshakeException("handshake"),
                new IOException(new ConnectException()));

        for (Exception exception : neverSent) {
            MpesaHttpCaller.FailedAttempt attempt = MpesaHttpCaller.classify(exception, settings);
            assertTrue(attempt.retryable, exception.toString());
            assertTrue(attempt.countsAsFailure, exception.toString());
        }
    }

    @Test
    void localErrorsAreRethrownWithoutRetry() {
        MpesaResilienceProperties.EndpointSettings settings = properties.getStkPush();
        MpesaHttpCaller.FailedAttempt attempt = MpesaHttpCaller.classify(new IllegalStateException(), settings);
        CircuitBreakerConfig circuitBreaker = MpesaHttpCaller.circuitBreakerConfig(settings);
        final RetryConfig retry = MpesaHttpCaller.retryConfig(settings);

        assertFalse(attempt.retryable);
        assertFalse(circuitBreaker.getRecordExceptionPredicate().test(attempt));
        assertTrue(circuitBreaker.getIgnoreExceptionPredicate().test(attempt));
        assertFalse(retry.getExceptionPredicate().test(attempt));
        assertFalse(circuitBreaker.getRecordExceptionPredicate().test(new IllegalStateException()));
        assertFalse(retry.getExceptionPredicate().test(new IllegalStateException()));
        final Exchange exchange = newExchange();
        assertThrows(RuntimeCamelException.class,
                () -> callWith(MpesaEndpoint.STK_PUSH, "unknown-scheme:nowhere", exchange));
    }

    @Test
    void checkedLocalErrorsAreWrappedWithoutRetry() {
        ProducerTemplate failing = mock(ProducerTemplate.class);
        when(failing.send(anyString(), any(Exchange.class))).thenAnswer(invocation -> {
            Exchange sent = invocation.getArgument(1);
            sent.setException(new TimeoutException("local"));
            return sent;
        });
        MpesaHttpCaller localCaller = TestCallers.caller(failing, properties);
        final Exchange exchange = newExchange();

        RuntimeCamelException thrown = assertThrows(RuntimeCamelException.class,
                () -> localCaller.call(MpesaEndpoint.STK_PUSH, url(), exchange));

        assertTrue(thrown.getCause() instanceof TimeoutException);
        verify(failing, times(1)).send(anyString(), any(Exchange.class));
    }

    @Test
    void reasonsNameOnlyTheFailureType() {
        assertEquals("none", MpesaHttpCaller.reason(null));
        assertEquals("IOException", MpesaHttpCaller.reason(new IOException("https://host/path?secret")));
        assertEquals("status:503",
                MpesaHttpCaller.reason(new MpesaHttpCaller.FailedAttempt("status:503", null, true, true)));
    }

    // WireMock logs a delayed request only once its delay has passed, after the client gave up.
    private void verifyEventually(int count) {
        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> safaricom.verify(count, postRequestedFor(urlEqualTo(PATH))));
    }

    private void smallWindow() {
        properties.getStkPush().getCircuitBreaker().setSlidingWindowSize(4);
        properties.getStkPush().getCircuitBreaker().setMinimumNumberOfCalls(2);
        caller = newCaller();
    }

    private static void waitForOpenStateToEnd() {
        await().pollDelay(Duration.ofMillis(400)).atMost(Duration.ofSeconds(2)).until(() -> true);
    }

    private MpesaHttpCaller newCaller() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("meterRegistry", meterRegistry);
        return new MpesaHttpCaller(producerTemplate, properties, beans.getBeanProvider(MeterRegistry.class));
    }

    private void stub(int status) {
        safaricom.stubFor(post(PATH).willReturn(aResponse().withStatus(status)));
    }

    private String url() {
        return "http://localhost:" + safaricom.port() + PATH + "?throwExceptionOnFailure=false";
    }

    private Exchange call(MpesaEndpoint endpoint) throws Exception {
        Exchange exchange = newExchange();
        callWith(endpoint, url(), exchange);
        return exchange;
    }

    private void callWith(MpesaEndpoint endpoint, String uri, Exchange exchange) throws Exception {
        caller.call(endpoint, uri, exchange);
    }

    private Exchange newExchange() {
        Exchange exchange = new DefaultExchange(camelContext);
        exchange.getMessage().setHeader(Exchange.HTTP_METHOD, "POST");
        exchange.getMessage().setHeader("Authorization", "Bearer secret-token");
        exchange.getMessage().setBody("{\"Amount\":1}");
        return exchange;
    }

    private static Integer status(Exchange exchange) {
        return exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE, Integer.class);
    }

    private double circuitState(String name, String state) {
        return meterRegistry.get("resilience4j.circuitbreaker.state").tag("name", name).tag("state", state).gauge()
                .value();
    }
}
