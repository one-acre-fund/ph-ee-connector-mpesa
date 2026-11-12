package org.mifos.connector.mpesa.resilience;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.camel.Exchange;
import org.apache.camel.Expression;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.RuntimeCamelException;
import org.apache.camel.support.DefaultMessage;
import org.apache.http.conn.ConnectTimeoutException;
import org.mifos.connector.mpesa.resilience.MpesaResilienceProperties.EndpointSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sends HTTP calls to Safaricom with a per-endpoint timeout, bounded retries with jittered exponential backoff, and a
 * circuit breaker per endpoint and host. When Safaricom answers, its response is left on the message as before. When it
 * does not (connection failure, no response, circuit open), the call throws an {@link IOException}, which the routes
 * already handle: the STK push is failed and never sent again, a status check stays pending. When the exchange carries
 * {@link #JOB_DEADLINE}, an attempt that could not finish before it is not made.
 */
@Component
public class MpesaHttpCaller {

    /**
     * Epoch millis at which the Zeebe job that triggered the call times out.
     */
    public static final String JOB_DEADLINE = "mpesaJobDeadline";

    private static final Logger logger = LoggerFactory.getLogger(MpesaHttpCaller.class);
    private static final Pattern HOST = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://([^/:?]+)");

    private final ProducerTemplate producerTemplate;
    private final MpesaResilienceProperties properties;
    private final CircuitBreakerRegistry circuitBreakers = CircuitBreakerRegistry.ofDefaults();
    private final RetryRegistry retries = RetryRegistry.ofDefaults();
    private final Map<MpesaEndpoint, CircuitBreakerConfig> circuitBreakerConfigs = new EnumMap<>(MpesaEndpoint.class);
    private final Map<MpesaEndpoint, RetryConfig> retryConfigs = new EnumMap<>(MpesaEndpoint.class);

    public MpesaHttpCaller(ProducerTemplate producerTemplate, MpesaResilienceProperties properties,
                           ObjectProvider<MeterRegistry> meterRegistry) {
        this.producerTemplate = producerTemplate;
        this.properties = properties;
        for (MpesaEndpoint endpoint : MpesaEndpoint.values()) {
            EndpointSettings settings = properties.settingsFor(endpoint);
            circuitBreakerConfigs.put(endpoint, circuitBreakerConfig(settings));
            retryConfigs.put(endpoint, retryConfig(settings));
        }
        circuitBreakers.getEventPublisher().onEntryAdded(added -> added.getAddedEntry().getEventPublisher()
                .onStateTransition(event -> logger.warn("event=mpesa_circuit_state_changed circuit={} from={} to={}",
                        event.getCircuitBreakerName(), event.getStateTransition().getFromState(),
                        event.getStateTransition().getToState())));
        retries.getEventPublisher().onEntryAdded(added -> added.getAddedEntry().getEventPublisher()
                .onRetry(event -> logger.warn("event=mpesa_call_retry call={} attempt={} wait_ms={} reason={}",
                        event.getName(), event.getNumberOfRetryAttempts(), event.getWaitInterval().toMillis(),
                        reason(event.getLastThrowable())))
                .onError(event -> logger.error("event=mpesa_retry_exhausted call={} attempts={} reason={}",
                        event.getName(), event.getNumberOfRetryAttempts(), reason(event.getLastThrowable()))));
        meterRegistry.ifAvailable(registry -> {
            TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakers).bindTo(registry);
            TaggedRetryMetrics.ofRetryRegistry(retries).bindTo(registry);
        });
    }

    /**
     * A processor that calls the URI the expression yields for the exchange.
     *
     * @param endpoint the Safaricom endpoint being called
     * @param uri      the Camel HTTP URI, without timeout parameters
     * @return the processor
     */
    public Processor call(MpesaEndpoint endpoint, Expression uri) {
        return exchange -> call(endpoint, uri.evaluate(exchange, String.class), exchange);
    }

    void call(MpesaEndpoint endpoint, String uri, Exchange exchange) throws IOException {
        String name = "mpesa-" + endpoint.id() + "-" + hostOf(uri);
        CircuitBreaker circuitBreaker = circuitBreakers.circuitBreaker(name, circuitBreakerConfigs.get(endpoint));
        Retry retry = retries.retry(name, retryConfigs.get(endpoint));
        EndpointSettings settings = properties.settingsFor(endpoint);
        String target = uri + (uri.contains("?") ? "&" : "?") + timeoutParameters(settings);
        Message request = exchange.getMessage().copy();
        long lastStart = exchange.getProperty(JOB_DEADLINE, Long.MAX_VALUE, Long.class)
                - properties.getJobDeadlineMargin().toMillis() - longestAttempt(settings);
        AtomicReference<FailedAttempt> lastFailure = new AtomicReference<>();

        try {
            Retry.decorateRunnable(retry, CircuitBreaker.decorateRunnable(circuitBreaker, () -> {
                if (System.currentTimeMillis() > lastStart) {
                    throw outOfTime(name, lastFailure.get());
                }
                try {
                    attempt(target, exchange, request, settings);
                } catch (FailedAttempt failed) {
                    lastFailure.set(failed);
                    throw failed;
                }
            })).run();
        } catch (CallNotPermittedException e) {
            logger.warn("event=mpesa_call_rejected circuit={} state={}", name, circuitBreaker.getState());
            exchange.setMessage(new DefaultMessage(exchange.getContext()));
            throw new MpesaCircuitOpenException(name);
        } catch (FailedAttempt e) {
            if (e.failure != null) {
                // The request is not left on the message, where it could be mistaken for a response.
                exchange.setMessage(new DefaultMessage(exchange.getContext()));
                throw rethrowable(e.failure);
            }
        }
    }

    private static IOException rethrowable(Exception failure) {
        if (failure instanceof IOException io) {
            return io;
        }
        throw failure instanceof RuntimeException runtime ? runtime : new RuntimeCamelException(failure);
    }

    private void attempt(String target, Exchange exchange, Message request, EndpointSettings settings) {
        exchange.setException(null);
        exchange.setMessage(request.copy());
        producerTemplate.send(target, exchange);

        Exception exception = exchange.getException();
        if (exception != null) {
            exchange.setException(null);
            throw classify(exception, settings);
        }
        // The response stream can be read once; later steps log and parse it.
        exchange.getMessage().setBody(exchange.getMessage().getBody(String.class));
        int status = exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE, 0, Integer.class);
        boolean retryable = settings.getRetry().getRetryStatusCodes().contains(status);
        boolean failure = settings.getCircuitBreaker().getFailureStatusCodes().contains(status);
        if (retryable || failure) {
            throw new FailedAttempt("status:" + status, null, retryable, failure);
        }
    }

    /**
     * The failure to report when there is no time left for an attempt: the previous attempt's, whose response (if any)
     * is still on the message, or a request not sent when no attempt was made. Neither retried nor counted against the
     * circuit.
     */
    private static FailedAttempt outOfTime(String name, FailedAttempt previous) {
        logger.warn("event=mpesa_call_skipped call={} reason=job_deadline after={}", name, reason(previous));
        if (previous == null) {
            return new FailedAttempt("job_deadline", new MpesaJobDeadlineException(name), false, false);
        }
        return new FailedAttempt(previous.getMessage(), previous.failure, false, false);
    }

    static String hostOf(String uri) {
        Matcher matcher = HOST.matcher(uri);
        return matcher.find() ? matcher.group(1) : "unknown";
    }

    private static long longestAttempt(EndpointSettings settings) {
        return 2 * settings.getConnectTimeout().toMillis() + settings.getReadTimeout().toMillis();
    }

    static FailedAttempt classify(Exception exception, EndpointSettings settings) {
        String reason = exception.getClass().getSimpleName();
        if (neverSent(exception)) {
            return new FailedAttempt("not_sent:" + reason, exception, true, true);
        }
        if (exception instanceof IOException) {
            return new FailedAttempt("no_response:" + reason, exception,
                    settings.getRetry().isRetryWhenOutcomeUnknown(), true);
        }
        // Not a network failure (bad URI, local error): the request did not leave and retrying would not help.
        return new FailedAttempt("local_error:" + reason, exception, false, false);
    }

    private static boolean neverSent(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof ConnectTimeoutException
                    || cause instanceof UnknownHostException || cause instanceof NoRouteToHostException
                    || cause instanceof SSLHandshakeException) {
                return true;
            }
        }
        return false;
    }

    private static String timeoutParameters(EndpointSettings settings) {
        long connect = settings.getConnectTimeout().toMillis();
        return "httpClient.connectTimeout=" + connect + "&httpClient.connectionRequestTimeout=" + connect
                + "&httpClient.socketTimeout=" + settings.getReadTimeout().toMillis();
    }

    static CircuitBreakerConfig circuitBreakerConfig(EndpointSettings settings) {
        MpesaResilienceProperties.CircuitBreaker config = settings.getCircuitBreaker();
        return CircuitBreakerConfig.custom().slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(config.getSlidingWindowSize())
                .minimumNumberOfCalls(config.getMinimumNumberOfCalls())
                .failureRateThreshold(config.getFailureRateThreshold())
                .waitDurationInOpenState(config.getWaitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(config.getPermittedCallsInHalfOpenState())
                .recordException(e -> e instanceof FailedAttempt failed && failed.countsAsFailure)
                .ignoreException(e -> e instanceof FailedAttempt failed && !failed.countsAsFailure).build();
    }

    static RetryConfig retryConfig(EndpointSettings settings) {
        MpesaResilienceProperties.Retry config = settings.getRetry();
        return RetryConfig.custom().maxAttempts(config.getMaxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(config.getInitialBackoff(),
                        config.getBackoffMultiplier(), config.getJitter()))
                .retryOnException(e -> e instanceof FailedAttempt failed && failed.retryable).build();
    }

    static String reason(Throwable throwable) {
        if (throwable == null) {
            return "none";
        }
        return throwable instanceof FailedAttempt ? throwable.getMessage() : throwable.getClass().getSimpleName();
    }

    /**
     * One failed attempt. The message is a short reason safe to log: no URI, headers or body. {@code failure} is the
     * exception when no response came back, and null when Safaricom answered.
     */
    static final class FailedAttempt extends RuntimeException {

        final transient Exception failure;
        final boolean retryable;
        final boolean countsAsFailure;

        FailedAttempt(String reason, Exception failure, boolean retryable, boolean countsAsFailure) {
            super(reason, null, false, false);
            this.failure = failure;
            this.retryable = retryable;
            this.countsAsFailure = countsAsFailure;
        }
    }
}
