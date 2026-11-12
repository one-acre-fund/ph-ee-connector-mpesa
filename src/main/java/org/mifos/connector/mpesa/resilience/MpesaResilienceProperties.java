package org.mifos.connector.mpesa.resilience;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Timeout, retry and circuit-breaker settings for each Safaricom endpoint. Every value can be overridden from the
 * environment, for example {@code MPESA_RESILIENCE_STKQUERY_READTIMEOUT=20s}.
 */
@Component
@ConfigurationProperties(prefix = "mpesa.resilience")
public class MpesaResilienceProperties {

    private static final List<Integer> GATEWAY_ERRORS = List.of(502, 503, 504);

    // Kept free before the Zeebe job deadline to finish the job: an attempt that could end later is not made, so the
    // job cannot time out and be handed to another worker while a call is still running.
    private Duration jobDeadlineMargin = Duration.ofSeconds(10);

    private EndpointSettings token = new EndpointSettings(List.of(500, 502, 503, 504), true,
            List.of(500, 502, 503, 504));

    // No idempotency key on the STK push: only a request that never left is retried.
    private EndpointSettings stkPush = new EndpointSettings(List.of(), false, GATEWAY_ERRORS);

    // A 500 from the STK query means "still processing", so it is neither retried nor a failure.
    private EndpointSettings stkQuery = new EndpointSettings(GATEWAY_ERRORS, true, GATEWAY_ERRORS);

    public EndpointSettings settingsFor(MpesaEndpoint endpoint) {
        if (endpoint == MpesaEndpoint.TOKEN) {
            return token;
        }
        return endpoint == MpesaEndpoint.STK_PUSH ? stkPush : stkQuery;
    }

    public Duration getJobDeadlineMargin() {
        return jobDeadlineMargin;
    }

    public void setJobDeadlineMargin(Duration jobDeadlineMargin) {
        this.jobDeadlineMargin = jobDeadlineMargin;
    }

    public EndpointSettings getToken() {
        return token;
    }

    public void setToken(EndpointSettings token) {
        this.token = token;
    }

    public EndpointSettings getStkPush() {
        return stkPush;
    }

    public void setStkPush(EndpointSettings stkPush) {
        this.stkPush = stkPush;
    }

    public EndpointSettings getStkQuery() {
        return stkQuery;
    }

    public void setStkQuery(EndpointSettings stkQuery) {
        this.stkQuery = stkQuery;
    }

    /**
     * Timeouts, retry and circuit-breaker settings of one endpoint.
     */
    public static class EndpointSettings {

        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration readTimeout = Duration.ofSeconds(60);
        private Retry retry = new Retry();
        private CircuitBreaker circuitBreaker = new CircuitBreaker();

        public EndpointSettings() {
            this(GATEWAY_ERRORS, true, GATEWAY_ERRORS);
        }

        EndpointSettings(List<Integer> retryStatusCodes, boolean retryWhenOutcomeUnknown,
                         List<Integer> failureStatusCodes) {
            retry.setRetryStatusCodes(retryStatusCodes);
            retry.setRetryWhenOutcomeUnknown(retryWhenOutcomeUnknown);
            circuitBreaker.setFailureStatusCodes(failureStatusCodes);
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }

        public Retry getRetry() {
            return retry;
        }

        public void setRetry(Retry retry) {
            this.retry = retry;
        }

        public CircuitBreaker getCircuitBreaker() {
            return circuitBreaker;
        }

        public void setCircuitBreaker(CircuitBreaker circuitBreaker) {
            this.circuitBreaker = circuitBreaker;
        }
    }

    /**
     * Retry settings.
     */
    public static class Retry {

        /**
         * Total attempts, including the first call.
         */
        private int maxAttempts = 3;
        private Duration initialBackoff = Duration.ofMillis(500);
        private double backoffMultiplier = 2.0;

