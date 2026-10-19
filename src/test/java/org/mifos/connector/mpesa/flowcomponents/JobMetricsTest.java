package org.mifos.connector.mpesa.flowcomponents;

import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.worker.JobClient;
import io.camunda.zeebe.client.api.worker.JobHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class JobMetricsTest {

    private SimpleMeterRegistry registry;
    private JobMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new JobMetrics(registry);
        ReflectionTestUtils.setField(metrics, "workerMaxJobs", 20);
    }

    @Test
    void countsJobsWhileTheHandlerRuns() throws Exception {
        double[] seen = new double[1];
        JobHandler wrapped = metrics.track("init-transfer", (client, job) -> seen[0] = inFlight("init-transfer"));

        wrapped.handle(mock(JobClient.class), mock(ActivatedJob.class));

        assertEquals(1.0, seen[0]);
        assertEquals(0.0, inFlight("init-transfer"));
    }

    @Test
    void releasesTheCountWhenTheHandlerThrows() {
        JobHandler wrapped = metrics.track("init-transfer", (client, job) -> {
            throw new IllegalStateException("provider down");
        });

        assertThrows(IllegalStateException.class, () -> wrapped.handle(mock(JobClient.class), mock(ActivatedJob.class)));
        assertEquals(0.0, inFlight("init-transfer"));
    }

    @Test
    void publishesTheConfiguredBatchSize() {
        metrics.track("init-transfer", (client, job) -> { });

        assertEquals(20.0, registry.get("zeebe.jobs.max.active").tag("job_type", "init-transfer").gauge().value());
    }

    @Test
    void keepsSeparateCountsPerJobType() throws Exception {
        JobHandler first = metrics.track("init-transfer", (client, job) -> { });
        JobHandler second = metrics.track("get-transaction-status", (client, job) -> { });

        first.handle(mock(JobClient.class), mock(ActivatedJob.class));
        second.handle(mock(JobClient.class), mock(ActivatedJob.class));

        assertEquals(2, registry.get("zeebe.jobs.in.flight").gauges().size());
    }

    private double inFlight(String jobType) {
        return registry.get("zeebe.jobs.in.flight").tag("job_type", jobType).gauge().value();
    }
}
