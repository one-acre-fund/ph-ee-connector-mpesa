package org.mifos.connector.mpesa.zeebe;

import io.camunda.zeebe.client.api.ZeebeFuture;
import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.worker.JobClient;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Helpers to keep Zeebe command waits bounded so worker {@code maxJobsActive} slots
 * cannot be held forever on a half-open gRPC channel.
 */
public final class ZeebeCommandHelper {

    private ZeebeCommandHelper() {
    }

    public static <T> T join(ZeebeFuture<T> future, Duration timeout) {
        try {
            return future.join(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            restoreInterruptStatus(e);
            throw e;
        }
    }

    /**
     * Completes a job with a bounded wait. On timeout/failure, attempts a bounded fail
     * so the job can be retried. Does not rethrow — returning frees the local worker slot
     * even if the broker ack never arrives (broker job timeout will re-offer).
     */
    public static void completeJob(JobClient client, ActivatedJob job, Map<String, Object> variables,
                                   Duration timeout, Logger logger) {
        try {
            if (variables != null) {
                join(client.newCompleteCommand(job.getKey()).variables(variables).send(), timeout);
            } else {
                join(client.newCompleteCommand(job.getKey()).send(), timeout);
            }
        } catch (Exception e) {
            restoreInterruptStatus(e);
            logger.error("Complete command failed for job {}; attempting fail for retry", job.getKey(), e);
            failJob(client, job, "Complete command failed: " + e.getMessage(), timeout, logger);
        }
    }

    public static void failJob(JobClient client, ActivatedJob job, String errorMessage,
                               Duration timeout, Logger logger) {
        try {
            join(client.newFailCommand(job.getKey())
                    .retries(Math.max(job.getRetries() - 1, 0))
                    .errorMessage(errorMessage)
                    .send(), timeout);
        } catch (Exception e) {
            restoreInterruptStatus(e);
            logger.error(
                    "Fail command also failed for job {}; releasing worker slot without broker ack",
                    job.getKey(), e);
        }
    }

    private static void restoreInterruptStatus(Throwable throwable) {
        if (isInterrupted(throwable)) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isInterrupted(Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            if (t instanceof InterruptedException) {
                return true;
            }
        }
        return false;
    }
}
