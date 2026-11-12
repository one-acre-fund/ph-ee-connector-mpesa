package org.mifos.connector.mpesa.zeebe;

import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.worker.JobClient;
import io.camunda.zeebe.client.api.worker.JobHandler;
import io.camunda.zeebe.client.api.worker.JobWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Drains the Zeebe job workers when the pod is asked to stop (FD-1939).
 *
 * Spring destroys the {@code ZeebeClient} bean at the very end of a shutdown, and its close()
 * interrupts every job handler that is still running. A handler interrupted between the payment
 * API call and the complete-job command leaves the job activated at the broker; it is re-offered
 * to another pod after the job timeout, which repeats the payment call.
 *
 * This component runs first on ContextClosedEvent, before Camel, Tomcat and any bean destruction:
 * it closes every registered worker so no new jobs are activated for this pod, hands back any job
 * that still arrives from a poll already on the wire (unchanged retries, so the broker re-offers it
 * at once), and waits until every handler that was already running has finished and completed or
 * failed its job, bounded by zeebe.worker.drain-timeout.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ZeebeWorkerDrain implements ApplicationListener<ContextClosedEvent> {

    private static final Logger logger = LoggerFactory.getLogger(ZeebeWorkerDrain.class);

    private final List<JobWorker> workers = new CopyOnWriteArrayList<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicBoolean draining = new AtomicBoolean();
    private final Duration drainTimeout;
    private final Duration requestTimeout;

    public ZeebeWorkerDrain(@Value("${zeebe.worker.drain-timeout:60s}") Duration drainTimeout,
                            @Value("${zeebe.client.request-timeout:30s}") Duration requestTimeout) {
        this.drainTimeout = drainTimeout;
        this.requestTimeout = requestTimeout;
    }

    /** Keeps the worker handle so it can be closed before the client is. Returns it unchanged. */
    public JobWorker register(JobWorker worker) {
        workers.add(worker);
        return worker;
    }

    /** Wraps a handler so the drain knows how many jobs are being executed and can hand back late ones. */
    public JobHandler track(JobHandler handler) {
        return (client, job) -> {
            inFlight.incrementAndGet();
            try {
                if (draining.get()) {
                    handBack(client, job);
                    return;
                }
                handler.handle(client, job);
            } finally {
                inFlight.decrementAndGet();
            }
        };
    }

    public int inFlight() {
        return inFlight.get();
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        if (!draining.compareAndSet(false, true)) {
            return;
        }
        workers.forEach(JobWorker::close);
        logger.info("shutdown: {} Zeebe workers closed, waiting up to {} for {} jobs in flight",
                workers.size(), drainTimeout, inFlight.get());
        long started = System.nanoTime();
        long deadline = started + drainTimeout.toNanos();
        while (inFlight.get() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        int left = inFlight.get();
        if (left > 0) {
            logger.error("shutdown: {} Zeebe jobs still in flight after {} ms, the broker will retry them after the job timeout",
                    left, waitedMs);
        } else {
            logger.info("shutdown: all Zeebe jobs drained after {} ms", waitedMs);
        }
    }

    private void handBack(JobClient client, ActivatedJob job) {
        logger.info("shutdown: handing back job {} of type {} without running it", job.getKey(), job.getType());
        try {
            client.newFailCommand(job.getKey())
                    .retries(job.getRetries())
                    .errorMessage("worker is shutting down, job handed back untouched")
                    .send()
                    .join(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            logger.warn("shutdown: could not hand back job {}, the broker will re-offer it after the job timeout: {}",
                    job.getKey(), e.toString());
        }
    }
}
