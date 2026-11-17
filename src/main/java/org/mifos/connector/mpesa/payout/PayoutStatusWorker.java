package org.mifos.connector.mpesa.payout;

import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.response.ActivatedJob;
import io.camunda.zeebe.client.api.worker.JobClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.support.DefaultExchange;
import org.mifos.connector.mpesa.resilience.MpesaHttpCaller;
import org.mifos.connector.mpesa.utility.MpesaUtils;
import org.mifos.connector.mpesa.zeebe.ZeebeCommandHelper;
import org.mifos.connector.mpesa.zeebe.ZeebeWorkerDrain;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;

import static org.mifos.connector.mpesa.camel.config.CamelProperties.CORRELATION_ID;
import static org.mifos.connector.mpesa.camel.config.CamelProperties.MPESA_API_HOST;
import static org.mifos.connector.mpesa.payout.PayoutVariables.PAYOUT_ACCOUNT;
import static org.mifos.connector.mpesa.payout.PayoutVariables.PAYOUT_STATUS_CHECKS_EXHAUSTED;
import static org.mifos.connector.mpesa.payout.PayoutVariables.PAYOUT_STATUS_RETRY_COUNT;
import static org.mifos.connector.mpesa.payout.PayoutVariables.PAYOUT_TIMER;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.AMS;
import static org.mifos.connector.mpesa.zeebe.ZeebeVariables.TRANSACTION_ID;

/**
 * {@code get-mpesa-payout-status}: asks Safaricom for the status of a payout whose result has not arrived. The answer
 * comes back on the status ResultURL and is published as {@code payout-result}; this job only sends the query, counts
 * the check and doubles the wait before the next one. After the last check it completes with
 * {@code payoutStatusChecksExhausted=true} and the process waits for an operator: an unknown payout is never failed
 * automatically, because the money may have been paid.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PayoutStatusWorker {

    public static final String JOB_TYPE = "get-mpesa-payout-status";

    private final ZeebeClient zeebeClient;
    private final ZeebeWorkerDrain drain;
    private final ProducerTemplate producerTemplate;
    private final CamelContext camelContext;
    private final MpesaUtils mpesaUtils;

    @Value("${zeebe.client.evenly-allocated-max-jobs}")
    private int workerMaxJobs;

    @Value("${zeebe.client.request-timeout}")
    private Duration requestTimeout;

    @Value("${mpesa.payout.status.max-checks}")
    private int maxChecks;

    @Value("${mpesa.payout.status.max-timer}")
    private Duration maxTimer;

    @EventListener(ApplicationReadyEvent.class)
    public void setupWorkers() {
        drain.register(zeebeClient.newWorker()
                .jobType(JOB_TYPE)
                .handler(drain.track(this::checkStatus))
                .name(JOB_TYPE)
                .maxJobsActive(workerMaxJobs)
                .open());
    }

    void checkStatus(JobClient client, ActivatedJob job) {
        var variables = job.getVariablesAsMap();
        var transactionId = (String) variables.get(TRANSACTION_ID);
        int check = 1 + ((Number) variables.getOrDefault(PAYOUT_STATUS_RETRY_COUNT, 0)).intValue();

        Map<String, Object> completion = new HashMap<>();
        completion.put(PAYOUT_STATUS_RETRY_COUNT, check);
        if (check > maxChecks) {
            log.error("event=payout_unknown transaction={} checks={}: no result after the last status check, "
                    + "waiting for an operator", transactionId, maxChecks);
            completion.put(PAYOUT_STATUS_CHECKS_EXHAUSTED, true);
            ZeebeCommandHelper.completeJob(client, job, completion, requestTimeout, log);
            return;
        }

        var account = mpesaUtils.getMpesaProperties((String) variables.get(AMS), transactionId);
        log.info("Payout status check {} of {} for transaction {}", check, maxChecks, transactionId);
        var exchange = new DefaultExchange(camelContext);
        exchange.setProperty(CORRELATION_ID, transactionId);
        exchange.setProperty(PAYOUT_ACCOUNT, account);
        exchange.setProperty(MPESA_API_HOST, account.getApiHost());
        exchange.setProperty(MpesaHttpCaller.JOB_DEADLINE, job.getDeadline());
        producerTemplate.send("direct:b2c-status-base", exchange);
        if (exchange.getException() != null) {
            // A status query is a read: a failure only means the next check has to find the answer
            log.warn("Payout status check failed for transaction {}", transactionId, exchange.getException());
        }

        completion.put(PAYOUT_STATUS_CHECKS_EXHAUSTED, false);
        completion.put(PAYOUT_TIMER, nextTimer((String) variables.get(PAYOUT_TIMER), maxTimer).toString());
        ZeebeCommandHelper.completeJob(client, job, completion, requestTimeout, log);
    }

    static Duration nextTimer(String timer, Duration maxTimer) {
        Duration current;
        try {
            current = timer == null ? maxTimer : Duration.parse(timer);
        } catch (DateTimeParseException e) {
            current = maxTimer;
        }
        var next = current.multipliedBy(2);
        return next.compareTo(maxTimer) > 0 ? maxTimer : next;
    }
}
