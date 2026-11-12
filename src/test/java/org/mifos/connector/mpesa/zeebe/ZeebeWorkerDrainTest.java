package org.mifos.connector.mpesa.zeebe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.worker.JobClient;
import io.camunda.zeebe.client.api.worker.JobHandler;
import io.camunda.zeebe.client.api.worker.JobWorker;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;

class ZeebeWorkerDrainTest {

    private final JobClient client = mock(JobClient.class, RETURNS_DEEP_STUBS);
    private final ActivatedJob job = mock(ActivatedJob.class);
    private final JobWorker worker = mock(JobWorker.class);
    private final ContextClosedEvent closing = new ContextClosedEvent(mock(ConfigurableApplicationContext.class));

    @Test
    void stopClosesWorkersFirstAndWaitsForTheRunningHandler() throws Exception {
        ZeebeWorkerDrain drain = new ZeebeWorkerDrain(Duration.ofSeconds(5), Duration.ofSeconds(1));
        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch handlerStarted = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        drain.register(worker);
        JobHandler tracked = drain.track((c, j) -> {
            handlerStarted.countDown();
            releaseHandler.await();
            order.add("handler finished");
        });
        Thread running = new Thread(() -> run(tracked));
        running.start();
        assertTrue(handlerStarted.await(2, TimeUnit.SECONDS));

        Thread stopping = new Thread(() -> {
            drain.onApplicationEvent(closing);
            order.add("drain returned");
        });
        stopping.start();
        Thread.sleep(300);
        verify(worker).close();
        assertTrue(stopping.isAlive(), "drain must wait while a job is in flight");

        releaseHandler.countDown();
        stopping.join(2000);
        assertFalse(stopping.isAlive(), "drain must return once the handler is done");
        assertEquals(List.of("handler finished", "drain returned"), order);
        assertEquals(0, drain.inFlight());
    }

    @Test
    void jobArrivingAfterStopIsHandedBackWithoutRunning() {
        ZeebeWorkerDrain drain = new ZeebeWorkerDrain(Duration.ofSeconds(1), Duration.ofSeconds(1));
        when(job.getKey()).thenReturn(42L);
        when(job.getRetries()).thenReturn(3);
        boolean[] ran = {false};
        JobHandler tracked = drain.track((c, j) -> ran[0] = true);

        drain.onApplicationEvent(closing);
        run(tracked);

        assertFalse(ran[0], "late job must not run its handler");
        verify(client.newFailCommand(42L)).retries(3);
        verify(client.newFailCommand(42L).retries(3)).errorMessage(anyString());
        verify(client.newFailCommand(42L).retries(3).errorMessage(anyString()).send()).join(anyLong(), org.mockito.ArgumentMatchers.any());
        assertEquals(0, drain.inFlight());
    }

    @Test
    void stopGivesUpAfterTheDrainTimeout() throws Exception {
        ZeebeWorkerDrain drain = new ZeebeWorkerDrain(Duration.ofMillis(300), Duration.ofSeconds(1));
        CountDownLatch never = new CountDownLatch(1);
        JobHandler tracked = drain.track((c, j) -> never.await());
        Thread running = new Thread(() -> run(tracked));
        running.start();
        Thread.sleep(100);

        long started = System.nanoTime();
        drain.onApplicationEvent(closing);
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(waitedMs >= 250 && waitedMs < 2000, "waited " + waitedMs + " ms");
        assertEquals(1, drain.inFlight());
        verify(client, never()).newFailCommand(anyLong());
        never.countDown();
        running.join(1000);
    }

    private void run(JobHandler handler) {
        try {
            handler.handle(client, job);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
