package org.mifos.connector.mpesa.flowcomponents;

import io.camunda.zeebe.client.api.worker.JobHandler;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Publishes how many Zeebe jobs this pod currently holds, per job type. The handler blocks on the
 * provider, so CPU stays near idle under load and cannot drive autoscaling; job occupancy can.
 */
@Component
public class JobMetrics {

    private static final String IN_FLIGHT = "zeebe.jobs.in.flight";
    private static final String MAX_ACTIVE = "zeebe.jobs.max.active";
    private static final String JOB_TYPE_TAG = "job_type";

    private final MeterRegistry registry;
    private final Map<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();

    @Value("${zeebe.client.evenly-allocated-max-jobs}")
    private int workerMaxJobs;

    public JobMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public JobHandler track(String jobType, JobHandler delegate) {
        AtomicInteger counter = inFlight.computeIfAbsent(jobType, this::register);
        return (client, job) -> {
            counter.incrementAndGet();
            try {
                delegate.handle(client, job);
            } finally {
                counter.decrementAndGet();
            }
        };
    }

    private AtomicInteger register(String jobType) {
        AtomicInteger counter = new AtomicInteger();
        Gauge.builder(IN_FLIGHT, counter, AtomicInteger::get)
                .tag(JOB_TYPE_TAG, jobType)
                .register(registry);
        Gauge.builder(MAX_ACTIVE, this, metrics -> metrics.workerMaxJobs)
                .tag(JOB_TYPE_TAG, jobType)
                .register(registry);
        return counter;
    }
}