        /**
         * Each wait is randomised by up to this fraction either way.
         */
        private double jitter = 0.5;

        /**
         * Response codes that are retried. Other responses are returned to the route as they are.
         */
        private List<Integer> retryStatusCodes;

        /**
         * Retry when the request was sent but no response came back (read timeout, connection dropped). Only safe for
         * calls that change nothing; never for the STK push, which would ask the customer to pay twice.
         */
        private boolean retryWhenOutcomeUnknown;

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getInitialBackoff() {
            return initialBackoff;
        }

        public void setInitialBackoff(Duration initialBackoff) {
            this.initialBackoff = initialBackoff;
        }

        public double getBackoffMultiplier() {
            return backoffMultiplier;
        }

        public void setBackoffMultiplier(double backoffMultiplier) {
            this.backoffMultiplier = backoffMultiplier;
        }

        public double getJitter() {
            return jitter;
        }

        public void setJitter(double jitter) {
            this.jitter = jitter;
        }

        public List<Integer> getRetryStatusCodes() {
            return retryStatusCodes;
        }

        public void setRetryStatusCodes(List<Integer> retryStatusCodes) {
            this.retryStatusCodes = retryStatusCodes == null ? List.of() : retryStatusCodes;
        }

        public boolean isRetryWhenOutcomeUnknown() {
            return retryWhenOutcomeUnknown;
        }

        public void setRetryWhenOutcomeUnknown(boolean retryWhenOutcomeUnknown) {
            this.retryWhenOutcomeUnknown = retryWhenOutcomeUnknown;
        }
    }

    /**
     * Circuit-breaker settings.
     */
    public static class CircuitBreaker {

        /**
         * Response codes that count as a failure. Connection errors and timeouts always count; other responses never
         * do, since Safaricom answers business errors with 4xx and 500.
         */
        private List<Integer> failureStatusCodes;

        /**
         * Percentage of failed calls in the window that opens the circuit.
         */
        private float failureRateThreshold = 50;

        /**
         * Number of most recent calls the failure rate is computed over.
         */
        private int slidingWindowSize = 20;

        /**
         * Calls needed in the window before the failure rate is evaluated.
         */
        private int minimumNumberOfCalls = 10;
        private Duration waitDurationInOpenState = Duration.ofSeconds(30);

        /**
         * Probe calls let through while half-open; all must complete before the circuit closes or reopens.
         */
        private int permittedCallsInHalfOpenState = 3;

        public List<Integer> getFailureStatusCodes() {
            return failureStatusCodes;
        }

        public void setFailureStatusCodes(List<Integer> failureStatusCodes) {
            this.failureStatusCodes = failureStatusCodes == null ? List.of() : failureStatusCodes;
        }

        public float getFailureRateThreshold() {
            return failureRateThreshold;
        }

        public void setFailureRateThreshold(float failureRateThreshold) {
            this.failureRateThreshold = failureRateThreshold;
        }

        public int getSlidingWindowSize() {
            return slidingWindowSize;
        }

        public void setSlidingWindowSize(int slidingWindowSize) {
            this.slidingWindowSize = slidingWindowSize;
        }

        public int getMinimumNumberOfCalls() {
            return minimumNumberOfCalls;
        }

        public void setMinimumNumberOfCalls(int minimumNumberOfCalls) {
            this.minimumNumberOfCalls = minimumNumberOfCalls;
        }

        public Duration getWaitDurationInOpenState() {
            return waitDurationInOpenState;
        }

        public void setWaitDurationInOpenState(Duration waitDurationInOpenState) {
            this.waitDurationInOpenState = waitDurationInOpenState;
        }

        public int getPermittedCallsInHalfOpenState() {
            return permittedCallsInHalfOpenState;
        }

        public void setPermittedCallsInHalfOpenState(int permittedCallsInHalfOpenState) {
            this.permittedCallsInHalfOpenState = permittedCallsInHalfOpenState;
        }
    }
}
